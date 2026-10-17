# 双向翻译 WebSocket 设计与修复跟踪

日期：2026-10-16。取代旧文档的传输选型；旧文档保留为历史验证记录。

## 决策与范围

双向翻译仅支持 App → 业务 WebSocket 网关 → 百炼。移除本模块的 AOQ、WebRTC 入口、连接实现和专用进程。AI 通话、会议、采集翻译及其共享依赖不属于删除范围。业务会话保持 29 种音频语言、自动/固定方向、音色、播报控制和逐句回放。

服务端签发一次性、短时效凭证；凭证仅通过首帧发送。模型 API Key、模型事件配置及上游地址由服务端管理。供应商音频分片和完成事件在同一 WebSocket 内按序处理，网关按输出项标识转发音频与结束事件。客户端分别管理接收完成、播放排空、声学尾音和会话结束，不根据音频到达间隔猜测结束。

## 实施顺序与完成证据

以下分别记录代码修复、本轮验证和发布状态；仅有实际证据的测试标为通过，用户豁免与发布状态单独列示。

| 清单 | 工作 | 状态 |
| --- | --- | --- |
| 01–03 | WebSocket 完整功能、逐句边界、统一模型配置 | 已实现；离线协议、本机 socket、真实供应商固定/自动方向探针通过 |
| 04–05 | 空闲/最长时长、噪声退避、业务会话配额及网关强制执行 | 已实现；默认 300 秒持续底噪、16 次续约、空闲结束及业务关闭通过；已记录流量，用户确认实际账单金额后续核对 |
| 06–07 | 页面/账号/异常释放、到期文案、主动结束排空 | 已实现；修复短时 ACK 停顿引发的录音缓冲耗尽后，61 项模拟器设备测试通过 |
| 08 | 删除专用直连代码、迁移偏好、旧版退役与入口开关 | 已实现并通过本地回归；生产尚未切换 |
| 09 | 登录/鉴权/配额/边界/回放/账号切换及容量验收 | 隔离业务到供应商链路通过；OPPO 上真实 PKCE 登录、UserInfo、401 刷新与加密持久化通过，WebView 点击未覆盖；用户明确本轮不验并发容量 |
| 10–11 | 路由相关回声尾音、集合上限、取消回放竞态 | 已修复代码；真机扬声器自动/固定方向 × 安静/敲击桌面背景声共 4 项通过程序检查及人工听感确认；有线/蓝牙耳机按用户要求本轮豁免，未实测 |
| 12–14 | 配置启停/异常、能力版本、用户触发重连 | 已实现；缺失版本按旧协议处理，新增回归通过 |
| 15–16 | 流量/背压/可观测性、公共能力归属及死代码 | 队列、日志及本机 64 连接门禁已验证；3% 丢包与 100±25 ms 抖动链路通过，内核实际记录 19 次丢包；用户明确本轮不验并发容量，不作生产容量承诺 |
| 17–20 | 正式设计、可复现证据、语言/i18n/无障碍、音色偏好一致性 | 已补设计、命令、契约脚本；app i18n 和本轮设备回归通过 |

## 产品行为与边界

用户选择语言对、方向和音色后主动开始。自动方向对每个语音片段识别来源，再送入对应的翻译连接；固定方向只向指定连接发送。手动模式中更换语言仍保留手动模式，原语种跟随左侧语言。会话期间这些配置锁定，播报和暂停收音可随时调整。

本版保持半双工：播报或回放时，上行麦克风帧替换为静音；关闭播报后持续收音。WebSocket 不提供 AEC，不能将本次切换描述为解决了双讲或回声消除。客户端等待输出排空，再根据输出路由保持尾音保护。当前路由初值为蓝牙 600 ms、有线/USB 100 ms、扬声器/听筒 300 ms、未知 400 ms，须由真机测试校准。

返回或导航离开页面停止会话并清除页面回放缓存；应用进入后台或锁屏由既有前台服务继续承载。屏幕旋转保留会话。停止后仍在当前页时，已完整收到的音频允许回放；不完整音频丢弃。页面最多保留 100 行，回放 PCM 总量不超过 5.76 MB，单句不超过 1.44 MB，最多 100 条；会话停止封闭缓存写入，离页清空。

