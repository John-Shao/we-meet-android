# Android AI 电话：语音控制摄像头与结束对话

Android 的 AI 工具“打电话”通过当前 `qwen3.8-omni-flash-realtime` 会话理解自然语言，调用客户端工具控制摄像头，并使用当前通话音色播报实际结果。AOQ 与 WebRTC 共用工具协调器和摄像头状态控制器。媒体仍直连百炼，切换不重新申请业务会话、租约或模型连接，不增加 ASR、TTS、数据库或后端接口。

“结束对话”“停止对话”“结束通话”“挂断电话”等明确请求调用 `end_call`，由 App 立即执行当前通话的挂断操作。语音和视频模式、输出静音、后台通话都沿用同一结束流程；用户麦克风关闭时模型无法接收新的语音指令，仍可使用挂断按钮。不会等待告别语、引入本地 TTS 或再次请求模型续答。“不要结束对话”“怎么停止对话”、假设、引用和画面文字不构成挂断请求；“关闭摄像头”“只用语音聊”也不结束通话。

## 用户行为

首次开始语音通话保持摄像头关闭，首次开启默认使用后置镜头；本次通话再次开启沿用最后使用的镜头。

实际摄像头已开启、通话为 Active 且页面处于 RESUMED 时，设置 `FLAG_KEEP_SCREEN_ON` 防止因闲置超时变暗、熄屏和自动锁屏。摄像头关闭、挂断、页面进入后台或离开后释放本页持有的常亮标志；返回前台且摄像头仍开启时重新保持常亮。按钮和语音开启共用实际状态，AOQ、WebRTC 一致，功能不依赖语音工具构建开关。仅选择视频模式、正在连接或等待授权不触发常亮；保留用户主动锁屏和系统自动亮度行为，不修改系统超时设置、不新增权限。退出时恢复窗口原有常亮标志，避免清除其他页面已有的设置。

| 当前摄像头 | 用户请求 | 实际操作 | 中文反馈含义 |
|---|---|---|---|
| 关闭 | 打开摄像头 | 开启采集、发送和预览 | 摄像头已打开 |
| 关闭 | 关闭摄像头 | 保持关闭 | 摄像头已经关闭了 |
| 开启 | 关闭摄像头 | 关闭发送、采集和预览 | 摄像头已关闭 |
| 开启 | 打开摄像头 | 保持开启 | 摄像头已经打开了 |

“开启视频”“让你看看眼前的东西”“只用语音聊”等明确表达也可执行。“摄像头开着吗”只查询。“不要打开摄像头”“怎么打开摄像头”、假设、引用和画面中的文字不应触发开启；意图不明确时模型先澄清。用户转写只用于会话记录，不执行第二条关键词控制通道。

## 工具协议

客户端在最终场景提示词之后追加控制规则，`OmniCallTools` 按各自开关在 `session.update` 注册嵌套 `function` 工具，显式设置 `enable_search=false`。开启全部语音控制时共三个工具；摄像头工具关闭时仍可单独注册结束工具。

工具启用时同时设置 `temperature=0`、`presence_penalty=0`，降低生成随机性并避免重复惩罚影响用户反复发出相同摄像头指令；不设置未经官方协议确认的 `tool_choice`。这有助于一致性，但不能把模型的普通回复当作执行结果。工具关闭时不改变原会话生成参数。

- `set_camera_enabled`：仅接受一个必填 JSON Boolean 参数 `enabled`。字符串、缺失或额外字段均返回错误。
- `get_camera_state`：参数必须为空对象，不改变设备状态。
- `end_call`：参数只能为 `{}`，不接受额外字段。它是终止工具，不进入摄像头串行队列、不需要新权限，也不等待摄像头授权。完整工具项通过类型、参数及当前响应归属校验后，抑制旧输出、取消工具／反馈任务，调用当前客户端绑定的 `AiCallViewModel.endCall()`；取消摄像头权限和设备任务，关闭客户端、音视频资源、前台服务与本次业务租约，完成记录关闭，更新为通话结束状态。只影响当前 App 通话。

完整 `response.function_call_arguments.done` 才进入执行；增量事件不执行。`response.output_item.done.item` 和 `response.done.output` 中的完整工具项用于缺失事件补充。三个入口共用 `call_id` 去重，同一连接中的重复事件不重复操作或回传。

