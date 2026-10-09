# Android 人工说话人身份标记

日期：2026-10-10。分支：`feature/speaker-identity-voiceprint`。

会议记录的说话人页面现在接通记录范围内的通讯录和自定义标签。入口仅对 `can_attribute=true` 且已分人的说话人显示；只读记录、未知音轨和在线账号身份不提供改名操作。

## 行为与边界

- 通讯录支持姓名搜索、成员／外部联系人筛选、部门查找和前后分页；同名联系人显示组织或部门辅助信息。
- 选择后先展示姓名预览，再点击保存。外部联系人有共享姓名的说明，不展示电话、邮箱或私人备注；真正绑定成员还是仅保存姓名快照由服务端重新校验。
- 标签按去除首尾空白后的 Unicode 码点校验，允许 1～64 个字符；拒绝控制、格式、非法代理码点和换行。清除请求不携带联系人或标签字段。
- 面板展示当前名称，可从有效时间线中的首个来源片段试听。是否能播放仍由记录页面的媒体权限决定。
- 所有写入使用 `identity-decision` 接口和打开面板时的记录 revision，不再从界面调用旧归属 PATCH。成功后重新读取记录，使用服务端的最终姓名。
- 409 或已观察到的版本变化保留草稿、禁用保存／清除；标签成为可选择复制的只读文本，重新加载后才能再次编辑。权限失效也禁止旧面板继续提交。
- 迟到的目录响应不会覆盖新的筛选，切换模式或分页会清除旧选择。保存期间不能重复提交；关闭／切换账号后丢弃旧面板的数据和结果，不持久化通讯录或草稿。

这些操作只更新当前记录的最终名称，不登记声纹、不打开任何声纹授权，也不确认模型身份建议。自动身份匹配、声纹设置和登记界面继续按主方案开发。

## 验证方法

测试使用隔离 application ID `com.we.meet.fixturespeakeridentity` 和 `IsolatedRecordsRunner`，不启动账号、推送或生产仓库。网络请求由 fixture interceptor 回答，不连接实际后端，不使用真人录音。

在 JDK 17、已有 Android SDK／Gradle 缓存的环境执行（PowerShell，属性参数需整体加引号）：

```powershell
& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain `
  :app:testDebugUnitTest `
  --tests 'com.we.meet.ui.records.SpeakerIdentityControllerTest' `
  --tests 'com.we.meet.data.MeetingRecordIdentityContractTest' `
  --tests 'com.we.meet.data.MeetingRecordAttributionTest' `
  --tests 'com.we.meet.data.MeetingRecordRepositoryTest' `
  --tests 'com.we.meet.data.MeetingRecordCorrectionTest' `
  --tests 'com.we.meet.data.SpeakerTimelineTest' `
  :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain `
  '-PWE_MEET_TEST_RUNNER=com.we.meet.ui.records.IsolatedRecordsRunner' `
  '-PWE_MEET_TEST_ID_SUFFIX=.fixturespeakeridentity'
```

核对 APK 的 `output-metadata.json` 中 application ID 后，只安装上述隔离应用和对应 test APK，并在测试模拟器运行：

```text
adb -s emulator-5556 shell am instrument -w -r -e class com.we.meet.ui.records.SpeakerIdentityEditorTest,com.we.meet.ui.records.SpeakerTimelineTest com.we.meet.fixturespeakeridentity.test/com.we.meet.ui.records.IsolatedRecordsRunner
```

截图输出到隔离应用的 external files `speaker-identity` 目录，供中文 1.5 倍字号／暗色和外部联系人界面的视觉检查。测试执行中发现并修复了可空状态订阅导致的重组崩溃、暗色正文颜色以及晚到保存失败覆盖版本冲突的问题。

本轮验证：上述 6 个 JVM 测试类共 72 项通过；API 29 模拟器（1080×1920、420 dpi）中，8 项身份界面交互和 5 项时间线回归共 13 项通过。交互覆盖真实 Retrofit 请求的标签／清除、外部联系人预览、部门／分页成员选择、冲突、只读／未知轨道隐藏及来源试听。5 个语言资源文件各 18 项键一致，XML 解析和 APK 编译通过。

冲突用例进一步检查只读标签的文本选择、复制动作和模拟器剪贴板内容，单独复测通过；这是一项已有用例的增强，不重复计入测试总数。

已检查[中文大字体暗色界面](reviews/speaker-identity/label-zh-dark-large.png)和[外部联系人姓名预览](reviews/speaker-identity/contacts-external-light.png)：文本颜色正确，筛选项可换行，保存／清除可见并可滚动到达。这些图片全部来自合成界面数据。