不在本次范围内：AI 通话的 AOQ/WebRTC、会议媒体链路、通用 SDK 删除、全双工 AEC、自建 TURN。双向翻译专用 AOQ 三进程与 WebRTC 实现直接退役，相应原生音频 spike 不再作为上线前置项。

## 准入与协议

1. 登录客户端读取 `GET /api/v1.0/assistant-translation/config/`。响应包含 `protocol_version=2`、`enabled/reason`、模型标识、音色目录、默认音色及 29 种音频语言。模型标识用于展示和诊断，Android 不按具体型号字符串锁定协议；缺失版本按旧协议处理。
2. 开始时提交 `POST /api/v1.0/assistant-translation/ticket/`，冻结 `source_language`、`target_language`、`fixed_source`、`voice`、`protocol_version`。事务锁定用户，按一次业务会话检查并发与每日额度。自动方向内部的多连接不会重复消耗会话额度。
3. 后端返回无凭据、无查询串的 `wss://…/capture-translation` 地址与有效期 30 秒的一次性签名 ticket。客户端通过独立 OkHttp 客户端连接，不重定向、不自动重试；ticket 只放在首条 `assistant_translation` 消息内。
4. 网关调用内部 claim 接口，数据库行锁保证 ticket 只能消费一次。仅通过后端校验后才打开供应商连接。用户客户端没有内部 claim/lease 权限。
5. 固定方向打开一条翻译连接；自动方向打开两条翻译连接，并按需打开单次语言识别连接。识别不预热付费连接，不做方向冲突的额外模型修复。它不是固定三条永久连接的原直连架构。
6. 网关发出 `ready` 后才启用麦克风。上行是 16 kHz 单声道 S16 二进制 PCM，每帧最多 3200 B，逐帧 ACK；下行使用 `translation`、`audio`、`audio_end`、`language_unknown`、`finished`、`expired`、安全枚举 `error`。
7. 下行译音为 24 kHz 单声道 S16 PCM。供应商的 response/item ID 关联到网关输出 ID；响应完成事件关闭对应音频项。分片间隔再长也不作为句子结束依据。`audio_end` 只表示该项接收完成，不等于设备已播放完。

## 时限、成本和重试

后端开关默认关闭。默认每用户并发 1 次、每日 100 次、业务会话最长 900 秒、有效输入/译文空闲 300 秒。零配额或无效时限配置拒绝启动，不表示无限制。每日额度统计包含已签发但未使用的 ticket，按服务端 UTC 日界计算；弃用 ticket 最多占用并发名额 30 秒。API 节流为每用户 20 次请求/分钟。

业务会话最长时长从 ticket 分配开始，claim 下发剩余秒数，网关启动过程也消耗此预算。网关为尾句排空预留 30 秒（供应商结束最多 25 秒及下行发送余量），不另起一个完整 900 秒计时。网关每 20 秒向后端续约，租约最长 90 秒且不超过业务截止时间；后端停用用户、停用功能、租约过期或主动关闭均不可续活。失去租约立即终止供应商连接，异常关闭仍尝试归还业务会话。

有效活动只由路由成功的语音或输出译文/音频刷新；PCM、ACK、静音心跳及 unknown 不延长空闲时限。连续未知语言达到 3 次后，探测间隔按 1、2、4、8、16、30 秒退避，最大前缀缓存 10 秒；判定成功后复位。此策略可能暂时丢弃噪声之后紧跟的短句，界面提示未识别语言并继续监听。识别连接发生传输错误则结束本次会话，不偷偷重新收费重试。

双向翻译禁用透明传输恢复。连接失败后保留已完成结果，由用户再次点击开始申请新会话，不重播未确认的上行音频。采集翻译的恢复/分段策略保留其原产品语义，公共传输组件不把该差异隐式抹平。

用户主动结束先排空上行 ACK，再发送 finish；网关排空 VAD 前缀与供应商尾句，客户端继续播放已收到的音频。收尾不完整时返回停止状态并提示“最后一条结果可能不完整”，不把它标记成完整成功。到期提示不再承诺固定“15 分钟”。

## 容量与观测

移动端上行二进制 PCM 约 32 kB/s（约 57.6 MB/30 分钟，未含帧头）；供应商侧 Base64 音频约 42.7 kB/s/流。下行 24 kHz PCM 的 Base64 约 64 kB/s/有声输出。网关目前还向未选中的翻译连接发送静音维持流；自动方向空闲时两条翻译流均有该开销。语言识别会再次发送待识别前缀。因此不能把 WebSocket 切换宣传为 Opus 带宽优化，实际费用和双向流量须在测试环境测量。

