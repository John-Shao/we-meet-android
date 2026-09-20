# 会议三子模块（AI 录音 / 会议实录 / 智能纪要）设计规范符合性审计

范围：Android `app/src/main/java/com/we/meet/ui/records/**` 全部 + `ui/home/{ActionCard,MeetingListItem,MeetingListSectionTitle}` + `ui/nav/AppNav.kt`（相关路由）+ `ui/main/MainTabScreen.kt` + `values*/strings.xml`、`meeting_records.xml`、`record_*.xml`。

依据：`docs/设计规范.md`（§1 token / §2 共享组件 / §3 页面结构 / §4 文案 / §5 无障碍 / §7 走查清单）、`docs/page-backgrounds.md`（§1 层级 / §3 实现映射 / §4 验收清单）、`docs/app-ux-device-matrix.md`、Web 端 `we-meet/docs/component-system.md` 与 `we-meet/docs/reviews/meetings-ux-migration-2026-09-16.md` §3.2 / §3.5 / §3.9 / §3.14 / §3.17。

方法：静态通读；未跑 gradle（按题目要求）。所有结论均给出 `file:line`。

分类约定：**[确认违规]** = 与规范某条明确冲突 · **[判断题]** = 规范未明确、依视觉/一致性推理 · **[有意为之]** = 代码注释已声明理由或 Web 收口记录已决策。

---

## 0. 执行摘要

| 项 | 结论 |
|---|---|
| `checkDesignTokens` 十条规则 | 在该范围内基本无欠账（无裸色值、无 `N.sp`、无 `TopAppBar(`、无中文业务字面量）。**但 `raw-dimen` 漏了 1 处** —— 见 V1 |
| 页面层级配色 | 一级页（AI 录音 / 会议实录 / 智能纪要）**正确**；三个二级页（录音详情 / 记录工作台 / 录制页）滚动区未铺浅灰，**整页白**（V2/V3/V5） |
| 状态栏衔接 / 底部导航栏 | ✅ 通过：一级页顶栏用 `background` 接状态栏；二级页注册在 NavHost 全屏路由上，不经 `MainTabScreen`，底部 tab 自然不显示、返回自然恢复 |
| 三态成组 | 4 处空态裸写 `Text`、1 处错误被静默吞（V9–V12） |
| 共享组件复用 | **整个 records 模块零使用** `PrimaryButton` / `SecondaryButton` / `DangerButton` / `WeMeetChipRow`（V13）；破坏性动作全用 `TextButton` 而非 `DangerButton`（V14） |
| 按钮层级 | 1 处真·多主按钮风险（V15）、1 处同级动作过多（V16） |
| 与 Web 对齐 | 术语 2 组不一致（D1/D2）、头部动作可发现性差 1 处（D5）、行 anatom 2 处不一致（D3/D4） |

---

## A. 规范符合性核查

### A1 硬编码色值 / 字号 / 间距 / alpha / tint 槽位

| # | 位置 | 问题 | 规范条款 | 判定 |
|---|---|---|---|---|
| **V1** | `app/.../records/UploadMediaPlayer.kt:203` `val videoMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.3f).dp` | 未过 token 的 `.dp`。`DesignLintTask` 的 `RAW_DIMEN = \b[0-9]+(\.[0-9]+)?\.dp\b` 要求 `.dp` 前是数字，而这里是 `) .dp`，**正则漏判**；`config/design-lint-baseline.txt` 为空，所以构建不会红 | §1.3「禁止裸 `N.dp`」、§7 第 1 条、§9「基线为 0 不等于没有欠账，只等于规则覆盖的那部分没有欠账」 | **[确认违规]**（含护栏漏判） |
| **V1b** | 同上文件 `:14` `import androidx.compose.ui.unit.dp` | 唯一用到 `.dp` 的地方就是 V1；修掉 V1 后 import 应删 | 代码卫生 | **[确认违规]**（次要） |
| — | `records/RecordSpeakerAttribution.kt:27` | `import androidx.compose.ui.unit.dp` 全文件未使用（`dp` 出现次数 = 1，只有 import 本身） | — | **[判断题]** 死 import |
| — | `records/RecordLibraryScreen.kt:284`、`ui/home/MeetingListItem.kt:56` | `MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)` 作为行首图标块底色 | §1.1 未直接禁止 `alpha`；`text-color-slot` 只查 `color=`/`tint=` 右侧槽位，此处是 `Surface(color=…)` 的**底色**，规则有意放过（`NON_FG_CONTEXT` 含 `Surface\s*\(`） | **[判断题]** 0.08 与 12% M3 state-layer 基准不一致，两处相同只是巧合；建议收成一个 `WeMeetTheme.extras` token |
| — | 全模块 | `tint` / `contentColor` **未发现**指向 surface/outline/container 槽位 | §1.1、§9 `text-color-slot` | ✅ 通过 |
| — | 全模块 | 无 `Color(0xFF…)`、无 `Color.White/Black/Gray/…` | §1.1、§7 第 1 条 | ✅ 通过 |
| — | 全模块 | 无 `fontSize =` / `lineHeight =`；无裸 `N.sp` | §1.2、§7 第 1 条 | ✅ 通过 |
| — | 全模块 | 无手写 `TopAppBar(`（全部走 `WeMeetTopBar`） | §2、§7 第 3 条 | ✅ 通过 |

**护栏盲区（值得补规则，非本模块问题）**：`RAW_DIMEN` 不认 `) .dp` / `* 0.3f).dp` 形态；`text-color-slot` 不管 `alpha` 数值；`text-color-slot` 的 `inNonFgContext` 靠缩进判定，缩进不规范的代码会误判（`DesignLintTask.kt:283-294` 注释自己承认）。

---

### A2 页面层级与背景（`page-backgrounds.md` §1 / §4）

规范原文：**一级页 = 浅灰固定头（`background`）+ 白色滚动区（`surface`）**；**二级及更深 = 白色固定头 + 浅灰滚动区**；状态栏底色必须与固定头一致；底部模块导航栏只在一级页显示。

#### ✅ 做对的

| 页面 | 证据 |
|---|---|
| AI 录音（一级） | `RecordingHomeScreen.kt:59` Column 铺 `background`、`:62` 顶栏 `containerColor = background`、`:72` Divider、`:76` 滚动区 `background(surface)` —— 两段式正确 |
| 会议实录 / 智能纪要（一级） | `RecordLibraryScreen.kt:97` 顶栏 `background`、`:136` Scaffold `background`、`:184` 内容 Box 铺 `surface`，且**注释明说**「加载态/空态/错误态也得把白底铺满」；`:141` TabRow 也显式 `background` |
| 一级页的底部导航栏 | 由 `MainTabScreen.kt:663-671` 提供（`fullScreenPageVisible=false` 时显示 `CompactTabBar`，`containerColor = surface`），会议三个分区都在 tab 内切换，未隐藏 —— 符合「导航栏只在一级页显示」 |

#### ❌ 二级页配色错（本模块最系统的问题）

