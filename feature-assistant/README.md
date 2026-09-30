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
- 挂断、页面退出、后台切换和连接失败均释放 WebRTC、采集器、音频路由和渲染资源。
- 临时断网最多等待 10 秒恢复，重复断连通知不会延长等待；恢复后沿用原会话，
  超时或连接失败才结束通话。主动挂断会取消恢复计时。
- 打断播报时，近期取消请求的可恢复拒绝不会挂断通话；其他服务端错误仍结束通话。
- 拨号失败提示保留到用户重试或退出，不再自动消失。

需同步发布后端 SDP 接口及带 `model_code` 的目录接口，并完成后端 0195 模型迁移。
后端需要配置 `DASHSCOPE_API_KEY`、`DASHSCOPE_WORKSPACE_ID`、`DASHSCOPE_REGION`。
地域必须与业务空间和 API Key 匹配。正式连接还依赖手机网络到百炼 WebRTC 媒体端点可达。

验证命令：`gradlew :app:compileDebugKotlin :feature-assistant:testDebugUnitTest`。
单元测试覆盖配置选择、握手回调顺序、取消错误分类和断连恢复时限；
`AiCallFailureUiTest` 验证失败提示保留。真实设备音频路由、弱网与供应商连通性需通话验收。

协议依据：[百炼 WebRTC 接入](https://help.aliyun.com/zh/model-studio/realtime-webrtc-access)。