以下结果回传和语音续答仅适用于摄像头工具。`end_call` 在本地校验后立即关闭连接，不等待 `response.done`、不发送 `function_call_output` 或 `response.create`；这是产品选择的终止行为，不宣称已经生成或播放告别语。关闭后的重复／迟到事件全部忽略，旧客户端的回调以实例身份检查防止结束新通话。非法结束参数仍回传 `invalid_arguments`，保持通话。

工具通过 `conversation.item.create` 的 `function_call_output` 回传原始 `call_id`。结果包含 `success`、实际 `enabled`、`changed`、`code` 和本地化 `message`。无法确认状态时 `enabled=null`，禁止播报成功。该响应全部结果已回传后，仅发送一次 `response.create` 请求续答。通常使用 `response.done` 作为输出结束依据；AOQ 实测部分仅有 Function Calling 的轮次没有 `response.done`，因此还接受全部已声明工具项的 `response.output_item.done`，等待 200 毫秒收齐相邻项后续答。有普通消息项的混合响应仍等待 `response.done`；参数增量、未完成工具项或未回传结果都不能触发续答。模型必须每轮调用工具核实状态，不能用历史结果代替重复用户请求。

这是相对最初方案的协议修正：官方要求 Function Calling 回传结果后显式续答，必须等待 `response.done` 的说明针对 MCP；本功能没有注册 MCP。仅等待 AOQ 工具轮的 `response.done` 会导致部分请求没有语音反馈。上述 200ms 补充结束条件仅在 AOQ 启用；WebRTC 必须收到本轮 `response.done` 且全部结果回传后才能续答，不能把工具项结束当作整轮结束。

WebRTC 对真实链路实测的 `invalid_request_error`、空错误码、`Conversation already has an active response` 请求冲突做精确处理：必须有 30 秒内尚未确认的 `response.create`，错误参数不得归属其他操作；有请求 ID 时必须匹配。无请求 ID 时关联最新未确认的续答请求。若是工具续答被拒绝，显示反馈失败、保留实际设备状态；已有响应及媒体连接继续使用，不重做摄像头操作、不重新分配会话。其他协议错误及 PeerConnection、DataChannel、音频焦点或设备故障继续按原失败流程处理。日志只记录协议类型、响应 ID、错误类型／码和断连原因，不记录指令、工具参数、音频、SDP 或凭证。

常见代码：`enabled`、`disabled`、`already_enabled`、`already_disabled`、`permission_denied`、`foreground_required`、`video_unavailable`、`device_error`、`timeout`、`cancelled`、`invalid_arguments`。

结果回传失败保留已完成的设备状态，不重做操作、不重连。仅匹配客户端工具事件 ID 的服务端错误按反馈失败处理。续答等待 15 秒，失败显示文字，不增加声音兜底或重复请求。新用户语音、取消响应和挂断使未开始的旧操作及续答失效；已完成操作不会因打断而自动反向切换。

错误反馈区分状态同步、工具执行、结果回传、等待响应结束和语音续答五个阶段，携带本轮实际设备结果；没有结果时不得借用历史成功结果。新摄像头工具开始时清除旧结果。状态同步失败显示“摄像头状态同步暂时失败”；尚未取得本轮结果时显示“尚未确认摄像头操作结果”，不再宣称“结果已显示”。只有本轮结果已确认时才显示该结果并提示暂时无法语音确认。反馈错误本身不改变实际摄像头状态或触发设备重试。Release 日志记录规范化操作类别、实际状态、结果码、执行耗时和反馈阶段，便于区分没有工具调用、设备失败与播报失败，不记录原始指令或参数。

## 权限和设备状态

按钮与语音工具共用 `CameraActionController.requestCameraEnabled`，采用串行设备操作和明确目标状态，避免反向切换。查询和手动换镜头也进入同一串行入口。

手动按钮确认设备改变后，以及每轮用户语音开始时，将最新实际状态以 `session.update.instructions` 同步给模型，保留最终场景和工具控制规则，防止模型沿用过时结果。语音操作通过工具结果返回状态，不在每次工具执行期间重写提示词。提示词要求每轮调用工具核实；重复请求验收同时检查工具实际返回值、状态不变和反馈准确。不能以模型口头承诺作为成功依据。

开启顺序：前台与解锁检查、摄像头权限、摄像头前台服务类型确认、启动采集、首帧确认、开启发送、更新视频模式。关闭顺序：停止视频发送、停止采集并确认停止回调、更新语音模式并移除预览、撤销摄像头前台服务类型。

