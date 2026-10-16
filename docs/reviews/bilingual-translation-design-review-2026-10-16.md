# 双语互译（App 端）设计方案审查

审查日期：2026-10-16（第 3 节与 P1-12、P2-8 为同日追加）

审查对象：`we-meet-android/docs/bilingual-webrtc-direct.md`

审查范围：Android 客户端实现（`app/src/main/java/com/we/meet/ui/ai/*`、`data/api`、`data/capture`、`realtime-client` 的 AOQ 适配层）、后端契约（`we-meet/src/backend/core/api/assistant_translation.py`、`ai_call.py`、`core/services/direct_ai_allocations.py`、`meet/settings.py`）、网关侧 `we-meet/src/agents/src/plugins/qwen/live_translate.py`、阿里云百炼官方文档（LiveTranslate 实时翻译客户端事件、WebSocket 接入、WebRTC 接入、AOQ Android SDK API 参考、AOQ 接入）。

方法说明：本审查逐文件阅读了上述实现，并与官方协议文档逐字段比对；测试数量、提交号、发布产物、迁移文件均在本机仓库中实测核对。凡未能核实的内容在第 8 节单列。

---

## 1. 结论

**技术实现质量高于文档质量。** 客户端与服务端的工程约束执行得很扎实：密钥不落客户端、模型与上游 URL 由服务端固定、禁用重定向、SDP 双向 128 KiB 上限、Offer 必须同时含 `m=audio` 与 `m=application`、AOQ 凭据按白名单裁剪后才返回、日志经过清洗（18 处全部只记枚举/耗时/字符数）、各缓冲区均有显式上限。文档中列出的提交号、迁移、发布产物、交叉引用文档全部真实存在。

**但当前文档不足以作为「设计方案」被评审或据此做发布决策**，且存在三个应该在扩大放量前解决的问题：成本没有兜底（无空闲自动停止、配额默认关闭）、一个用户可见的「15 分钟会话」提示对应的是不可达代码、以及 WebRTC 直连在弱网/蓝牙场景下的回声与可达性风险没有被记录。

**传输选型结论（第 3 节）**：默认通道 AOQ 目前**没有使用官方推荐的原生音频通路**，因此丢掉了 Opus 压缩与 SDK 的 3A/回声消除——上行带宽约为 Opus 路径的 15–20 倍，且三条音频链路全都没有回声消除。这使「传输选型」从实现细节升级为一个需要决策的设计问题。

建议：**不作为阻断发布的问题处理，但应在放量前完成第 4 节的 P0 三项，按第 3.6 节确认传输路线，并把本文档升级为正式设计方案（见 P2-1）。**

---

## 2. 文档与实现的定位问题（第一性问题）

`bilingual-webrtc-direct.md` 不是设计方案，而是**带日期的交付/验证流水账**。它缺少设计方案应有的章节：目标与非目标、用户流程、错误分类与用户可见文案、容量与成本模型、传输选型依据、回滚方案、可观测性、风险登记、验收标准。同仓库其他模块有正式设计方案（如 `docs/云文档模块原生化_可行性评估与设计方案.md`），`docs/reviews/` 也是既有惯例。

直接后果是：本审查只能从代码「反推」设计意图，而反推过程中发现了 2 处文档与代码的实际矛盾（P1-3、P2-7）和 1 处用户可见文案与代码矛盾（P0-3）。建议保留本文档作为验证附录，另立正式设计方案。

---

## 3. 对接方式选型：WebSocket / WebRTC / AOQ

本节为 2026-10-16 追加。原始审查只覆盖 App 已采用的两种直连方式（AOQ、WebRTC）；本节补上第三种（WebSocket），并给出选型结论与前置验证项。

### 3.1 三种传输承载的是同一套模型协议

`qwen3.8-livetranslate-flash-realtime` 在三种传输上都受官方支持，客户端事件与服务端事件定义相同（`output_modalities`、`audio.input.turn_detection`、`translation.language`、`session.finish → session.finished`）。差别只在**媒体与事件走什么管子**，不在语义。**因此换传输既不会重写业务逻辑，也不需要改动 §P1-5 里那份会话 schema。**

### 3.2 对照表

| | WebSocket | WebRTC | AOQ |
|---|---|---|---|
| 本质 | `wss://` 上的 Realtime 会话协议 | 标准 WebRTC，SDP 由 AppServer 代理 | AI over QUIC，厂商 SDK + 中继 |
| 端点 | `wss://{workspace}.{region}.maas.aliyuncs.com/api-ws/v1/realtime?model=…` | `https://{workspace}.{region}.maas.aliyuncs.com/api/v1/webrtc/realtime?model=…` | 同左，另加 `x-dashscope-rtc-transport: moq` |
| 音频上行 | Base64 PCM，`input_audio_buffer.append` | RTP 媒体轨（Opus） | **媒体轨（Opus，SDK 编码）** |
| 音频下行 | Base64 PCM，`response.audio.delta` | RTP 媒体轨（Opus） | 媒体轨（Opus，SDK 解码） |
| 事件通道 | 同一 WebSocket | DataChannel（含服务端建的 `txt` 通道） | Data 轨 |
| 采集 / 播放 / 3A / 回声消除 | 全部自研 | 全部自研 | **SDK 提供**（`startAudioCapture` / `startAudioPlayer`） |
| NAT 穿透 | TCP/443，天然最好 | 官方示例 `iceServers: []`，仅主机候选；需自建 TURN | 厂商中继端点（`clientRelayEndpoints`），厂商保证连通 |
| 官方 SDK | Python / Java（**无 Android SDK**） | 无（官方明确不提供 WebRTC SDK） | 各平台原生（Android AAR 已在仓内） |
| 连接/进程模型 | 同进程 N 条 WSS | 同进程 N 条 PeerConnection | **引擎为进程内全局单例**：N 条连接 = N 个进程 |
| 断线恢复 | 标准做法，我们已在网关实现（`_recover_transport`） | 官方建议「清状态、重建连接」，无内建重连 | 官方有独立《连接状态管理》文档 |
| 服务端可否代理媒体 | **可以**（我们已经在这么做） | 只代理 SDP | 只发凭据 |