网关每进程与采集翻译共享 64 个连接名额；这是保护上限，尚不是容量承诺。单客户端入口队列最多 15 秒 PCM、供应商关联项/音频有界，所有发送有超时。2026-10-17 修复候选将 Android 在途窗口设为最多 50 帧，覆盖每帧 100ms 时的 5 秒 ACK 预算；待发送录音另有 50 帧上限（PCM 不超过 160,000B），出站队列不超过 64,000B，事件队列 256 条，播放队列 2.88 MB。发送前为整帧预留写队列空间，空间不足时最多等待 5 秒；ACK 看门狗也保持 5 秒。超时或缓冲耗尽结束会话，避免无界内存或静默截断。早期历史记录中的 8 帧窗口不能支撑持续超过 800ms 的 RTT，已被本候选替代。

日志只记录随机会话关联码、枚举、字节数、数量和耗时。当前包括语言识别耗时/unknown、首音频与方向门控耗时、客户端 ACK RTT、播放等待、会话总时长及输入输出字节数。不得输出 ticket、API Key、用户标识、原文、译文或音频。

## 发布、退役与公共组件

生产迁移为 `0227_bilingual_session`，依赖 `0226_remote_control_signing_rotation`，创建独立业务会话表，不修改 AI 通话配额；`0203_bilingual_session` 仅是早期开发隔离环境的历史编号。先部署支持 V2 的后端与网关并保持开关关闭，再验证新 APK 与测试环境，满足上线门禁后才开启。旧直连接口统一返回 HTTP 426，不再向旧客户端签发 AOQ/WebRTC 凭据。新版删除连接方式选择并清除本模块历史偏好键。当前生产状态和 10 月 17 日未闭环的声学反馈见[内部 Release 记录](bilingual-websocket-release-2026-10-16.md)。

回滚优先关闭 `ASSISTANT_TRANSLATION_ENABLED`，停止新准入，并由网关续约检查关闭存量会话；保留迁移数据用于审计，不回滚为无配额旧直连。已升级客户端的入口由配置开关控制；直接进入页面仍检查可用性，后端再次强制执行。

`plugins/qwen/live_translate.py` 继续负责供应商协议；`assistant_gateway.py` 负责双向翻译业务准入、路由和时限；`capture_gateway.py` 保留采集翻译入口和公共 WebSocket 承载。Android 复用 `CaptureTranslationWire`、采集源与输出组件，双向翻译控制器负责本页状态与回放。产品边界分别归属现有 App 助手模块与 agents 翻译模块，责任人仍需项目维护者指派；旧 `AssistantTranslationApi.ticket()` 已成为实际使用接口，不能再按死代码删除。

## 基线

- Android HEAD：`9699c335`；后端 HEAD：`e95c6ebb8`。两个工作区已有大量与本任务无关的未提交修改，实施保留这些修改。
- 网关已有 `translation/assistant_gateway.py` 和 `bilingual_*` 实现，复用其按输出项关联与有界路由；原实现没有客户端固定方向和音色选择的完整契约。
- 修改前网关基线：在 `we-meet/src/agents` 下设置 `PYTHONPATH=src`，执行 `.venv/Scripts/python.exe -m unittest tests.test_assistant_translation tests.test_bilingual_audio tests.test_bilingual_router tests.test_bilingual_settings tests.test_bilingual_languages`：45 项通过。
- 修改前仓库 i18n：`python scripts/i18n.py check` 有 273 项错误，位于其他在改模块；本任务需保持双向翻译及 app 模块检查通过，并单独报告全仓基线。

## 验收边界

本地单元测试、协议集成测试、Android 编译/设备测试、真实供应商测试、真机声学验收和生产部署证据分别记录，互不替代。本轮有线/蓝牙耳机未实测、并发容量不验，均由用户明确豁免；费用按用户要求先记录用量，实际账单金额后续核对。这些项目不计为测试通过，也不构成生产容量或实际费用承诺。

## 2026-10-16 验证记录

本轮未提交、未发布 APK，未替换生产部署。现有工作区还有其他功能的未提交修改，因此这些数字与下面命令绑定，不代表整个工作区全部功能已验收。

