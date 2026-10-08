# AI 电话语音摄像头控制：内部验收记录

日期：2026-10-08。源码基线：`353e404af859aae4b65044cdd2fc4613a3fa831b`，当前功能为内部验收候选，具体来源见候选元数据及 Git 提交。功能方案见 [语音控制摄像头](ai-call-camera-voice-control.md)。

## 构建与范围

Debug 默认注册 `set_camera_enabled`、`get_camera_state`；生产 Release 默认关闭语音控制。另提供使用 `-PAI_CALL_CAMERA_VOICE_CONTROL_RELEASE=true` 构建的内部 Release APK，用 Android 调试证书签名，供验证非 Debug 执行路径；它不是正式生产发行包。

APK 使用真实 AOQ SDK、WebRTC、模型会话、摄像头和权限流程。测试账号只用于登录；不向客户端增加 API Key，不部署后端，不迁移数据库，不重新分配摄像头切换的业务会话。

内部候选目录为 `release/0.3.0-work.2-camera-voice-20261008/`，包含 APK、来源与测试记录 `candidate.json`、`SHA256SUMS`。目录忽略于 Git；测试源码与方案文档纳入代码审阅。

## 已验证与待验收

| 项目 | 结果和证据 |
|---|---|
| 单元测试 | `feature-assistant` 57 项、`app` 561 项；摄像头新增测试 27 项，覆盖协议、参数、去重、续答时序、超时、权限和设备生命周期 |
| Debug、默认 Release、内部启用 Release | 均验证构建；默认 Release 的 `AI_CALL_CAMERA_VOICE_CONTROL=false`，内部启用版为 `true` 且 `DEBUG=false` |
| 真实模型协议 | AOQ、WebRTC 无设备副作用查询工具完成调用、结果回传、文字续答和播放能量检测；每条连接仅分配一次会话 |
| 内部 Release 实测 | AOQ 与 WebRTC 查询工具 2 项通过（41.012 秒）；系统权限拒绝 1 项通过（28.538 秒），授权、真实预览、镜头保持及后台限制 1 项通过（38.727 秒），共 4 项 |
| AOQ 媒体连续开关 | 同一 SDK 引擎、同一租约完成 10 轮真实 Camera2 采集和关闭；SDK 视频统计显示非零编码帧率、码率和发送字节 |
| 权限与实际页面 | 系统权限拒绝后保持语音；授权后执行原开启目标，真实预览显示；镜头切换及重新开启保持镜头；后台可关闭，后台新开启返回 `foreground_required` |
| 真实语音基本流程 | 各基本步骤曾在长流程中通过；单独探针最新复测出现一次续答超时，**整体未通过**。保留严格测试，不将单步成功等同于整个流程稳定 |
| 真实语音连续 10 轮 | **未通过，不具备生产发布条件**。后半段出现过模型口头回复但不发工具调用，实际设备状态断言超时；完整严格探针保留，不能以媒体 10 轮通过替代语音 10 轮验收 |
| 荣耀实机 | 当前未连接荣耀 AMM-AN00／MagicOS 10／Android 16，等待用户验收 |
| WebRTC 视频 | 模拟器没有 H264 编码器，当前百炼拒绝其仅含 VP8/VP9/AV1 的视频 SDP；语音及工具协议可验证，完整视频切换须在支持 H264 的手机上验收 |

工作区证据位于 `../artifacts/omni-camera-*.log`（相对于 Android 仓库）。候选目录复制交付所需的成功和失败结果、已知问题及来源记录。当前真实语音基本和长流程均有未通过项，实机也尚未验收，不能将全部发布条件标为通过。

## 实测调整和问题

### 旧版开关均无操作及误导提示排查（2026-10-08）

用户补充确认：两张 20:15 截图均来自旧 `video-fps` 内部 Release，使用 WebRTC，分别为视频时关闭和语音时开启未执行，却显示“摄像头结果已显示，暂时无法语音确认”。它们不属于后续修复包回归；原包已卸载，缺少现场日志，无法确定当时是否收到工具、执行了哪个目标或在哪个阶段失败。旧实现将状态同步、工具执行及续答错误全部归到该提示，并保留上一次摄像头结果，所以截图的“已经打开／已关闭”不能证明本轮实际工具结果。