### 3.3 关键量化

**上行带宽。** 16 kHz 单声道 16-bit PCM = 32 000 B/s，Base64 后 ×4/3 ≈ **42.7 kB/s ≈ 341 kbps**；Opus 16 kHz 单声道语音 ≈ **2–3 kB/s ≈ 16–24 kbps**。

| 30 分钟会话上行 | 估算 |
|---|---|
| Base64 PCM（WebSocket；**以及本仓库当前的 AOQ 实现**） | ≈ **77 MB** |
| Opus 媒体轨（WebRTC；AOQ 原生） | ≈ **4–5 MB** |

**约 15–20 倍差距**，直接决定移动数据消耗与弱网表现。

**连接数与进程数。** 本项目的「自动方向」需要 3 条连接（2 条翻译 + 1 条识别，`BilingualSessionPlan.connections`）：

* WebSocket：同进程 3 条 WSS —— 最轻；
* WebRTC：同进程 3 条 PeerConnection + 3 套编解码 —— 中等；
* AOQ：**3 个原生引擎进程**（`realtime-client/src/main/AndroidManifest.xml:10-15`）—— 最重，低端机需评估。

AOQ 的进程隔离不是设计失误，而是 SDK 约束：`createEngine` 返回全局单例、`destroy()` 是静态方法，一个进程只能有一个引擎。

### 3.4 本仓库现状：三种路径都已存在，成熟度差很多

选型的真实起点不是零，而是下面这些既有资产：

| 路径 | 位置 | 状态 |
|---|---|---|
| **WebSocket** | 网关侧 `we-meet/src/agents/src/plugins/qwen/live_translate.py` + `translation/capture_gateway.py` | **生产在用**。有传输恢复（`_recover_transport` / `retryable_transport` / `NON_RETRYABLE_CLOSE_CODES`）、有界队列（`MAX_PENDING=16`）、超时（`IO_TIMEOUT=5` / `FINISH_TIMEOUT=20`）、可配置回合静音窗（`turn_silence_ms` 300–2000 ms）、拒绝重定向 |
| **AOQ** | App 侧 `DirectBilingualWire` + `realtime-client/.../rtc/AoqDataService.kt` | App 双语互译**默认**通道。**但没有使用 AOQ 的原生音频通路**（见 P1-12） |
| **WebRTC** | App 侧 `WebRtcBilingualConnection.kt` | App 双语互译**可选**通道。是三条里唯一真正走 Opus/RTP 的 |
| AOQ 原生通路参考实现 | `OmniAoqClient.kt:337-376`（AI 通话） | 同一仓库里**用对了** AOQ：`startAudioCapture(captureConfig())` + `enableSendMediaStream(audio, true)` |

**这是实现不一致，不是 AOQ 本身的问题**：AI 通话的功能已经正确使用 AOQ 原生音频通路，翻译模块没有。

### 3.5 决定选型的三组架构冲突

**冲突 A：按句切换翻译方向 ←→ SDK 原生采集。**
自动方向要求逐帧决定这段音频送去哪条翻译连接，只有「App 自己持有音频流」才能做到；而 AOQ 的 3A/AEC 只有「SDK 自己持有麦克风」才生效。**两者今天互斥。**（若让每条引擎都开原生采集，3 个引擎会同时收到全部音频，两条翻译连接都会翻译每一句——输出翻倍且成本 ×2。）

**冲突 B：SDK 原生播放 ←→ App 自有回放与缓存。**
要拿到回声参考信号，最好由 SDK 播放译音；但本项目需要自有回放队列、音量与 5.76 MB 回放缓存。待验证：`enableAudioFrameObserver(…, AoqAudioSourcePlayback, mode = ReadOnly)` 在 `isExternal = false`（SDK 播放）时是否仍能把播放帧交给 App 喂缓存。若可以，此冲突可解。

**冲突 C：方向切换能否在单条连接上完成。**
若 `session.update` 允许**会话中途修改 `translation.language`**，则可改为「1 条可切换方向的翻译连接 + 1 条识别连接」= **2 个引擎**，且翻译连接可用 SDK 原生采集，同时解决冲突 A 与进程开销。官方文档只说明 `session.update` 用于初始化，未定义中途改语言的语义，**必须实测**。

### 3.6 选型建议

**首选：AOQ + 原生音频通路。** 前提是冲突 A 或 C 至少有一个能解（见 3.7）。值得做的理由是它唯一能一次性拿到四点：① 厂商中继保证 NAT 穿透；② QUIC 抗丢包，优于 WebSocket 的 TCP 队头阻塞；③ Opus 媒体轨，上行带宽降到约 1/20；④ SDK 侧 3A/回声消除，直接消掉 P1-1 里「半双工硬静音 + 200 ms 尾音」的整套妥协。且 AOQ AAR 已随 AI 通话进包，**二进制边际成本为 0**。

**次选：WebSocket（当 AOQ 原生通路确认不可行时）。** 若冲突 A 证明方向切换必须由 App 持有音频，那么 AOQ 的优势只剩「中继 + QUIC」，不足以抵消 3 个原生进程、按会话凭据与原生 ABI 的复杂度。此时应选 WebSocket：有我们自己的生产实现与断线恢复，服务端可代理（配额、观测、密钥、区域切换、schema 修复都无需发版）。代价是清楚的——上行 base64 PCM（341 kbps）、TCP 队头阻塞、服务端成为容量与延迟的必经一跳。**这种情况下也不该退回 WebRTC 直连**，除非补上 TURN，否则弱网可达性比 WebSocket 更差。

**WebRTC：不作为 App 默认。** 仅在需要把同一套实时翻译能力复用到 **Web 端**（AOQ 无 Web SDK）、或无法内置原生 SDK 时选，且**必须补 TURN**。

