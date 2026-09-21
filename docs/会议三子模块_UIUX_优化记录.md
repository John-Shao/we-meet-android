# 会议三子模块（AI 录音 / 会议实录 / 智能纪要）UI / UX 优化记录

日期：2026-09-19。范围：Android `app/src/main/java/com/we/meet/ui/records/**` 全部，
以及与它共用的 `ui/home/{ActionItem,MeetingListItem,MeetingListSectionTitle}`、
`ui/nav/AppNav.kt` 的四个路由、`core-design` 的按钮与状态组件。

依据：`docs/设计规范.md`（§1 token / §2 共享组件 / §3 页面结构 / §4 文案 / §5 无障碍 /
§7 走查清单）、`docs/page-backgrounds.md`（§1 页面层级 / §4 验收清单）、
`docs/app-ux-device-matrix.md`；跨端对齐以 Web 端
`../we-meet/docs/reviews/meetings-ux-migration-2026-09-16.md`（§3.2 / §3.9 / §3.14 /
§3.17 与本次新增的 §3.34）为准。

审计基线单独留档：`docs/reviews/meeting-modules-design-audit-2026-09-19.md`（静态审计报告，
含逐条 `file:line` 与「未覆盖 / 需实机验证」附录）。本文只记**改了什么、为什么、怎么验**。

## 1. 页面层级：三个二级页恢复「白固定头 + 浅灰滚动区」

`page-backgrounds.md` §1 的二级页规则此前在这三页都没落地 —— 顶栏与滚动区同色，
浅色下只有 1px 分割线（`WeMeetTopBar` 其实连分割线都不画），深色下 `surface` 与
`background` 都接近 `#121212`，**整页没有任何层级线索**。

| 页面 | 改动 |
| --- | --- |
| 录音详情 `RecordingDetailScreen.kt` | `Scaffold` 的 `containerColor` 由「依赖 M3 默认值」改为**显式** `background` —— 原先靠默认值是对的，但谁改一次那个默认就静默丢掉分区 |
| 记录工作台 `RecordScreens.kt` | `containerColor` 由 `surface` 改 `background`；标题 + 元信息包成一块白面、`ScrollableTabRow` 自己带 `surface` 底色，两者之间无缝，合起来就是那条白色固定头；其下补 `HorizontalDivider` 画出与正文的分界（与「通讯录」二级名单页同款） |
| 录制页 `CaptureScreen.kt` | `containerColor` 由 `surface` 改 `background`；顶栏固定为「返回 + 标题」。顺带删掉 **死参数** `onOpenNavDrawer`（`AppNav.kt:1575` 从不传它，全仓无调用点），于是这一页不再同时保留「汉堡菜单 + 底部导航栏（一级页形态）」与「返回箭头 + 全屏路由（二级页形态）」两套互相矛盾的写法 |

底部的录制 Dock（`CaptureRecordingDock`）本来就是一块 `Surface(surface)` 白面，放在浅灰
底上正好立起来 —— 与「浅灰滚动区里的表单 / 信息卡片保持白色」同一条。

## 2. 三态收口：空态走共享件、错误不再被吞

`设计规范.md` §2.1 要求三态**成组**处理，§2 要求用共享组件而不是手写。审计查出
records 模块对 `WeMeetEmptyState` / `WeMeetInlineEmptyState` 的使用极不均匀，
`WeMeetInlineEmptyState` 全仓 **0 调用**。