本次引入带阶段及本轮结果的 `CameraFeedbackFailure`，区分状态同步、执行、回传、等待响应结束与续答。新工具开始清除旧结果；无本轮结果或状态同步失败不展示历史成功。仅已确认本轮结果时保留结果并提示暂时无法语音确认。不把反馈失败反向解释成设备失败，不重做设备操作。生产日志新增规范化开／关／查询类别、实际状态、结果码、执行耗时和反馈阶段，不记录原始指令、参数或凭证。本包同时包含下节 WebRTC 续答时序及冲突处理修复。

新增四项反馈分类与结果归属回归；`feature-assistant` 79 项单元测试通过。新增 `OmniWebRtcCameraToolLifecycleLiveTest` 通过真实模型 DataChannel 请求，调用生产 ViewModel／权限与前台服务／Camera2 控制，依次检查开启、重复开启、关闭、重复关闭。Debug 测试通过（25.805 秒）；实际关闭返回 `disabled`、耗时 139ms，停止后无新预览帧且发送器轨道为空。每步检查实际工具 Boolean、设备状态、模式与模型反馈文字，全程只分配一次业务会话且保留同一客户端。额外的受控反馈回调注入验证三类界面提示及旧结果清除，明确属于故障注入，不能称为供应商错误复现。

原生 x86 模拟器没有 H264 编码器；该探针在供应商音频协商完成后仅补充未协商的本机发送器，实际采集和关闭是真的，但不能证明供应商收到或停止接收 H264 视频。输入为合成文字，不覆盖荣耀麦克风 ASR、实际语音意图理解或屏幕预览布局。最初测试因 Android 29 不存在 `POST_NOTIFICATIONS` 而失败，测试修正为仅 Android 33 以上授予该权限；原失败日志保留，不作为产品故障。荣耀实机和完整语音连续开关的原验收门槛保持不变。

最新内部 Release 的同一联合探针通过（25.108 秒），实际开启 147ms、关闭 131ms，重复目标未重新操作设备，反馈界面故障注入符合三类提示，全程分配一次。`checkDesignTokens` 通过；Release 构建、工具开关和签名校验通过。此处的设备耗时从已收到工具到完成设备操作计，不包含麦克风识别、模型理解或播报延迟。

内部 Release 的 AOQ 查询及语音反馈、WebRTC 终止工具共两项回归通过（61.354 秒），分别保持一次业务分配。AOQ 工具及反馈阶段 1496ms；WebRTC 终止 982ms、`endTools=1`、`leaseCloses=1`，否定与用法问句未结束通话。两个模拟器最终 `Active Camera Clients=[]`。此次三个 Release 探针全部通过，之前批量 AOQ 超时及历史语音长流程未通过记录仍保留，不据此取消真机与真实语音验收要求。

本次候选归档于 `release/0.3.0-work.2-camera-feedback-20261008/`，测试源码、构建记录、源码快照和校验值同目录交付。内部 Release 继续使用调试证书，摄像头语音及结束工具开启，默认帧率 15／2；不修改生产默认摄像头语音开关，也不部署后端。

### WebRTC 关闭摄像头后无反馈／重复指令断连排查（2026-10-08）

用户在荣耀 Android 16 上使用 `we-meet-video-fps-release-internal.apk`，已有摄像头预览，第一次语音关闭无反馈，第二次断连。旧 Release 已卸载，无原现场日志，因此本次不能确诊该实机故障的完整根因。模拟器没有 H264 硬件编码器，真实 WebRTC 协议与本机 Camera2 生命周期分别验证，不能据此宣布完整 WebRTC 视频发送／停止已在真机通过。

