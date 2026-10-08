# 人 ♾️ 智能体协同办公平台：Android 客户端

We Meet 的原生 Android 客户端 —— **Kotlin + Jetpack Compose**。

## 这是什么

对接 **we-meet** 后端（Django REST API + 自托管 LiveKit）的原生 Android 客户端。后端仓库位于同级目录 `../we-meet/`。

**功能：**

- 手机号 + 短信验证码登录
- 创建会议 / 通过 8 位数字会议号或 UUID 加入 LiveKit 房间
- 入会前摄像头预览
- 音视频通话：静音、摄像头开关、前后置切换、扬声器、画中画、挂断
- 会中聊天、屏幕共享
- 会议历史
- 个人资料：组织、用户名、简介、头像、封面

## 仓库关系

```
D:\workspace\we-meet\
├── we-meet\            ← 后端 + Web 前端（Django / React + LiveKit）
└── we-meet-android\    ← 本仓库
```

移动端设计与接口说明（位于后端仓库）：
[../we-meet/docs/extensions/移动端App客户端支持方案.md](../we-meet/docs/extensions/移动端App客户端支持方案.md)

模型、直连协议及云端分工见[大模型接入方案](../we-meet/docs/features/llm-integration.md)；Omni 通话和独立双语互译在新版 Debug／Release 中均默认 AOQ，升级迁移与手动备选入口见 [AI 电话说明](feature-assistant/README.md)。

## UI 设计规范

新增和改动移动端页面须遵循[移动端 UI 设计规范：页面层级与配色](docs/page-backgrounds.md)，统一一级与二级及更深页面的背景分区、状态栏衔接、底部模块导航栏和日历日期栏例外。
Android 组件、主题 token 与提交前检查见 [Android 设计规范](docs/设计规范.md)。

## 前置要求

- JDK 17
- Android Studio Koala (2024.1+) 或更新
- Android SDK Platform 34
- 一台 API 30+ 的模拟器或真机（启用摄像头）

## 构建

在仓库根目录执行：

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

后端基础地址在 `gradle.properties` 配置（可在 `local.properties` 覆盖）：

```properties
WE_MEET_BASE_URL=https://meet.we-meet.online
```

本地开发时指向自托管后端（模拟器视角）：

```properties
WE_MEET_BASE_URL=http://10.0.2.2:8071
WE_MEET_LIVEKIT_URL_OVERRIDE=ws://10.0.2.2:7880
```

> access_token 有效期较短；当前不做静默自动刷新，会话过期时会提示重新登录。

## 许可

见 [LICENSE](LICENSE)。

## Work 桌面任务（Android，2026-10-07）

内部候选归档：提交并保持本仓库及同级 `jusi-light-im` 工作区干净后，运行 `python scripts/package-work-candidate.py --android-sdk <SDK目录>`。命令执行规范检查、全应用 JVM 测试与正常 Debug 构建，再用 Android 原生工具核实 APK 身份、版本和 Debug 签名，归档到 ignored `release/<版本>-<提交>/`。归档含 APK、来源提交、SDK 提交、配置文件哈希、签名证书指纹、测试计数及 SHA256SUMS；配置值不进入清单。fixture 包、错误版本、签名失败或构建期间源码变化均拒绝交付。运行 `python scripts/package-work-candidate.py --verify <归档目录>` 可复核内容哈希；清单是完整性记录，不能替代可信发布签名。该命令不安装、不上传，不执行商店发布。

`0.3.0-work.2` 提供侧边抽屉“工作”入口：查看同账号已登记桌面工作空间，向在线或离线桌面派发待办，查看统一任务状态、取消及预览桌面主动同步的成果。Android 不安装 dsh、不读取桌面目录，也不保存模型供应商密钥。iOS 暂不开发。