**无论选哪条，都应立刻做**：① 统一 `session.update` 的事实来源（P1-5）；② 若继续保留当前 AOQ 实现，至少改用 `pushAudioExternalStreamData` 走媒体轨，把上行带宽拿回来——投入最小、收益最直接。

### 3.7 建议先验证的 spike（按价值排序）

1. **`session.update` 中途改 `translation.language`**（冲突 C）。若支持，架构可从 3 连接降为 2 连接，且翻译连接可用原生采集。收益最大。
2. **`isExternal = false` 播放 + ReadOnly 播放帧回调**（冲突 B）。验证 SDK 播放时 App 仍能拿到帧喂回放缓存。
3. **AOQ 原生采集下的回声消除实测**（冲突 A 的替代路径）。真机扬声器 + 蓝牙 A2DP 双向对话，测双讲时是否还串音。
4. **外部推流但走媒体轨**：`startAudioCapture(isExternal = true)` + `pushAudioExternalStreamData` + `enableSendMediaStream(audio, true)`。既保住「App 持有音频、可逐帧路由」，又拿回 Opus 压缩——**是当前架构下最可能落地的改造**（仓库已有测试在用该 API：`CallSyntheticInput.kt:36`、`ScreenAoqPlaybackMixPreflightTest.kt:134`）。

---

## 4. P0 —— 放量前应解决

### P0-1 没有空闲自动停止，付费会话最长可挂 12 小时

* `BilingualTranslationController.Session.run()` 的循环以 `while (isActive)` 持续运行，只有在用户主动停止、连接失败或收到 `finished` 时才结束，**没有任何基于静音时长的空闲超时**（`BilingualTranslationController.kt:113-174`、`:319-490`）。
* 后端每次分配的生命周期上限为 **12 小时**：`MAX_LIFETIME_SECONDS = 43200`，租约 `LEASE_SECONDS = 120` 通过心跳续期（`core/services/direct_ai_allocations.py:15-16,121-123`）。也就是说，一次被遗忘的会话可以维持 1 条（固定方向）或 3 条（自动方向）供应商会话最长 12 小时。
* 自动方向模式下，语言识别探针的「有没有人说话」判据非常宽松：`voiced()` 的阈值是 RMS > 200（满量程 32768，约 −44 dBFS），`DirectBilingualWire.kt:177-182`。**普通的房间底噪就会持续触发识别窗口**，每次 `classify()` 都会向 Omni 连接发送 `input_audio_buffer.clear` + 音频 + `commit` + `response.create` 并等待一次完整响应（`DirectBilingualWire.kt:187-216`），未知时窗口按 12800 字节（约 0.4 秒）递增重试（`:164`）。文档只记录了「噪声不再导致断连」这一崩溃修复，没有评估噪声下的调用频率与费用。
* 上行带宽见第 3.3 节：当前 AOQ 路径的 base64 PCM 使这一条的成本再放大 15–20 倍。

**建议**：客户端增加空闲超时（例如 5 分钟无新译文且 `lastVoiceAt` 无变化即自动结束并明确提示）；后端为双语翻译单独设置会话时长上限；把 `voiced()` 阈值改为可配置，或对连续 unknown 的探针做指数退避/静默丢弃。

### P0-2 配额默认关闭，且计费/限额单位是「连接」而非「会话」

* `DIRECT_AI_MAX_ACTIVE_ALLOCATIONS` 与 `DIRECT_AI_MAX_DAILY_ALLOCATIONS` **默认值为 0**，注释写明「Zero observes existing usage without imposing a new product quota」（`meet/settings.py:1313-1319`）。
* `reserve()` 在限额为假值时跳过两项检查（`direct_ai_allocations.py:44-49`）。
* 更关键：`enforce` 字段直接由该限额推导——`"enforce": bool(settings.DIRECT_AI_MAX_ACTIVE_ALLOCATIONS)`（`:73`）。因此**默认配置下 `enforce=false`，而客户端的 `DirectAILease` 在 `enforce=false` 时把租约丢失当作「仅观测」，不会结束会话**（`realtime-client/.../data/DirectAILease.kt:50-59`）。即：默认部署既没有并发上限，也没有「服务端叫停即停止」的机制。
* 限额的计数单位是 `DirectAIAllocation` 行，而自动方向一次启动会创建 **3 行**（`DirectBilingualWire.kt:64-85` 并发 `awaitAll` 三次 `POST /session/`）。后果有两个：
  1. 若运维把限额配成 1 或 2，自动方向会因其中一次 `reserve()` 抛 `Throttled` 而**整体失败**（并发请求中任一个 429 会让 `awaitAll` 失败）；
  2. 未来想表达「每个用户只允许一个互译会话」无法用这个计数器实现。

**建议**：确认并调整生产环境变量；把限额的语义改为「会话」而不是「连接」（由客户端传入会话 ID，或服务端按 3 连接聚合成一会话）；单独暴露 `enforce` 而不与限额是否配置耦合。

### P0-3 `BilingualPhase.EXPIRED` 与「15 分钟会话」文案是不可达代码

* 客户端资源里有 `bilingual_expired`：「15-minute session ended. Tap Start to continue.」/「本次 15 分钟翻译已结束，可点击开始继续。」（`app/src/main/res/values/bilingual_translation.xml:25`、`values-zh-rCN/...:25`）。
* `BilingualTranslationController.kt:382` 处理事件类型 `"expired"` 并切到 `BilingualPhase.EXPIRED`。
* **但没有任何代码路径会发出 `"expired"` 事件。** `DirectBilingualWire` 只发出 `ready` / `ack` / `language_unknown` / `translation` / `audio` / `audio_end` / `finished`（`:107,126,138,143,165,249,277,290,299,307`）；其 `accept()` 的 `when` 没有 `else` 分支，供应商侧即使返回未知类型也会被静默丢弃（`:229-306`）。AOQ 适配层只转发数据消息原文，不做类型映射（`AoqDataService.kt:63-68`）。
* 后端也没有 15 分钟这个概念：翻译路径只创建 `DirectAIAllocation`（上限 12 小时），不创建 `RealtimeSession`，因此 `direct_ai_allocations.update()` 里的 `realtime_sessions.guard()` 对双语互译不生效（`direct_ai_allocations.py:99-114`）。

