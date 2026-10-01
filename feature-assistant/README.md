# AI 电话

「AI 助手 → 打电话」使用 Qwen 3.8 Omni WebRTC 直连。普通会议的 LiveKit 通话不受影响。

- 客户端从目录读取 `model_code=aliyun/qwen3.8-omni-flash-realtime` 的 profile。
- 音色和提示词共用一套选择；旧版视频设置优先迁入，失效音色按新目录默认值回退。
- 使用用户登录态调用 `POST /api/v1.0/ai-call/session/`。后端代理 SDP 交换，并返回音色、提示词。
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
地域必须与业务空间和 API Key 匹配。正式连接还依赖手机网络到百炼 WebRTC 媒体端点可达。

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

- AI 首页增加「会话记录」：按登录账号隔离，只保存本机文字，不上传记录或保存音视频。
  使用 `noBackupFilesDir` 中的 SQLite，逐句落盘，保留最近 200 次会话；支持全文搜索、
  单次删除及清空。开始前可关闭保存，设置对新会话生效。删除中的会话不会被迟到回调重新创建。
- 通话启用 `qwen3-asr-flash-realtime` 输入转写，通过 DataChannel 的 ASR completed、
  audio_transcript.done / text.done 保存双方最终文字。提前用 committed / output_item.added
  分配顺序，避免源语音转写晚到导致问答倒序。音视频传输仍直连 WebRTC。
- 双语互译保存原文、译文及每句方向；每次连接独立标识，重连不会覆盖先前会话。
  历史详情支持整段或逐句复制/系统分享；超过 100000 字符的分享使用完整 UTF-8 文本附件，
  不截断。长文本复制提示改用分享或逐句复制。附件仅通过专用 FileProvider 临时授权读取。
- 翻译页缓存近期完整的 24 kHz PCM，支持手动重播；缓存上限为 120 秒音频、单句最多
  30 秒、最多 100 条，不完整及被淘汰的音频不提供重播。离开页面后释放音频，历史仅留文字。
  自动播报关闭时也可手动重播。实时会话中重播与自动播报共用串行队列，播放时继续做
  麦克风回声隔离；停止翻译后也能在本页重播。
- `OmniTranscriptTest`、`BilingualReplayCacheTest` 验证乱序转写与缓存/队列边界；
  `AssistantHistoryTest`、`AssistantHistoryUiTest` 与 `BilingualTranslationTest` 验证持久化、
  账号隔离、删除回调竞争、搜索复制分享、关闭自动播报后的重播与回声隔离。

转写事件依据：[Omni Realtime](https://help.aliyun.com/zh/model-studio/realtime)。

### P1：面对面、后台控制与摘要待办

- 双语互译默认使用「面对面模式」，右上角显示当前模式，点击可选择「面对面模式 / 肩并肩模式」。肩并肩沿用原会话列表。面对面上半屏旋转 180°，显示第二语言；
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