后端先迁移至 `work.0005`，并启用 `WORK_ENABLED`、`WORK_LOCAL_AGENT_ENABLED`、`WORK_REMOTE_AGENT_ENABLED`；模型为服务端 `WORK_AGENT_MODEL` 指定值。桌面先通过原生目录选择器授权，并允许远程待办；重新登录需重新授权，任务必须在桌面“审阅并领取”后执行，工具调用还需逐次原生审批。手机不自动领取任务，也不将断线任务改成云端执行。

提交应答丢失保留原 UUID、目标及工作空间，界面锁住输入并允许确认同一请求；SavedStateHandle 支持 Android 系统进程恢复，不作为强制停止/重启设备后的持久存储保证。成果只预览已明确同步、校验 SHA-256 的受限 UTF-8 文本，未同步时提示在桌面完成同步。

构建/测试（JDK 17）：`./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`。Work 仪器测试使用现有 `IsolatedRecordsRunner` 与独立 `.fixturework` applicationId，避免覆盖正式安装：

```powershell
$env:ANDROID_SERIAL='emulator-5556'
./gradlew.bat :app:connectedDebugAndroidTest '-PWE_MEET_TEST_ID_SUFFIX=.fixturework' '-PWE_MEET_TEST_RUNNER=com.we.meet.ui.records.IsolatedRecordsRunner' '-Pandroid.testInstrumentationRunnerArguments.class=com.we.meet.ui.work.WorkScreenTest,com.we.meet.ui.work.WorkReviewScreenTest'
```

正常构建不要传 fixture 参数；正常输出 `applicationId=com.we.meet`，测试包不安装到用户现有账号。当前 APK 为 Android Debug 签名内部候选，未发布应用商店。真实服务端/dsh/DeepSeek 联调与 Android fixture UI 验收分别记录在主仓库 `docs/reviews/work-delivery-and-android-2026-10-07.md`，尚未覆盖真实 Android 登录到桌面的整条 UI 验收。

### 实际跨端验收补充

`0.3.0-work.2` 在任务详情增加成果复核历史：展示排队/运行/完成/失败/取消状态、模型、选定文件、结论、意见、证据文件与哈希及实际 token 用量。原任务结束后，未完成复核仍会在页面可见时轮询。手机只读取业务接口；复核由桌面显式选择并同步成果后开启，模型与供应商 key 在服务端管理。

服务端 capabilities 缺少 `review_enabled` 时兼容旧版并跳过复核接口；值为 false 时仍可读取有权限的历史。访问失败会隐藏缓存的文件、预览和报告；切换账号或关闭详情后，迟到响应不能重新显示旧内容。待确认派发意图也绑定登录会话，刷新 access token 保留会话，重新登录清除旧意图。

新增 `WorkReviewScreenTest` 使用实际 Retrofit 与本机 MockWebServer 检查复核进度、结构化证据与撤权后的隐藏；`WorkBackendReviewIntegrationTest` 由主仓库 `src/backend/work/tests/test_android_review_delivery.py` 显式启动，读取真实 PostgreSQL、Work API 和锁定 Pi Docker 运行时交付的报告。后者模型响应为合成 SSE，身份为隔离预置会话，不产生付费模型调用；默认跳过。验收记录见主仓库 `docs/reviews/work-android-review-2026-10-07.md`。

新增 opt-in `WorkRemoteIntegrationTest`，通过正式 `ApiClient.workApi` 实际 HTTP 访问隔离 Django API，同一任务由实际 Electron / 内置 dsh / DeepSeek 执行、逐次审批并同步，手机校验预览和取消待办均通过。它使用隔离预置会话，不覆盖真实 OTP/OIDC 登录；默认无 `workCrossDevice=1` 时跳过，不会自动产生模型调用。测试专用 `.fixturework` / 本机端口构建与后端编排见主仓库 `docs/reviews/work-cross-device-acceptance-2026-10-07.md`。测试后已正常构建 `com.we.meet` 候选，哈希与前阶段一致。
