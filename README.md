# We Meet Android

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
- 个人资料：昵称、简介、头像、封面

## 仓库关系

```
D:\workspace\we-meet\
├── we-meet\            ← 后端 + Web 前端（Django / React + LiveKit）
└── we-meet-android\    ← 本仓库
```

移动端设计与接口说明（位于后端仓库）：
[../we-meet/docs/extensions/移动端App客户端支持方案.md](../we-meet/docs/extensions/移动端App客户端支持方案.md)

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