已复现并修正两项具体缺陷：统一协调器曾把 AOQ 的工具项完成后 200ms 续答策略用于 WebRTC；整轮结束延迟的新增单测在修复前失败，修复后通过。另在真实 WebRTC DataChannel 连续发出两个 `response.create`，供应商返回 `invalid_request_error`、空错误码、`Conversation already has an active response`；原实现日志显示 `Call failed reason=protocol_error`。修复后只精确恢复有近期未确认请求的该冲突，继续原连接；工具续答受影响时报告反馈失败，不再次操作设备或重拨。新增原因日志区分协议、PeerConnection、DataChannel、音频焦点和摄像头失败，不记录用户内容或凭证。

本次 `feature-assistant` 75 项单元测试通过，覆盖 WebRTC 迟到结束、AOQ 补充结束保留、冲突归属／顺序／过期、已完成操作不重试及下一条命令。`OmniWebRtcCameraLifecycleTest` 使用生产 WebRTC 客户端、真实 Camera2、VideoSource 与本机 RtpSender，完成 10 轮采集／关闭／重复关闭／重开（修复后 6.437 秒），关闭后无新预览帧，未触发结束回调。该探针为无收费会话的本地生命周期测试，显式补充本机发送器，未协商供应商 H264 视频。

内部 Release 的真实模型探针覆盖五项：WebRTC 查询、无冲突连续 10 次关闭、主动制造回复冲突后继续 10 次关闭、AOQ 查询及 WebRTC 终止工具。前述 WebRTC 四项在最终批量运行中通过，每条连接只分配一次；两组重复命令检查工具实际调用、摄像头保持关闭、语音文字及播放能量，未触发失败回调。终止工具在明确结束请求后 996ms 完成既有清理，`endTools=1`、`allocations=1`、`leaseCloses=1`，否定句及用法问句保持连接。

该批量运行总计 476.009 秒，AOQ 查询出现一次 25 秒等待超时，批量结果为 4/5；在不改产品代码、超时门槛或断言的情况下单独复测，AOQ 查询通过（28.477 秒，工具及反馈阶段约 1393ms）。所以五个测试项各有通过证据，不能表述为批量运行零失败。WebRTC 两组测试均用合成文字驱动真实 DataChannel／模型，不代表真实麦克风 ASR 和 H264 视频已验收；部分轮次包含较长的模型等待，不据此承诺端到端性能。最终测试后两个模拟器的活动摄像头客户端为空。

候选目录 `release/0.3.0-work.2-webrtc-camera-fix-20261008/` 保留修复前失败、临时诊断、最终结果、源码来源和 APK 校验。内部 Release 使用调试证书，`DEBUG=false`、摄像头语音与挂断工具同时开启，帧率为 15／2；生产摄像头语音默认关闭门槛未改变。临时诊断错误原文输出已从交付代码删除。模型生成“摄像头已经关了”等同义短句属于原验收允许范围；初始反馈断言只接受“关闭”造成的失败保留记录，后续断言接受准确同义句并继续严格检查实际设备状态、工具调用、音频播放、失败回调及分配次数。

### 视频通话屏幕常亮增量验证（2026-10-08）

本次增量将屏幕常亮绑定到实际摄像头状态、Active 通话及 RESUMED 页面；不更改工具协议或媒体连接。Debug、默认 Release 和内部启用 Release 重新构建，`feature-assistant` 57 项单元测试及设计规范检查通过。`AiCallFailureUiTest` 与 `AssistantSceneTest` 共 7 项现有页面回归通过（33.462 秒）。

在支持 ARM 转译的模拟器上运行现有 `grantingCameraPermissionExecutesOriginalTargetAndShowsPreview`，真实 AOQ 授权、预览、换镜头、关闭重开、镜头保持及后台关闭流程通过（41.618 秒）。外部观察 `dumpsys window`：开始时 `mHoldScreenWindow=null`，摄像头开启后为 App 的 MainActivity，进入后台后恢复 `null`；探针结束时 `Active Camera Clients` 为空。该观察验证系统窗口实际持有与释放常亮，不以模型口头回复为依据。

