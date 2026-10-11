# Android AI 录音分人、派生版本与代码走查

日期：2026-10-10（Asia/Shanghai）。分支：`feature/speaker-identity-voiceprint`；本轮基线：`abee2a103cbd4b424ccf9a00ea7fb6578af9c815`。

本轮接通录音详情中的说话人分离操作、固定派生版本原文、身份候选／人工确认和录音区间试听。模型路线仍为 Qwen 优先；没有真人采样、收费 ASR、生产后端访问或部署。

## 行为与边界

- 分人请求须明确确认费用；先在已有加密意图库保存 nonce、原始 revision 和本地登录世代，再发送 POST。回执必须匹配 nonce、capture scope 和原始 revision，才能清除待恢复请求。读取、进入前台和轮询均不重放 POST；未知结果只能由用户恢复原请求。登录世代只用于本地隔离，不进入请求正文。
- 独立客户端固定拥有者与实际登录，通过私有 Retrofit 的 `PrivateLogin` 标签约束凭证。请求超时为 15 秒；同账号重新登录、晚到结果及后台工作不可复写旧私有状态。关闭创建后仍可读取历史和取消已有任务。
- 拥有者原文查询同时绑定 ASR 和派生 job ID；返回行须匹配所请求的派生版本。版本更新后清除旧游标，编辑时保留旧阅读版本与草稿，禁止新分人／恢复请求；取消或保存退出编辑后才切换阅读版本。
- 录音身份入口仅在当前成功分人指针存在并满足记录能力时开放。新派生版本清除旧候选、建议面板和试听；人工通讯录选择、自定义标签及确认／拒绝复用已有流程。
- 试听最多 10 秒，下载前验证连续来源区间，不预取区间之后的分片。最后一片的 WAV 实际 PCM 内容裁到结束边界，原生 `AudioTrack` 不接收下一说话人的样本。来源时钟、速率和 30 秒总期限共同约束，结束／失权／后台清除音频。关闭未使用的身份面板不影响普通回放。

## 发现的问题及修复

| 问题 | 修复 |
|---|---|
| 身份面板在 Compose `key` 分组中提前退出，引发 Start/end imbalance，上传及录音入口均可能崩溃 | 将有条件返回的主体拆为独立 composable；分组只负责版本隔离。首轮八个相关界面失败在修复后通过 |
| 身份目录已读取但界面仍停留在初始加载状态；模拟器状态收集在网络回调线程恢复 | 明确使用 `Dispatchers.Main.immediate` 收集界面状态。完整回归与单独运行均复现原问题，修复后同用例通过；临时诊断日志已移除 |
| 试听总期限抛出取消异常，界面把它当作外部取消，可能保留准备／播放状态 | 内部期限使用 `withTimeoutOrNull`，超期成为播放失败；外部取消仍继续传播。虚拟时间用例覆盖两种情况 |
| 取消任务收到 401／403／404 后仍展示旧私有历史并允许再次取消 | 立即清空数据、费用确认和取消入口，停止轮询；仅明确刷新后重新读取 |
| 首次读取成功后，意图存储再次读取失败被错误归类为接口拒绝 | 按本次意图读取完成状态区分存储与接口失败，存储失败重置 ready，禁止写入 |
| 新派生版本到达后，旧草稿保存可能借用新记录 revision | 派生版本草稿保存使用冻结的阅读 revision；冲突保留草稿，并在 POST 前拒绝旧版本写入 |
| 取消测试按独立完整文本匹配“已取消”，而产品显示“版本号 · 已取消” | 更正测试为包含匹配，保留真实取消请求和状态验证 |

三项异常边界的新增 JVM 用例先运行复现：29 项中 3 项失败，分别为内部试听期限、取消权限丢失和后续存储失败；修复后相关回归通过。测试文本匹配问题单独记录，不计为产品缺陷。

## 验证

- 120 项 JVM 回归通过：分人 API 契约 13、分人控制器 12、录音播放引擎 17、播放仓库 8、ASR 仓库 7、记录仓库 35、身份 API 契约 15、身份控制器 13。测试报告零失败／错误。日志：`work/android-capture-diarization-review-final-build-2026-10-10.log`。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 和 `checkDesignTokens` 通过，界面状态收集修复后的收尾构建日志为 `work/android-capture-diarization-review-main-thread-build-2026-10-10.log`。
- 最终 API 29 隔离模拟器 35 项全部通过：分人面板 7、录音播放器 12、身份识别面板 12、派生版本／草稿 4。日志：`work/android-capture-diarization-review-verified-ui-2026-10-10.log`，终态 `OK (35 tests)`，无失败栈。首轮 34 项中 9 项失败，修正分组及文本断言后 34 项通过；追加草稿用例后的第一次 35 项中一项目录加载失败，单独复现后修复状态收集线程。各次运行不直接相加。
- 五种语言新增各 21 个资源键，XML 解析、键集合和占位符一致性检查通过；`git diff --check` 通过。中文暗色 1.5 倍字号截图已检查，费用确认和操作完整可读，无横向裁切。截图：`work/capture-diarization-android-zh-dark-large-2026-10-10.png`。
- 模拟器测试使用 API 29、独立包 `com.we.meet.fixturespeakeridentity`、裸 `Application` 与 `IsolatedRecordsRunner`；录音权限撤销且 appops 禁止录音。本地拦截器只接受虚构域名，使用合成 WAV；原生输出用例只播放合成静音，没有生产账户、麦克风采样或外部云端请求。

构建参数须整体加引号，避免 Windows PowerShell 改写含点号的 Gradle 属性：

```powershell
$env:JAVA_HOME='D:/Program Files/Java/jdk-17'
$env:ANDROID_HOME='D:/ProgramData/AndroidSDK'
& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain `
  :app:assembleDebug :app:assembleDebugAndroidTest checkDesignTokens --offline --console=plain `
  '-PWE_MEET_TEST_RUNNER=com.we.meet.ui.records.IsolatedRecordsRunner' `
  '-PWE_MEET_TEST_ID_SUFFIX=.fixturespeakeridentity' `
  '-PWE_MEET_BASE_URL=http://127.0.0.1:9/'
```

相关 JVM 类见 `app/src/test/java/com/we/meet/data/` 和 `ui/records/`。模拟器运行 `CaptureDiarizationPanelTest`、`CaptureAudioPlayerTest`、`SpeakerIdentificationPanelTest`，并单独选择 `RecordScreensTest` 的新旧版本分页、编辑保留、旧版本保存冲突及原有 revision 草稿用例；不运行需要完整产品 Application 的其他详情路由测试。

## 尚待完成

模拟器和本地契约证明技术链路及交互边界；完整产品 Application／真实设备回归、可信通话采样／暂停／共享设备排除、实际设备分组、部署调度／清理／可信恢复演练，以及获授权真人多人和跨设备识别效果、生产容量与上线验收仍待完成。功能默认关闭，本轮结果不代表真人准确率达标或生产发布完成。