| 位置 | 改动 |
| --- | --- |
| `RecordingHomeScreen.kt`（AI 录音空态） | 裸 `Text` → `WeMeetInlineEmptyState`。文案本身合规（「暂无录音，可开始录音或导入音视频」既说现状也给出路），所以**不另加 action**：页面顶部就是「录音 / 导入」两个入口块，再来一颗同名按钮是重复入口 |
| 10 个面板的空态 | `CaptureAsrPanel` / `CaptureAudioPlayer` / `RecordExports` / `RecordNotifications` / `RecordOriginals` / `RecordScreens`（章节空态）/ `RecordSharing`（×2）/ `RecordSummaryControls` / `RecordSummaryTasks` 的「一行裸 `Text`」全部换成 `WeMeetInlineEmptyState` —— 这是「已可见页面里的一个区段」那一档，正好对应这些调用点 |
| `RecordLibraryScreen.kt`「进行中」段 | **读失败此前被静默吞掉**：`ongoing` 失败 → `ongoingRows` 为空 → 无提示无重试；而主列表成功时整屏错误态也不会触发，用户只会以为「录的东西不见了」。现在这一段有自己的 `WeMeetInlineErrorState` + 重试（新增文案 `records_ongoing_unavailable`，说清「录音仍在继续」），并且**空态判定排除它**（`ongoing?.isFailure != true`），否则那一段的错误会被空态盖掉 |
| `RecordLibraryScreen.kt` 空态 action | 「刷新」是「再问一次服务器」，对「没有符合条件的记录」没有帮助。改成：**有筛选条件时给「清除筛选」**（真的能改变结果），没有筛选时才退回「刷新」 |

## 3. 按钮层级与破坏性动作

- **破坏性动作改用共享 `DangerButton`**（`RecordTrash.kt` 的「移入回收站」）。
  此前是一颗中性色 `TextButton`，与相邻的「上一页」长得一模一样，误点的代价是记录被
  移出正常库。二次确认本来就有、文案也是动作名（合规）。
- **同一个弹窗里方向相反的两个动作不再同色**：`RecordLifecycleConfirmation` 同时承载
  「移入回收站」与「恢复」，确认键现在只在 `target == "trashed"` 时用危险色。
- **回收站里「恢复」与「永久删除」不再同色同重**：永久删除（`RecordPurge.kt` 入口与
  确认键）改危险色。这一条尤其重要 —— 它的流程（`purge_acknowledge` 勾选 + 后果说明）
  设计得很好，但入口按钮和「上一页」长得一样。
- **一屏一个主操作**（§2.2）：`RecordSummaryControls` 的 `ready.forEach { Button(...) }`
  在分阶段生成开启时可以同时画三颗实心主按钮。现在按 `final > quick > realtime` 排序，
  只把最高一档做成主按钮，其余降为 `OutlinedButton`。

## 4. 顶栏动作收敛到三格

`设计规范.md` §3：「右侧 actions 超过 3 个收进溢出菜单」。会议实录 / 智能纪要页此前
最多挂 **5 颗** 24dp 图标（回收站 / 排序 / AI 搜索 / 搜索 / 筛选），其中「搜索会议 AI」
与「搜索」是两个语义靠内容描述才分得清的图形图标。

现在顶栏只有三格：**搜索会议 AI、搜索、更多**；「排序」「筛选」「回收站」进「更多」菜单
（新增文案 `records_more` = 「更多操作 / More actions」）。排序的两档在菜单里带勾，
一眼能看出当前方向。

## 5. 与 Web 端的术语和版式对齐

| # | 项 | 改前（Android） | 改后 |
| - | -- | --------------- | ---- |
| D1 | 智能纪要 scope 标签 | `minutes_owned` = **归我所有**、`minutes_shared` = **与我共享**（实录页已经是「我的内容 / 共享内容」→ **同一台设备上自相矛盾**） | 与 Web `library.scope` / `minutesLibrary.scope` 逐字一致：**我的内容 / 我参与的 / 共享内容**（英文 `Owned by me` / `I participated` / `Shared with me` 本来就对） |
| D2 | 「搜索会议 AI」 | 词条是 **AI 搜索** | 改成「搜索会议 AI / Search meetings with AI」，与 Web `minutesReader.searchMeetings` 同一句 |
| D3 | 列表副行字段顺序 | `时间 · 来源 · 上传状态 · 所有者` | `时间 · 来源 · 所有者 · 上传状态` —— Web 窄屏（`MeetingLibrary.tsx` 的 `narrowOnly` 段）就是把所有者插在来源与上传状态之间 |
| D7 | 「进行中 / 已有纪要」状态签 | `if/else`，两者只能出现一个 | 两段独立判断，**可以同时出现**（Web 的 `ongoing` / `hasSummary` 本来就是两段） |