| # | 位置 | 现状 | 应为 | 判定 |
|---|---|---|---|---|
| **V2** | `RecordingDetailScreen.kt:29` `Scaffold(topBar = { WeMeetTopBar(…, onBack = onBack) })` → `:30` `Column(...padding(padding).verticalScroll(...))` | 顶栏用 `WeMeetTopBar` 默认底色 = `surface`（白）；内容区**没有铺任何底色**，落到 Scaffold 默认 `containerColor = surface` → **整页白** | 二级页：白固定头 + **浅灰**滚动区，内容区需显式 `background(MaterialTheme.colorScheme.background)` | **[确认违规]** |
| **V3** | `RecordScreens.kt:129-131` `Scaffold(containerColor = MaterialTheme.colorScheme.surface)`，内容 Column 无底色 | 同上，整页白，滚动区不是浅灰 | 同上 | **[确认违规]** |
| **V5** | `CaptureScreen.kt:222-223` `Scaffold(containerColor = MaterialTheme.colorScheme.surface)`，`:240` 内容 Column 无底色 | 同上 | 同上 | **[确认违规]**（另见 V8：这一页的顶栏形态自相矛盾） |

#### ⚠️ V8 `CaptureScreen` 的层级归属自相矛盾

`CaptureScreen.kt:225-227` 通过 `onOpenNavDrawer` 动态切换「汉堡菜单（一级页形态）」与「返回箭头（二级页形态）」：

```kotlin
onBack = if (onOpenNavDrawer == null) onBack else null,
onMenu = onOpenNavDrawer, menuDescription = …,
```

- `AppNav.kt:1575` 注册 `Routes.CAPTURE` 时**只传 `onBack`**，从不传 `onOpenNavDrawer` → 实际永远是「返回箭头」形态；`onOpenNavDrawer` 这个参数在本页**没有任何调用点**（`grep onOpenNavDrawer` 在 `Records` 目录只见 `RecordingHomeScreen.kt:39,55` 与 `CaptureScreen.kt:86,193`；后者无人传值）。
- 但 `CaptureScreen` 的 `containerColor` 写死 `surface`（白），而 Web 收口记录 §3.1 把录制页列为**次级页（钉头：返回 + 标题）**，即白固定头 + **浅灰滚动区** —— 与 V5 是同一条。
- 这一页同时保留了「汉堡菜单 + 底部模块导航栏」（一级页形态）与「返回箭头 + 全屏路由」（二级页形态）两套写法，而 `containerColor` 只服务后者，前者一旦被启用就会露出「浅灰头部 + 白内容」的反向错配。

判定：**[确认违规]**（配色与死参数）。建议二选一并写进代码注释：要么删掉 `onOpenNavDrawer` 明确它是二级页（同时铺浅灰滚动区），要么把它接到 `MainTabScreen` 的会议 tab 并改成一级页配色。

#### ✅ 底部导航栏隐藏/恢复

`RecordingDetailScreen` / `RecordDetailScreen` / `CaptureScreen` 都注册在 `AppNav.kt:1574-1603` 的 NavHost 上，是**全屏路由**，不经过 `MainTabScreen`，所以底部 tab 自然不显示，不需要 `onFullScreenVisibilityChanged`。

对照：`CalendarTabScreen.kt:130,156` 与 `MeetingRoomsCalendarScreen.kt:199,253-257` 都有该回调，但 **`MainTabScreen.kt:429-457` 的会议 tab（含会议室页）一个都没接**。

| 判定 | 说明 |
|---|---|
| **[判断题]** | 当前会议三子模块不走 tab 内嵌详情，所以**不构成违规**；但 `MeetingRoomsCalendarScreen` 的「会议室详情」是 tab 内页，`MainTabScreen` 未接它的 `onFullScreenVisibilityChanged`（`MainTabScreen.kt:303-307` 的 `fullScreenPageVisible` 只算 Tasks / Calendar），会议室详情打开时状态栏底色与底部导航栏不会切换 —— 属于同一根因的相邻缺陷 |

#### ✅ 状态栏衔接 —— 复查后判定为通过（原疑点已排除）

`MainActivity.kt:83` 是 `enableEdgeToEdge()`。逐页核对了「顶栏是否落在 `Scaffold` 的 `topBar` 槽」与「content 的 `padding` 是否只加在内容上」：

| 页面 | 写法 | 判定 |
|---|---|---|
| `RecordLibraryScreen.kt:90-138` | 顶栏在 `topBar` 槽；content 是 `Column(Modifier.fillMaxSize().padding(padding))` | ✅ padding 已含状态栏高度，只加一次 |
| `RecordScreens.kt:129-131` | 同上 | ✅ |
| `RecordingDetailScreen.kt:29-30` | 同上 | ✅ |
| `CaptureScreen.kt:222-240` | 同上，`Scaffold(bottomBar = …)`，content 里另加 `consumeWindowInsets(insets)` | ✅ |
| `RecordingHomeScreen.kt:59-62` | 无 Scaffold，顶栏直接作 Column 第一行 —— 但 `TopAppBar` 自带 `windowInsets` 处理状态栏，且整页 Column 已铺 `background` | ✅ |

→ **本模块没有「把 `Scaffold(padding)` 套在含顶栏的 Column 上」的写法**，状态栏不会重复留白、也不会断层。

真正的问题落在 **V2 / V3 / V5**：二级页的滚动区没有铺 `background`，导致「白顶栏 + 白内容」看不出分界。浅色下顶栏（`WeMeetTopBar` 默认 `surface` = 白）与滚动区同色，两级页面看起来完全一样；深色下更没有分区。

---

### A3 三态成组（§2.1）

规范要求：三态成组、**已有内容时不能用整屏态盖掉内容**、`WeMeetErrorState` 必须带重试。

#### ✅ 做对的

| 位置 | 证据 |
|---|---|
| `RecordLibraryScreen.kt:185-226` | `result == null → WeMeetInlineLoading` / `isFailure → WeMeetErrorState(onRetry)` / `空 → WeMeetEmptyState(title, description, action)`，且 `:188` 注释「只剩『进行中』那几条时不算空」 |
| `RecordOriginals.kt:148-155` | 同上三态完整，错误态带 `onRetry = onRefresh` |
| `RecordScreens.kt:205-222` | 纪要版本三态完整，空态带 `description` + `action = 刷新` |
| `RecordCaptureTools.kt:56-64`、`CaptureAsrPanel.kt:128-131`、`RecordHumanSummary.kt:89-91`、`RecordNotifications.kt:28-30`、`RecordExports.kt:62-64`、`RecordSharing.kt:95-97`、`RecordQuestions.kt:94-96`、`RecordTrash.kt:71-73`、`RecordDocuments.kt:67-69` | 弹层/卡片级三态均成组，错误态均带 `onRetry` |

#### ❌ 问题