### Android

在 `we-meet-android` 执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process'
adb -s emulator-5586 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5586 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5586 shell am instrument -w -r -e class com.we.meet.ui.ai.BilingualTranslationTest,com.we.meet.ui.ai.BilingualTranslationUiTest,com.we.meet.ui.ai.AoqDefaultPreferencesTest,com.we.meet.ui.ai.AssistantSceneTest com.we.meet.test/androidx.test.runner.AndroidJUnitRunner
python scripts/check_bilingual_contract.py
python scripts/i18n.py check --module app
```

- JVM：750 项，0 失败；Debug 主 APK 和测试 APK 构建成功。
- Pixel_8 模拟器初轮：60 项，0 失败，74.091 秒。包含关闭播报后回放、真实 AudioTrack 排空、锁屏与后台、旋转/离页、账号退出、迟到凭证、显式结束、到期排空、700 ms 音频分片间隔、配置停用/旧版本、旧偏好迁移和入口隐藏。后续 ACK 停顿修复后的 61 项重跑结果见本文末节。
- 第一轮曾发现两项失败：握手前失败通知未立即唤醒启动流程，以及测试错误地要求新的回放播放器累计旧播放器数据；修复后全套重跑通过。
- 三端语言契约：29 项集合一致。app i18n：0 错误、158 条提示（141 条为音色专名保持拉丁拼写，17 条为既有法语复数形式）。全仓原有其他模块的 273 项错误不能计作本模块通过证据。
- 初轮主 APK SHA256：`B4C6F7290A06C463130F3A3D6E1106402C66B4AFCCDB5C48425D5BD255ED669C`。其后至外部身份验收期间仅新增测试 APK；最后的 ACK 停顿修复更新了主 APK，见本文末节。

### 后端与网关

在 `we-meet/src/backend` 执行：

```powershell
.venv/Scripts/python.exe ../../scripts/test_bilingual_backend.py
```

该脚本只连接本机 loopback 的 `meet-import-validation` PostgreSQL，创建本次专用数据库，运行四个指定测试文件，再清理该数据库。94 项通过，115.15 秒；模型迁移检查无漂移。覆盖业务会话配额、并发准入、一次性 ticket、权限、租约撤销、关闭后不可复活、音色方向冻结及旧直连接口退役。新增 5 项非法 JSON 请求体回归，避免数组/标量等触发 HTTP 500。

在 `we-meet/src/agents` 执行：

```powershell
$env:PYTHONPATH='src'
.venv/Scripts/python.exe -m unittest tests.test_bilingual_websocket tests.test_bilingual_gateway_socket tests.test_assistant_translation tests.test_bilingual_audio tests.test_bilingual_router tests.test_bilingual_settings tests.test_bilingual_languages tests.test_bilingual_recovery tests.test_qwen_live_translate tests.test_omni_language_id tests.test_bilingual_direction tests.test_capture_translation_gateway
```

137 项通过。两个 socket 测试使用真实本机 WebSocket 和生产路由/归一化实现，仅替换上游模型、准入服务及 VAD 推断：验证末句、音频结束与 finished 的顺序，以及 64 条并发已占满时，第 65 条在申请供应商连接前被拒绝，断开后所有资源回收。它不是生产压测。

### 真实供应商探针

确认现有服务器运行 `meet.we-meet.online`；现网翻译网关尚未部署本次 V2 协议。在网关容器的随机隔离目录加载当前源码和两个既有合成 PCM 夹具，运行 `scripts/live_bilingual_probe.py --assets <夹具目录>`。真实 WebSocket、Silero VAD、语言识别、LiveTranslate 与音频完成链路均实际执行；业务 claim/lease 使用专门夹具，未写生产用户、数据库或路由，结束后已删除隔离目录。

| 场景 | 结果 | ready 耗时 | 收到译音 | 完整结束 |
| --- | --- | --- | --- | --- |
| 固定 zh → en，Tina | 通过，1 条翻译连接 | 336 ms | 84,480 B | audio_end + finished |
| 自动 zh → en / en → zh，Tina | 通过，2 条翻译连接及按需识别 | 231 ms | 84,480 B / 96,000 B | 两个 audio_end + finished |

[探针报告](reviews/bilingual-websocket-provider-probe-2026-10-16.json)仅保存语言代码、字符数、音频字节数、耗时和错误码；不包含文本、音频或凭据。该初轮探针未覆盖业务准入与 Android 客户端，后续补验见下一节。真机声学、外部弱网和生产容量另行记录。

### 隔离业务链路与 Android 端到端补验

后续已补齐真实业务接口与 Android 链路，见[集成报告](reviews/bilingual-websocket-integration-2026-10-16.json)。后端使用本机临时 PostgreSQL 数据库与临时登录会话，实际执行 SessionAuthentication、CSRF、签名 ticket、一次性 claim、配额事务和续约 HTTP 请求。模型连接使用真实供应商，验证后删除测试数据库；未改动生产部署、用户和数据库。外部 OIDC 登录流程不在该夹具覆盖范围内。

| 场景 | 结果与证据 |
| --- | --- |
| 固定 zh → en，Ethan | 通过；1 条翻译连接，72,960 B 译音，audio_end，30.626 秒；初次续约及 20 秒后的续约均成功 |
| 自动 zh ↔ en，Tina | 通过；两种方向分别 80,640 / 99,840 B，均收到 audio_end；只消耗 1 个业务名额 |
| 持续低幅底噪，自动方向 | 通过；设置空闲阈值 30 秒，收到 expired/idle，无译文；包含收尾与关闭握手共 43.545 秒，业务会话关闭 |
| Android 模拟器 → HTTPS API → WSS 网关 → 供应商 | 通过，20.963 秒；真实控制器、Retrofit、OkHttp、TLS 主机校验、合成麦克风、AudioTrack 回放、显式结束、数据库关闭 |

匿名取票、普通用户领取内部票据、占满并发后的再次取票、重复消费 ticket、旧直连接口均按预期拒绝。三个业务测试会话全部关闭，Android 独立测试中的会话也关闭。

同一份集成报告还记录了网关实际收到的 PCM 与实际发送的 JSON 负载，补充带宽估算的测量依据：

| 场景 | 收到上行 PCM | 等价 PCM 时长 | 发出下行 JSON |
| --- | ---: | ---: | ---: |
| 固定方向，含续约等待 | 795,520 B | 24.860 秒 | 101,750 B |
| 自动方向，两种语言 | 176,480 B | 5.515 秒 | 242,867 B |
| 连续低幅底噪，到期结束 | 889,600 B | 27.800 秒 | 4,344 B |

计数位置是网关接收 PCM 与发送 JSON 成功之后；JSON 含 Base64 音频、文本及控制事件，计数不保留这些内容。它不含握手、WebSocket/TLS/TCP 头和重传，也未统计供应商侧独立流量或账单费用。整个用例耗时包含启动与关闭等待，不能直接当作持续录音时长。30 秒空闲配置用于缩短验证，不能将它冒充生产默认 300 秒的长挂成本曲线。

在配置供应商环境变量的终端中，于 `we-meet/src/backend` 执行：

```powershell
.venv/Scripts/python.exe ../../scripts/test_bilingual_live_integration.py
.venv/Scripts/python.exe ../../scripts/test_bilingual_live_integration.py --android emulator-5586 --client-only
```

Android 用例需先安装本轮 Debug APK 和测试 APK；`BilingualLiveIntegrationTest` 默认跳过，只有隔离脚本提供本机 HTTPS 地址、临时证书与会话时才执行。脚本创建临时 TLS 证书和独立网关进程，只给此用例的客户端信任证书，保留主机名验证，完成后移除端口映射、证书与进程。生产客户端的 TLS 策略未改变。

补验过程中修正了三个测试夹具问题：续约等待阶段应持续发送静音、终态后客户端应主动关闭 socket、测试 Retrofit 应使用与应用一致的 Kotlin JSON adapter。各场景修正后已重跑通过；这些失败不作为通过证据。

### Android Bearer 鉴权补验

Android 正式 API 请求使用 Bearer Token，因此在 Session Cookie 测试之外又补跑一轮 Bearer 链路，见[鉴权测试报告](reviews/bilingual-websocket-bearer-probe-2026-10-16.json)。本轮未替换后端认证类：生产 `OIDCAuthentication` 实际通过 HTTP 请求本机 UserInfo 夹具，校验临时凭据并映射临时用户。身份夹具只接受本轮随机 Token；无效 Token 返回 HTTP 401。测试 API 客户端只携带 Authorization，不携带 Session Cookie。

- 固定中文 → 英文通过：69,120 B 译音、30.500 秒、两次心跳。
- 自动双向通过：84,480 / 96,000 B 译音、9.696 秒，一个业务会话。
- 底噪空闲结束通过：无译文、`expired/idle`，包含关闭等待共 43.532 秒。
- Android 到真实供应商链路通过：11.881 秒，音频显式结束、主动结束及回放均正常。
- 身份夹具接受 11 次有效查询、拒绝 1 次无效查询；4 个业务会话全部关闭，临时数据库和端口映射已回收。

```powershell
.venv/Scripts/python.exe ../../scripts/test_bilingual_live_integration.py --android emulator-5586 --auth bearer
```

该证据不覆盖外部身份服务、短信发送、Token 签发或客户端 TokenStore 刷新流程。通过本轮验收的测试 APK SHA256 为 `D9076257DE005F14A5D51CAC002549A3772055D140BE63A9C235EB6DFB1BA1F0`；随后为完整声学矩阵增加了测试页参数，更新包见下一节真机准备记录。

### 受控下行延迟补验

Android HTTPS/WSS 到真实供应商的链路加入 250 ms 下行传播延迟后通过，17.180 秒，见[延迟测试报告](reviews/bilingual-websocket-delay-probe-2026-10-16.json)。收到 1 条译文、3 个音频分片、`audio_end` 和 `finished`，回放成功、无收尾不完整提示，1 个业务会话关闭。

延迟夹具使用有界 FIFO，允许连续传输并保持帧顺序；校验中 10 帧约 251 ms 到达，关闭事件排在 `finished` 后。早期夹具曾把延迟错误实现为串行限速，并让关闭绕过待发消息；对应失败已查明并修正，以上结果来自修正后的重跑。该测试仅覆盖受控下行延迟，不代表丢包、蜂窝网络抖动或生产容量验收。

```powershell
.venv/Scripts/python.exe ../../scripts/test_bilingual_live_integration.py --android emulator-5586 --client-only --downstream-delay-ms 250
```

### 真机声学验收结果

已在 OPPO PDYM20（Android 12）安装 Debug 主 APK 和测试 APK；用户已同意使用该手机并准备扬声器、有线耳机、蓝牙耳机三种路由。首轮等待期间手机保持熄屏，未创建任何翻译业务会话，因此主动结束等待。测试运行器在被主动停止后显示的 `Process crashed` 不作为应用故障证据，也不计作声学测试完成。

测试页已加入保持亮屏处理，仍需用户自行解锁；首次开始通过系统正常权限弹窗申请麦克风，授权后再点开始。增加 Bearer 鉴权与完整声学矩阵参数后的测试 APK 构建成功并安装到该手机，SHA256：`29E42D7099C6DD2CC1E0E81781B877B09548B04A51CC41AA4C835B49F56D18F6`。

准备就绪后运行：

```powershell
.venv/Scripts/python.exe ../../scripts/test_bilingual_live_integration.py --android <设备序列号> --client-only --physical --auth bearer
```

每种路由分别说一句中文和一句英文，听完译音后再说下一句，随后安静十秒，验证没有额外回声翻译，结束并回放。程序检查双向结果、音频结束、对应路由尾音、无额外结果、会话关闭和回放状态；可懂度、尾字完整性、爆音及主观回声仍需人工听辨。

手机恢复 USB 连接并解锁后，扬声器自动方向、安静环境用例已完成：两条双向译文、两个 `audio_end`、结束与回放、300 ms 路由尾音检查均通过，业务会话关闭，用户反馈“扬声器测试完成，功能正常”。随后用户明确表示没有有线和蓝牙耳机并要求跳过，两种路由记为本轮豁免、未实测。旧的三路由批次在有线等待页主动停止，运行器的 `Process crashed` 对应该主动停止，不是扬声器用例失败。证据见[真机记录](reviews/bilingual-websocket-physical-2026-10-16.json)。

完整声学矩阵按原审查第 9 节包含 12 个组合。本轮完成 4 个扬声器组合，其余 8 个耳机组合按用户要求豁免、未实测。上述默认命令仅覆盖第一行；后续批次补齐剩余扬声器场景：

| 方向与环境 | 扬声器 | 有线耳机 | 蓝牙耳机 |
| --- | --- | --- | --- |
| 自动方向、安静环境 | 通过，用户确认正常 | 本轮豁免，未实测 | 本轮豁免，未实测 |
| 固定中文 → 英文、安静环境 | 通过，66.955 秒，用户确认清晰完整 | 本轮豁免，未实测 | 本轮豁免，未实测 |
| 自动方向、敲击桌面背景声 | 通过，65.287 秒，用户确认无明显回声或重复翻译 | 本轮豁免，未实测 | 本轮豁免，未实测 |
| 固定中文 → 英文、敲击桌面背景声 | 通过，47.455 秒，用户确认无明显回声或重复翻译 | 本轮豁免，未实测 | 本轮豁免，未实测 |

脚本新增 `--physical-mode automatic|fixed` 和 `--physical-environment quiet|background_noise`；默认依次验证三种路由，可按本轮豁免筛选。固定方向只要求一句中文，自动方向要求中英文各一句。背景噪声由现场人员准备和确认；停止说话十秒时保留环境声，以检查额外触发。本轮未测量声压级或记录具体播放音量，未单独取得播报期间插话场景的人工确认。

为执行本轮豁免，增加 `--physical-routes speaker` 路由筛选；通过 `--physical-scenarios fixed:quiet automatic:background_noise fixed:background_noise` 连续执行剩余三项扬声器场景，每项重新创建会话并显示独立开始按钮。三项均正常完成，运行器退出码为 0。该更新的测试 APK SHA256：`9CFB7A8E7E1674B26A0219CB3E9C793F0C91EA6ED8906D090186AC939CF245BB`。

用户确认：“剩余三项的译音和回放清晰完整，没有明显回声或重复翻译，噪声场景使用了敲击桌面的背景声”。因此本轮真机噪声证据限定为敲击桌面，不代表风扇、空调等连续背景声也已实测。

四项合计 6 条译文、6 个 `audio_end`、4 个 `finished`，300 ms 扬声器尾音及回放检查通过，4 个业务会话全部关闭。结束后确认临时数据库、该手机的 ADB 反向端口映射、集成测试及网关 Python 进程均无残留。用户随后提供演示账号继续外部登录验收，并明确本轮不验并发容量；后续补验见下文。

### 默认 300 秒持续底噪补验

将隔离后端设置为生产默认最长 900 秒、空闲 300 秒，重跑固定方向、自动方向和持续低幅合成底噪三个业务场景，全部通过。身份服务仍为本机 UserInfo 夹具，模型、业务准入、续约和关闭均实际执行，见[长时间空闲报告](reviews/bilingual-websocket-long-idle-probe-2026-10-16.json)。

底噪场景未产生译文，两个供应商翻译连接在约 303.617 秒发出 `expired/idle`，包含连接关闭等待总耗时 313.685 秒；16 次续约、一次关闭，数据库会话为 `closed`。网关实际收到 8,832,000 B PCM（276 秒等价音频），实际发出 41,575 B JSON。客户端按帧等待与调度使等价音频时长小于用例墙钟时长；不得把后者直接当作输入音频量。

该结果补齐默认时限下的实际运行证据，未读取供应商账单，不能据此宣称实际费用为零。用户确认“本轮记录用量，账单金额后续核对”。两个其他场景收到完整译音并正常关闭，三个业务会话均已释放。

```powershell
.venv/Scripts/python.exe ../../scripts/test_bilingual_live_integration.py --auth bearer --idle-seconds 300
```

### 外部身份服务与 Android Token 刷新补验

使用用户指定的演示账号，在 OPPO PDYM20 上完成真实 Keycloak 手机/验证码表单、PKCE 授权码交换和 UserInfo 校验，耗时 3.423 秒。随后仅将测试 TokenStore 中的 access token 替换为无效测试值，触发真实 HTTP 401；生产 `AuthInterceptor`、`TokenRefreshAuthenticator` 和 `KeycloakOidc` 完成刷新重试，UserInfo 状态依次为 200、401、200，刷新前后身份一致，重新读取加密 TokenStore 与刷新结果一致。见[外部登录报告](reviews/bilingual-websocket-external-auth-probe-2026-10-16.json)。

该用例采用专属名称的加密首选项，不修改主 App 的登录凭据。测试结束向身份服务注销本次会话，得到 HTTP 204，并删除专属首选项。测试 APK SHA256：`2219AD0DD3F4EF25827B0B03FFDB0035E247D3160EEEFFBEB6C7CAA2563D7A0A`。模拟器先前因业务域名 DNS 解析失败未到达验证码校验，改用真机后的结果才计为通过。

可复现测试类为 `com.we.meet.ui.ai.BilingualExternalAuthTest`，仅在临时 instrumentation 参数提供 `bilingual_demo_phone` 与 `bilingual_demo_otp` 时执行；默认跳过。账号、验证码、令牌及身份正文不写入证据文件。该结果覆盖外部身份协议和 Android 凭据组件，不覆盖 WebView 渲染/点击或运营商短信送达；V2 翻译业务仍在隔离后端验证，未切换生产部署。

### 实际丢包、抖动与 ACK 停顿修复

在隔离 Docker TCP 代理内对 WSS 两侧出口配置 Linux `tc netem`：100 ms 延迟、25 ms 抖动、3% 随机丢包，随机种子为 20261016。代理透传加密流量，仅发布本机 loopback 端口，不修改电脑或手机的全局网络配置。通过实际内核丢包计数确认故障注入生效，见[丢包回归报告](reviews/bilingual-websocket-network-probe-2026-10-16.json)。早期 1% 配置的一次运行没有实际丢包，只作为抖动证据，不计为丢包通过。

首次 3% 丢包运行记录 16 次丢包，客户端因录音队列耗尽失败：原队列只容纳 10 个 100 ms 帧，而 ACK 超时允许等待 5 秒，TCP 重传造成的短时停顿会提前触发录音错误。新增可重复的 ACK 暂停测试后，旧主 APK 在 3.489 秒失败，状态由预期的 `LISTENING` 变为 `ERROR`。

修复将待发送录音队列调整为 50 帧、最多 160 KB，保持 8 帧在途窗口和 5 秒 ACK 超时，不引入自动重连或静默丢帧。回归测试填满在途窗口后再暂停 ACK 2.2 秒，恢复时检查录音帧数与发送帧数一致，完成主动结束并释放麦克风；持续没有 ACK 的既有用例仍检查超时失败与资源释放。见[修复前后报告](reviews/bilingual-websocket-ack-stall-regression-2026-10-16.json)。

修复后重新构建主 APK 与测试 APK，750 项 JVM 测试通过，61 项设备回归全部通过（81.888 秒）。随后真实供应商链路在相同网络配置下通过（20.129 秒）：内核记录 426 个已发送包、19 次丢包，收到 1 条译文、4 个音频分片、1 个 `audio_end` 和 1 个 `finished`，回放成功，无错误码，业务会话关闭。该结果覆盖受控丢包与抖动，不代表真实蜂窝网络全部场景。

- 修复后主 APK SHA256：`D55E3B67AFF8C8FAC1CC2EEB58E31A2AE486FE57146A48568F957322D1E6BEE1`。
- 修复后测试 APK SHA256：`1538356FFB31793E58232F33C05433F0827AF5B4742F61AC4CB41BE016985618`。
- 修复后 APK 已安装至模拟器；前述 OPPO 声学与外部身份结果对应各节所列历史版本，未把它们记为新主 APK 的真机重跑。

于 `we-meet` 构建一次故障注入镜像，再在配置供应商环境变量的终端执行：

```powershell
docker build -t bilingual-netem:20261016 scripts/bilingual_netem
src/backend/.venv/Scripts/python.exe scripts/test_bilingual_live_integration.py --android emulator-5586 --client-only --auth bearer --network-profile loss-jitter
```

结束后确认测试数据库、故障注入容器、测试网关/集成脚本进程及模拟器和 OPPO 的 ADB 反向端口映射均无残留。保留本地故障注入镜像以便复现。

### 本轮收尾状态

本轮代码修复及约定范围内的测试验证已完成。双向翻译统一使用业务 WebSocket 网关，专用 AOQ/WebRTC 实现退役，共享会议与 AI 通话能力保留。真实供应商、真实外部身份、默认空闲时限、受控弱网和扬声器声学证据均已记录；有线/蓝牙耳机与并发容量按用户要求本轮豁免，实际账单金额后续核对。未提交或部署生产，发布仍按本文的开关与退役步骤执行。
