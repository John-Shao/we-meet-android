# 第 65 批：原生会议笔记与智能纪要阅读

## 已实现

- 会议首页新增「会议笔记」「智能纪要」入口，使用 `WE_MEET_RECORDS_NATIVE` 构建开关，默认 `false`。配置沿用 local.properties 优先、Gradle property 次之的规则。
- 二级独立页面不占用六模块导航栏；统一顶栏返回。列表按最近、归我所有、我参与的、与我共享及来源筛选，标题搜索，服务端游标分页；空页可刷新回到第一页。
- 按规范记录 ID 展示实时、速记、完整纪要及历史标识，保留原始负责人／时间文字。未识别阶段不会误标成完整纪要；识别中与识别不完整分别显示。
- 纪要只读权限与原文权限分离；引用按原快照及完整坐标精确读取。失权、读错误、离开前台时清除私有正文，前台每 15 秒重新检查；不写磁盘缓存。
- 页面使用共享主题、顶栏及加载／空／错误态，固定头部为 surface、滚动区域为 background，兼容深色。

## 验证

- `:app:assembleDebug :app:assembleDebugAndroidTest`、12 项 MeetingRecordRepository JVM 测试、`checkDesignTokens` 通过。
- Pixel_8 API 36 只读临时模拟器：5 项 Compose 仪器测试通过，覆盖分页及筛选、后台清屏、权限撤销、引用权限、未知阶段与识别不完整状态。浅色列表、仅纪要详情及深色详情截图已查看。
- 使用 `-PWE_MEET_TEST_RUNNER=com.we.meet.ui.records.IsolatedRecordsRunner` 构建测试 APK，普通构建仍用默认 runner。测试 Application 不启动账户、推送或业务仓库；接口使用内存固定数据，模拟器网络关闭。
- 未进行登录后真实服务器导航联调、真机 TalkBack／大字体完整矩阵或实际会议数据测试；部署后由用户验证。开关保持关闭。

## 后续

原文浏览及搜索、精确版本通知入口、原生采集及前台服务继续分批实现。当前阅读页面不提供录音、播放、AI 生成、任务或分享写操作；不能据此标记 Android 会议 AI 完成交付。