已核对**一致、无需改动**的：详情页 tab 集合与顺序（`text → summary → chapters →
speakers → info → translations`，与 `MeetingRecordWorkspace.tsx` 完全相同）、
四个一级页页头无副标题、录音详情头部「来源 · 时间」的顺序。

## 6. 顺带清掉的护栏盲区

`UploadMediaPlayer.kt` 的 `(LocalConfiguration.current.screenHeightDp * 0.3f).dp`：
`0.3f` 是未过 token 的魔数，而 `DesignLintTask` 的 `RAW_DIMEN` 正则要求 `.dp` 前是数字，
`).dp` 这种拼法**正好漏判**（基线为空所以构建不红 —— 正对应 `设计规范.md` §9
「基线为 0 不等于没有欠账，只等于规则覆盖的那部分没有欠账」）。

改法：比例收进 `Dimens.MediaPreviewMaxHeightRatio`（附注释说明为什么是比例而不是固定
dp：横屏 / 折叠屏的屏幕高度差异很大）。**没有**顺手去扩 `RAW_DIMEN` 正则 —— 那会把
全仓的 `).dp` 一次性暴露成基线，属于独立的一批。

## 7. 按妙记参考稿的两处调整（2026-09-19 追加）

拿飞书妙记的实机截图逐屏比对之后，补了两件参考稿里已经成型、这边还没做的事。
两件都不改信息架构。

### 7.1 会议信息：定宽两列 → 标签在上、值在下

`RecordInfo.InfoRow` 此前是「左列定宽 `Dimens.LabelColumnWidth`(88dp) + 右列值」。
参考稿（`08.jpg`）的信息页是竖排：标签一行小字，值在下一行。改过来的理由有两条，
第二条是审计里已经点过的隐患：

1. 版式与参考稿一致（所有者 / 创建时间 / 智能纪要 各占一块）；
2. **88dp 是写死的** —— 1.5× / 2.0× 字号下「媒体时长」这类标签会折行，右列的值跟着
   错位（审计 A7 的静态推断）。竖排没有这个问题，而且这一页的值是**可变长内容**
   （用户昵称、导出的文档名），竖排时能自然换行。

`Dimens.LabelColumnWidth` 保留不动 —— 任务详情页还有三处在用它。

### 7.2 逐字稿搜索：去掉「搜索」按钮 + 命中高亮

与 Web 端 §3.35 同改，两端现在口径一致：

- **提交只剩键盘上的搜索键**（`ImeAction.Search` 本来就在，那颗按钮只是重复入口）；
  输入清空时**立刻撤销**关键词筛选 —— 此前清空输入框并不会撤销已提交的查询，
  用户会以为「清空了但列表没变」。
- **命中处加底色**：`CorrectableOriginalText` 的正文改用 `buildAnnotatedString` +
  `SpanStyle`，底色取 `primaryContainer` / `onPrimaryContainer`（与「正在播放」那一行
  同族，两套主题成对翻转）。服务端只负责把不匹配的**行**过滤掉，命中的**是哪个词**
  此前完全没标出来。
- 匹配规则与 Web 相同：**大小写不敏感**、**按字面量匹配**（`indexOf` 而不是正则 ——
  查询词直接来自用户输入，含 `(` / `*` 时正则要么抛异常要么误匹配）。

对应截图：`06.jpg` 里被高亮成浅蓝底的「中国」。

## 8. 翻页行、播放状态机、`cd_` 前缀（2026-09-19 追加，「遗留」第二批）

### 8.1 翻页行收成 `RecordPager`（7 处 → 1 处）

「上一页 / 下一页 / 刷新」这一行原先在七个地方各写一遍，而七处的行为都不一样：有的带
刷新有的不带、有的在某个条件下把「下一页」藏起来、有的整行左右内边距跟同屏其它行对不齐。
用户在**同一次会话**里翻这三种页，手感却不一致。

