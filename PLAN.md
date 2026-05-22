# We Meet Android 端 MVP

> **状态更新**：本文为初版 MVP 实施计划，已完成。后端现已切换为 **we-meet**
> （同级仓库 `../we-meet/`，基础地址 `https://meet.we-meet.online`）。下文中
> 对 `jusi_meet_suite1.9`、`meet.jusiai.com` 的引用为历史路径；最新接口契约与
> 移动端设计见 `../we-meet/docs/extensions/移动端App客户端支持方案.md`。
> 关键差异：可输入的会议号是房间的 `meeting_code` 字段（6 位数字）；头像/封面
> 桶为私有，图片 URL 为短期签名 URL。

## 背景

We Meet 的后端（Django REST API + 自托管 LiveKit + AI agents）和现有的 React + TypeScript Web 前端位于同级仓库 `D:\workspace\Meeting\jusi_meet_suite1.9`。该后端已经提供了面向移动端的接口（短信验证码 `/api/mobile/auth/send-otp/` 与 `/api/mobile/auth/verify-otp/`）和 Keycloak Token Exchange，并在 [../jusi_meet_suite1.9/docs/mobile-integration-guide.md](../jusi_meet_suite1.9/docs/mobile-integration-guide.md) 与 [../jusi_meet_suite1.9/docs/mobile-integration-auth.md](../jusi_meet_suite1.9/docs/mobile-integration-auth.md) 中提供了完整的接入文档。目前缺的是一个真正的原生客户端。

本独立仓库（`we_meet_android`）交付一个 **原生 Android MVP**，用于打通与现有后端和 LiveKit 房间的端到端集成：手机号登录 → 拉取房间 → 加入 LiveKit 房间 → 推流/渲染音视频 → 离开。AI agent 控制、聊天、屏幕共享、管理员等功能在首版中**故意不做**，以保持表面足够小、可评审。后续可在此基础上分批补充。

**已确认的关键决策**（来自前期澄清）：
- 技术栈：**原生 Kotlin + Jetpack Compose**（使用官方 `io.livekit:livekit-android` SDK）
- 范围：**仅 MVP** —— 登录、加入房间、音视频、静音/摄像头/挂断
- 认证：**仅短信验证码**（中国市场路径；Keycloak PKCE 推迟）
- 位置：**独立仓库**，路径 `D:\workspace\Meeting\we_meet_android`（与 `jusi_meet_suite1.9`、`volcengine_bidirection_demo` 同级）

### 跨仓库协作

后端在 `../jusi_meet_suite1.9/`。契约就是该仓库下的两份 `mobile-integration-*.md`：当上游字段变更时，本仓库跟进。本仓库的 README 应注明最低兼容的后端版本（当前为 **We Meet Suite 1.9**）。

---

## 模块布局

仓库根目录 = `D:\workspace\Meeting\we_meet_android\`，下列路径均相对此根目录。

```
.
├── settings.gradle.kts
├── build.gradle.kts                  # 根模块，插件版本
├── gradle.properties
├── gradle/libs.versions.toml         # 版本目录（version catalog）
├── README.md                         # 构建与运行说明
├── PLAN.md                           # 本文件
├── LICENSE                           # 已存在
├── .gitignore                        # 已存在；需补充 Android Studio 相关条目
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/{strings.xml, themes.xml, colors.xml}
        │   └── values-zh-rCN/strings.xml
        └── java/com/we/meet/
            ├── WeMeetApp.kt              # Application 类
            ├── MainActivity.kt             # 单 Activity 宿主
            ├── data/
            │   ├── api/
            │   │   ├── ApiClient.kt        # Retrofit + OkHttp 装配
            │   │   ├── AuthApi.kt          # send-otp、verify-otp
            │   │   ├── RoomApi.kt          # GET rooms/{id}/
            │   │   └── dto/{OtpDtos.kt, RoomDto.kt, LiveKitDto.kt}
            │   ├── auth/
            │   │   ├── TokenStore.kt       # EncryptedSharedPreferences 封装
            │   │   └── AuthInterceptor.kt  # 自动加 Bearer 头
            │   └── repository/
            │       ├── AuthRepository.kt
            │       └── RoomRepository.kt
            ├── ui/
            │   ├── theme/{Theme.kt, Color.kt, Type.kt}   # Material 3 主题
            │   ├── nav/AppNav.kt           # NavHost：login → home → room
            │   ├── login/
            │   │   ├── LoginScreen.kt      # 手机号 → 验证码 Composables
            │   │   └── LoginViewModel.kt
            │   ├── home/
            │   │   ├── HomeScreen.kt       # 输入房间 id/slug，"加入"
            │   │   └── HomeViewModel.kt
            │   └── room/
            │       ├── RoomScreen.kt       # 视频网格 + 控制栏
            │       ├── RoomViewModel.kt    # 持有 LiveKit Room 生命周期
            │       └── ParticipantTile.kt  # 每个 track 的 VideoRenderer
            └── livekit/
                └── LiveKitController.kt    # 对 io.livekit.android.Room 的薄封装
