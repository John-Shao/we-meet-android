# Android 本人声纹设置、登记与代码走查

日期：2026-10-10。分支：`feature/speaker-identity-voiceprint`。设置页接入个人和组织范围的三类独立权限、管理员组织开关、随机朗读登记、样本试听及确认／拒绝、本人删除及清理回执。模型路线继续 Qwen 优先；本阶段不评估真人识别准确率，也不启用 CAM++。

## 行为与边界

设置入口位于用户设置。打开页面只读，不创建授权、登记或上传；主动登记、通话积累、身份匹配三类开关分别提交，个人范围显式发送 `organization_id: null`。管理员只能修改所选组织的功能开关，不能代替本人授权。功能暂停时仍允许撤销已有权限和删除本人档案。

独立 Retrofit API 使用既有私有客户端，不缓存、不记录 HTTP 内容、不跟随重定向或自动重试。请求绑定打开页面时的账号及实际登录会话，并在附加凭证前复核本地 tag。同账号重新登录也拒绝旧请求；正常 token 续期仍可使用。仓库验证范围、版本、世代、分页、响应动作及固定音频契约。

登记采用服务端六条随机朗读提示，录音为单声道 24 kHz PCM16 WAV，每段 3～10 秒。麦克风仅在明确点击并获得系统权限后打开；录音有帧数与十秒计时上限，取消、后台、权限变化或来源异常会终止。原有 AI 录音默认采样率仍为 16 kHz。

系统文件选择只接受有界、结构有效的同格式 WAV，不自动上传。新录音和选择的文件先留在内存，支持本地试听及丢弃，必须再明确上传。客户端结构检查不代表单人、语音或随机朗读质检通过。

样本音频先复核所选范围内的元数据，再从固定第一方 API 有界读取；无私有音频 URL 或磁盘缓存。试听通过内存 AudioTrack 完成，失去音频焦点、拔出耳机、关闭、后台或身份失效时停止。只有服务端允许确认、实际播放完整以及明确勾选“这是我本人”同时成立，才能确认；待质检样本仍可明确拒绝。

登记开始、上传和删除结果未知时保留原命令，只有用户明确操作才重试。上传重试保留原槽位、字节及令牌；最后一段已使登记关闭时仍可重试原槽位。409 禁止继续写入，须手动重新读取，不自动重放写操作。

后台清空设置、音频、样本、令牌与命令，并停止录音／播放；迟到音频被清零。文件选择或系统权限对话框仅保留登记 ID、授权版本和世代，不保留上传令牌。返回前台后重新读取设置与登记，确认原登录、范围、版本、世代、档案及有效期；任一改变都拒绝恢复。录音中的五秒只读心跳仍执行权限复核。

## 走查发现与修复

| 问题 | 修复及验证 |
|---|---|
| 个人权限请求省略 null 或带上其他权限的 null | 独立 Moshi 保留范围 null；仅发送被修改的权限，真实 Retrofit JSON 断言通过 |
| 首次登记前无档案，客户端误拒绝服务端新建的档案 | 登记响应后读取当前设置复核新档案；首次登记及原命令重试回归通过 |
| 录音忙碌时普通刷新跳过，不能及时发现权限撤销 | 录音期间保留独立只读授权心跳；撤销后取消来源并清空媒体 |
| 最后一段上传成功但响应丢失，刷新关闭登记后无法重试 | 重试固定原槽位及原许可快照，不用新槽位；真实关闭响应及相同字节断言通过 |
| 系统文件选择器／首次麦克风权限造成后台清理，返回操作被丢弃 | 最小待返回描述与音频清理分开；返回后重新获取许可，暂停／恢复模拟器用例及失效版本、过期、关闭登记用例通过 |
| 停止并释放 AudioTrack 与完成度读取竞争，抛出原生指针异常 | 播放／停止／完成度读取同步；异常终止试听并保持未完整试听，原失败交互及原生并发回归通过 |
| 语言测试上下文未提供 Activity Result owner，不能打开设置页 | 隔离测试显式提供 fixture Activity 注册器；中文暗色 1.5 倍字号删除弹窗验证通过 |
| 泛型分页 fixture 使用 Any adapter 丢失泛型，负向契约可能错误通过 | fixture 显式编码分页结构，增加有效分页正向用例，不能仅用错误响应验证接口 |

五种语言使用相同的 85 个资源键及格式参数。权限行提供开关语义及完整说明，弹窗继承调用方语言、配置及字号；中文暗色大字体截图确认删除和取消可见、文字可换行。

## 验证与复现

JVM 共 86 项通过：新增声纹 API 12、WAV 4、录音器 4、控制器 17；既有识别契约／控制器 28、录音 pump／tap 21。隔离 debug／androidTest APK 编译、设计 token 检查及差异检查通过。

模拟器 14 项验证通过，覆盖设置只读、独立权限、组织政策、合成录音的明确上传、原槽位重试、真实 AudioTrack 完整播放门禁、质检中拒绝、暂停组织删除、登录变化、409 手动恢复、中文暗色大字号、文件选择／系统权限返回及原生停止竞争。结果与日志按不同用例计数，不累计重跑；必须检查 JUnit 结果，adb 退出码不代表测试成功。

使用 JDK 17、已下载 Android 依赖及指定隔离模拟器：

```powershell
& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest --tests com.we.meet.data.VoiceprintContractTest --tests com.we.meet.data.VoiceprintWaveTest --tests com.we.meet.data.VoiceprintRecorderTest --tests com.we.meet.ui.voiceprint.VoiceprintControllerTest :app:assembleDebug :app:assembleDebugAndroidTest checkDesignTokens --offline --console=plain '-PWE_MEET_TEST_RUNNER=com.we.meet.ui.records.IsolatedRecordsRunner' '-PWE_MEET_TEST_ID_SUFFIX=.fixturespeakeridentity'
```

测试包为 `com.we.meet.fixturespeakeridentity`／`.test`，runner 为 `com.we.meet.ui.records.IsolatedRecordsRunner`，类为 `com.we.meet.ui.voiceprint.VoiceprintSettingsScreenTest`。普通设置用例暂授予隔离包录音权限，但注入的来源只生成合成 WAV，未打开真实麦克风；首次授权返回用例撤销权限并使用测试 Activity Result 注册器。测试结束撤销隔离包权限。文件选择用例只写测试缓存中的合成 WAV，并在 finally 删除。

日志及中文截图位于工作区私有目录 `work/speaker-identity-android-2026-10-10/`，不入库。未初始化真实账号／推送，未连接生产后端，未采集真人声音，未调用收费 ASR。原生 AudioRecord 的真实设备兼容性、真人质量和多人的匹配效果仍需获授权数据与设备验收。

本阶段不是完整目标交付。有效设备组／需更新原因的服务端状态投影、跨端通话采样、长 ASR 前导入预检、AI 录音分人、部署调度／清理／可信恢复及生产验收继续按 Web 方案开发。
