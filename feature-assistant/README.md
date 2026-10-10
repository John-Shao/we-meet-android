# AI 电话

「AI 工具 → 打电话」默认使用 Qwen 3.8 Omni AOQ 直连，Debug 与 Release 一致。普通会议的 LiveKit 通话不受影响。

## AOQ 正式默认接入（2026-10-08）

- 新安装默认 AOQ；升级时将原验证阶段的 WebRTC／云端偏好一次性迁移到 AOQ，保留音色、提示词、场景、翻译语言与播报设置。迁移后手动选择的接入方式持续保留。
- 通话设置保留 WebRTC。双语互译的新实现提供「AOQ 客户端直连／WebRTC 客户端直连」，以 WebRTC 代替原云端网关选项，均只能在会话开始前手动切换。需同步更新后端 SDP 分配接口，连接失败不自动创建另一条收费会话。
- 双语互译不再受 `BuildConfig.DEBUG` 限制，默认通过 `DirectBilingualWire` 建立正向翻译、反向翻译和语言识别连接；后端只分配会话凭证，音频直达模型。
- 两条互译直连通道共用自动／固定方向：自动模式建立正向翻译、反向翻译和 Omni 语言识别三条连接；固定方向仅建立一条翻译连接。识别依赖云端模型能力，不依赖业务网关。方向按账号保存，改变语言对时恢复自动，切换 AOQ／WebRTC 保留方向，会话中不可修改。
- WebRTC 使用 RTP 音频轨道、DataChannel 模型事件及后端 SDP 交换；外部 PCM 输入关闭 SDK 内部录音，远端 PCM 复用现有播报／回放队列。双语互译两种通道均由 App 播放器控制媒体音量，下面的通话音量区别仅针对 AI 电话。
- AOQ 默认沿用媒体音量控制；WebRTC 沿用通话音量控制。两种音量由系统分别保存。
- `app` 模块负责打包 `libs/AoqClientSdk-release.aar`；`feature-assistant` 使用编译期依赖。宿主集成时必须包含该 SDK，避免 library 的 Release AAR 检查失败。SDK 与 Opus 原生库沿用现有 ARM 版本。
- `AoqDefaultPreferencesTest` 覆盖新安装、旧偏好迁移、手动回退持久化和账号隔离；`BilingualTranslationTest` 验证默认直连不申请云端 ticket，以及会话中禁止切换；设置 UI 测试验证正式入口。

## 通话生命周期

- 候选客户端支持后端返回可选 `session_lease`：每 30 秒报告心跳，连接关闭时释放；旧后端未返回时沿用现有行为。后端迁移 `0197` 新增申请记录，两个准入限额默认 0，只观测；开启活动限额时客户端处理租约拒绝，纯观测故障不打断直连音频。应用声明与供应商费用、实际并发分开核对。发布顺序和验收见 [接入改进记录](../../we-meet/docs/reviews/llm-integration-improvements-2026-10-08.md)。
- 客户端从目录读取 `model_code=aliyun/qwen3.8-omni-flash-realtime` 的 profile。
- 音色和提示词共用一套选择；旧版视频设置优先迁入，失效音色按新目录默认值回退。
- 使用用户登录态调用 `POST /api/v1.0/ai-call/session/`。AOQ 返回会话令牌与 Relay 配置；WebRTC 返回 SDP answer，两者均返回音色、提示词。
- 客户端不接收百炼 API Key，也不创建 LiveKit 房间或分派 AI worker。
- WebRTC 预先协商双向音频、上行视频、DataChannel。收到 `session.created` 后发送配置，
  等待 `session.updated` 才开始上行媒体；同时支持服务端创建的 `txt` 事件通道。
- 摄像头按需开启，视频目标 720p / 2 fps；关闭时解绑发送轨道并停止采集。
  摄像头开关保留连接、音色、提示词和模型对话上下文。
- 切换其他 App、回到桌面或息屏保留通话及已开启的摄像头。主动挂断、返回退出
  对话页、通知栏点击结束或连接失败才释放媒体资源。
- 临时断网最多等待 10 秒恢复，重复断连通知不会延长等待；恢复后沿用原会话，
  超时或连接失败才结束通话。主动挂断会取消恢复计时。
- 打断播报时，近期取消请求的可恢复拒绝不会挂断通话；其他服务端错误仍结束通话。
- 拨号失败提示保留到用户重试或退出，不再自动消失。

需同步发布后端 SDP 接口及带 `model_code` 的目录接口，并完成后端 0195 模型迁移。
后端需要配置 `DASHSCOPE_API_KEY`、`DASHSCOPE_WORKSPACE_ID`、`DASHSCOPE_REGION`。
地域必须与业务空间和 API Key 匹配。正式连接还依赖手机网络到所选百炼媒体端点可达。