```

说明：`LICENSE`、`.gitignore`、`README.md` 与 `PLAN.md` 在新初始化的仓库里已存在。`.gitignore` 需要追加 Android Studio / Gradle 相关条目（`.gradle/`、`build/`、`local.properties`、`.idea/`、`*.iml`、`captures/`）。

---

## 关键依赖（gradle/libs.versions.toml）

| 库 | 版本 | 用途 |
|---|---|---|
| `io.livekit:livekit-android` | 2.x latest | WebRTC + 信令；对应 Web 端使用的 `livekit-client` 2.17.1 |
| `com.squareup.retrofit2:retrofit` | 2.11.0 | 后端 REST 客户端 |
| `com.squareup.retrofit2:converter-moshi` | 2.11.0 | JSON 解析（与 Kotlin data class 兼容良好） |
| `com.squareup.okhttp3:logging-interceptor` | 4.12.0 | 调试期网络日志 |
| `androidx.security:security-crypto` | 1.1.0-alpha06 | EncryptedSharedPreferences 用于令牌存储 |
| `androidx.compose.bom` | 2024.x | Compose UI |
| `androidx.compose.material3` | (随 BOM) | Material 3 组件 |
| `androidx.navigation:navigation-compose` | 2.8.x | 页面导航 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.8.x | Compose 中的 ViewModel |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.x | 协程 |

`minSdk` = 24（满足 LiveKit Android SDK 的最低要求），`targetSdk` = 34，Kotlin 2.0+。

---

## 端到端流程

### 1. 认证（短信验证码）
LoginScreen → 用户输入手机号 → `AuthRepository.sendOtp(phone)` 调用 `POST {BASE_URL}/api/mobile/auth/send-otp/`（无需鉴权头）→ 成功后显示验证码输入框 → 用户输入 6 位验证码 → `AuthRepository.verifyOtp(phone, otp)` 调用 `POST /api/mobile/auth/verify-otp/` → 响应 `{access_token, refresh_token, expires_in}` 写入 `TokenStore`（EncryptedSharedPreferences）→ 跳转 HomeScreen。

接口契约取自 [../jusi_meet_suite1.9/docs/mobile-integration-auth.md](../jusi_meet_suite1.9/docs/mobile-integration-auth.md) 第 1、2 节。Token 有效期 300 秒；MVP **不做**自动刷新——若用户在首页停留超过 5 分钟，重新登录即可。这是有意识的范围裁剪，README 里需要明确写出。

### 2. 拉取房间
HomeScreen 只有一个 `OutlinedTextField`（输入房间 slug 或 UUID）和一个"加入"按钮。点击 → `RoomRepository.getRoom(idOrSlug)` 调用 `GET /api/v1.0/rooms/{idOrSlug}/`，由 `AuthInterceptor` 自动添加 `Authorization: Bearer <token>`。响应中包含 `livekit: { url, room, token }`，参考 [../jusi_meet_suite1.9/docs/mobile-integration-guide.md](../jusi_meet_suite1.9/docs/mobile-integration-guide.md) 中的响应示例。把 `(livekitUrl, livekitToken)` 作为导航参数传给 RoomScreen。

MVP 阶段同时支持 `id`（UUID）和 `slug` —— 后端两者都接受。不做房间列表，也不做创建房间。

### 3. 加入 LiveKit 房间
RoomScreen 申请 `CAMERA` + `RECORD_AUDIO` 运行时权限，然后 `RoomViewModel` 构造 `LiveKitController` 并调用 `room.connect(livekitUrl, livekitToken)`。连接成功后：
- `room.localParticipant.setCameraEnabled(true)`
- `room.localParticipant.setMicrophoneEnabled(true)`

订阅 `room.events` Flow → 向 Compose 发出 `(participantSid, videoTrack?, audioTrack?, name)` 列表。RoomScreen 用 `LazyVerticalGrid` 渲染若干 `ParticipantTile`，每个 tile 内部用 `VideoRenderer`（封装 livekit-android 的 `SurfaceViewRenderer`）渲染视频轨。

底部控制栏：静音/取消静音、开/关摄像头、前后摄像头切换、挂断（调用 `room.disconnect()` 并 pop 回 Home）。

LiveKit 连接选项：开启 `adaptiveStream = true` 与 `dynacast = true`，对齐 Web 端 [../jusi_meet_suite1.9/src/frontend/src/features/rooms/components/Conference.tsx](../jusi_meet_suite1.9/src/frontend/src/features/rooms/components/Conference.tsx) 的设置。

### 4. 清理
`RoomViewModel.onCleared()` 调用 `room.disconnect()` 并释放视频渲染器。由于是单 Activity 架构，要确保 RoomScreen 的 Compose 状态在旋转时不会丢失——把 Room 持有在 `ViewModelScope` 中即可，无需依赖 `AndroidManifest.xml` 的 `configChanges`。

---

## 待新增的关键文件

所有新文件都落在本仓库（`we_meet_android`）。本计划**不**修改上游后端仓库的任何代码 —— 所需的服务端接口已经全部就位：
- [../jusi_meet_suite1.9/src/backend/core/api/mobile_auth.py](../jusi_meet_suite1.9/src/backend/core/api/mobile_auth.py) —— OTP send/verify（已实现）
- 输出 `livekit: {url, room, token}` 的 Room 序列化器（已被 Web 使用）

实现期间需要参考的文件（位于上游仓库；**不**修改）：
- [../jusi_meet_suite1.9/docs/mobile-integration-guide.md](../jusi_meet_suite1.9/docs/mobile-integration-guide.md) —— 完整的 API 清单、基础地址、错误码
- [../jusi_meet_suite1.9/docs/mobile-integration-auth.md](../jusi_meet_suite1.9/docs/mobile-integration-auth.md) —— 验证码接口的请求/响应规格
- [../jusi_meet_suite1.9/src/frontend/src/features/rooms/components/Conference.tsx](../jusi_meet_suite1.9/src/frontend/src/features/rooms/components/Conference.tsx) —— LiveKit `Room` 配置参考（编码器、dynacast、adaptiveStream）
- [../jusi_meet_suite1.9/src/frontend/src/features/rooms/livekit/prefabs/VideoConference.tsx](../jusi_meet_suite1.9/src/frontend/src/features/rooms/livekit/prefabs/VideoConference.tsx) —— 视频网格布局参考

通过 `gradle.properties` 配置 base URL：
```
WE_MEET_BASE_URL=https://meet.jusiai.com
WE_MEET_LIVEKIT_URL_OVERRIDE=
```
（生产环境留空 override；本地开发时模拟器侧后端用 `http://10.0.2.2:8071`、LiveKit 用 `ws://10.0.2.2:7880`。仅在 `debug` 构建类型的 `AndroidManifest.xml` 中设置 `usesCleartextTraffic="true"`，正式包保持 HTTPS-only。）