AOQ SDK 1.3.0 的内部 Camera1 采集在当前模拟器上停止后无法可靠再次输出首帧，连续开关测试暴露了该问题。实现改为 Android Camera2 采集，使用已有的摄像头组件提供原始帧，通过 AOQ 官方 `pushExternalVideoCapturedFrame` 接口交给 SDK 编码和发送；没有创建 WebRTC PeerConnection。摄像头硬件以兼容的 15fps 工作，`CameraFrameRouter` 将原始帧直接交给本机预览，仅上传分支每 500ms 最多提交一帧，AOQ 编码目标仍为 2fps。旋转、I420 转换和复制只发生在上传分支，不对全部预览帧做 CPU 图像转换，也不保存图像。首次实际 Camera2 帧被 SDK 接受后才开启发送并更新视频模式；关闭先停止发送和推帧，再等待实际摄像头关闭回调并停止 SDK 外部采集。

AOQ 和 WebRTC 均使用共享 EGL 上下文的 `TextureViewRenderer` 直接预览 Camera2 原始帧，保留帧自带方向并随前后镜头设置预览镜像；预览不再依赖 AOQ SDK 本地渲染或 WebRTC 的低帧率模型轨道。默认本地预览为 15fps，模型上传为 2fps，WebRTC 的 VideoSource 和 RTP 编码上限使用同一上传参数。每次停止屏蔽新帧，解除预览绑定等待已有回调返回后才释放渲染器。AOQ 摄像头、纹理采集资源每次关闭释放；共享 EGL 根上下文保留到当前通话挂断，确保快速重开及页面旋转仍使用兼容上下文，挂断时统一释放。实际帧率仍受设备、曝光与运行负载影响。

帧率为两个独立的构建配置，统一适用于 AOQ、WebRTC、Debug 和 Release，在 `gradle.properties` 中设置：

| 参数 | 默认值 | 配置范围 | 生效位置 |
| --- | --- | --- | --- |
| `AI_CALL_LOCAL_PREVIEW_FPS` | 15 | 1–30 的整数 | 本地原始帧预览上限；硬件采集目标为 `max(15, 配置值)`，保持低预览帧率下的 Camera2 兼容性 |
| `AI_CALL_MODEL_UPLOAD_FPS` | 2 | 1–本地预览帧率的整数 | 模型分支提交上限、AOQ 编码 fps、WebRTC RTP maxFramerate |

也可执行 `./gradlew.bat :app:assembleDebug -PAI_CALL_LOCAL_PREVIEW_FPS=30 -PAI_CALL_MODEL_UPLOAD_FPS=3` 覆盖。参数在构建时校验，修改后需重新构建并安装 APK；不是用户设置或云端热配置。`AiCallVideoConfig` 汇总配置，分流器使用纳秒时钟独立计算两个分支的最小帧间隔，不再写死 500ms；摄像头实际输出高于请求值时，本地预览也按配置限帧。模型上传不依赖预览是否已绑定，帧率配置不改变分辨率、码率、媒体连接或语音控制发布开关。