| # | 位置 | 问题 | 判定 |
|---|---|---|---|
| **V9** | `RecordingHomeScreen.kt:81-82` 空态写成 `Text(stringResource(R.string.recording_history_empty), Modifier.padding(ScreenPadding), color = onSurfaceVariant)` | ① 裸 `Text` 直替 `WeMeetEmptyState`（§2「新页面必须用这些，不要重新手写」）；② 缺 `description`「怎么才能有」；③ **该页有「开始录音 / 导入」两个入口**，空态却没有 `action`（Web 的 `recordingOverview.empty` = 「暂无录音，可开始录音或导入音视频」，经 `PageState` 渲染，见 `RecordingOverview.tsx:99-104`） | **[确认违规]** |
| **V10** | `RecordTrash.kt:76` `if (result.results.isEmpty()) Text(stringResource(R.string.record_trash_empty))` | 同上，裸 `Text` 替 `WeMeetInlineEmptyState`（该组件已存在于 `StateViews.kt:73`，全仓无人用） | **[确认违规]** |
| **V11** | `RecordOriginals.kt:263` `if (page.getOrThrow().results.isEmpty()) Text(stringResource(R.string.records_no_speakers))`；`RecordSharing.kt:112,205`、`RecordExports.kt:80`、`RecordNotifications.kt:46`、`RecordInfo.kt:73,138`、`RecordHumanSummary.kt:98`、`CaptureAudioPlayer.kt:164`、`CaptureAsrPanel.kt:175`、`RecordCaptureTools` 内空态 | 清一色裸 `Text`。规范 §2 表把 `WeMeetEmptyState` 列为「手写空态 Column」的替代；此处连组件都不算 | **[确认违规]**（批量） |
| **V12** | `RecordLibraryScreen.kt:84-88` `val ongoing = … visibleRead(…)`；`:88` `ongoingRows = ongoing?.getOrNull()?.results.orEmpty()` | **「进行中」那一段的错误被静默吞掉**：`ongoing` 失败 → `ongoingRows` 为空 → 既无错误提示、也无重试入口，用户只会觉得「没有进行中的记录」。主 `result` 的成功态会把整个 `when` 走到 `else` 分支，此时连整屏错误都不会出现 | **[确认违规]**（§2.1「刷新失败要让人知道」的反面） |
| **V12b** | `RecordLibraryScreen.kt:191` `WeMeetEmptyState(… action = { TextButton(刷新) })` | 空态动作给了「刷新」。规范 §2 对空态 `action` 的定位是「引导用户」（§2.1 的示例也是空态给引导）；这里空列表点刷新等于「再问一次服务器有没有」，对「没有符合条件的记录」没有帮助，而这一页**恰恰有**两个该引导的动作（AI 录音页的录音 / 导入） | **[判断题]** |

---

### A4 共享组件复用与按钮层级

#### V13 整个 records 模块零使用共享按钮 / chip 组件

全仓 grep 结果（排除 build 目录）：

```
PrimaryButton   → 仅 calendar/{CalendarOwnerShareScreen,CalendarManagementScreens,CalendarShareScreen}
DangerButton    → 仅 calendar/CalendarManagementScreens.kt:770
SecondaryButton → 0 处
WeMeetChipRow   → 仅 自身定义 + 无调用点之外
WeMeetInlineEmptyState → 0 处调用
```

`records/` 下 46 个文件中：**0 处** `PrimaryButton` / `SecondaryButton` / `DangerButton` / `WeMeetChipRow` / `WeMeetInlineEmptyState`。

§2 的表格把 `PrimaryButton/SecondaryButton/DangerButton` 的保证写得很死：**高度统一 52dp、主按钮内置 loading 态**。现状是本模块 100+ 处使用裸 M3 `Button` / `OutlinedButton` / `TextButton`，导致：

| 具体后果 | 证据 |
|---|---|
| 按钮高度**逐处不同**：`Button(... heightIn(min = ButtonHeight))`（`CaptureWorkspaceComponents.kt:114,120`）、`heightIn(min = MinTouchTarget)`（`:145`）、完全不给高度（`RecordSummaryControls.kt:127`、`RecordNotifications.kt:42`、`RecordExports.kt:73` …） | §1.3 / §2 |
| 没有统一的 loading 态 —— 各家自己写 `if (busy) WeMeetInlineLoading()`（`CaptureAsrPanel.kt:146`、`RecordSharing.kt:137`、`RecordQuestions.kt:112`、`RecordNotifications.kt:57`、`RecordExports.kt:91`） | §2 |
| `loading` 不阻止重复提交 —— 靠 `enabled = !busy` 逐处手写，`RecordRename.kt:60` 就是 `enabled = !busy && …` | §2（`PrimaryButton` 的 `enabled && !loading` 保证被绕过） |

判定：**[确认违规]**（§2「新页面必须用这些，不要重新手写」+ §7 第 3 条）。

#### V14 破坏性动作全部不用 `DangerButton`，也没有 `error` 内容色

| 位置 | 动作 | 现状 |
|---|---|---|
| `RecordTrash.kt:27` | 移入回收站 | `TextButton` + `Text(record_trash_remove)`，**无 error 色** |
| `RecordTrash.kt:45-56` | 确认移入回收站 | 对话框 `TextButton`，无 error 色 |
| `RecordPurge.kt:52` | 永久删除 | `TextButton`，无 error 色 |
| `RecordScreens.kt:163` → `RecordTrashControl` | 同上 | — |

§7 第 5 条：「破坏性操作用 `DangerButton` 且有二次确认」。二次确认**有**（`RecordTrash.kt:37`、`RecordPurge.kt`）✅，但按钮组件与颜色都不合规。

文案另计：`record_trash_confirm_remove` = 「确认移入回收站」、`record_purge_remove` = 「永久删除」——**已符合** §4「确认按钮写动作名」✅。

判定：**[确认违规]**（组件层）；文案 **[有意为之]** 且合规。

#### V15 `RecordSummaryControls.kt:127` 可能同时渲染多个主按钮

```kotlin
ready.forEach { stage -> Button(onClick = { operate(false, stage) }, enabled = canClick && !active) { … } }
```

`:80` `val ready = if (state?.stagedEnabled == true) state.readyStages else if (state?.generationReady == true) listOf("final") else emptyList()`

`readyStages` 是**服务端下发的列表**。分阶段（staged）生成开启时，`ready` 可以是 `["realtime", "quick", "final"]` → **一屏三颗同级实心 `Button`**。

对 §2.2「一屏只能有一个 `PrimaryButton`」的判定：这里用的是 M3 `Button` 而非 `PrimaryButton`，但视觉上三颗实心主按钮等价，语义上也是「三个都同等重要」。Web 侧同类动作是有层级的（`SummaryTaskActions.tsx` / `RecordSummaryPanel.tsx` 用 `primary` + `secondaryText`）。

判定：**[确认违规]**（精神上违反 §2.2；字面上因未用 `PrimaryButton` 而逃过检查）。

#### V16 `Records` 详情页尾部三个同级 `TextButton`

`RecordScreens.kt:241-247`：`问问 AI` / `管理纪要` / `刷新` 三个 `TextButton` 并排 `SpaceBetween`。

`:244-245` 已有注释解释「刷新只在有内容时出场，避免同一屏两个同名动作」——说明作者想过层级；但三颗同级文字按钮仍是「没有主次」。Web 的纪要阅读器把工具收进 `minutesReader.tools` 面板。

