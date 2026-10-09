# 双语互译 WebRTC 客户端直连

核对日期：2026-10-09。本次为代码与候选包验证，尚未部署生产后端，也未完成用户真机验收。

## 连接方式与方向

设置提供「AOQ 客户端直连／WebRTC 客户端直连」，默认仍为 AOQ。App 的 WebRTC 选项替代原云端网关选项；旧网关接口保留以兼容已安装客户端及其他翻译入口。

两种直连共用 `DirectBilingualWire` 的路由与翻译结果处理。自动模式建立两条 LiveTranslate 3.8 翻译连接，以及一条 Omni 3.8 语言识别连接。客户端缓冲讲话，由识别模型判断所选两种语言之一，再把原语音发往对应方向。固定方向只建立一条翻译连接。

**自动方向识别依赖云端模型能力，不依赖业务网关接入。** 两种协议均支持自动和固定方向。方向按账号保存，切换 AOQ／WebRTC 保留方向，改变语言对恢复自动；活动会话中不能修改连接方式、语言或方向。

原有 `direct-aoq=false` 的已迁移偏好在新版本中表示 WebRTC，`aoq_default_v1` 的首次 AOQ 默认迁移保持不变。

## WebRTC 媒体和控制

后端 `POST /api/v1.0/assistant-translation/session/` 接受 `transport=aoq`（兼容默认）或 `webrtc`。WebRTC 请求必须附带音频和 DataChannel 的 Offer SDP。后端固定模型、验证语言及 SDP、携带服务端 Key 交换 SDP，返回 Answer 和原有业务租约。禁止重定向、自动重试建连、客户端自选模型或上游 URL；不回显 Offer，不转发音频。新版 App 需配套发布新版后端接口。

WebRTC 使用 RTP 音频轨道与 DataChannel 模型事件。原生发送器在配置前关闭编码发送，收到 `session.updated` 后挂载并启用音频，防止初始化静音 RTP 提前启动翻译。Answer 去除尾部空行并规范为 CRLF。

WebRTC 库使用外部 PCM 输入，明确关闭内部 AudioRecord；外部回调由单调时钟按每帧时长限速，避免无硬件阻塞读取时高速空转。录音仍由控制器唯一采集；播放时沿用现有回声保护。远端解码 PCM 复用 App 的媒体音量、播报队列和回放缓存，关闭 SDK 自带播放声音。方向识别的输入也是 RTP；提交输入和结束会话前等待本地 PCM 队列排空。

缓冲、事件及数据通道均有上限；连接、模型或租约失败沿用错误与清理流程，不自动切换到另一条收费会话。关闭取消协程、清空 PCM、解绑音频 sink、关闭 DataChannel／PeerConnection、释放工厂／音频模块和租约。日志只记录协议事件、耗时、错误分类和代码位置，不记录讲话、译文、SDP 或凭据。

协议依据：[LiveTranslate 3.8 模型说明](https://help.aliyun.com/zh/model-studio/qwen3-8-livetranslate-flash-realtime)、[WebRTC 接入](https://help.aliyun.com/zh/model-studio/realtime-webrtc-access)。

## 验证

- 双语互译 JVM 单元测试：18 项通过，包括语言、连接计划、播放／回放、外部 PCM 有界队列和音频时钟限速。
- Android 模拟器回归：`BilingualTranslationTest`、`BilingualTranslationUiTest`、`AoqDefaultPreferencesTest` 共 36 项通过，覆盖连接选择、固定方向持久化、会话锁定、后台、回放和资源释放。
- 后端翻译接口：95 项通过，覆盖旧 AOQ／网关兼容、鉴权、固定模型、两种 WebRTC 用途、非法 Offer、异常或过大 Answer、租约返回和无凭据泄漏；Ruff 通过。
- `WebRtcBilingualLiveTest`：三项通过。合成中文→英文自动路由、英文→中文自动路由、固定英文→中文均使用真实百炼模型和 RTP，验证原文／译文方向、解码音频能量、实际 AudioTrack 播放、回放及正常结束；自动分配三次、固定分配一次。
- 最终 Debug App／测试 APK 构建以及 Release Kotlin 编译通过。

真实模型探针通过仅监听本地环回的临时信令服务执行当前后端视图，使用实际百炼 SDP 请求。业务租约在该隔离探针中被替换，鉴权和租约返回由后端测试另行覆盖；不据此宣称生产登录／租约链路已完成验收。探针使用模拟器及合成语音，不等同于用户真机麦克风、蓝牙、弱网或生产发布验收。生产环境未部署本次后端修改。

测试入口显式启用 `liveBackend=true`（使用已登录的配套后端），或由本地验证环境传入 `translationSignalingUrl`。不要将永久供应商 Key 放入 APK、测试参数或日志。

验证期间修复了 Answer 尾部空行、音频 sink 参数顺序、配置前 RTP 门控与外部音频线程限速问题；首次失败结果保留在本地验证日志中，以上通过结论对应修复后的完整测试。