因此：**UI 向用户承诺了一个优雅的会话上限结束，而实际达到供应商会话上限时，用户看到的是通用的「翻译已中断，请点击开始重新连接」**（`bilingual_error`）。

**建议**：三选一并保持三方一致——(a) 在 wire 层把供应商的会话上限/error 映射成 `expired`；(b) 由后端下发会话时长上限，客户端本地计时；(c) 删除 `EXPIRED` 阶段与 `bilingual_expired` 文案。无论哪种，文档都必须写明会话时长约束。

---

## 5. P1 —— 应解决或明确接受

### P1-1 三条音频链路都没有回声消除，保护手段是「半双工硬静音」，且 200 ms 尾音在高时延路由上不够

* 采集端使用 `MediaRecorder.AudioSource.VOICE_RECOGNITION`（`data/capture/AndroidCapturePcmSource.kt:96`），应用内没有任何 `AcousticEchoCanceler`；WebRTC 侧显式关闭 SDK 自带 AEC/NS 且不打开 `AudioRecord`（`WebRtcBilingualConnection.kt:48,61`）；播放端用 `USAGE_MEDIA`（`AndroidTranslationOutput.kt:34`）——平台 AEC 在这条链路上都不会生效。
* **AOQ 路径同样没有 AEC**（2026-10-16 更正）：`AoqDataService` 从不调用 `startAudioCapture`，反而把上行媒体发送显式关闭（`enableSendMediaStream(audio, false)`，`realtime-client/.../rtc/AoqDataService.kt:71`，全程不再开启），输入 PCM 走 Data 轨的 base64 `input_audio_buffer.append`（同文件 `:105-111` 注释），播放也是 `isExternal = true`（`:50-52`）。既然 SDK 既不管采集也不管播放，它就既没有回声参考信号、也不执行 3A。**所以「沿用现有回声保护」在两条直连路径上都不成立**（此前版本的本报告误称 AOQ 可拿到 SDK 的 AEC，特此更正）。
* 唯一保护是播报期间**把麦克风整帧置零**（`BilingualTranslationController.kt:473-475`）加固定 `ECHO_TAIL_MS = 200`（`:39,424`）。代码注释称「framework has already consumed it, so this only covers the device's acoustic tail」，该前提不成立。
* 风险：蓝牙 A2DP 的输出时延通常 150–300 ms 以上，200 ms 尾音不足时译音尾部会被麦克风重新拾取、再次进入识别与翻译。
* 另需注意：这是**已知的产品取舍**——设置页文案已经写明「播报时暂停语音输入；关闭播报可持续收音」（`bilingual_translation.xml:15`）。也就是说用户若要不漏掉插话，必须完全关掉译音播报。文档没有记录这个取舍。

**建议**：在真机 + 蓝牙/扬声器路由上补回声与串音验收（当前验证是模拟器 + 合成音频，文档自己也声明不替代真机音频验收）；把尾音时长改为按输出路由动态取值；把「半双工」作为明确的产品决策写进设计方案。若要让 AOQ 提供真正的 AEC，需要让 AOQ SDK 同时接管采集与播放——其可行性取决于第 3.5 节的冲突 A/B。

### P1-2 `direct-aoq` 一个布尔值承担了三个历史语义，升级/降级会静默切换传输通道

* `BilingualPreferences.load()`：`direct-aoq=true` → AOQ，`false` → WebRTC；首次迁移由 `aoq_default_v1` 守护（`BilingualPreferences.kt:20-22,27`）。
* 旧版本里 `false` 表示「云端网关」，新版本里 `false` 表示 WebRTC。因此：从 WebRTC 版本降级到旧版本，用户的 WebRTC 选择被解释成网关；降级到更早版本时 `aoq_default_v1` 会存活，再升级回来不会重跑迁移，于是那个 `false` 又变成 WebRTC。**两个方向的降/升级都会静默改变传输通道**，而这个键不是版本键。
* 同一仓库里 `AiCallPreferences` 用**同名键 `aoq_default_v1`** 做了另一套迁移，但存在不同的 prefs 文件（`we_meet_ai_call_prefs`，且是应用全局而非按账号）并用字符串 `call_transport` 表达（`feature-assistant/.../ai_call/data/AiCallPreferences.kt:11,15-16,57`）。两套同名迁移、两种取值类型、两种作用域，容易漂移。

**建议**：引入带版本的新键（如 `transport_v1: "aoq"|"webrtc"|"gateway"`），显式迁移并加测试；`direct-aoq` 只读用于迁移。

### P1-3 文档与代码矛盾：「改变语言对恢复自动」不成立

文档第 11 行称「方向按账号保存，切换 AOQ／WebRTC 保留方向，**改变语言对恢复自动**」。代码相反：`selectLanguage` 在手动方向下把 `fixedSource` 重新锚定到**新的** `pair.source`，即手动方向被保留（`BilingualTranslationController.kt:231-232`），并且有测试明确定格该行为（`BilingualTranslationTest.kt:38-42`）。设置页文案也只承诺「手动指定时，按左侧语言 → 右侧语言翻译」，并未承诺恢复自动（`bilingual_translation.xml:9`）。

判定：**代码是有意为之，文档写错了。** 修正文档。

### P1-4 「空目录」与「后端异常」不可区分，会静默保留陈旧音色目录

`BilingualVoices.valid()` 要求空列表时 `default_voice == null` 才算合法（`BilingualVoices.kt:64-68`）。而当 `AIModel` 行缺失/停用/capability 不匹配时，配置接口同样返回空列表（`core/api/assistant_translation.py:54-63`）。客户端无法区分「管理员停用了全部音色」和「后端配错了」，后者会静默回退到内置 47 音色列表并保持「开始」可用，用户点下去才拿到会话错误。