判定：**[判断题]**（可用性 > 规范字面）。

#### ✅ 下拉菜单宽度（§2.3）

`RecordLibraryScreen.kt:107` `DropdownMenu`（挂在 `IconButton` 锚点上，用的是 `DropdownMenu` 而非 `ExposedDropdownMenuBox`）→ §2.3 只约束 `ExposedDropdownMenu`，`matchTextFieldWidth` 不适用。

全模块**无 `ExposedDropdownMenuBox`** → `dropdown-menu-width` 规则天然通过 ✅。

`RecordLibraryScreen.kt:252-255` 的日期筛选用的是两个普通 `OutlinedTextField`（非下拉），与 `Menu` 无关 ✅。

---

### A5 文案与 i18n（§4）

| 项 | 结论 |
|---|---|
| 业务代码中文字面量 | **0 处**（grep 全模块，排除注释后无命中）✅ |
| 中英双语齐全 | `values/strings.xml` 953 条 ↔ `values-zh-rCN/strings.xml` 953 条，数量一致 ✅；`meeting_records.xml` / `record_library.xml` 亦成对 |
| `cd_` 前缀 | ⚠️ **app 侧只有 5 个 `cd_` key**（`cd_more` / `cd_prev` / `cd_next` / `cd_mic_on` / `cd_mic_off`），而 records 模块的图标 `contentDescription` 取的全是普通 key：`meeting_navigation`、`record_trash_title`、`records_sort`、`meeting_ai_search`、`records_clear_search`、`records_search`、`records_filters`、`records_grid_view`、`records_list_view`、`records_close` |

§5.1 要求「字符串走 `strings.xml` 的 `cd_` 前缀」。**判定：[确认违规]**（全局约定层面；`icon-button-description` 规则只查 `null`，不查前缀，所以护栏也不报）。

另：`DesignLintTask` 的 `ICON_BUTTON_NULL_DESCRIPTION` 只匹配「`contentDescription = null` 出现在 `IconButton { … }` 内」。本模块所有功能性 `IconButton` 都给了非 null 描述 ✅；装饰性图标（列表行首、`WeMeetEmptyState` 内）显式 `null` ✅（例：`RecordLibraryScreen.kt:286,312`、`MeetingListItem.kt:65`、`CaptureWorkspaceComponents.kt:60,73,115,121`、`RecordOriginals.kt:174`）。

---

### A6 触控目标 ≥48dp 与无障碍

| 项 | 结论 | 证据 |
|---|---|---|
| `IconButton` 触控目标 | ✅ M3 `IconButton` 默认 48dp；`icon-button-touch-target` 规则禁止 `.size(Dimens.IconXxx)`，本模块无违反 | 全模块 |
| 播放器主按钮 | ✅ `FilledTonalIconButton(modifier = Modifier.size(Dimens.ButtonHeight))` = 52dp | `CaptureAudioPlayer.kt:173`、`UploadMediaPlayer.kt:254` |
| 行级热区 | ✅ `RecordLibraryRow` 用 `heightIn(min = Dimens.MinTouchTarget)` | `RecordLibraryScreen.kt:280` |
| `RecordTrashSheet` 的「上一页/下一页」 | ⚠️ `TextButton` 在 `ModalBottomSheet` 里，M3 默认高 40dp < 48dp 目标；同页 `:91` 的「关闭」同理 | `RecordTrash.kt:85-91` | **[判断题]**（`TextButton` 视觉可小、热区由 M3 保证 48dp 触摸但布局高 40dp） |
| `RecordOriginals.kt:143` 「回到播放位置」 | ⚠️ `TextButton` 直接挂在 Column 下，**左右无 `ScreenPadding`** → 与上方筛选行（`:112` 有 `padding(horizontal = ScreenPadding)`）左缘错位 | **[判断题]** 视觉缺陷 |
| `RecordScreens.kt:270` 「刷新」 | 同上，无左右内边距 | 同上 |
| `ContentDescription` 语义 | ✅ `CaptureAudioPlayer.kt:168` 用 `semantics { contentDescription = positionLabel }` 给 Slider 命名；`:166` 位置读数用可见文本 | 良好实践 |
| 颜色不单独传信息 | ✅ 转写「正在播放」行同时用底色 + `stateDescription`（`RecordOriginals.kt:166-168`） | 良好实践 |
| `SpeakerActivity` 进度 | ⚠️ `LinearProgressIndicator` 无 `contentDescription`/`progressSemantics`，`records_activity_value` 是紧邻的可见文本 → TalkBack 会读两次文本 + 一次无标签进度条 | `RecordInfo.kt:170-171` | **[判断题]** |

---

### A7 深色模式 / 1.5× 字号 / 横屏 / 折叠屏（`app-ux-device-matrix.md`）

静态可判定的部分：

| 配置 | 结论 |
|---|---|
| 深色模式 | 本模块**无硬编码色**（V1 之外），全部走 `MaterialTheme.colorScheme.*` → 深色下自动翻转 ✅。**唯一例外**是 V2/V3/V5 的「整页 `surface`」：深色下 `surface` 与 `background` 都变深，层级差消失，二级页会**完全看不出分区**（浅色下至少还有白/灰差） |
| 1.5× / 2.0× 字号 | ⚠️ 风险点：`RecordInfo.kt:110` `InfoRow` 左列写死 `Modifier.width(Dimens.LabelColumnWidth)` = 88dp。1.5× 字号下「媒体时长」等标签会换行或被裁（`Text` 无 `maxLines`，会换行成两行，右列值不对齐）。**未实机验证**，属静态推断 → **[判断题]** |
| 1.5× 字号 | ⚠️ `RecordLibraryScreen.kt:145-149` 的 `TextButton(weight(1f)) { Text(...); Icon(ExpandMore) }` 里 `Text` **也带 `weight(1f)`**，在外层 `TextButton` 已 `weight(1f)` 的情况下再套内层 weight，窄屏 + 大字号下文字会被压到极窄。同上 `:147` 用 `Modifier.weight(1f)` 而非 `fillMaxWidth` —— 语义可疑 → **[判断题]** |
| 横屏 | `RecordLibraryScreen.kt:194` 用 `GridCells.Adaptive(Dimens.RecordGridMinWidth=180dp)`，网格模式横屏/平板能自适应 ✅；列表模式 `GridCells.Fixed(1)`，横屏下单行极宽但可读 ✅ |
| 横屏 | `UploadMediaPlayer.kt:203` `videoMaxHeight = screenHeightDp * 0.3f` —— 横屏下屏幕高度小，视频被压到 ~120dp，而宽度受 `maxWidth` 约束；两维度都封顶的逻辑在 `:212` `minOf(maxWidth / aspect, videoMaxHeight)` 尚可 ✅ |
| 折叠屏 / 平板 | `RecordGridMinWidth = 180.dp` 只在**网格模式**生效；列表模式横跨整宽（无 `maxWidth` 约束）→ 平板上一行文字会拉很长。Web 侧有 `maxWidth` 处理（`libraryStyles` 的 `pageShell` 铺满策略是刻意的，反向一致）→ **[有意为之]** |
| 键盘遮挡 | `RecordLibraryScreen.kt:243` 筛选面板 `verticalScroll + imePadding` ✅；`CaptureScreen.kt:240` 内容 `verticalScroll` 但**没有 `imePadding`** —— 该页无输入框，✅；`RecordOriginals.kt:114` 搜索框页无 `imePadding` ⚠️ → **[判断题]** |
| 滚动 | 全部超屏内容都有 `verticalScroll` / `LazyColumn` ✅ |