新增 [`RecordPager.kt`](../app/src/main/java/com/we/meet/ui/records/RecordPager.kt)，规则写死在里面：

- **单页时整行不出现** —— 只有「刷新」而没有上一页/下一页的那一行是挂在底部的孤立动作。
  这条规则原先只在 `RecordOriginals` 的注释里写着，现在由组件统一保证，调用点也不必再
  自己写 `if (cursors.size > 1 || next != null)`。
- 「上一页」靠左、「下一页」与「刷新」靠右（`Spacer(weight)` 顶开），三个都是同级导航动作，
  字号与点击区一致。
- 左右内边距固定 `Dimens.ScreenPadding`。

改用的七处：`RecordLibraryScreen`、`RecordOriginals`、`RecordTrash`、`RecordSharing`
（带 `enabled = !busy` → 组件新增 `enabled` 参数）、`RecordHumanSummary`、`RecordScreens`
（纪要历史版本）、`SpeakerTimeline`。

### 8.2 两个播放器共用一个状态机（`MediaPlaybackState`）

审计的 C11 指出：`CaptureAudioPlayer` 与 `UploadMediaPlayer` 各有一套**字符串**状态机，
而**同名状态在两处含义不同** —— 前者的 `"loading"` 是「还没读到播放列表」，后者是
「媒体还没准备完」。后果是同一条记录的两条入口行为不一致：录音播放器里**每一次拖动
进度条**（它会进入 `"buffering"`）都会把整条控件换成一个转圈，而导入播放器只会在第一次
准备时转一下。

新增 [`MediaPlaybackState.kt`](../app/src/main/java/com/we/meet/ui/records/MediaPlaybackState.kt)：
六档（`Loading` / `Preparing` / `Ready` / `Playing` / `Gap` / `Error`），并把两条渲染规则
收成属性 —— `showsPause`（含 `Preparing`：准备中那一下点下去是「停下」）与 `showsSpinner`。
两个播放器的状态赋值与判断全部换成枚举常量，**渲染条件改成读这两个属性**。

用户可见的变化只有一处，但那正是缺陷本身：**录音播放器在换段 / 拖动时不再把整条控件
换成转圈**（`Preparing` 仍是 `showsSpinner = true`，但控件保留）。

**没有做整份合并**：两个播放器的控件排布本来就不同（一个有视频 Surface 与 `duration`
可空，一个只有音频），真正的重复在中段；把它们抽成一个组件需要同时改媒体生命周期与
签名续期那两条链，属于独立的一批（审计估 2 人日）。这一轮先把**同状态不同语义**这个
真缺陷消掉。

### 8.3 功能性图标的无障碍名加 `cd_` 前缀

设计规范 §5.1 要求功能图标的 `contentDescription` 走 `strings.xml` 的 `cd_` 前缀（与可见
文案分开，改文案不会顺手改掉读屏），而全 app 只有 5 个 `cd_` key。这一轮把**记录模块里
图标独占、没有可见文字**的那些收进来：顶栏图标（搜索会议 AI / 搜索 / 清空 / 更多）、
网格/列表开关、播放器的播放/暂停/前后跳 15 秒、弹层关闭 —— **11 条新 key × 2 种语言**
（`values` + `values-zh-rCN`）。

既有可见文字又兼作无障碍名的（例如「搜索标题」既是输入框占位又是图标描述）**保留原键**，
另给图标一条 `cd_` —— 两句话本来就可能不一样长。

同步改了 **35 处**仪器测试断言：以无障碍名定位图标的 `onNodeWithContentDescription(label(R.string.cd_…))`
与玩家测试里那个「文本或描述都认」的 `await(R.string.cd_…)` 助手。它们断言的就是读屏
读到的那句话，键跟着走才是正确的绑定。

### 8.4 转写关键词芯片行：需要后端先给数据（未实现）