AI 电话与双语互译共用 `AssistantForegroundService`，在用户可见页面发起会话时
先启动并确认前台服务，再打开麦克风/摄像头。语音模式声明 microphone/mediaPlayback，
视频模式额外声明 camera；开启摄像头前先升级服务类型。通知栏可返回对话或结束，
通知权限被拒绝不阻止会话启动。服务持有按会话释放的部分唤醒锁，息屏后保持音频与
连接处理；不会在进程被系统终止后自动重建录音会话。双语互译控制器由导航 ViewModel
持有，页面重建不再结束会话。原有会话时长、断网与鉴权限制仍然适用。

`AssistantForegroundSessionTest` 验证后台/息屏摄像头采集、通知结束、过期通知隔离
及启动取消清理；`BilingualTranslationTest` 使用真实麦克风与播放器验证后台/息屏互译。

验证命令：`gradlew :app:compileDebugKotlin :feature-assistant:testDebugUnitTest`。
单元测试覆盖配置选择、握手回调顺序、取消错误分类和断连恢复时限；
`AiCallFailureUiTest` 验证失败提示保留。真实设备音频路由、弱网与供应商连通性需通话验收。

协议依据：[百炼 WebRTC 接入](https://help.aliyun.com/zh/model-studio/realtime-webrtc-access)。

### 本机会话记录与单句重播

- 打电话的语音页面以双方聊天气泡展示最终转写，提供独立的「打断 AI」按钮；视频页面可打开「聊天记录」面板。当前文字保留在通话 ViewModel 中，关闭本机保存仍可查看；挂断后保留，开始下一次通话清空。翻阅旧消息时不自动跳转，可点「回到最新」。
- 打电话顶部的历史入口在页内打开电话记录，查看期间保持通话，返回先关闭详情或面板。此入口只展示电话记录，清空不会删除双语互译记录；AI 首页的会话记录仍展示全部类型。
- AI 首页增加「会话记录」：按登录账号隔离，只保存本机文字，不上传记录或保存音视频。
  使用 `noBackupFilesDir` 中的 SQLite，逐句落盘，保留最近 200 次会话；支持全文搜索、
  单次删除及清空。打电话与双语互译分别在各自设置页控制文字保存，设置仅对对应的新会话生效；会话记录页不再提供保存开关。升级时两项设置继承原有统一开关，之后独立保存。删除中的会话不会被迟到回调重新创建。
- 通话启用 `qwen3-asr-flash-realtime` 输入转写，通过 DataChannel 的 ASR completed、
  audio_transcript.done / text.done 保存双方最终文字。提前用 committed / output_item.added
  分配顺序，避免源语音转写晚到导致问答倒序。音视频传输默认直连 AOQ，手动选择 WebRTC 时沿用原通道。
- 双语互译保存原文、译文及每句方向；每次连接独立标识，重连不会覆盖先前会话。
  历史详情标题栏右侧提供整段复制、系统分享与删除，句子卡片仅展示可选择的文字；超过 100000 字符的分享使用完整 UTF-8 文本附件，
  不截断。长文本复制提示改用分享。附件仅通过专用 FileProvider 临时授权读取。
- 翻译页缓存近期完整的 24 kHz PCM，支持手动重播；缓存上限为 120 秒音频、单句最多
  30 秒、最多 100 条，不完整及被淘汰的音频不提供重播。离开页面后释放音频，历史仅留文字。
  自动播报关闭时也可手动重播。实时会话中重播与自动播报共用串行队列，播放时继续做
  麦克风回声隔离；停止翻译后也能在本页重播。
- `OmniTranscriptTest`、`BilingualReplayCacheTest` 验证乱序转写与缓存/队列边界；
  `AssistantHistoryTest`、`AssistantHistoryUiTest` 与 `BilingualTranslationTest` 验证持久化、
  账号隔离、删除回调竞争、搜索复制分享、关闭自动播报后的重播与回声隔离。

转写事件依据：[Omni Realtime](https://help.aliyun.com/zh/model-studio/realtime)。

### P1：面对面、后台控制与摘要待办

- 双语互译右上角进入独立设置页，集中选择翻译语言、显示模式、语音播报和本机文字保存。进入设置或返回不会结束翻译；会话中锁定语言与文字保存，仍可调整显示模式和播报。
- 默认使用「面对面模式」，可在设置中切换为沿用原会话列表的「肩并肩模式」。面对面上半屏旋转 180°，显示第二语言；
  下半屏显示第一语言。每个面板根据逐句真实方向选择原文或译文，双方都读自己的语言，
  不会因为临时调整显示方式重连模型或改变自动识别方向。
- 前台服务就绪后，通知栏增加暂停/恢复语音输入、静音/恢复播报。按钮状态与页面同步，
  连线中不提供可误操作的控制。暂停保留连接，电话禁用麦克风轨道，互译持续发送静音帧，
  不缓存或补发暂停期间的话音。静音播报与暂停输入独立。通知操作按会话 UUID 隔离，
  旧通知不能操作新会话；后台更新通知不重新申请 microphone/camera 前台服务类型。
- 已结束的会话记录可按需生成摘要、决定与待办。点按前说明本次文字会发送到 AI 服务，
  不自动上传历史。复用后端 `LLMClient` 的会议模型配置，结果存入当前账号的本机 SQLite；
  待办仅为本机清单，可勾选、查阅对应原文、复制或分享，不自动创建协作任务或发通知。
  请求由 ViewModel 持有，重复点按不会重复生成；删除记录、退出账号不会被迟到结果恢复。
- 新接口 `POST /api/v1.0/assistant-summary/` 要求登录，每用户 3 次/分钟；输入不超过
  2000 行 / 60000 UTF-8 字节，超限返回错误，不截断记录。输出检查完整性和待办引用，
  provider 超时 25 秒且不自动重试，错误不暴露会话文字。后端不保存原文或摘要，只计用量。

部署：更新 Android App 与 `we-meet` 后端（`release-meet.sh backend`）。复用已有
`DASHSCOPE_API_KEY`、`MEETING_SUMMARY_MODEL`、`MEETING_SUMMARY_BASE_URL`；无新增环境变量，
无需后端数据库迁移或 agents 发布。本机 `summaries` 表随 App 首次读取历史自动创建。

验证：`AssistantSummaryTest` / `AssistantHistoryUiTest` 覆盖重复请求、错误重试、删除竞争、
原文引用与勾选；`AssistantForegroundSessionTest` 覆盖后台控制与旧通知隔离；
`BilingualTranslationTest` 覆盖暂停静音帧及恢复；`BilingualTranslationUiTest` 覆盖双向分屏。
后端：`core/tests/test_assistant_summary.py`，模型响应使用桩，不依赖付费外部服务。

### P2：场景预设

- 打电话设置统一使用「对话场景」入口，合并通用助手、英语旅行、日语旅行、商务沟通、英语口语陪练与已有目录提示词，每次只选一个，并显示实际名称和用途说明。保留已有选择与音色；连接中或通话中不能修改场景或音色。
- 预设提示词沿用现有 `session.update` 的 instructions，应用于下一次通话；无需更改后端 SDP 接口或供应商协议。旅行强调实用表达，商务强调准确的数字与承诺，陪练每轮一个问题并温和纠错。
- 双语互译设置提供通用交流、旅行交流、商务沟通，场景、语言和播报独立保存，修改任一项不改变另外两项。旧英语/日语旅行预设统一迁移为旅行交流，保留原语言与播报。场景目前仅记录本机使用偏好，尚未传入翻译模型，界面明确说明；不使用陪练提示词。设置按当前账号保存，重新进入页面会恢复。会话进行中不能更换场景或语言。
- 两类预设均不改动文字保存开关、摄像头开关或已有会话记录。只需更新 Android App；无需发布后端或 agents。
- 验证：`AssistantSceneTest` 覆盖场景/语言/播报独立修改、旧预设迁移、配置恢复及账号隔离；`AiCallConfigTest` 覆盖目录解析保留场景；`BilingualTranslationUiTest` 覆盖预设选择与语言显示。

### P3：互译响应时间与埋点

- 播放预缓冲由 200 ms 降为 80 ms（`STARTUP_BUFFER_MS`）。网关在方向确认后会一次性下发音频，
  预缓冲主要用于吸收调度抖动，不再是 200 ms 的固定等待；短回复仍由 `finishTurn()` 补齐阈值后出声。
- 播报结束后的回声保护尾由 350 ms 降为 200 ms（`ECHO_TAIL_MS`）。该值只覆盖设备放音尾音，
  缩短后更早恢复真实麦克风输入；这段窗口内对方话音仍会被静音帧替代，不做半双工改动。
- 新增两条只含时长的诊断日志，不记录音频、文字、语言或身份：
  - `translation_playback_started queue_ms=<收到音频到首帧写入播放器> reply_ms=<本机最后一帧话音到首帧写入>`。
    `reply_ms` 是客户端侧「说完 → 听到译音」口径；跨句重叠或本会话还没说过话时为 -1。
  - `translation_ack_rtt_ms=<上行帧到 ACK 的往返>`，每 100 帧（约 10 秒音频）采样一次。
- 网关侧的 `translation_first_audio`、`translation_audio_delivered gate_ms` 与 `since_speech_ms`
  见 `we-meet/src/agents/README.md`；三段相加即为端到端首段译音延迟。
- 验证：`BilingualPlaybackQueueTest` 覆盖队列顺序、分片与上限；真机弱网下同时观察
  `AudioTrack.underrunCount`（`BilingualTranslationTest` 暴露的 `playbackUnderruns`），
  确认 80 ms 预缓冲没有带来欠载或咔哒声。真实设备音频路由、蓝牙与回声仍需实机验收。