---

### A8 §7 走查清单逐条汇总

| §7 条目 | 结论 |
|---|---|
| 无裸色值 / `N.sp` / 未 token 的 `N.dp` | ❌ V1（1 处，护栏漏判） |
| Shape 取 `MaterialTheme.shapes`、Elevation 取 `Dimens.Elevation*` | ✅ 通过（`CaptureWorkspaceComponents.kt:100` `tonalElevation = ElevationSubtle, shadowElevation = ElevationOverlay` 是唯一显式 elevation，取值合规）；`RecordLibraryScreen.kt:307` `MaterialTheme.shapes.extraLarge` ✅ |
| 顶栏用 `WeMeetTopBar`；三态用共享组件且成组 | ⚠️ 顶栏 ✅；三态 ✅成组，但**空态裸 `Text`** ❌ V9/V10/V11、**一处错误被吞** ❌ V12 |
| 页面配色验收清单 | ❌ V2/V3/V5 |
| 一屏一个主按钮；破坏性用 `DangerButton` + 二次确认 | ❌ V14/V15/V16 |
| 文案进 `strings.xml`，中英齐全 | ✅ |
| 图标 `contentDescription`（装饰 `null`）、触控 ≥48dp | ⚠️ 语义值 ✅，**`cd_` 前缀约定** ❌ A5 |
| 新增颜色对算过对比度 | N/A（未新增颜色） |
| 深色模式过一遍 | ⚠️ 未见问题，但 V2/V3/V5 在深色下更糟 |
| 内容可滚动；输入框处理键盘 | ✅ 滚动；⚠️ `RecordOriginals` 搜索无 `imePadding` |
| 长文本 / 长昵称不撑破布局 | ⚠️ 见 A7（`LabelColumnWidth`、双层 `weight`） |
| 窄锚点下拉给 `matchTextFieldWidth = false` | N/A（无 `ExposedDropdownMenuBox`） |

---

## B. 与 Web 端的对齐差异