增量 APK 与原始日志归档于 `release/0.3.0-work.2-video-screen-on-20261008/`，包含来源和哈希；内部 Release 仍使用调试证书且启用语音工具。此处没有重跑完整语音连续 10 轮或 WebRTC 实机视频，荣耀设备的闲置超时／主动锁屏行为仍待真机确认；上表未通过项及生产默认关闭门槛继续保留。

### 本机预览与模型上传分流增量验证（2026-10-08）

两种传输以 15fps 为本机采集目标，将原始帧直接交给 TextureViewRenderer；仅模型分支每 500ms 最多提交一帧。AOQ 不再使用 SDK 的低帧率本地预览，WebRTC 不再从低帧率模型轨道取预览。共享 EGL 根上下文保留到挂断，渲染器按客户端实例保持，避免 EGL 包装对象变化引起无谓重建。

最终构建覆盖 Debug、默认 Release 和内部启用 Release；相关单元测试 60 项通过，其中 3 项新增测试验证高帧率预览、模型限流、停开与解绑时的回调屏障。原生 x86 模拟器的 `CameraPreviewTest` 使用生产 Camera2 源、真实渲染器和 I420 转换，仅替换模型提交端，不分配收费会话：最终 3001ms 内采集及实际渲染各 81 帧，模型分支提交 6 帧，关闭后无新预览，重开保留原生 EGL 上下文且同一渲染器继续出画面。设备只有一颗摄像头，因此换镜头由两颗摄像头的真实 AOQ 探针覆盖。该测试与 7 项现有页面回归共 8 项通过（17.796 秒）。

真实 AOQ 页面授权、预览、换镜头、关闭重开、镜头保持及后台关闭探针最终通过（51.487 秒）。预览截图已核对，结束后两个模拟器的活动摄像头客户端均为空。此探针断言有真实帧且关闭后不再喂帧；帧率性能由独立原生源／渲染测试衡量。

保留最初 ARM 转译模拟器的严格帧率失败记录：曾观测采集约 1fps、渲染约 0.5fps，未达到预览目标；该环境的图形和转译性能不能作为荣耀手机性能结论。后续采用独立原生测试验证分流，并分别验证真实 AOQ 生命周期，没有把降低权限探针的帧率断言当作性能通过。目标 15fps 与实测值分别记录，荣耀设备及 WebRTC H264 真机流畅度仍待确认，既有完整语音控制发布门槛不变。

包含屏幕常亮和预览分流的最新内部包位于 `release/0.3.0-work.2-video-preview-20261008/`。包、来源、哈希、原始成功／失败日志、单元测试 XML 和页面截图同目录归档；未提交来源明确记录为工作区增量，不冒充已提交发行版本。

### 独立帧率参数增量验证（2026-10-08）

新增 `AI_CALL_LOCAL_PREVIEW_FPS` 与 `AI_CALL_MODEL_UPLOAD_FPS`，默认 15／2；AOQ、WebRTC、Debug 和 Release 共用。硬件采集使用 `max(15, 本地预览 fps)`，原始预览和模型提交分支分别以纳秒时钟限帧；AOQ 编码 fps、WebRTC RTP maxFramerate 同步模型上传值。本次增加预览上限，前一阶段 81 帧的观测属于尚未增加预览限帧的历史候选，不能作为新版 15fps 配置的结果。

30／3 fps 覆盖构建和 61 项单元测试通过，生成的 BuildConfig 确认为 30／3；0fps 预览、上传 16fps 大于预览 15fps 均被构建校验拒绝。默认配置再次执行 61 项单元测试及设计检查通过，Debug、默认 Release、内部启用 Release 构建通过。新增测试注入 10／3 fps，验证两条分支各自限流且预览不控制上传时点。

默认配置原生预览／页面回归共 8 项通过（16.814 秒）；3002ms 内本地原始帧预览和实际渲染各 41 帧，模型提交分支 6 帧；关闭、重开及原生 EGL 上下文保持通过。这仍是生产 Camera2 源与渲染器、替换模型提交端的测试，不分配真实模型会话；本次未重跑真实 AOQ 语音或 WebRTC H264 实机验收，既有发布门槛不变。

