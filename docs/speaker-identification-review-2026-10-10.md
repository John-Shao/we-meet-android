# Android 导入说话人身份识别与代码走查

日期：2026-10-10。分支：`feature/speaker-identity-voiceprint`。本阶段接通已完成导入的显式候选选择、独立识别请求、状态复核、限段试听及人工确认／拒绝，沿用 Web 与后端的既有契约。Qwen 优先，功能仍由服务端默认关闭的两个开关共同控制。

## 接入与边界

识别入口位于记录的说话人页，只对已完成导入且具有编辑、读取转写和播放媒体能力的用户显示。未打开时只读取配置，不加载目录；缺少配置或任一开关关闭时不显示入口。人工通讯录／自定义标签标记继续独立使用。

新增独立 DTO、Retrofit API、会话仓库和面板控制器。个人库显式发送 `organization_id: null`，候选目录使用 `personal`；组织记录只允许服务端指定组织。每页最多 25 人、检索最多 80 字符、偏移最多 10000，明确选择最多 50 人。换库清除原选择，分页保留明确选择。候选目录不返回模板、评分或登记状态。

面板关闭、后台或登录改变时停止试听并丢弃目录、姓名与建议。私有客户端复核打开时的账号和登录会话；请求带本地 OkHttp tag，AuthInterceptor 在实际附加凭证前再次核验，tag 不发送到服务器。同账号重新登录也隔离旧请求，正常 token 续期继续可用。请求使用既有无缓存、无 HTTP 日志、不跟随重定向的媒体私有客户端；仓库单次调用最多 15 秒。

网络结果未知时，应用内存最多保留 20 个未确认原命令，只含请求 key、revision、范围与人员／说话人 ID。关闭重开仍可查询或明确重试同一个命令；不自动提交、不用新 key 重复排队、不放到磁盘。同一记录的另一面板不能覆盖已有未确认命令；返回副本避免调用者修改原命令，换登录清空全部命令。

处理中的批次及完成后待决定的建议每 5 秒只读复核。暂不可验证、权限失败或冲突后隐藏候选姓名、试听与确认；暂不可用仍可取消已知请求。刷新先读取服务器当前 revision，再读取对应目录，可以自行恢复 409。整批完成后才允许决定，确认只发送 suggestion ID、动作与 revision，由服务器事务重新复核并写最终归属，不登记声纹。逐人确认使用每次更新后的 revision。

## 试听和显示

建议试听复用固定来源的 UploadMediaPlayer，播放经服务端复核的 3～10 秒区间。原生时钟采样到末尾时暂停并停在区间末尾；关闭、后台、登录变化、错误或决定会终止试听。普通跳转、字幕跳转和视频进度拖动退出片段模式。无效区间不打开媒体，准备后的实际时长仍须覆盖结束点。

五种语言共用 42 个新资源键。底部弹窗保留调用界面的语言与字号，使用可滚动内容及完整展开，支持中文、暗色和 1.5 倍字号。播放器最小高度同时受当前屏幕高度上限约束。

## 走查发现与修复

| 问题 | 修复及证据 |
|---|---|
| 同账号重新登录可能让排队请求携带新凭证 | 实际附加 Authorization 前校验固定登录 tag；真实 TokenStore／AuthInterceptor 的排队请求与 token 续期测试通过 |
| 同一记录多个面板可能覆盖未确认命令，恢复或取消使用错误 key | 拒绝不同命令覆盖，恢复原 key；仓库与控制器的双面板回归通过，Web 同步补齐保护 |
| 个别 DTO 新字段插入旧位置会破坏 Kotlin 位置参数调用 | `edit` 能力追加在原字段末尾，人工标记、记录仓库及媒体回归通过 |
| 个人库提交省略 null 会违反服务端显式范围契约 | 独立 Moshi converter 保留 null，真实 Retrofit JSON 断言通过 |
| 关闭、后台或旧目录迟到结果可能保留姓名／继续试听 | 会话核验、控制器世代与生命周期清理；迟到响应、换库、后台及关闭回归通过 |
| 原播放器没有片段终点，试听可能继续整段播放 | 增加原生时钟结束检查及停止信号；真实合成 WAV 与模拟时钟回归通过 |
| 视频最小高度超过小屏幕上限 | 最小高度取原最小值和屏幕上限中的较小值；原横竖屏／比例测试复现后通过 |
| 弹窗 Android owner 覆盖调用方资源与字体设置 | 弹窗内容显式继承调用方语言、配置和密度；中文暗色大字号选择、试听和决定控件通过 |