Web 文件根：`D:\workspace\we-meet\we-meet\src\frontend\src\features\meetings\`

### D1【确认差异 · 术语】智能纪要的 scope 标签两套没统一

Web 收口记录 §3.2 第 6 条：

> 「我的内容」这件事**两页两套文案**：实录「我的内容 / 共享内容」，纪要「归我所有 / 与我共享」→ 统一到实录那套（zh）：纪要 scope 改为「我的内容 / 我参与的 / 共享内容」。

| 端 | 证据 | 值 |
|---|---|---|
| Web 实录 | `locales/zh/meetings.json` `library.scope.owned/shared` | 我的内容 / 共享内容 |
| Web 纪要 | `locales/zh/meetings.json` `minutesLibrary.scope.owned/participated/shared` | 我的内容 / 我参与的 / 共享内容 |
| **Android 实录** | `app/src/main/res/values-zh-rCN/meeting_records.xml:63,65` | 我的内容 / 共享内容 ✅ |
| **Android 纪要** | `app/src/main/res/values-zh-rCN/strings.xml:1059-1061` `minutes_owned` / `minutes_shared` | **归我所有 / 与我共享** ❌ |

Android 引用点：`RecordLibraryScreen.kt:354-358` `minutesScopeLabel()`。

→ **Android 内部也自相矛盾**：同一次会话里切到「会议实录」看 scope 是「我的内容」，切到「智能纪要」变成「归我所有」。这是 §3.2#6 明确点名要统一的那条，且**同一台设备上就能复现**。

判定：**[确认违规 / 跨端不一致]**。

### D2【确认差异 · 术语】「AI 搜索」vs「搜索会议 AI」

| 端 | 证据 | 值 |
|---|---|---|
| Web（实录 / 纪要页头动作） | `MeetingLibrary.tsx:770` `t('minutesReader.searchMeetings')`；`locales/zh` | **搜索会议 AI** |
| Android | `values-zh-rCN/strings.xml:1075` `meeting_ai_search`；用于 `RecordLibraryScreen.kt:119` | **AI 搜索** |

同时 §3.5 明确这一动作「补 `RiSparklingLine` 图标」且是**带文字标签的 action 按钮**；Android 是裸 `IconButton`（只有内容描述）。

判定：**[确认差异]**（文案 + 形态）。

### D3【确认差异 · 行版式】列表行副行的所有者位置

| 端 | 证据 | 版式 |
|---|---|---|
| Web 卡片 | `MeetingLibrary.tsx:510-527` | `时间 · 来源 · 上传状态`，**所有者不出现** |
| Web 表格（≥md） | `MeetingLibrary.tsx:570-594, 598-601` | 副行同上；所有者单独一列 |
| Web 窄屏（<md） | `MeetingLibrary.tsx:577-581` `narrowOnly` | 所有者**追加**到副行末尾 |
| **Android** | `RecordLibraryScreen.kt:337-344` `recordMetaLine()` | `[会议时间：]时间 · 来源 · 上传状态 · 所有者`（**永远拼上**） |

Android 只在**手机**上渲染，所以「窄屏补所有者」这个决策本身是对的（`:342` 注释也引用了 Web 的表格）；差异是**副行字段顺序**：Android 把上传状态放在所有者之前、Web 窄屏把所有者放在上传状态之前。

判定：**[判断题]**（顺序差异，影响「扫描副行找状态」的一致性）。真正的问题是**字段多且无截断**：`recordMetaLine` 是一条无 `maxLines` 的 `Text`（`:329` 有 `bodySmall`），5 段用 `·` 连接在 360dp 宽的手机上必然折行，而行高是固定的 `SpaceM` 上下边距。

### D4【确认差异 · 行版式】AI 录音列表行的图标块尺寸

已知收口记录（§3.1 最后一条走查：「录音页的行首图标块仍是 **48×48**、标题 16px」）。

| 端 | 证据 | 值 |
|---|---|---|
| Web | `RecordingOverview.tsx:114-119` `rowIconTile` + `<RiMicLine size={24}>`；`libraryStyles.ts` 中 `rowIconTile` 为 48px 品牌浅蓝底 | **48 × 48**，图标 24 |
| Android（AI 录音页） | `RecordingHomeScreen.kt:84` → `MeetingListItem`；`ui/home/MeetingListItem.kt:60` `Modifier.size(Dimens.ListLeadingIcon)` | **44 × 44**（`Dimens.kt:123` = 44.dp），图标 24 |
| Android（实录/纪要） | `RecordLibraryScreen.kt:284-287` 同样 `Surface` + `Dimens.ListLeadingIcon` | **44 × 44** |

判定：**[确认差异]**。同时 **Android 内部两套**：`RecordLibraryScreen` 手写了与 `MeetingListItem` 同构的行（`:275-299`，注释也说「与 MeetingListItem 同一块图标底」），但网格卡（`:303-316`）完全没有图标块 —— Web 的卡片视图保留 32px 图标块（`MeetingLibrary.tsx:499-501` `rowIconTileCompact`）。

### D5【确认差异 · 页头动作】AI 录音页的「录音 / 导入」形态与可发现性

Web 收口记录 §3.14 明确：

> 1.「录音」在前、「导入」在后 —— **与 App 端 `RecordingHomeScreen` 同序（录音 ActionCard 在左、导入在右），主操作落在靠左那一颗**
> 2.「录音」改用 `variant="primary"`

| 端 | 证据 | 形态 |
|---|---|---|
| Web | `RecordingOverview.tsx:181-199` | 页头右侧两个 **action 尺寸按钮**：`variant="primary"` + `RiMicLine 18`「录音」、`RecordingUpload`（`secondaryText` + `RiDownload2Line 18`）「导入」 |
| Android | `RecordingHomeScreen.kt:64-71` | 页头下方一行**两个等宽 ActionCard 磁贴**（`weight(1f)` 各半），图标 26dp、**无按钮文字标签的层级差** |

顺序 ✅ 一致（录音在左、导入在右），但形态差异明显：Android 的两个入口是**磁贴**（`ActionCard`，`HomeScreen.kt:205-234`），Web 是**页头按钮**。§3.5 曾专门把 AI 录音页的「两枚大入口块」删掉换成 `headerActions`：

> 「`RecordingOverview`：页头改成『标题 + 说明 + 两个 action 按钮』，**删掉大入口块**」
> 「`RecordingUpload` 的 `tile` 形态随之没有调用点，一并删除」

**而 Android 至今仍在用这个 tile 形态**：`RecordingHomeScreen.kt:49` 传 `tile = true`；`RecordingUpload.kt:83-95` 的 `ImportEntry(tile = true)` 分支是活的。

判定：**[确认差异 / 有意保留]**（Android 无「页头放按钮」的等价物 —— `WeMeetTopBar` 的 `actions` 槽是 `IconButton` 导向，塞文字按钮会破坏标题单行截断。但 Web 已把 tile 形态判为「删掉」，Android 保留等于两端口径相反）。

### D6【确认差异 · 页头副标题】四个一级页的说明行

Web 收口记录 §3.17：四个一级页的 `pageLead`（说明行）**整行删除**，走查断言「页头里 `header p` 数量必须是 0」。

| 端 | 结论 |
|---|---|
| Web | ✅ 已删（`RecordingOverview.tsx:172-176` 只剩 `<h1>`） |
| Android | ✅ **本来就没有**副标题（`RecordingHomeScreen.kt:60` 只传 `title`；`RecordLibraryScreen.kt:92` 只传 `title`） |

判定：**[一致]**（Android 天然对齐）。

### D7【确认差异 · 状态签】「进行中 / 已有纪要」

| 端 | 证据 | 行为 |
|---|---|---|
| Web 卡片 | `MeetingLibrary.tsx:521-528` | 两个状态签可**同时**出现（`ongoing && <span>`、`record.has_summary && <span>`） |
| Web 表格 | `MeetingLibrary.tsx:586-593` | 同上 |
| **Android** | `RecordLibraryScreen.kt:330-333` | `if (!summariesOnly && (record.isOngoing || record.hasSummary)) Text(if (record.isOngoing) ongoing else minutesReady)` —— **`if/else`，只能出现一个** |

判定：**[确认差异]**（一条既在录又有纪要的记录，Web 显示两个签，Android 只显示「进行中」）。

### D8【一致 · 详情页 tab 集合与顺序】

| 端 | 证据 |
|---|---|
| Web | `MeetingRecordWorkspace.tsx:551-563`：`text` → `summary` → `chapters` → `speakers` → `info` → `translations` |
| Android | `RecordScreens.kt:143-150`：`text` → `summary` → `chapters` → `speakers` → `info` → `translations` |

判定：✅ **完全一致**（顺序、可见性条件都对得上）。

### D9【确认差异 · 详情页头部结构】

| 端 | 证据 | 结构 |
|---|---|---|
| Web | `RecordingDetail.tsx:92-102` | `<h2>` 标题 → `<p>` **来源 · 时间**；资料卡用 `<h3>` + `<p>` + `<Link>` |
| Android | `RecordingDetailScreen.kt:49-51` | `headlineSmall` 标题 → `Text(来源 + " · " + 时间)`；资料卡用 `OutlinedCard` + `titleMedium` + `TextButton` |

顺序 ✅ 一致（来源在前、时间在后）。差异：Web 的资料入口是 `<Link>`（文字链），Android 是 `TextButton`（`：72-74`）—— 判定 **[判断题]**，属平台映射。

### D10【确认差异 · 视频会议 tab 的可发现性】

`MeetingNavigation.kt:62-81` 的四个分区（视频会议 / AI 录音 / 会议实录 / 智能纪要）**图标全部传 `null`**（`:79`），只有文字标签；Web 的 `MeetingModuleNav` 胶囊行是「文字 + 选中态」、`MeetingNavPanel` 侧栏带图标。判定：**[判断题]**（drawer 内图标缺失不影响可用性）。

---

## C. UX 改进机会（按用户影响排序）

### C1【高】二级页配色无分区，深色模式下彻底「糊成一片」

V2 / V3 / V5。用户进「录音详情」时，顶栏和内容的边界只剩一条 1dp 分隔线（其实连分隔线都没有 —— `WeMeetTopBar` 不画底边）。深色下 `surface` 与 `background` 都接近 `#121212`，**整页没有任何层级线索**。三个二级页全部如此。

影响：用户无法判断「现在处于哪一层」，也无法通过视觉判断哪里是可滚动区。

### C2【高】「会议实录 / 智能纪要」的上一页/下一页/刷新一行是唯一翻页路径，但按键裸贴在屏幕最底

`RecordLibraryScreen.kt:218-224`。这一行只在 `cursors.size > 1 || page.nextCursor != null` 时出现；出现时它是 `LazyVerticalGrid` 的**最后一个 item**，随列表滚走。用户滚到第 3 屏时想翻页必须先滚到底；翻完页后 `listState` 被 `remember(...cursor...)` 重建（`:72`）→ 回到顶部，但**没有**任何位置反馈。

对照 `RecordOriginals.kt:206-212`、`RecordTrash.kt:85-88`、`RecordSharing.kt:122-125`、`RecordHumanSummary.kt:193-194`、`SpeakerTimeline.kt:64-66` 都是同一形态 —— **7 处各写一遍「上一页 / 下一页 / 刷新」**，位置、间距、是否带刷新都不同。

影响：翻页是列表页的核心动作，却是最难够到、最不一致的一处。

### C3【高】`RecordDetailScreen` 的「进行中」段错误被静默吞（V12）

