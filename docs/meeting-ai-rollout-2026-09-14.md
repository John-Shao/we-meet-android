# 会议 AI 入口启用

根据用户部署反馈和启用指令，在受版本管理的 `gradle.properties` 中开启 `WE_MEET_RECORDS_NATIVE`、`WE_MEET_CAPTURE_NATIVE`、`WE_MEET_CAPTURE_TRANSLATION_NATIVE`、`WE_MEET_ONLINE_AI_NATIVE`、`WE_MEET_CLOUD_RECORDING_NATIVE`。不只修改本机的 `local.properties`，因此其他构建机拉取代码后也能使用相同设置。

`local.properties` 仍有更高优先级；如果构建机明确设置了同名 `false`，需移除该覆盖或改为 `true`。这些是编译时开关，须重新打包并安装 APK。服务端能力开关和对应 Worker 另行开启，原生开关不绕过 API 权限。

验证：`:app:assembleDebug checkDesignTokens --offline` 通过，生成的 BuildConfig 中上述 5 项均为 true。没有执行真机录音或模型调用。部署配置与重新发布步骤见 Meet 仓库 `docs/plan/meeting-ai-rollout-2026-09-14.md`。