**建议**：配置接口返回显式的 `enabled` 与 `reason` 字段。

### P1-5 会话配置（`session.update`）在 App 与网关各写一遍，且 App 把可配置项写成了常量

`configureTranslation()` 自行拼装模型会话配置（`DirectBilingualWire.kt:219-225`）：

```json
{"type":"session.update","session":{
  "output_modalities":["text","audio"],
  "audio":{"input":{"format":{"type":"pcm","sample_rate":16000},
                    "turn_detection":{"type":"server_vad","threshold":0.2,"silence_duration_ms":1000}},
           "output":{"format":{"type":"pcm","sample_rate":24000},"voice":"<voice>"}},
  "translation":{"language":"<lang>"}}}
```

与官方文档比对（[LiveTranslate 客户端事件](https://help.aliyun.com/zh/model-studio/live-translator-client-events)）：`output_modalities` 与 `session.audio.*.format` 是 3.8 的形态，与代码一致；但 `silence_duration_ms` 只记录在 **3.5** 的顶层 `session.turn_detection` 下，3.8 的 `session.audio.input.turn_detection` 只文档化了 `type` 与 `threshold`；`voice` 在公开文档中是 `session.voice`，而代码写的是 `session.audio.output.voice`。

**补充核实（2026-10-16 追加）**：网关侧的**生产** WebSocket 实现 `we-meet/src/agents/src/plugins/qwen/live_translate.py:121-140` 使用**完全相同**的 3.8 载荷（含 `silence_duration_ms` 与 `audio.output.voice`）。因此这两个「文档未记载」的字段路径是**经生产验证可用**的，原先对字段正确性的怀疑可以撤回。真正的问题因此变得更明确：

1. **同一份会话 schema 在三个代码库中各写一遍，没有单一事实来源**：App Kotlin（`DirectBilingualWire.kt:219-225`）、网关 Python（`live_translate.py:121-140`），外加官方文档。任何一处改动都不会自动传导。
2. **App 把可配置项写成了常量**：网关把回合静音窗做成配置 `turn_silence_ms`（校验范围 300–2000 ms），并让显式静音边界的字节数与它对齐（`live_translate.py:44,87,105-111`）；App 则硬编码 `silence_duration_ms: 1000`，并用固定的 1.2 秒静音块收尾（`DirectBilingualWire.kt:129,223`）。只要运维把网关窗口调到 1200 ms 以上，App 的收尾静音就会**短于服务端窗口，最后一句被截断**。
3. **与「配置后端化」的方向矛盾**。文档第 61 行称提示词已迁入后台、第 55 行称音色目录由后端管理，但会话 schema 仍硬编码在 APK 里（且与网关重复）：任何格式/字段/VAD 参数变化都需要发版。这与「无需发布新 APK」的收益相冲突（该收益只对音色 ID 成立）。

**建议**：把 `session.update` 的正文也交给后端渲染（后端已经在渲染 `instructions`），让 App 与网关共用同一份事实来源；或在客户端启动时断言 `session.updated` 回显了请求的 VAD/voice/format，不一致即明确报错而不是继续跑。

### P1-6 模型字符串在客户端硬钉，后端换模型 = 对所有已发布客户端破坏性变更

`BilingualVoices.kt:65` 要求 `config.model == "qwen3.8-livetranslate-flash-realtime"`，`DirectBilingualWire.kt:83` 用 `check()` 断言会话返回的模型。后端若把模型升到 3.9：旧客户端的音色目录被判定非法而回退内置目录，且 `check` 失败导致会话直接报错；没有任何能力协商或最低版本提示。

**建议**：配置接口增加模型能力/契约版本字段，客户端据此给出「请升级」的明确错误。

### P1-7 「结束会话」在连接不健康时以错误告终，而不是正常结束

`finishedConnections` 要求**所有**翻译连接都回报 `session.finished` 才发出 `finished`（`DirectBilingualWire.kt:299`）。自动方向下若其中一条连接异常（例如方向确定后另一条从未收到音频），永远不会凑满 2，用户点「结束」后要等 30 秒看门狗，并落到 `BilingualPhase.ERROR`（`BilingualTranslationController.kt:512-515`）。

**建议**：用户已主动结束、看门狗到期的情况应视为正常结束（或沿用 `EXPIRED`/`FINISHING` 的收尾语义），不应报错。

### P1-8 生命周期与资源问题（按风险排序）

| # | 问题 | 位置 |
|---|---|---|
| 1 | `receivedAt` 在关闭播报时整个会话只增不减（清理只发生在被 `continue` 跳过的播放分支），`release()` 也不清 | `Controller:287,368,433,448,496` |
| 2 | `rowOrder` 每行译文加一项，永不裁剪（只有 `rows` 被裁剪到 100） | `Controller:274,363,361` |
| 3 | 回放缓存（≤5.76 MB）与 `discarded` 集合在 `stop()` 后仍驻留，只有 `close()` 才清 | `BilingualReplayCache.kt:4,11,33`、`Controller:521,267` |
| 4 | 页面没有 `DisposableEffect`/`onDispose`，清理完全依赖 ViewModel 清除；除显式返回外的任何离开路径都会让麦克风、前台服务租约、付费会话继续存活 | `BilingualTranslationScreen.kt`（全文） |
| 5 | `viewModel(key = "bilingual:$user")` 在账号就地切换时会新建控制器而旧的 `ViewModel` 不被清除 —— 遗留一个仍持有麦克风、前台租约和付费会话的控制器 | `BilingualTranslationScreen.kt:41-42` |
| 6 | `WebRtcBilingualConnection.connect()` 失败时不自清理（`module`/`factory`/`peer` 已建），只依赖 `listener.failed()` → `stop(ERROR)` → `wire.close()` 这条间接链；`DirectBilingualWire` 的三个连接在字段初始化期创建，同样无回滚 | `WebRtcBilingualConnection.kt:74,75,79`、`DirectBilingualWire.kt:31-33,55-59` |
| 7 | 音频 sink 解绑非原子：`onTrack` 在 `dispatch{}` 里挂载、`close()` 同步摘除，存在摘除后被重新挂上的窗口；`remote` 从不显式释放 | `WebRtcBilingualConnection.kt:96-101,203,218` |
| 8 | 用户主动取消重播会误报 `replayError`：`stopReplay()` 另一线程关闭输出，而重播协程可能正在 `play()` 内，`AndroidTranslationOutput.play()` 的 `check(!closed)` 抛异常 | `Controller:156,162-164,175-179`、`AndroidTranslationOutput.kt:89` |

**建议**：在 `Session.release()` 里清空 `receivedAt`/`rowOrder`；把回放缓存的清理也纳入 `stop()`；页面加 `DisposableEffect` 或把控制器生命周期绑到导航条目；`connect()` 内 try/catch 自清理；`play()` 的关闭竞态改为可捕获的取消语义。

### P1-9 WebRTC 直连的弱网可达性没有兜底，且失败提示不指向 AOQ

`RTCConfiguration(emptyList())` —— 无 STUN/TURN，仅主机候选，等 ICE gathering 完成（15 秒超时）后一次性交换 SDP，`onIceCandidate` 为空实现（`WebRtcBilingualConnection.kt:65-67,74,105`）。**这与官方 WebRTC 接入文档的示例一致**（官方示例同样用 `iceServers: [ ]`），所以不是实现偏差；但它是产品风险：对称 NAT/CGNAT 或只放行 TCP/443 的网络下 WebRTC 选项直接失败，而设计明确禁止自动重试与自动切换（出于成本考虑，这是对的），用户只拿到通用的「翻译已中断」。

**建议**：在 `bilingual_error` 场景下，若当前是 WebRTC 且连接在 ICE/协商阶段失败，提示用户「可切换到 AOQ 直连重试」——一次性、用户确认的切换与「不自动切换收费会话」的原则并不冲突。

### P1-10 限流与「一次启动 = 3 次分配」没有写进文档

`TranslationThrottle` 为 `20/min`（按用户），作用于 `POST /assistant-translation/session/`（`core/api/assistant_translation.py:32-34,187`）。自动方向每次启动消耗 3 次请求，即约 6 次启动/分钟。文档完全未提及限流，也未说明「自动 = 3 次分配」对配额的实际影响（见 P0-2 与第 3.3 节）。

### P1-11 远端解码音频的采样率/声道处理过脆且质量有损

`audioSink` 用 `check(rate in setOf(24000, 48000))` 硬约束远端采样率，超出即整会话失败；48 kHz 用 `stride = 2` 直接抽取（无抗混叠滤波），立体声只取通道 0（不做下混）（`WebRtcBilingualConnection.kt:111-128`）。供应商一旦改变输出格式，会话直接崩而不是降级。

**建议**：改为带限重采样 + 声道下混，并把硬 `check` 降级为可恢复的告警路径。

### P1-12 默认通道（AOQ）未使用官方推荐的原生音频通路，上行带宽约为 Opus 路径的 15–20 倍

2026-10-16 追加。这是第 3 节对照的直接产物，也是当前「传输选型」的核心问题。

* `AoqDataService.kt:71` 调用 `enableSendMediaStream(audio, false)` 后**全程不再开启**，输入 PCM 改走 Data 轨的 base64 `input_audio_buffer.append`（同文件 `:105-111` 注释），因此上行是 16 kHz 单声道 PCM 的 Base64 流：**≈341 kbps / ≈77 MB per 30 分钟**。
* 而 AOQ 媒体轨与 WebRTC RTP 都走 Opus：**≈16–24 kbps / ≈4–5 MB**。差距 **15–20 倍**（第 3.3 节）。
* 这与官方 AOQ 接入文档的指引相反。官方明确写：「Transmit audio and video on their media tracks, and model events and text on Data tracks. For example, Omni over AOQ does not require `input_audio_buffer.append` or `input_image_buffer.append` to send the same media again.」即 AOQ 下**不应**用 `input_audio_buffer.append` 重发媒体。
* 同一仓库的 AI 通话已经用对（`OmniAoqClient.kt:337-376`：`startAudioCapture` + `enableSendMediaStream(audio, true)`），**翻译模块是唯一的例外**——说明这是实现不一致，而非 SDK 限制。
* 代价直接体现在现有代码里：为承受 base64 PCM 的体量，控制器必须用 `Semaphore(8)` 做在途流控、`queuedBytes ≤ 64000` 上限、且每条 Data 消息都要等一次 IPC ACK（`BilingualTranslationController.kt:281,483`、`AoqDataConnection.kt:64-74`）。

**建议**：先做第 3.7 节的 spike 4（外部推流 + 媒体轨），以最小改动拿回 Opus 压缩；若同时要拿回 3A/AEC，则需按冲突 A/B 的结论决定是否改由 SDK 接管采集与播放。

---

## 6. P2 —— 文档、证据与卫生问题

### P2-1 缺少正式设计方案（含没有任何传输选型记录）
见第 2 节。补充：文档通篇没有记录**为什么**在已有生产可用的 WebSocket 网关路径之外，还要为 App 引入 AOQ 与 WebRTC 两条直连路径，也没有任何对照与决策依据（第 3 节即为补上这部分）。

### P2-2 测试数量是不可复现的快照
* JVM `ui/ai`：**当前 22 个 `@Test`**。文档第 29 行称 18 项——与文档第 77 行单独报告的 4 项 `BilingualProbeBuffer` 噪声回归相加正好 22，**内部自洽**，只是快照未更新。
* 文档第 30 行「BilingualTranslationTest、BilingualTranslationUiTest、AoqDefaultPreferencesTest 共 36 项」：这三个类当前分别为 **22 / 10 / 17 = 49**，36 已过期（第 57 行的「41 项」是另一个更晚的快照）。
* 后端「95 项」「100 项」无法从文档给出的文件范围复现：直接翻译相关的测试文件合计 24 项（`test_assistant_translation_direct.py` 11、`test_assistant_translation.py` 8、`test_assistant_translation_config.py` 3、`test_assistant_prompts.py` 2），整个翻译测试面（含 capture/meeting/private/upload/archive）约 105 项。数字量级合理，但文档没有给出可执行的选取范围。

**建议**：把每处「N 项通过」替换为一条可复现命令（gradle filter / pytest 路径）。

### P2-3 证据链实际成立（正面结论）
本机核对通过：
* 提交 `7961947b`（Android，"Use backend-managed translation voices and assistant prompts"）与 `3fd572f6b`（后端，"Manage translation voices and assistant prompts in backend"）均存在，时间戳 2026-10-09 15:31，与文档描述一致；
* 迁移 `core/migrations/0198_seed_translation_voices.py`、`0199_manage_assistant_prompts.py` 存在；
* 交叉引用 `we-meet/docs/reviews/managed-assistant-production-2026-10-09.md`（及 `.json`）、`we-meet/docs/features/assistant-prompts.md` 存在；
* 文档第 67 行的内部 APK `release/0.3.0-work.2-managed-assistant-20261009/we-meet-managed-assistant-release-internal.apk` 存在（144 MB，2026-10-09 15:36）。
* Helm revision 461 与阿里云探针结果无法从仓库核实（第 8 节）。

### P2-4 语言列表三处重复
`BilingualLanguages.kt:9-25`、`core/api/assistant_translation.py:26-29`、agent 插件 `live_translate.py:25-32` 的 `AUDIO_LANGUAGES`。**当前三方完全一致（29 项，已逐项比对）**，但仅靠注释保持同步。建议由一处生成或用契约测试锁定。
（顺带核实：后端 `LANGUAGES` 与 Android `labels` 都是 29 项且集合相同。网关另有 `TEXT_LANGUAGES`（+32 项纯文本语言），App 仅支持音频语言，符合其用途。）

### P2-5 双语模块 i18n 干净，但工具存在盲区
* `bilingual_translation.xml` 在 en / zh-rCN / fr / de / nl 各 **60 键，键集完全一致，无缺漏无多余**；`bilingual_voices.xml` 47 键。`python scripts/i18n.py check --module app` 对双语模块 **0 错误**。
* 但 `bilingual_voice_*` 在 `values/bilingual_voices.xml` 标记 `translatable="false"`，同时又在 `values-zh-rCN/bilingual_voices.xml` 提供 47 个中文别名，而无 de/fr/nl 版本。`android_i18n.py` 会把 `translatable="false"` 排除在门禁之外，**这些中文覆盖永远不会被检查、`plan`/`merge` 也不会产出**；表现为中文用户看到「Tina（甜甜）」而 fr/de/nl 用户看到拉丁名。
* 硬编码符号无内容描述：`"⇄"/"→"`（`BilingualTranslationScreen.kt:160`）、`"✓"`（`:178`）、`"—"` 占位（`BilingualTranslationSettingsScreen.kt:89`），对读屏与 RTL 不友好。
* **注意**：仓库级 `python scripts/i18n.py check` 当前失败 **273 项**，但全部落在 `feature-assistant/strings.xml`（267）与 `realtime-client/strings.xml`（6），即模块搬迁中的欠账，**`app/` 下没有一项**。因此在当前状态下，i18n 门禁不能作为发布证据使用（这与双语模块本身无关，但会影响「设计规范检查通过」这类说法的可信度）。

### P2-6 客户端存在死代码与两条会漂移的翻译管线
* `AssistantTranslationApi.ticket()`（`data/api/AssistantTranslationApi.kt:45-47`）在 main 源码中**已无调用方**；线上网关路径走的是另一个接口 `CaptureTranslationApi.ticket`。
* 两条管线规则已经不同：双语互译支持 29 种语言且只认 3.8（`BilingualLanguages.kt:9-25`、`DirectBilingualWire.kt:83`）；录像/采集翻译只允许 zh/en，且为了读取历史产物仍接受 3.5 模型（`data/repository/CaptureTranslationRepository.kt:75-77,90`）。
* `AiHubScreen.kt:60-65` 的双语互译入口**没有功能开关**，随版本对所有用户可见。

**建议**：为旧网关路径指定负责人与下线时间，或明确记录其长期存在。

### P2-7 其他
* `BilingualPreferences.load()` 在读取路径里写入（`:20-22`），且注释「without overwriting later user choices」与紧邻的 `:21`（确实会覆盖已存的 `direct-aoq=false`）自相矛盾。影响面有限（旧 Release 从未真正生效过云端选项），但应修正注释或把迁移移出读取路径。
* `stop()` 只在内存里把音色校正回目录值而不落盘（`Controller:210`），进程若紧接着被杀会造成偏好与状态短暂不一致。
* 音色下拉在「当前音色不在非空目录中」时显示 `—`（`BilingualTranslationSettingsScreen.kt:89`），与 `resolve()` 的保证是两条不同来源，建议统一。
* AOQ 自动方向绑定 **3 个独立进程**（`:aoq_translation`、`:aoq_language`、`:aoq_translation_reverse`，`realtime-client/src/main/AndroidManifest.xml:10-15`）。**进程隔离本身是正确的**——AOQ SDK 的 `createEngine` 是全局单例、`destroy()` 是静态方法，一个进程只能有一个引擎。但 3 个原生进程的内存开销应纳入低端机评估（见第 3.3 节）。注意：此前版本的本报告把「输入走 Realtime JSON」也一并称为「符合官方要求」，**该判断有误**，见 P1-12。

### P2-8 同一模型两种断线恢复策略，用户可见行为不一致
网关路径实现了传输恢复并在同一会话内透明重连（`live_translate.py:17-22,531-535`，按关闭码区分可重试/不可重试）；App 直连路径则完全禁止重连（文档第 17 行「禁止重定向、自动重试建连」）。对成本控制而言后者是**有意为之**——直连重连需要重新申请分配、重新计费。但结果是：同一网络抖动下，会议采集翻译能自愈，面对面互译直接中断并报错。这个差异既没有在文档中记录，也没有给用户任何「一键重连」的入口。

**建议**：保留不自动重连的成本原则，但在断连时提供**用户确认的一次性重连**（P1-9 的建议覆盖了 WebRTC，应扩展到 AOQ 与全部错误码），并在设计文档中记录该策略差异及其理由。

---

## 7. 值得肯定、不应在重构中丢掉的设计

* **密钥与凭据边界清晰**：`parse_aoq_allocation()` 按白名单裁剪字段后才返回（`core/api/ai_call.py:42-66`）；API Key 只存在于服务端（`assistant_translation.py:200,215`）；`allow_redirects=False`；模型与上游 URL 由服务端固定，客户端无法指定（`:207-212`）；响应里不含上游地址。网关侧同样拒绝重定向（`live_translate.py:63-68`）。
* **Offer 校验到位**：必须 `v=0` 且同时含 `m=audio ` 与 `m=application `（`assistant_translation.py:168-177`）；AOQ 不允许携带 SDP（`:178-179`）；Offer 与 Answer 双向 128 KiB 上限，超限抛错转 502（`ai_call.py:21`、`assistant_translation.py:240-241`）；Answer 在服务端统一 CRLF 规范化（`ai_call.py:75`），客户端再防御性规范一次。
* **日志清洗彻底**：双语包内 18 处日志全部只记事件枚举、耗时、字符长度、类名与代码位置；SDP 失败原因被归一化为 `invalid_description` / `negotiation_rejected`（`WebRtcBilingualConnection.kt:234-236`）；`BilingualRow` / `BilingualState` / `DirectAILeaseInfo` 的 `toString()` 显式改为 `<private>`，杜绝误打印。
* **缓冲与配额边界明确**：WebRTC 外部采集 PCM 320 000 B、探针缓冲 320 000 B、回放队列 2 880 000 B、回放缓存 5 760 000 B（单条 1 440 000 B）、出站 DataChannel `bufferedAmount ≤ 64 000`、事件通道 `Channel(256)`、事件体 ≤300 000。越界一律 `check` 失败即中止会话——是 fail-closed 而非静默丢数据。
* **连接门控与排空顺序正确**：配置前 `setSending(false)` 且 sender 无 track，收到 `session.updated` 后才挂载并启用（`WebRtcBilingualConnection.kt:70,153-176`），避免初始化静音 RTP 提前启动翻译；提交与结束前等待本地 PCM 排空（`:188-191`），且静音填充不会推进 `consumed` 标记（`BilingualPcmBuffer.kt:26-28`），排空判据可靠。
* **噪声场景的处理是正确的**：不再以异常终止会话，而是开启新的识别窗口并继续监听，且用 `generation` 丢弃迟到的旧识别结果（`DirectBilingualWire.kt:160,164-168`、`BilingualProbeBuffer.kt:6,15-26`），避免把旧结果套用到新音频上。
* **三条传输共用一套业务协议与路由**：`CaptureTranslationWire` 接口 + `DirectBilingualWire` 的路由/翻译结果处理对 AOQ 与 WebRTC 完全共用，切换传输不触碰业务逻辑——这也是第 3 节「换传输不需要重写」判断的代码依据。

---

## 8. 未能核实 / 未被现有验收覆盖

**无法从仓库核实**：Helm revision 461；阿里云 47 音色探针、LiveTranslate 会话时长上限、真实百炼 SDP 往返结果；文档中「生产环境已发布」的部署事实（代码提交已核实，部署未核实）；后端 95/100 项测试的具体选取范围；AOQ 在不同网络环境下的实际连通率与丢包表现。

**现有验收明确未覆盖（文档自己也已声明，这里汇总为行动项）**：
1. 真机麦克风、蓝牙/A2DP 路由、弱网下的音频与回声表现——当前全部是模拟器 + 合成语音；
2. 业务租约心跳在真实登录链路下的端到端验证（AOQ 噪声复验时租约是私下转交的）；
3. 账号登出后「迟到响应不应用」路径——客户端没有测试引用 `captureAccount`/登出；
4. 空闲长挂与噪声条件下的成本曲线（P0-1），以及上行带宽的真实流量统计（P1-12）；
5. 限额被真正配置（非 0）时的自动方向行为（P0-2）；
6. 会话结束时连接不健康的收尾路径（P1-7）；
7. 供应商模型版本变更时的客户端降级行为（P1-6）；
8. 第 3.7 节的四项 spike（尤其冲突 C：`session.update` 中途改语言）。

---

## 9. 建议的下一步

1. **P0 三项**（空闲超时、配额与 `enforce` 语义、`expired` 文案与代码对齐）——建议 1 个迭代内完成，并把结论回写文档。
2. **按第 3.7 节做四项 spike，再定传输路线**（第 3.6 节）。其中 spike 4（外部推流走媒体轨）投入最小、收益最直接，建议最先做——它能在不改架构的前提下把上行带宽从 ≈341 kbps 降到 ≈20 kbps。
3. **统一 `session.update` 的事实来源**：由后端渲染会话配置正文，App 与网关共用（P1-5）；这是消除「网关调了静音窗、App 硬编码 1000 ms 导致最后一句被截断」的唯一办法。
4. **补真机音频验收矩阵**：扬声器 / 有线耳机 / 蓝牙 A2DP × 自动 / 固定方向 × 有噪声 / 无噪声，重点看回声尾音与插话丢失（P1-1）。
5. **补 `direct-aoq` → `transport_v1` 的显式迁移与降级测试**（P1-2）。
6. **把本文档升级为正式设计方案**（P2-1），把测试数量换成可复现命令（P2-2），并修正 P1-3、P1-5、P2-7、P2-8 中的文档错误与缺失。
7. **决定旧网关路径的归属**：保留则记录负责人与下线时间，不保留则删除 `AssistantTranslationApi.ticket()` 等死代码（P2-6）。
