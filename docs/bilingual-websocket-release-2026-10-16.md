# 双向翻译内部 Release 候选

候选版本为 `0.3.0-work.4`，`versionCode=6`，包名 `com.we.meet`。用户确认沿用既有内部签名；证书 SHA-256 为 `053d9864295c3d3f87b615bacb4872f136bdb1a34af7fe503ca036530141c528`。它是不可调试的内部 Release 包，不是应用商店正式证书签名的包。

以 Android 主线 `9699c335` 建立独立发布分支，只提取双向翻译改动。保留原模块结构及既有会议、AI 通话、声纹功能，不携带混合工作区中未提交的办公平台、屏幕共享和 realtime-client 模块搬迁。双向翻译专用 AOQ 服务从原 feature-assistant 模块退役。

生产后端基线已应用远程控制迁移 `0226`，发布分支中的双向翻译迁移使用 `0227_bilingual_session`。此前设计和探针报告中 `0203` 属于开发隔离环境的历史证据。

## 构建与检查

使用 JDK 17 执行：

```powershell
.\gradlew.bat :app:testReleaseUnitTest :app:assembleRelease :app:assembleReleaseAndroidTest --max-workers=1 --no-parallel --console=plain '-Pkotlin.compiler.execution.strategy=in-process' '-PWE_MEET_INTERNAL_RELEASE=true' '-PWE_MEET_TEST_BUILD_TYPE=release'
.\gradlew.bat checkDesignTokens --max-workers=1 --console=plain
python scripts/check_bilingual_contract.py
python scripts/i18n.py check --module app
```

- Release 主 APK、测试 APK 构建成功，750 项单元测试通过，0 失败、错误、跳过。
- Release 关键 lint 和设计规范检查通过；29 语言契约一致；app i18n 为 0 错误、158 条既有提示。
- `apksigner verify` 通过，证书与此前内部 Release 一致；包检查确认版本 6，未带 `application-debuggable`。
- 保持项目既有 `isMinifyEnabled=false`，本候选未启用 R8 混淆，不能声称已验证混淆行为。
- 首次测试 APK 编译发现一处混合工作区的办公入口参数残留，提取时已移除并重建通过；生产 APK 不包含该办公入口。
- Release 集成测试使用已声明的 MainActivity，避免向交付 APK 加入测试 Activity。

覆盖安装、Release 设备测试、真实登录页面、扬声器冒烟和生产发布结果待执行后补充。此前 Debug 与真机声学结果见[修复验收记录](bilingual-websocket-design.md)，不冒充本候选的验证结果。