## 验证结果

全部输入为合成 ID、姓名、接口响应及无真人语音的 WAV。模拟器使用独立 application ID `com.we.meet.fixturespeakeridentity` 和普通 Application runner，未初始化真实账号、推送或生产仓库。实际媒体测试只读取测试应用自身的合成文件或本地 HTTP fixture。

| 检查 | 结果 |
|---|---|
| JVM 契约、控制器与相关记录／归属／媒体仓库 | 113 项组合通过；收尾增加两个双面板用例，15 项识别契约与 13 项控制器复验通过，共覆盖 115 个不同用例 |
| 模拟器识别面板 | 10 项通过，包括实际 Retrofit、排队凭证隔离、明确选择、未知请求重开重试、暂不可用取消、409 后两次确认及中文暗色大字号 |
| 模拟器播放器、URL 续租与人工标记 | 36 项通过；其中 5 项新增试听边界测试，包含真实 8 秒合成 WAV 从 1 秒播放到 4 秒后自动暂停 |
| 编译 | 隔离 debug APK、androidTest APK 及 JVM 编译通过 |
| 资源与差异 | 五种语言 XML 可解析、42 个键一致；`git diff --check` 通过 |
| Web 同步修复 | 13 项私有 API／命令测试、14 项识别面板交互通过；TypeScript、修改文件 ESLint／Prettier 通过 |

模拟器整组初次验证发现视频高度及中文弹窗问题，修复后对应回归通过。另有首次目录加载在测试原 10 秒等待内未完成：现场仍为 loading，并已请求目录，没有权限或响应结构错误；将控件等待调整为 30 秒以覆盖串行读取，10 项面板收尾复验通过。生产请求的 15 秒超时未调整，不将这次模拟器等待视为性能或模型效果结论。

日志和中文可见界面截图位于工作区私有产物 `work/speaker-identity-android-2026-10-10/`，不入库。截图检查确认文字可换行、内容可滚动、确认与拒绝均可见。结果按不同用例计数，不累计重跑次数；instrumentation 的 adb 退出码不能替代 JUnit 失败统计。

## 复现

在 JDK 17 和 Android SDK 环境中，使用已下载依赖编译隔离包：

```powershell
& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain '-PWE_MEET_TEST_RUNNER=com.we.meet.ui.records.IsolatedRecordsRunner' '-PWE_MEET_TEST_ID_SUFFIX=.fixturespeakeridentity'
```

JVM 识别契约／控制器可用同一 wrapper 命令运行 `:app:testDebugUnitTest --tests com.we.meet.data.SpeakerIdentificationContractTest --tests com.we.meet.ui.records.SpeakerIdentificationControllerTest`。组合验证另覆盖 SpeakerIdentityControllerTest、MeetingRecordIdentityContractTest、MeetingRecordAttributionTest、MeetingRecordRepositoryTest、MeetingRecordCorrectionTest、MeetingRecordMediaTest 和 CapturePlaybackRepositoryTest。

安装上述隔离包和测试包后，在指定测试模拟器运行：

```powershell
adb -s emulator-5556 shell am instrument -w -r -e class 'com.we.meet.ui.records.SpeakerIdentificationPanelTest,com.we.meet.ui.records.UploadMediaPlayerTest,com.we.meet.ui.records.UploadMediaRenewalTest,com.we.meet.ui.records.SpeakerIdentityEditorTest' com.we.meet.fixturespeakeridentity.test/com.we.meet.ui.records.IsolatedRecordsRunner
```

只安装隔离包，不使用真实账号或录音。这次模拟器 HTTP 为本地契约 fixture，未连接真实后端，也未触发收费 ASR；不能替代真人多人匹配或跨端端到端验收。

Android 本人设置／登记、收费 ASR 前导入预检、可信通话采样、AI 录音分人、部署／清理／恢复、真人与生产验收仍待完成。缺少获授权样本不判定 Qwen 不合格，不触发 CAM++ 替换。