这是相对原方案“AOQ 内部采集首帧观察接口”的设备兼容性调整。[AOQ 官方外部视频输入](https://www.alibabacloud.com/help/zh/model-studio/aoq-custom-video-input)支持此类自定义采集，编码和媒体传输仍由 AOQ SDK 完成。WebRTC 使用 CameraEventsHandler 首帧及关闭回调。已有支持 H264 的 WebRTC 连接预先协商视频发送器，开启只附加轨道，关闭移除轨道；不重新协商业务会话。

设备操作整体限时 10 秒，首帧及停止回调分别最多等待 8 秒。失败或取消后再次关闭媒体并回收服务类型；若仍无法确认资源释放，则结束当前通话，不能展示虚假的关闭成功。

AOQ 已建连后若收到实测的精确帧顺序错误 `Error append image before append audio.`，且错误没有其他请求归属，则放弃该帧并继续同一会话，不重传、不重连。其他服务端错误仍按原错误处理。该处理覆盖视频与新音频到达顺序不同的情况，不把权限、设备或会话错误当作可恢复错误。

没有权限时，由前台通话页面申请系统权限，最多等 60 秒。请求 UUID 绑定当前通话控制器，迟到授权不能开启已取消或已挂断的通话。明确关闭可取消未完成的开启授权。后台或锁屏不能新开启摄像头，关闭仍可执行；已有视频通话后台行为保持原生命周期。

模拟器的 WebRTC 编码器可能只有 VP8、VP9、AV1。当前工作空间实测包含这些视频编码的 SDP 分配被百炼拒绝，纯音频 SDP 成功。客户端检测不到 H264 时使用音频连接，开启工具返回 `video_unavailable`，保留语音。该限制来自当前实际验证，不作为供应商永久编码支持结论；完整 WebRTC 视频验收需要具有 H264 编码器的真机。

## 播放与记录

取得工具项后抑制该轮模型播放，结果续答恢复；用户静音始终优先。AOQ 使用 SDK 可写播放帧将需要抑制的 PCM 清零，保持解码和事件流继续处理，不依赖播放器暂停或打断来抑制工具轮音频。WebRTC 合并工具抑制、用户静音与打断状态控制远端音轨。音量路径保持 AOQ 媒体音量、WebRTC 通话音量，不改变麦克风或音频焦点。

中文请求使用中文短句，UI 使用资源字符串；系统语言不同也不能改变反馈含义。模型生成语音不承诺逐字一致，必须简短且不能把失败描述成成功。开启本机记录时保留用户指令与实际助手播报；内部工具 JSON 不进入聊天记录。关闭记录不影响工具执行。

## 构建和回退

`feature-assistant` 的 `BuildConfig.AI_CALL_CAMERA_VOICE_CONTROL` 同时控制工具注册和控制提示词。Debug 默认开启，可用 `-PAI_CALL_CAMERA_VOICE_CONTROL=false` 回退。Release 默认关闭，内部验收可显式指定 `-PAI_CALL_CAMERA_VOICE_CONTROL_RELEASE=true`；生产默认值等待 AOQ、WebRTC 和荣耀 Android 16 真机验收后再修改。关闭时恢复原按钮入口，不向模型提供摄像头工具。

语音挂断独立使用 `BuildConfig.AI_CALL_VOICE_HANGUP`，Debug 和 Release 默认开启，构建时可用 `-PAI_CALL_VOICE_HANGUP=false` 关闭其工具和提示词。关闭摄像头语音工具不会关闭挂断工具；同时关闭两项开关时恢复原会话行为。修改开关需重建安装 APK，按钮挂断始终保留。已有摄像头语音控制的完整实机验收门槛不变。

内部测试包包含真实工具、权限和媒体操作。工作区候选包不等于已提交或正式发布版本；发布版仍需正式签名和真机验收。测试与交付证据见 [验收记录](ai-call-camera-voice-verification.md)。

## 验证方法

离线单元测试覆盖四种状态、查询、去重、非法参数、多工具、取消、发送失败、续答超时、权限允许与拒绝、60 秒超时及迟到回调、后台限制、首帧超时、关闭失败、挂断、按钮与语音并发。

真实协议探针 `OmniCameraToolLiveTest` 使用实际 AOQ SDK、WebRTC 和百炼，只调用无设备副作用的查询工具，检查工具请求、结果、语音文字及播放能量。实际语音探针 `OmniCameraControlLiveTest` 向 SDK 外部发布流注入测试 PCM，使用真实摄像头和 ViewModel，检查四种状态、自然表达、否定与说明性问句、连续 10 轮开关及仅一次业务分配；不启用文字保存。

运行真实测试须显式传入 instrumentation 参数 `liveBackend=true`，会使用已授权演示账号并产生模型用量；正常设备回归不会自动执行收费探针。PCM 来源和格式记录在 `app/src/androidTest/assets/camera-speech.txt`。

荣耀 AMM-AN00、MagicOS 10、Android 16 需分别使用 AOQ 和 WebRTC 验收：首次授权及拒绝、锁屏与后台、四种指令、自然表达、引用句、静音与打断、前后镜头保持、连续 10 轮、关闭后不再发送新帧、音色与提示准确。设备执行目标不超过 3 秒，不包含授权等待；同时记录语音结束到工具请求、操作完成及提示开始的耗时。

## 协议依据

- [百炼 Omni Realtime 与 Function Calling](https://www.alibabacloud.com/help/zh/model-studio/realtime)
- [服务端事件](https://www.alibabacloud.com/help/zh/model-studio/server-events)
- [客户端事件及工具结果回传](https://www.alibabacloud.com/help/zh/model-studio/client-events)
- [Android 权限申请](https://developer.android.com/training/permissions/requesting)