`RecordLibraryScreen.kt:84-88`。正在录音的那条记录是**用户最关心的一条**；它的读失败没有任何提示，用户只会认为「录的东西不见了」。且因为主 `result` 成功，整屏错误态也不会触发 → **完全静默**。

### C4【高】破坏性动作没有视觉警示（V14）

「移入回收站」和「永久删除」都用中性色 `TextButton`。特别是 `RecordPurge.kt:52` 的「永久删除」—— 它有 `acknowledge` 勾选（`purge_acknowledge`「我理解此操作无法撤销。」）、有 `purge_hint` 说清后果，**流程设计得很好**，但入口按钮和「上一页」长得一模一样。用户误点的成本是永久数据丢失。

同一屏还并列着「恢复」（`RecordTrash.kt:81`）—— **恢复与永久删除两个 TextButton 上下相邻、同色同重**。

### C5【中】AI 录音页空态不给出路（V9）

`RecordingHomeScreen.kt:81`。这一页的语义是「你还没有录音」，而页面上方就有两个大入口。空态文案「暂无录音」只说了现状，没说「怎么做才有」；规范 §4 明确要求后者。Web 侧的 `recordingOverview.empty` 是「暂无录音，可开始录音或导入音视频」——**连文案都是退化的**。

### C6【中】智能纪要 scope 标签与实录页不一致（D1）

同一会话内两个相邻分区对同一件事用两套词（我的内容 vs 归我所有；共享内容 vs 与我共享）。用户在两个页之间切换时无法建立「这是同一个筛选器」的心智。

### C7【中】「AI 搜索」只有一个图标（D2）

`RecordLibraryScreen.kt:117-121`。这一页顶栏已有 4 个图标（回收站 / 排序 / AI 搜索 / 搜索）+1 个条件图标（筛选），5 个 24dp 图标挤在顶栏右侧，且「AI 搜索」与「搜索」两个相邻的放大镜类图标语义靠内容描述区分 —— 视觉上极难分辨。Web 把它做成带文字的 action 按钮正是为了解决这个。

同时 **§3 规定「右侧 actions 超过 3 个收进溢出菜单」，本页 4-5 个 → 已违反**。

### C8【中】`RecordInfo` 的 `InfoRow` 在长值/大字号下不可靠（A7）

`RecordInfo.kt:108-113`。左列固定 88dp、右列 `weight(1f)`；`record.owner` 是用户昵称、`:39` 的来源标签、`:38` 的创建时间是可变长内容。1.5× 字号 + 长昵称时标签会折行，左列高度撑开而右列不对齐。

另：`:38` `record.createdAt?.let(::recordTime) ?: stringResource(records_owner_unknown)` —— `recordTime` 解析失败返回**空串**（`RecordScreens.kt:427`），此时该行显示成空白，而不是「未知」或整行不渲染。Web §3.2#3 专门修过这类「解析失败回显原值/空白」的问题。

### C9【中】`RecordOriginals` 的「回到播放位置」按钮无左右边距（A6）

`RecordOriginals.kt:143-146`。同页所有其他控件都在 `ScreenPadding` 内，只有这一颗贴左缘。它是**音频跟读的关键回位入口**，位置突兀会降低使用率。

### C10【低】同一会议在首页与会议模块显示两种时间格式

- 首页历史：`ui/home/HistoryList.kt:107-109` `fullDateTimeLocalized()` → 走 `context.getString(R.string.fmt_full_date_time)` + 应用语言（注释 `:83-87` 解释了为什么必须本地化）。
- 会议模块：`RecordScreens.kt:425-427` `DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")` → **恒定**，注释 `:420-424` 声明「会议模块内唯一的时间格式」。

判定：模块内一致 ✅，但**跨模块不一致**：同一条录音在「AI 录音」是 `9月18日 13:30`，在「会议」首页是 `9/18/26, 1:30 PM`（英文环境）。两个数字来自同一份数据。

### C11【低】`CaptureAudioPlayer` 与 `UploadMediaPlayer` 是两份几乎相同的播放器

`CaptureAudioPlayer.kt:158-187` ↔ `UploadMediaPlayer.kt:205-285`。控件行（速率 / 后退 15s / 播放暂停 52dp / 前进 15s）、速率对话框（`listOf(0.75f, 1f, 1.25f, 1.5f, 2f)`）、位置文本、Slider 语义全部重复。

差异只在：`UploadMediaPlayer` 多了视频 SurfaceView 与 `duration` 可空。两份的 **`state` 字符串机**（`"loading"/"ready"/"buffering"/"playing"/"gap"/"error"`）由两套代码各自维护，`CaptureAudioPlayer.kt:156` 认 `buffering` 而 `UploadMediaPlayer.kt:255` 把 `loading` 当「播放中」处理 —— **同一状态语义在两处不同**。

影响：用户会在两个入口看到行为不同的播放器（例：转圈的暂停按钮文案）。

### C12【低】返回文案不统一

同一次操作的不同入口：
- `RecordingDetailScreen` 返回：系统箭头（`WeMeetTopBar` 的 `cd_back`）
- `RecordTranslationArchives.kt:57` / `CaptureTranslationArchives.kt:59`：页内 `TextButton(stringResource(R.string.archives_back))`
- `RecordSharing` 预览对话框：`records_close`「关闭」
- `RecordPurge`：`record_purge_close`「关闭」，`RecordTrash`：`record_trash_cancel`「取消」，`RecordRename`：`capture_keep_recording`「取消」，`RecordHumanSummary`：`records_close`「关闭」

一共 5 种取消/关闭文案。规范没有硬性要求统一，但对同一类「退出当前弹层」的动作有 4 种说法。

---

## D. 优先级建议

四个可独立发布的批次。规模按「改动文件数 / 预估工时（人日）」估。

### 批次 1 —— 二级页分区配色（P0，最小改动、最高收益）

**目标**：三个二级页恢复「白固定头 + 浅灰滚动内容」。

| 文件 | 改动 |
|---|---|
| `app/.../records/RecordingDetailScreen.kt` | Scaffold `containerColor` 保持 `surface`；内容 Column 加 `.background(background)` |
| `app/.../records/RecordScreens.kt` | `:131` 内容 Column 加 `.background(background)`；`RecordInfo` / `RecordDocuments` 的卡片区域按需加白卡 |
| `app/.../records/CaptureScreen.kt` | `:240` 内容 Column 加 `.background(background)`；`bottomBar` 的 `CaptureRecordingDock` 已是 `Surface(surface)` ✅ |

同时处理 V1（`UploadMediaPlayer.kt:203` → 新增 `Dimens.MediaPreviewMaxHeightRatio` 或 `Dimens.MediaPreviewMaxHeight` token，或直接 `Dimens` 里加一档比例常量）。

**规模**：3 文件、~15 行；**0.5 人日**（含浅/深色 + 1.5× 字号实机走查）。

**验收**：`page-backgrounds.md` §4 第 2 条 + `app-ux-device-matrix.md` 深色行。

---

### 批次 2 —— 三态收口（P1）

