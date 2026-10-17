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

## 2026-10-17 发布与验收结果

主 APK 源码提交为 `69d3a9752f686c87f0e476541546ced8bd71f5bc`，SHA-256 为 `8c3dc7b16d56ce71154b0f6f75b72e26aa6332c37e27dc4b9d1507141e758256`。已在 OPPO PDYM20 / Android 12 覆盖安装；用户于 10 月 16 日确认亲自在真实 WebView 页面输入验证码并完成登录。后续仅更新测试代码，主 APK 未改变。

- Release 设备回归 50 项通过，53.601 秒。初跑出现回放标志与主线程状态切换之间的测试竞态，等待完整状态转换后重跑全部 50 项通过；未修改生产播放逻辑。测试源码 `95ace9c1`。
- 候选 APK 到隔离业务后端及真实供应商通过，19.254 秒；1 次翻译、4 个音频事件、1 个 `audio_end`、1 个 `finished`，回放与业务关闭通过。
- OPPO 到真实生产服务的关闭开关验证通过，5.501 秒：协议版本 2、配置关闭、新票据 403、旧直连接口 426。
- 生产活动会话关闭开关验证通过，98.803 秒；从触发关闭到客户端释放约 78.246 秒，包括发布和滚动更新。输入释放、会话停止、配置关闭均通过；回滚会话终态为 expired，不能写为正常 closed。
- 恢复开关后，OPPO 合成中英双向输入通过，95.159 秒：2 次翻译、2 个 `audio_end`、1 个 `finished`，回放通过。此项不是麦克风声学验证。
- OPPO 真实麦克风与扬声器重试通过，362.125 秒，含等待用户开始及测试退出时间：中英各 1 次翻译、13 个音频事件、2 个 `audio_end`、1 个 `finished`；10 秒静音尾部无额外翻译、主动结束和回放通过，路由尾音参数 300ms。413 帧发送均收到 ACK，播放异常 0，采样到的排队峰值 0，服务端错误枚举为空。测试源码 `1a09f2829bea6a1fb07cfb0457e54f6b2b58228f`，结果 `OK (1 test)`。

本次采用用户操作和说话、程序验证状态与事件的方式，不再要求用户回答听感问题。因此本次没有新增主观音质确认；10 月 16 日四个扬声器场景的用户确认仍仅作为历史声学证据。噪声为敲击桌面。有线和蓝牙耳机本轮均未测试，按用户决定暂缓；不验并发容量；账单金额后续核对。

### 首次失败与观察项

生产首次真实麦克风测试 106.376 秒失败，会话在第二条完成前变为 inactive。客户端仅记录 `stage=microphone class=IllegalStateException`；该 stage 是流式阶段标签，不能证明由麦克风引起。对应网关会话 53.115 秒、输入 1,312,000 字节、输出 150,420 字节、1 次翻译、2 个 provider、通用错误 `translation_failed`。随后增加不含文本和音频的计数诊断，合成双向和真实麦克风重试均通过，未修改生产代码；首次中断根因仍未定位，不宣称已修复或稳定性已充分证明。

诊断中的 `wire_failures=2` 包含主动取消连接触发的关闭回调，不等同于 2 次业务失败。`last_ack_age_ms` 在结束和回放之后采集，不能直接当作流式 ACK 超时。继续关注再次出现的提前结束、ACK 超时、收尾不完整及会话释放。

### 生产状态与用量