参考稿 `06.jpg` 里逐字稿上方那排「现金 / 地主 / 老板 / 中国 / …」是**服务端抽取的关键词**，
点一颗即按该词筛选 / 高亮。查过两边：`RecordDto`（Android）与 `MeetingRecord`（Web）都
**没有任何关键词字段**，也没有对应接口 —— 现在只有用户自建的「个人热词」（用于 ASR 偏置）
与在页面内做的逐字稿搜索。

中文要抽词就得有分词能力（客户端没有词典，硬按字切出来的「词」不是词），所以这一条
**不能在客户端单独做出可用版本**。需要的最小后端契约是二选一：

- `RecordDto.keywords: [String]`（导入 / 转写完成时抽一次，随记录返回）；或
- `GET /meeting-records/{id}/keywords/`（按需返回 top-N 词与出现次数）。

拿到之后客户端这一半是现成的：芯片行用 `FilterChip`（与列表页的通用控件同一档），
点一颗 → 复用 §7.2 已有的逐字稿搜索 + 命中高亮（把芯片的文本当查询词提交即可），
不需要新的渲染逻辑。

## 9. 验证

```bash
./gradlew checkDesignTokens :core-design:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleDebug
# 仪器测试(需要模拟器 / 真机)
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=…
```

结果见下方「验证记录」。

### 验证记录

- `./gradlew :app:compileDebugKotlin :core-design:compileDebugKotlin` —— **BUILD SUCCESSFUL**。
- `./gradlew checkDesignTokens :core-design:testDebugUnitTest :app:testDebugUnitTest` ——
  **BUILD SUCCESSFUL**（`config/design-lint-baseline.txt` 仍为空 = 无新增违规）。
- `./gradlew :app:assembleDebug` —— **BUILD SUCCESSFUL**。
- §7 的两处调整落地后复跑 `:app:assembleDebug checkDesignTokens :app:testDebugUnitTest
  :core-design:testDebugUnitTest` —— **BUILD SUCCESSFUL**（基线仍为空）。
  随动改动同步：`RecordOriginals` 的逐字稿搜索不再有「搜索」按钮，
  提交走 `ImeAction.Search`；`records_search_action` 这条文案仍被
  `RecordLibraryScreen` / `RecordSharing` / `RecordSummaryTasks` 使用，未成死键。
- **仪器测试：已跑通。** 之前卡在本机唯一的 AVD 是 `Pixel_9_Pro`（**API 37**），任何用例都在
  `createComposeRule()` 的规则初始化阶段抛
  `NoSuchMethodException: android.hardware.input.InputManager.getInstance []`
  （工程的 `androidx.test` / Compose 测试版本与 API 37 不兼容）。已在干净树上复现同样报错，
  确认是环境问题。**解决办法**（不改工程依赖）：装 Android commandline-tools，用
  `android sdk install "system-images;android-36;google_apis;x86_64"` 装 API 36 镜像，
  `avdmanager create avd -n Pixel_API36 -k … -d pixel_6` 建一台 AVD，再按 CLAUDE.md 的两趟跑：

  ```bash
  # 第二趟:其余全部(默认 runner)
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.package=com.we.meet.ui.records
  # 第一趟:需要 fixture Application 的那几个
  ./gradlew :app:connectedDebugAndroidTest \
    -PWE_MEET_TEST_RUNNER=com.we.meet.ui.records.IsolatedCaptureRunner \
    -Pandroid.testInstrumentationRunnerArguments.class=com.we.meet.ui.records.CaptureForegroundServiceTest,com.we.meet.ui.records.CaptureScreenIntegrationTest
  ```

  | 趟 | 结果 |
  | --- | --- |
  | 第二趟（`package=com.we.meet.ui.records`） | **325 条：316 passed / 1 skipped / 9 failed** |
  | 第一趟（capture fixture） | **9 条全通过** |

  那 9 条失败**全部**是 `ClassCastException: WeMeetApp cannot be cast to CaptureFixtureApplication`
  —— CLAUDE.md 里写明的「跑错 runner」症状，换到第一趟后 9/9 通过。合计等于
  **325 条全部有着落**（1 条 skip 是既有的 `@Ignore`）。