最新默认 15／2 fps 内部包位于 `release/0.3.0-work.2-video-fps-20261008/`，包含配置快照、构建／测试日志、源码及哈希。参数修改需重建安装 APK，不能从通话页面或云端动态修改。

### 语音结束对话增量验证（2026-10-08）

新增无参数终止工具 `end_call`，共享协调器更名为 `OmniCallTools`。`AI_CALL_VOICE_HANGUP` 默认在 Debug／Release 开启；摄像头工具保留原独立开关和验收门槛，默认 Release 只注册结束工具。本地执行直接复用 `AiCallViewModel.endCall()`，不等待告别语或续答。两个语音开关均关闭的模块编译及 BuildConfig 快照已验证，恢复默认配置后相关单元测试为 69 项、失败 0；其中 8 项新增覆盖终止、去重、完整项补充、参数非法、取消、权限等待取消、同轮摄像头请求取消及独立注册。8 项原生预览／页面回归通过（15.924 秒）。

Debug 真实增量 3 项通过（123.474 秒）：AOQ 合成语音“结束对话”结束语音通话、“停止对话”结束静音视频通话；WebRTC 通过真实 DataChannel 和模型的合成文本验证终止工具，不能将它描述为 WebRTC 实际麦克风语音验收。否定和用法问句保持通话。默认 Release 最终 3 项通过（86.557 秒），确认实际注册工具均为 `[end_call]`，每项 `endTools=1`、`allocations=1`、`leaseCloses=1`，实际完成状态为 Ended 且无断连／设备错误提示；摄像头状态、控制器、前台服务及租约引用全部清空，客户端实际关闭。测试仅计数包装原挂断回调，仍执行真实 ViewModel 清理，不以假客户端替代媒体或设备。关闭本机记录不影响执行。

Release 此次观测从请求提交到结束并收到租约关闭确认：AOQ 语音约 5.6 秒、AOQ 视频约 5.6 秒（包含测试 PCM 播放／VAD／模型识别），WebRTC 文本约 1.2 秒。它们不是“语音结束到执行”的测量，也不是稳定延迟承诺。PCM 由本地 Microsoft Huihui Desktop 生成，格式及内容见 `app/src/androidTest/assets/hangup-speech.txt`；不包含用户录音，产品不新增本地 TTS。

初次并行编译 Debug／Release 出现 Kotlin 编译器 OOM，改为 `--no-parallel --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx4096m` 后构建成功，未修改全局 JVM 配置。保留首次真实探针的语音等待超时和状态观察失败：客户端引用在原生清理前置空，测试已改为等待完整 Ended 状态，并增加实际结束工具计数与无错误断言，没有放宽结束／清理判据。默认 Release 首次 WebRTC 建连超时；保留失败记录，按原超时和原清理断言复测通过，不给产品增加自动重拨。

本次 APK、来源快照、测试记录和哈希位于 `release/0.3.0-work.2-voice-hangup-20261008/`，源码基线为 `99f83617` 加未提交增量。提供 Debug 和**默认 Release 配置、内部调试证书签名**的测试包，Release 中摄像头语音工具仍关闭，挂断按钮和摄像头按钮仍可用。荣耀真机、真实 WebRTC 麦克风／H264 视频结束、后台和锁屏语音结束尚未实机验收；已有摄像头语音连续 10 轮门槛不变。本次不部署后端或调整 API Key。

### 摄像头语音控制既有验证