**目标**：空态全部走共享组件、错误不再被吞。

| 文件 | 改动 |
|---|---|
| `RecordingHomeScreen.kt:81` | 换 `WeMeetEmptyState(title, description = 引导文案, action = { 录音 / 导入 })`。需在 `values*/strings.xml` 新增 `recording_history_empty_hint` |
| `RecordLibraryScreen.kt:84-88` | `ongoing` 失败时给 `WeMeetInlineErrorState(onRetry)` 或至少 Snackbar；不要让它静默为空 |
| `RecordTrash.kt:76`、`RecordOriginals.kt:263`、`RecordSharing.kt:112,205`、`RecordExports.kt:80`、`RecordNotifications.kt:46`、`RecordInfo.kt:73,138`、`RecordHumanSummary.kt:98`、`CaptureAudioPlayer.kt:164`、`CaptureAsrPanel.kt:175` | 裸 `Text` → `WeMeetInlineEmptyState`（`StateViews.kt:73`，目前全仓 0 调用） |
| `RecordLibraryScreen.kt:191` | 空态 `action` 从「刷新」换成「去 AI 录音」（或去掉 action 交给 description） |

**规模**：10 文件、~40 行 + 4 条新文案（`values` + `values-zh-rCN`）；**1 人日**。

---

### 批次 3 —— 按钮层级与破坏性动作（P1，跨端对齐收益最大）

**目标**：共享按钮组件进入 records 模块；破坏性动作有视觉警示；一屏一个主操作。

| 文件 | 改动 |
|---|---|
| `RecordSummaryControls.kt:127` | `ready` 多档时只把**最高优先档**（`final` > `quick` > `realtime`）做成 `PrimaryButton`，其余降为 `TextButton`/`OutlinedButton` |
| `RecordTrash.kt:27,45`、`RecordPurge.kt:52` | 改 `DangerButton`（`Buttons.kt:87`）。注意 `DangerButton` 内置 `fillMaxWidth` + `ButtonHeight(52dp)` —— 落在详情页的 `RecordInfo` 区块里需要评估布局，必要时给 `DangerButton` 加 `inline: Boolean = false` 参数（改 `core-design`，影响 calendar 那一个调用点） |
| `RecordScreens.kt:241-247` | 三颗同级 `TextButton` → 明确主次：`管理纪要` 提为 `OutlinedButton`，其余降为 `TextButton` |
| `RecordSummaryControls.kt:125`、`RecordSharing.kt:109`、`RecordQuestions.kt:101,109`、`RecordNotifications.kt:42`、`RecordExports.kt:73`、`RecordHumanSummary.kt:103`、`RecordSummaryTasks.kt:94` | 这些是**在 `ModalBottomSheet` 里的 reconcile 动作**，与同 sheet 的「确认/提交」会形成双主按钮。统一改用 `PrimaryButton` 或统一降级 —— 需要先定一条规则（建议：sheet 内确认类动作用 `PrimaryButton`，reconcile 用 `TextButton`） |

**规模**：9 文件、~60 行 + `core-design/Buttons.kt` 可能微调；**1.5–2 人日**（含 sheet 内主次规则的决策）。

**发布依赖**：与 Web 端 §3.27「标题栏里不出现 `dense`、每页只有一个主操作」的规则对齐 —— 建议同批更新 Web 侧若有差异。

---

### 批次 4 —— 跨端术语与页头对齐（P2）

**目标**：把 §3.2#6 / §3.9 / §3.14 / §3.17 的收口结论在 Android 落地。

| 文件 | 改动 |
|---|---|
| `values-zh-rCN/strings.xml:1059,1061` | `minutes_owned` 归我所有 → **我的内容**；`minutes_shared` 与我共享 → **共享内容**（`minutes_participated` 「我参与的」已对）。同步 `values/strings.xml` 的英文（`Owned by me` → 与 `library.scope.owned` 一致的英文值） |
| `values-zh-rCN/strings.xml:1075` | `meeting_ai_search` AI 搜索 → **搜索会议 AI**（D2） |
| `RecordLibraryScreen.kt:98-132` | 顶栏 actions 从 4–5 个收敛到 ≤3（§3）：把「排序」收进溢出菜单，或把「搜索」改成页头下方工具行。**这是本批次唯一有设计决策的部分** |
| `RecordLibraryScreen.kt:330-333` | 状态签改 `if/else` → 允许同时出现（D7） |
| `RecordLibraryScreen.kt:337-344` | 副行字段顺序对齐 Web 窄屏（上传状态与所有者互换）；加 `maxLines = 1, overflow = Ellipsis` |
| `values*/strings.xml` | 新增 `cd_*` 系列 key 并把 records 模块的图标 `contentDescription` 切过去（A5） |

**规模**：4 文件 + 文案；**1 人日**（不含顶栏信息架构决策）。

---

### 批次 5（可选）—— 结构性收敛（P3，建议单独立项）

| 项 | 说明 | 规模 |
|---|---|---|
| 抽 `RecordPager` 共享组件 | 7 处「上一页/下一页/刷新」收敛（C2） | 8 文件、~1 人日 |
| 合并两个播放器 | `CaptureAudioPlayer` / `UploadMediaPlayer` 抽 `MediaPlayerCard` + 单一状态机（C11） | 3 文件、~2 人日 |
| `InfoRow` 自适应标签列 | 用 `FlowRow` 或 `widthIn(min = LabelColumnWidth)` + `maxLines`（C8） | 1 文件、0.5 人日 |
| 时间格式统一 | 让 `recordTime` 走 `HistoryTimeFormatter`（C10） | 需先决定「本地化 vs 恒定格式」，属产品取舍 |
| 护栏补规则 | `RAW_DIMEN` 支持 `).dp` 形态；新增 `cd_` 前缀规则（A1） | `buildSrc/DesignLintTask.kt`，1 人日 |

---

## 附录：本审计未覆盖 / 无法静态判定的部分

按 `page-backgrounds.md:51` 的声明（「以上布局与状态切换须在模拟器或真机上走查」），以下必须实机验证，本次**未做**：

1. 状态栏 / 手势条底色在浅色与深色下、以及从二级页返回一级页时的**实际过渡**（本报告只做了代码路径推断）。
2. 底部模块导航栏在会议 tab 四个分区之间切换时的显示/隐藏（代码上无隐藏逻辑，所以应始终显示，但未实机确认）。
3. 1.5× / 2.0× 字号下的 `InfoRow`（A7）、`RecordLibraryScreen.kt:145-149` 双层 `weight`、`recordMetaLine` 折行 —— 本报告标为 **[判断题]** 的部分需要实机确认。
4. 横屏 / 折叠屏下 `UploadMediaPlayer` 的视频高度计算与网格列数。
5. TalkBack 实际朗读顺序（`SpeakerActivity` 的进度条重复朗读嫌疑）。
6. `checkDesignTokens` 未跑（按要求），V1 的「护栏漏判」结论来自对 `DesignLintTask.kt:205` 正则的静态阅读；若基线不为空则该结论需复核（当前 `config/design-lint-baseline.txt` 内容为空）。
