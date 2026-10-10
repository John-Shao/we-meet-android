# Android 通话声纹采样与代码走查

日期：2026-10-11。分支：`feature/speaker-identity-voiceprint`。模型路线继续 Qwen 优先；本阶段接入本人控制和状态界面，不评估真人身份匹配准确率。

## 接入范围

语音通话、视频通话和完整会议共用 `RoomScreen` 的一个入口。只读取现有 LiveKit 的连接、参与者、麦克风 track 和设备路由元数据；不新建录音／相机来源，不申请录音权限，不上传客户端音频。实际音频采样仍由后端许可与独立 sampler 执行。

客户端固定当前账号、实际登录会话、房间 SID 和参与者 SID；首次成功读取后固定服务端解析的会话及组织。组织不能由用户在通话控制请求中指定。GET 不分配许可或创建声明，只有用户明确保存设备、切换共享、允许、暂停或关闭积累时才提交相应命令。设备草稿不提交，保存设备和共享切换均暂停，允许本次采样需另一次明确操作。

本人三类授权保持独立。“关闭未来通话采样”仅按可信当前组织和授权版本撤销积累权限；不会修改登记或识别权限。设置入口打开当前组织；该组织不在目录第一页时保留明确标签，不推定管理员权限，后台返回仍保持相同范围。设置页接受并本地化展示登记基线、耳机、手机、电脑四个有效组。

## 状态与失效处理

功能需 `speaker_identity.enabled` 和 `sampling_enabled` 同时开启，旧服务缺字段时保持关闭。配置每 30 秒复核；关闭时不读取采样连接，不以三秒轮询重复请求配置。

连接快照最多使用 15 秒；“采样中／提交中”须有五秒内的服务端活动证明，期限计入读取 HTTP 的耗时。本地麦克风关闭覆盖旧活动文案，PiP 只显示状态，不提供声明操作。

设备变化监听建立前禁止允许；监听失败后尝试暂停并保持不可允许。麦克风 track 替换、现有路由选择变化、输入设备或 API 31+ 通信设备变化要求清除设备声明，重新明确允许。设备 ID 只存在于内存中的既有路由模块／监听器，网络 DTO 仅发送公开设备组。

登录／RTC 发生变化后废弃旧控制器和私有状态。后台取消读取和写入、尝试以原 revision 提交安全暂停与空设备声明，然后清空快照和弹窗；未知结果或冲突不自动允许。返回前台重新读取，必要时只重试安全暂停。设备变化发生在写入期间时，完成后追加一次安全清除。允许响应丢失、409 或设备清除后的无效读取均不立即循环重放写请求。

请求使用既有私有 Retrofit：绑定 owner 和本地登录 tag，响应后复核登录；无 HTTP 日志、缓存、重定向或自动网络重试，仓库调用有 15 秒期限。拒绝范围／会话漂移、错误 SID、无效日期／枚举／配额，以及缺少有效声明或授权的正活动状态。UI 只显示固定错误文案。

## 走查修复

| 问题 | 修复与证据 |
|---|---|
| 输入监听尚未建立时默认允许操作 | 默认不可观察，监听成功后开放；JVM 门禁与 API 29 原生元数据用例通过 |
| 关闭配置按三秒反复读取，启用配置从不复核 | 30 秒只读复核，后台清空复核时刻；开关变化不提交写请求 |
| 设备清除读到旧声明时可能立即反复写入 | 安全清除仅尝试一次，后续成功轮询才可再次尝试；丢失确认、无效读取和写入竞争回归通过 |
| Compose 内联返回编译通过，但 D8 无法转换 | 移除 `key` 内提前返回，使用条件分支；实际 debug／androidTest APK 打包通过 |
| 长告知正文挤压关闭操作 | 正文独立滚动，关闭固定在弹窗底部；中文暗色 1.5 倍字号的模拟器原生窗口截图与交互通过 |
| 弹窗丢失父页面中文资源，测试误报关闭不可见 | 通话和嵌套设置弹窗显式继承上下文、配置及字号；失败截图确认英文回退，修复后中文关闭与正文验证通过 |
| 后台返回重新打开旧私有弹窗 | 功能不可见时清除打开状态，后台关闭嵌套设置；返回不自动打开或允许 |
| 设置拒绝通话有效设备组，当前组织不在第一页时失去标签 | 四组校验与本地化标签；保留无管理员权的当前组织，包括后台返回 |

## 验证

89 项 JVM 通过：新增通话 API 契约 10、控制器 17，既有声纹契约 14、设置控制器 20、识别契约 15、识别控制器 13。覆盖真实 Retrofit JSON、只读／独立权限、SID／范围绑定、活动证明、请求时长、迟到响应、登录切换、冲突、设备写入竞争及后台返回。

隔离 API 29 模拟器 28 个不同用例通过：通话面板 10、既有设置与新增有效组／当前组织用例 18。首轮中文弹窗失败实际暴露语言回退，修复后组合结果为 `OK (28 tests)`，43.106 秒；重复截图核验不计为新增用例。测试使用普通 fixture Application 与合成元数据／WAV，实际录音权限保持未授予。原生设备观察只读取系统路由元数据；登记录音仍注入合成来源。

生产 application ID 的 debug APK、隔离 debug／androidTest APK、设计 token、五语言 47 个新增资源键和格式参数、差异检查通过。图片及日志位于工作区 `work/`，不入库：

- `android-call-sampling-final-check-2026-10-11.log`：89 项 JVM、debug APK、设计规范。
- `android-call-sampling-ui-final-2026-10-11.log`：28 项模拟器组合结果。
- `android-call-sampling-visual-2026-10-11.log`：中文大字号截图复核。
- `call-panel-zh-dark-large-initial-final-2026-10-11.png`、`call-panel-zh-dark-large-final-2026-10-11.png`：初始及滚动后的中文弹窗。

复现使用 JDK 17 和已下载依赖；APK 安装与 instrumentation 必须显式指定隔离模拟器，不能用未限定设备的 adb。隔离构建参数：

```powershell
& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug :app:assembleDebugAndroidTest checkDesignTokens --offline --console=plain '-PWE_MEET_TEST_RUNNER=com.we.meet.ui.records.IsolatedRecordsRunner' '-PWE_MEET_TEST_ID_SUFFIX=.fixturespeakeridentity'
```

测试包 `com.we.meet.fixturespeakeridentity.test`，runner `com.we.meet.ui.records.IsolatedRecordsRunner`；类为 `com.we.meet.ui.voiceprint.VoiceprintCallPanelTest` 和 `com.we.meet.ui.voiceprint.VoiceprintSettingsScreenTest`。检查 JUnit 的成功／失败结果，不能仅凭 adb 退出码判定通过。

本阶段证明客户端契约与隔离 UI，不证明实际完整 `RoomScreen` 媒体路径、生产 webhook／sampler 联调或真实硬件兼容。API 31+ 通信设备监听、蓝牙／耳机切换、后台运行及真人多人效果仍需后续验证。未使用物理设备、采集真人声音、调用收费 ASR或部署生产；完整目标的 RTC、部署／监控、历史版本／搜索、外部墓碑恢复和获授权真人验收继续完成。
