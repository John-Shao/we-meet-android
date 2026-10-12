# 声纹身份识别 Android 16 隔离验收

日期：2026-10-12。测试源码提交：`f5b81c724234fe8b6045eac27eec3102a765111f`，包含本次身份请求和私有状态走查修复。

## 环境与边界

使用已有 API 36／Android 16 模拟器 `emulator-5558`，独立包 `com.we.meet.fixturevoiceprintapi36` 与 `IsolatedRecordsRunner`。该 runner 使用隔离 Application，不初始化真实账号、推送、统计或生产仓储。原有 App 与其他设备保持原样；麦克风、相机和 `BLUETOOTH_CONNECT` 均未授予。

构建：JDK 17、现有 Android SDK 和离线依赖，串行执行 `checkDesignTokens :app:assembleDebug :app:assembleDebugAndroidTest`，指定独立后缀及 runner。构建通过，耗时 4 分 17 秒。

## 结果

仪器测试实际输出 `OK (66 tests)`，146.957 秒，无失败或中断。接口和音频继续采用本地私有协议夹具与合成 WAV，不请求真实 ASR。

| 测试组 | 通过数 |
|---|---:|
| 人工身份标记 | 8 |
| 导入前身份预检 | 6 |
| 身份候选、建议与确认 | 12 |
| 录音分人、版本与试听 | 7 |
| 通话采样控制与设备观察 | 10 |
| 本人设置、登记与候选审核 | 18 |
| 纪要身份历史提示 | 2 |
| 固定纪要版本分享导航 | 3 |

`nativeDeviceMetadataObserverDoesNotRequireOrGrantMicrophonePermission` 在本系统上真实创建并释放 `VoiceprintInputChanges`。由于 SDK 为 36，实际进入 API 31 以上的 `communicationDevice`／`OnCommunicationDeviceChangedListener` 注册与移除分支，并验证未授予麦克风权限。不使用硬件路由切换来伪造这一项的覆盖范围。

人工检查五张中文暗色／大字号截图：通话告知和滚动控制、身份建议、长姓名导入及录音分人。关闭入口固定可见，长姓名可换行，试听／确认与导入操作可达；滚动区域在视口边界的裁切属于预期滚动行为。截图来自隔离组件，不作为完整生产 Application 的视觉验收。

目标 APK SHA-256：`5aff5d35d7da73efdfb408985b71d73e6850b72fdf342bfff7d1fd48e4a88ed2`。测试 APK SHA-256：`30df587678527079882c8d3f5433266ac7f0b5083b0d8f26088f9e060d28139b`。日志、截图和回执保存在工作区 `work/speaker-identity-api36-20261012-f5b81c72/`，不入库。结束后两个隔离包均已卸载。

## 仍待完成

本轮补齐最新修复在 API 36 上的隔离交互和原生监听器生命周期证据。不能据此宣布所有 API 31 以上系统已验收，也不能替代真实手机／蓝牙耳机换路由、真实 LiveKit 麦克风采样、完整 Application、获授权真人效果和真实 Qwen 云端 ASR 验收。

没有升级版本号、替换之前的内部签名 APK、部署生产或调整声纹开关。完整方案目标保持未完成，后续按主方案继续验证。