---

## 清单权限（AndroidManifest）

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.CAMERA"/>
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>
<uses-permission android:name="android.permission.BLUETOOTH"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-feature android:name="android.hardware.camera" android:required="false"/>
<uses-feature android:name="android.hardware.microphone" android:required="true"/>
```

---

## MVP 明确不做的部分

集中列在这里以避免范围蔓延，每一项后续可独立立项：
- AI agent 启停（`/api/v1.0/rooms/{id}/start-ai-agent/` —— Qwen / Doubao）。位于 [../jusi_meet_suite1.9/src/agents/qwen-ai-agent.py](../jusi_meet_suite1.9/src/agents/qwen-ai-agent.py) 的 Qwen agent 在服务端 dispatch 后会自动加入房间，所以**只要别人触发了它**，MVP 收到它的音频是天然支持的；只是 MVP 不提供"启动 AI"的按钮。
- 聊天（LiveKit data channel）
- 屏幕共享（无论收发）
- 举手、表情反应
- 管理员控制（静音/移除参与者、录制启停、大厅/等待室）
- 实时字幕 / 转写
- Token 自动刷新（access_token 5 分钟）；MVP 过期后要求重新登录
- Keycloak PKCE 登录路径
- 设备选择器（仅使用默认摄像头与麦克风）
- 深色模式细节打磨、完整 i18n（zh-CN 必备，en 可选）

---

## 验证

1. **后端前置**：要么直接指向 `https://meet.jusiai.com`（已运行），要么在上游仓库 `../jusi_meet_suite1.9/` 中执行 `docker compose up` 启动本地栈，确认 `http://localhost:8071/api/v1.0/config/` 返回 200，且 LiveKit 在 `ws://localhost:7880` 可达。
2. **构建**：在本仓库根目录（`D:\workspace\Meeting\we_meet_android\`）执行 `./gradlew :app:assembleDebug`，预期生成 `app/build/outputs/apk/debug/app-debug.apk`。
3. **安装到模拟器**：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。模拟器选 API 30+ 并启用摄像头（Extended Controls → Camera → "Webcam0" 或 Virtual Scene）。
4. **冒烟 — 登录**：启动 App → 输入测试手机号（用 demo 账号让验证码出现在后端日志，或用配置好的火山引擎短信）→ 输入验证码 → 确认跳转到 HomeScreen，并且 `TokenStore` 在 App 重启后仍然有值。
5. **冒烟 — 加入**：在 Web 前端创建或查找一个 `public` / `trusted` 房间，复制其 slug，粘贴到 HomeScreen → 点击加入 → 确认：
   - 出现本地摄像头预览
   - 在另一窗口用 Web 加入同一房间，2-3 秒内出现在 grid 中
   - 双向语音畅通
6. **冒烟 — 控制**：切换麦克风 / 摄像头；在 Web 客户端验证本地参与者的 track 状态变化。点击前后摄像头切换，验证预览翻转。点击挂断，验证回到 Home 且可重新加入。
7. **异常用例**：错误验证码 → 红色错误文案。错误 slug → toast/错误提示，留在 Home。通话中断网 → LiveKit SDK 提供的重连指示。
8. **清理检查**：在房间内强制停止 App，再到 Web 房间查看 —— Android 参与者应在约 30 秒内消失（LiveKit 空闲超时）。Renderer 没有泄漏（如有需要，可用 Android Studio Profiler 验证）。

---

## 实现者注意事项

- 用 `Application` 范围的单例 Retrofit 实例，**不要**每个 ViewModel 都重建。
- `RoomViewModel` 暴露 `StateFlow<RoomUiState>`，Compose 侧用 `collectAsStateWithLifecycle` 收集，避免旋转屏导致泄漏。
- LiveKit Android SDK 2.x 提供 `Room.events: SharedFlow<RoomEvent>`，用它，不要用旧的 listener API。
- MVP 阶段不做自定义音频路由 —— SDK 默认的扬声器策略已经足够合理。
- 所有硬编码字符串从第一天就放进 `res/values/strings.xml`（以及 `values-zh-rCN/strings.xml`），这是廉价的保险。
- 真正的构建/运行指南请见 `README.md`：环境要求（JDK 17、Android Studio Koala+）、依赖的上游后端仓库、如何在 `gradle.properties`（或 `local.properties`）中设置 `WE_MEET_BASE_URL`，以及上面的冒烟测试流程。
- 在 `.gitignore` 中追加 Android Studio / Gradle 条目（`.gradle/`、`build/`、`local.properties`、`.idea/`、`*.iml`、`captures/`）。