- **三条随动用例同步**（口径都写进代码注释）：
  `RecordScreensTest` 里 7 处「排序 / 筛选」改成先开「更多」菜单（新增 `openMore()` 助手）；
  `MeetingNavigationTest` 的录制页用例由「汉堡菜单」改为「返回箭头」，并断言
  `meeting_navigation` **不存在**；`originalSearchAndSpeakerSelectionUseServerFilters`
  由「点搜索按钮」改 `performImeAction()`（那颗按钮已按参考稿删掉）。
- **长跑里的偶发失败：整包跑了两遍才下结论。** 第一遍除了上面那 9 条还多出 **3 条**；
  第二遍（同一份代码、同一台 AVD、同一条命令）这 3 条**全部通过**，单独跑也通过。
  所以稳定复现的失败集合就是那 9 条「跑错 runner」：

  | 第一遍多出的用例 | 报错 | 单独跑 |
  | --- | --- | --- |
  | `InterpretationPanelTest.managerStartNeedsConsentAndDoesNotJoinOrRetainByDefault` | `IllegalArgumentException: performMeasureAndLayout called during measure layout` —— Compose 在测量过程中又被要求测量，与用例里 `screenshot(…, dialog = true)` 抓图那一拍的时序有关 | ✓（与下一条同跑 **14/14**） |
  | `PersonalHotwordsTest.oversizedMergeIsRejectedWithoutTruncation` | `ComposeTimeoutException: Condition still not satisfied after 5000 ms` —— `show()` 里等热词编辑器出现的那 5 秒，负载高时不够 | ✓（同上） |
  | `RecordSharingCopyLinkTest.clipboardCarriesTheDeepLinkShapeTheAppAccepts` | 读回的剪贴板是**上一次运行的残留文本**：Android 10+ 只允许持有焦点的应用写剪贴板，整包长跑中那一次写入被丢掉 | ✓ **2/2**（改前与改后各单独跑一次） |

  三条都是「长跑 + 窗口焦点 / 负载」敏感，不是回归：它们依赖的源码本轮一行未改
  （`InterpretationPanel.kt`、`PersonalHotwords.kt`；`CaptureWorkspaceComponents.kt`
  只动了 `records_close` → `cd_records_close` 一个资源 id，取值逐字相同）。
- **模拟器实机走查：做了**（用仪器测试的挂载点，绕开登录）。
  装好 `app-debug.apk` + `app-debug-androidTest.apk` 后直接跑快照类，
  再从设备上把渲染结果拉回来：

  ```bash
  adb install -r -t app/build/outputs/apk/debug/app-debug.apk
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb shell am instrument -w -e class com.we.meet.ui.records.MeetingRecordsSnapshotTest \
    com.we.meet.test/androidx.test.runner.AndroidJUnitRunner     # OK (11 tests)
  adb pull /sdcard/Android/data/com.we.meet/files/ux
  ```

  留档在 [`docs/assets/meeting-ux-2026-09-19/`](assets/meeting-ux-2026-09-19/)：

  | 截图 | 看什么 |
  | --- | --- |
  | [`20-records-list.png`](assets/meeting-ux-2026-09-19/20-records-list.png) | 顶栏**只剩三格**（AI 搜索 / 搜索 / 更多，排序与筛选已进菜单）；副行顺序 `时间 · 来源 · 所有者 · 上传状态`；首行**同时**挂着「In progress」与「Minutes ready」两个状态签；浅灰固定头 + 白色滚动区 |
  | [`60-record-info.png`](assets/meeting-ux-2026-09-19/60-record-info.png) | 会议信息改成**标签在上、值在下**（与参考稿一致）；白色固定头（标题 + Tab）+ 分隔线 + 浅灰正文 |
  | [`11-recording-empty.png`](assets/meeting-ux-2026-09-19/11-recording-empty.png) | AI 录音一级页：浅灰固定头（标题 + 录音入口）+ 白色滚动区；空态走共享 `WeMeetInlineEmptyState` |
  | [`30-minutes-list.png`](assets/meeting-ux-2026-09-19/30-minutes-list.png) | 智能纪要列表（scope 术语已是「我的内容 / 共享内容」） |
  | [`50-record-originals.png`](assets/meeting-ux-2026-09-19/50-record-originals.png) | 逐字稿页（搜索入口在，无独立搜索按钮） |
  | [`61-record-speakers.png`](assets/meeting-ux-2026-09-19/61-record-speakers.png) | 发言人分布 |

  **仍未覆盖的**（`page-backgrounds.md` §4 里那几条要靠人眼/系统设置的）：浅色与深色下
  **状态栏的实际过渡**、从二级页返回一级页时**底部模块导航栏的恢复**、**1.5× / 2.0× 字号**、
  **横屏 / 折叠屏**、**TalkBack 实际朗读顺序**。快照类只渲染页面本身，不覆盖这些系统行为；
  真机/登录后仍需人工过一遍（`page-backgrounds.md:51` 自己也这么写）。
