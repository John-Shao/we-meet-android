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

荣耀手机分别选择 AOQ 和 WebRTC，验收四种指令、自然表达、否定／引用／假设、首次授权和拒绝、锁屏与后台、静音与打断、镜头保持、10 轮开关、关闭后无新画面发送、音色与准确提示。记录语音结束到工具请求、执行完成和播报开始的耗时，并检查设备执行不超过 3 秒（不计授权等待）。

通过这些条件后，再将 Release 默认语音控制开关改为开启；AOQ 传输默认开启与摄像头语音工具默认关闭是两个独立开关。回退语音功能可使用 `-PAI_CALL_CAMERA_VOICE_CONTROL=false` 或保留 Release 默认值，摄像头按钮继续工作。
