# We Meet Android

We Meet 的原生 Android 客户端 —— **建设中**。

> 📋 当前状态：脚手架阶段。详细实现计划见 [PLAN.md](PLAN.md)。

---

## 这是什么

一个用 **Kotlin + Jetpack Compose** 写的原生 Android 客户端，对接同级仓库 `../jusi_meet_suite1.9/`（We Meet 后端 + Web 前端 + AI agents）。

**MVP 范围：**

- 手机号 + 短信验证码登录
- 通过 slug / UUID 加入 LiveKit 房间
- 推流并渲染本地与远端音视频
- 静音 / 摄像头开关 / 前后置切换 / 挂断

详细范围、目录布局、依赖清单与验证步骤请见 [PLAN.md](PLAN.md)。

---

## 仓库关系

```
D:\workspace\Meeting\
├── jusi_meet_suite1.9\        ← 后端 + Web 前端 + AI agents（上游）
├── we_meet_android\          ← 本仓库
└── volcengine_bidirection_demo\
```

**最低兼容后端版本：** We Meet Suite 1.9

API 契约文档（位于上游仓库，本仓库只读引用）：

- [../jusi_meet_suite1.9/docs/mobile-integration-guide.md](../jusi_meet_suite1.9/docs/mobile-integration-guide.md) —— 完整 API 清单
- [../jusi_meet_suite1.9/docs/mobile-integration-auth.md](../jusi_meet_suite1.9/docs/mobile-integration-auth.md) —— 短信验证码登录细节

---

## 前置要求

- JDK 17
- Android Studio Koala (2024.1+) 或更新
- Android SDK Platform 34
- 一台 API 30+ 的模拟器或真机（启用摄像头）

---

## 构建（脚手架完成后）

在仓库根目录执行：

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

构建前请在 `gradle.properties`（或 `local.properties`）中设置：

```properties
WE_MEET_BASE_URL=https://meet.jusiai.com
```

本地开发时把后端指向自托管栈（模拟器视角）：

```properties
WE_MEET_BASE_URL=http://10.0.2.2:8071
WE_MEET_LIVEKIT_URL_OVERRIDE=ws://10.0.2.2:7880
```

> ⚠️ MVP 阶段不实现 token 自动刷新；access_token 5 分钟过期后请重新登录。

---

## 当前进度

- [x] 仓库初始化（PLAN.md、LICENSE、.gitignore）
- [ ] Gradle 脚手架（root + app 模块、版本目录）
- [ ] 数据层（Retrofit、TokenStore、Repositories）
- [ ] 登录页（LoginScreen + LoginViewModel）
- [ ] 首页（HomeScreen + HomeViewModel）
- [ ] 房间页（RoomScreen + LiveKitController）
- [ ] 端到端冒烟测试

---

## 许可

见 [LICENSE](LICENSE)。
