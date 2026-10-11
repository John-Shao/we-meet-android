# Android 说话人身份内部版本

本轮于 2026-10-11 开始，2026-10-12 完成构建与隔离回归。版本：`0.3.0-work.3`，`versionCode=5`，包名 `com.we.meet`，最低 Android 10（API 29）。

本次将声纹功能分支 `d5782b3218a8e7f4cefeed622fc44ff60c838ac1` 合并到 Android 主线 `f606fcfdac843b5bc91759ed7a7b9df0b0532b27`，保留主线最近的通话设置、双语展示及原生拍照实现。

## 用户可见行为

- 已分人的会议记录可人工选择通讯录联系人或填写自定义标签；按记录编辑能力显示入口。人工标记只更新当前记录的名称，不登记声纹，也不授予任何声纹权限。
- 新增声纹设置、授权、登记和样本管理页面。登记、通话积累、身份匹配分别授权；入口打开不创建授权或上传。
- 导入录音可显式选择身份识别范围和候选人；功能不可用时可继续普通转写。通话采样仅在服务端功能与采样开关均开启后提供。
- 补齐导入身份页面的德语、法语和荷兰语各 27 个资源，五种语言的资源键和格式参数一致。

服务端基线为 `we-meet` 的 `f12d6c04cba0107d2bfdf05d0be1e9c518386abe`（Helm 465），数据库已包含说话人身份迁移。生产 `speaker_identity.enabled`、`matching_enabled`、`sampling_enabled` 保持 `false`。安装新 APK 不会开启自动识别或通话采样。

## 构建与交付

交付非 debuggable 的内部 Release APK，沿用已交付内部 APK 的 Android Debug 证书，证书 SHA-256 为 `053d9864295c3d3f87b615bacb4872f136bdb1a34af7fe503ca036530141c528`，支持相同包名、相同证书旧版本的覆盖更新。本次不更换签名证书。

内部包沿用既有 `AI_CALL_CAMERA_VOICE_CONTROL_RELEASE=true`、`AOQ_MEDIA_PLAYBACK=true`、`AI_CALL_VOICE_HANGUP=true` 构建参数；原有五项会议 AI 原生入口保持启用。这些参数与服务端声纹开关独立。

内部归档位于工作区主仓库的 ignored 目录 `release/0.3.0-work.3-speaker-identity-20261011/`，包含签名 APK、校验值、来源提交、SDK 提交、各模块测试结果、生成配置哈希、签名与对齐验证记录。配置值和签名密钥不进入验收清单。交付路径不是应用商店或线上更新渠道的发布记录。

复现构建使用 JDK 17、Android SDK，以及已有依赖缓存；显式提供已发布后端的 IM 金标准样例目录，避免 worktree 的同级目录变化影响契约测试：

```powershell
& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain `
  checkDesignTokens testDebugUnitTest :app:assembleDebug :app:assembleRelease `
  --offline --console=plain --no-daemon --no-parallel --max-workers=1 `
  '-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8' `
  '-Pkotlin.compiler.execution.strategy=in-process' `
  '-PAI_CALL_CAMERA_VOICE_CONTROL_RELEASE=true' `
  '-PimCardFixtures=D:/workspace/jusi-meet/.worktrees/we-meet-speaker-identity/src/backend/core/tests/fixtures/im_cards'
```

## 验收范围

五语言检查与发布工具 40 项测试已通过。完整合并版本的 JVM 回归 1,018 项全部通过（App 750、设计组件 9、AI 通话 121、云文档 51、IM 87），无失败、错误或跳过；通讯录模块无 JVM 测试。Debug、Release APK 构建、设计规范及 Release 关键 lint 检查通过。隔离模拟器结果由本次交付清单记录，不累计历史重跑次数。

隔离 UI 使用 API 29 模拟器、独立包名 `com.we.meet.fixturevoiceprintrelease` 和 `IsolatedRecordsRunner`，请求通过 fixture 或本地 MockWebServer 返回，音频使用合成来源，不安装到真实设备或替换真实登录 App。66 个不同用例全部通过（人工身份 8、导入身份 6、身份建议 12、录音分人 7、通话面板 10、声纹设置 18、历史身份提示 2、固定版本分享导航 3），128.927 秒，实际麦克风权限保持关闭。

本次不证明获授权真人多人识别准确率、跨设备声纹效果、真实 Qwen ASR、生产 RTC 采样完整链路或 API 31+ 蓝牙路由已通过。上述验收继续按主方案推进，生产声纹自动识别和采样仍关闭。