1. AOQ 1.3.0 内部 Camera1 停止后再次开启没有首帧，尝试配置和管线重置均未解决。交付实现使用 Camera2 原始帧，经 AOQ 官方外部视频输入接口编码和传输。真实页面预览、连续重开和镜头保持已验证。没有加入 SDK 私有反射或创建额外 WebRTC 连接。
2. 部分 AOQ Function Calling 响应缺少 `response.done`。统一协调器接受全部已声明工具的 `response.output_item.done` 作为仅工具响应完成的补充，收齐相邻工具项及实际结果后仅续答一次；混合消息与工具仍等待响应结束。此调整有真实链路依据，详见方案中的协议说明。
3. 模型可能在工具调用前生成预告，或直接确认已满足的重复命令。提示词要求直接调用，已发现的工具响应音频及预告文字被抑制；实际状态变化必须有客户端操作结果。重复命令验收同时检查实际状态不变和反馈准确，不能仅凭模型文本确认设备状态。
4. 长语音探针曾在最后一轮收到单段续答文字后停滞。修复了首段文字过早取消 15 秒超时计时的问题：只有对应续答的完整文字或实际播放才能完成等待，停滞时保留实际摄像头状态并显示文字结果，不重复请求语音。
5. 10 轮媒体探针大多数开启约 1 秒、关闭约 0.3 秒；ARM 转译模拟器有一次关闭约 5.9 秒，长语音测试另有一次开启约 7.7 秒，低于操作超时但超过 3 秒目标。不能据此宣布实机性能目标已经满足。授权弹窗耗时独立计时，首次授权的总耗时不能直接与 3 秒设备目标比较。
6. 工具会话改为 `temperature=0`、`presence_penalty=0` 并在每轮用户语音开始时同步实际摄像头状态后，长流程仍出现模型仅生成摄像头回复、不调用工具的情况，测试正确地判定失败。这些措施没有证明连续语音控制已可靠，保留为内部验收功能。后续需排查模型重复指令的工具选择行为，并在真实链路上完成严格 10 轮测试；不能因回复含有成功短句而放宽设备断言。参数取值依据 [官方客户端事件](https://www.alibabacloud.com/help/zh/model-studio/client-events)。
7. AOQ 长语音探针捕获过精确错误 `Error append image before append audio.`，客户端原先将它作为会话失败处理。新增严格匹配仅处理已建连后、无请求归属的这一种帧顺序错误：该帧不重传，继续当前连接，其他错误仍按原逻辑处理。[官方事件约束](https://www.alibabacloud.com/help/zh/model-studio/client-events)要求图像前已有音频；音视频轨道在 VAD 提交后到达顺序不同是根据此次 AOQ 实测作出的解释，不是官方承诺。最终恢复结果见长流程证据。

## 可重复运行

从仓库根目录运行 Gradle（本地需 Java 17）：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :feature-assistant:testDebugUnitTest :app:testDebugUnitTest checkDesignTokens
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:assembleRelease -PAI_CALL_CAMERA_VOICE_CONTROL_RELEASE=true
```

真实模型测试必须显式启用，产生模型用量：

```powershell
adb shell am instrument -w -e liveBackend true -e class com.we.meet.ui.ai.OmniCameraToolLiveTest com.we.meet.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e liveBackend true -e class com.we.meet.ui.ai.OmniCameraControlLiveTest com.we.meet.test/androidx.test.runner.AndroidJUnitRunner
```

系统弹窗测试每个方法开始前必须在 instrumentation 外撤销 CAMERA 权限并清理授权记忆；运行中撤销会杀死应用。拒绝和授权分别执行，避免上一项授权影响下一项：

```powershell
adb shell pm revoke com.we.meet android.permission.CAMERA
adb shell pm clear-permission-flags com.we.meet android.permission.CAMERA user-set user-fixed
adb shell am instrument -w -e liveBackend true -e class 'com.we.meet.ui.ai.OmniCameraPermissionLiveTest#denyingCameraPermissionKeepsVoiceCall' com.we.meet.test/androidx.test.runner.AndroidJUnitRunner
# 再次清理权限状态后执行 grantingCameraPermissionExecutesOriginalTargetAndShowsPreview。
```

内部 Release 的协议探针额外使用 `-e releaseApp true`，启动正式 MainActivity；权限探针本身使用 MainActivity，覆盖实际 Release 页面流程。安装混合 ABI APK 到具备 ARM 转译的模拟器时，AOQ 必须使用 `adb install -r --abi arm64-v8a`。

## 上线条件

2026-10-08 荣耀用户回报：上述 VoIP 回声对照包没有复现偶现中断，但重现明显音量偏小，媒体／通话均最大（系统显示 200%）时 AOQ 200% 的听感约为 WebRTC 100%。因此不将 VoIP 对照方案转为默认，也不将未复现视作回声问题已解决。

随后交付媒体模式诊断包 `release/0.3.0-work.2-aoq-media-diagnostics-20261008/we-meet-aoq-media-diagnostics-release-internal.apk`，`AOQ_MEDIA_PLAYBACK=true`、内部 Release 摄像头语音工具开启，音频采集／播放路径恢复原媒体模式，未修改增益、VAD 阈值或输入静音策略。Debug、内部 Release 构建通过，84 项单元测试通过；模拟器 Debug 3 项（2 项 SDK 路由与音频模式／退出恢复，1 项真实模型摄像头综合流程）、内部 Release 4 项（2 项 SDK 测试、模型查询及语音续答、合成语音结束静音视频）通过。真实 SDK 周期日志及 VAD 日志已观察到有效 RTT、丢包、音频收发码率／字节、播放帧时间与静音／工具状态，Release 日志确认 `voip=false`；测试结束无摄像头客户端、无通话前台服务。新增单元测试覆盖统计缺失与零流量区分、SDK 回调对象复用、日志限频、统计过期、解码帧和可听播放时间分离、音频轨道筛选。日志、源文件、配置、签名及校验值存放于新候选目录，旧 VoIP 包归档保持不变。

媒体诊断包仍需荣耀确认音量及偶现中断；日志用于定位，不宣称已修复声学回声或网络拥塞。音量路径与模拟器测试结果不能代替真实设备声学及网络验收。

2026-10-08 AOQ 回声对照增量：内部包 `release/0.3.0-work.2-aoq-aec-20261008/we-meet-aoq-aec-release-internal.apk` 显式启用 VoIP 模式，采集／播放使用相同配置；Debug、内部 Release 均构建成功，79 项 feature-assistant 单元测试通过。ARM 转译模拟器上 Debug 的 2 项真实 SDK 初始化／路由／模式恢复测试和 1 项真实模型语音摄像头综合测试通过，后者包含四种状态、按钮后状态同步、否定／问句／自然表达、静音指令并保持一次会话分配。内部 Release 的 4 项测试通过：2 项 SDK 测试、真实模型查询工具与语音续答、合成语音结束静音视频通话并释放资源。默认 Release 的生成配置另行核对，仍为媒体模式、摄像头语音工具关闭。源文件、BuildConfig、构建与测试日志及校验值归档在同一候选目录。

这些测试使用模型真实连接及模拟器摄像头，语音控制输入为注入 PCM／文本；无法验证荣耀设备的麦克风、扬声器声学耦合和硬件 AEC 效果。当前只交付 VoIP 对照包，未将生产默认模式切换、未宣称无人讲话打断已修复。荣耀需在同等实际响度下对照耳机／扬声器、媒体／VoIP、语音／视频，确认安静播报不中断，正常讲话仍能打断且摄像头与结束通话指令可用。VoIP 包音量键调节“通话”音量。

荣耀手机分别选择 AOQ 和 WebRTC，验收四种指令、自然表达、否定／引用／假设、首次授权和拒绝、锁屏与后台、静音与打断、镜头保持、10 轮开关、关闭后无新画面发送、音色与准确提示。记录语音结束到工具请求、执行完成和播报开始的耗时，并检查设备执行不超过 3 秒（不计授权等待）。

通过这些条件后，再将 Release 默认语音控制开关改为开启；AOQ 传输默认开启与摄像头语音工具默认关闭是两个独立开关。回退语音功能可使用 `-PAI_CALL_CAMERA_VOICE_CONTROL=false` 或保留 Release 默认值，摄像头按钮继续工作。