生产后端与翻译网关镜像标签 `fff8391da`，完整提交 `fff8391daeaf564229f12c91b55efcd2e103b613`；[对应完整 CI 成功记录](https://github.com/John-Shao/we-meet/actions/runs/38997453828)。保留了当前生产新增的远程控制修复及未选中组件镜像。

Helm 469 首次操作失败，原因是提取 `--all` values 丢失 null 覆盖，重新引入被准入策略禁止的 Ingress annotation。改用原始 user values 保留 null 覆盖，并比对全部资源及 metadata annotations 后，470 关闭部署、471 开启、472 回滚关闭、473 恢复开启均成功。最终 473 为 deployed，开关 true，迁移 `0227_bilingual_session` 已应用，后端与网关均就绪。所有 4 个验收业务会话已终止：3 closed、1 expired，无 active/issued 残留。

生产四轮会话累计网关输入 PCM 6,108,800 字节，输出计数字节 780,322，翻译 5 次，provider 建连计数合计 7，会话时长合计 225.979 秒。输入 PCM 按 16kHz 单声道 16bit 折算为 190.9 秒；这些不是完整网络流量或供应商账单，未计 TLS/TCP、重传和供应商侧编码开销。

归档目录：`we-meet-android/release/0.3.0-work.4-bilingual-websocket-20261016/`。交付主包为 `we-meet-bilingual-websocket-release-internal.apk`，`release.json` 和 `SHA256SUMS` 记录主包、各阶段测试包及证据。测试 APK 不作为用户安装包。

此前 Debug 与声学结果见[修复验收记录](bilingual-websocket-design.md)，生产发布细节见后端仓库 `docs/reviews/bilingual-websocket-release-2026-10-16.md`。

## 2026-10-17 用户补充反馈后的状态（取代上文最终启用状态）

用户随后对先前待答问题补充：“页面或声音有异常”“期间有来电、切网或锁屏”“测试又提前退出或声音异常”。这些是组合选项，不能据此判断三种中断均发生，也不能确定具体声音症状。声学体验验收改为**未通过，原因待定位**；11:44 的程序通过结果仍保留，不能替代用户体验验收。首次中断不再仅列为上线后的观察项。

生产当前为 **Helm 475 deployed，`ASSISTANT_TRANSLATION_ENABLED=false`**，暂停启用。保留 V2 版本和 `0227` 数据表，不恢复 AOQ/WebRTC 直连接口。后端实际配置已复核为 false，会话仍为 3 closed、1 expired，无 active/issued。主 APK 未改动、未重新发布，不作为已完成体验验收的包交付。

补充测试源码 `2e992b72` 只修改 androidTest：增加真实音频焦点丢失、收音中断回调、断线后的释放与不自动重连验证，并为后续物理测试增加收音/输出/前台服务中断、read 异常类和播放收尾异常计数，不记录异常消息、文本或音频。

- OPPO 音频焦点竞争的单项实测通过，46.866 秒：失焦停止，恢复焦点不自动重连，手动开始能新建会话。未模拟真实来电，不能宣称来电场景已验收。
- OPPO 控制器 32 项初跑为 31 通过、1 失败；失败来自测试无条件调用 ADB 授权被厂商限制，发生在收音之前。改为复用已有授权后，强制 ActivityScenario 切换 CREATED 卡在生命周期等待；该次由测试人员主动 force-stop，日志中的 Process crashed 不是已证实的产品崩溃。
- 改为真实 Home 键和熄屏操作后，该后台/熄屏单项通过，49.724 秒。使用真实麦克风、AudioTrack 和前台服务，网关为本机测试替身，不消耗供应商调用。这只是短时后台与熄屏验证，不能说明长期待机或生产声音质量通过。
- 断线与收音中断回调验证均在 31 项成功结果中：关闭输入/输出/连接；完整结果保留，不完整音频不开放回放；不会自动申请新票据。断线回调注入不等于真实移动网络切换验证。

关闭开关时发现另一个生产运维问题：镜像仓库鉴权返回 401，未启动的定时任务占用 CPU 预留；474 升级的迁移 hook 因调度及拉取失败超时。475 使用相同已部署代码和已核对摘要的缓存镜像，在确认迁移已应用、完整资源比较及原提交 CI 成功后，仅修改功能开关与 `backend.image.pullPolicy=IfNotPresent`，以 `--no-hooks` 完成配置更新。只重试了从未执行的相关 CronJob 子任务，并清理本次失败且从未执行的迁移 hook；业务参数与任务环境保留。未修改 CPU 资源配置，最终后端仍为 200m 请求。其他组件和 Ingress annotations 保持原样。

后续优先级：P1 定位用户报告的页面/声音异常并重新完成生产声学验收；P1 恢复有效镜像仓库凭据、验证冷缓存拉取及正常发布/回滚；P2 检查节点预留是否能支撑 hook、定时任务和滚动更新同时运行。缓存拉取策略不是镜像鉴权问题的修复。问题未闭环前保持功能关闭，不自动恢复上线。