- **提交前在最终内容上再复跑一遍**：整包仪器测试第二遍 **325 条：316 passed / 1 skipped /
  9 failed**（那 9 条见上，换 `IsolatedCaptureRunner` 后 **9/9 通过**），
  `:app:assembleDebug checkDesignTokens :app:testDebugUnitTest :core-design:testDebugUnitTest`
  **BUILD SUCCESSFUL**、设计护栏基线仍为空。

## 10. 未做 / 有意留着

- **列表标题的机器名**（参考稿里那条
  `share_68a41415e7dfe0a4135fe8e9334db551781397189684` 就是把这件事放大了）：根治要后端在
  导入时给友好默认名，并提供重命名入口。App 侧已经有 `RecordRenameAction`（详情页顶栏），
  Web 侧只加了悬停提示 —— **需要产品定默认名规则**，没自作主张改后端。
- **转写关键词芯片行**（参考稿 `06.jpg` 里「现金 / 地主 / 老板 / 中国 / …」那一排）：
  点一颗就按该词筛/高亮。这需要抽词（服务端或客户端词频），是一件事而不是一处样式，
  没在这一轮做（拦在哪一步、需要什么后端契约，见 §8.4）—— 现在能替代它的是逐字稿
  搜索 + 命中高亮（§7.2）。
- **参考稿把「发言人 / 会议信息 / 会议片段」放在视频下方、把「智能纪要 / 文字记录」
  放在右栏**：那是两栏信息架构，与我们现在「一行 Tab 切内容」不同。改它等于重排
  整个工作区，收益不确定（我们的视频在窄屏下本来就要单独滚），没动。
- **两个播放器仍未合成一个组件**：这一轮只统一了状态机（§8.2，消掉「同状态不同语义」
  这个真缺陷）。控件排布本来就不同（一个有视频 Surface 与可空 `duration`，一个只有音频），
  真正的合并要同时改媒体生命周期与签名续期两条链，需要另跑一轮续期回归。审计估 2 人日。
- **`cd_` 前缀只做了 records 模块**（§8.3，11 条 key）：records 里图标独占的那些已收口，
  其余模块（`feature-im` / contacts / tasks / docs / calendar）的功能图标还在用普通 key。
  一次性铺开牵动 60+ 资源键，且**没有任何护栏检查这一条** —— 建议先给 `DesignLintTask`
  加一条「功能图标必须引用 `cd_` 前缀 key」的检查，再按模块改，否则改完还会退回去。
- **时间格式跨模块不一致**：会议模块内恒定 `yyyy/M/d HH:mm`（模块内一致，注释已声明），
  而会议首页走 `fullDateTimeLocalized()`。统一需要先定「本地化 vs 恒定格式」，属产品取舍。
- **`brand.N` 数字档**：Web 侧 `panda.config` 明确用 `brand.N` 替代裸 `primary.N`，与
  Web `color-system.md` §3.1「不得引用数字档」的口径冲突。属规范层待收敛，不是 Android
  的问题。
