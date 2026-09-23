@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.we.meet.ui.records

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.RecordOrdering
import com.we.meet.data.repository.RecordScope
import com.we.meet.data.repository.RecordSource
import com.we.meet.ui.components.*
import com.we.meet.ui.home.MeetingListSectionTitle
import com.we.meet.ui.theme.Dimens

@Composable
fun RecordLibraryScreen(
    repository: MeetingRecordRepository,
    viewer: String,
    summariesOnly: Boolean,
    onRecord: (String) -> Unit,
    onBack: () -> Unit,
    onOpenNavDrawer: (() -> Unit)? = null,
    onSummaryRecord: (String) -> Unit = onRecord,
    onSearchMeetingAi: (() -> Unit)? = null,
    initialSource: RecordSource? = null,
) {
    var scope by remember(viewer, summariesOnly) { mutableStateOf(if (summariesOnly) RecordScope.OWNED else RecordScope.RECENT) }
    var source by remember(viewer, initialSource) { mutableStateOf(initialSource) }
    var input by remember(viewer) { mutableStateOf("") }
    var query by remember(viewer) { mutableStateOf("") }
    var searchVisible by remember(viewer) { mutableStateOf(false) }
    var filtersVisible by remember(initialSource) { mutableStateOf(initialSource != null) }
    var grid by remember { mutableStateOf(false) }
    var ordering by remember(viewer, summariesOnly) { mutableStateOf(RecordOrdering.NEWEST) }
    var trashVisible by remember(viewer) { mutableStateOf(false) }
    var orderMenu by remember { mutableStateOf(false) }
    var dateFrom by remember(viewer) { mutableStateOf("") }
    var dateThrough by remember(viewer) { mutableStateOf("") }
    val dates = remember(dateFrom, dateThrough) { recordDateRange(dateFrom, dateThrough) }
    val hasDates = dateFrom.isNotEmpty() || dateThrough.isNotEmpty()
    var cursors by remember(viewer, scope, source, query, summariesOnly, dates, ordering) { mutableStateOf(listOf<String?>(null)) }
    var refresh by remember { mutableIntStateOf(0) }
    // 长按哪一行,菜单就锚在哪一行:只记这一条,不弹全局对话框。
    var menuRecord by remember(viewer) { mutableStateOf<RecordDto?>(null) }
    // 长按菜单要 app(剪贴板 / IM 会话 / 各仓库);取不到时行照常渲染,只是没有菜单。
    val app = LocalContext.current.applicationContext as? WeMeetApp
    val cursor = cursors.last()
    val listState = remember(viewer, scope, source, query, cursor, summariesOnly, dates, ordering) { LazyGridState() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val search = { query = input.trim(); refresh++; keyboard?.hide(); Unit }
    val result = visibleRead(viewer, scope, source, query, cursor, summariesOnly, refresh, dates, ordering) {
        // 会议实录只取已结束的:正在录的那条走下面单独一段,否则会同时出现在两处。
        // 纪要库不带这个条件(服务端本来就是 has_summary=true 的那批)。
        repository.records(viewer, scope, source, summariesOnly, query.ifBlank { null }, cursor,
            isOngoing = if (summariesOnly) null else false, createdFrom = dates.first, createdBefore = dates.second, ordering = ordering)
    }
    // 「进行中」单独一段:Web 端同样是两段(进行中 / 历史记录),正在进行的那条最该排在最前。
    // 只取第一页 —— 服务端单页上限 30,同时在录的记录不该有几十条,不值得再挂一套游标。
    val ongoing = if (summariesOnly) null else visibleRead(viewer, scope, source, query, refresh, dates, ordering) {
        repository.records(viewer, scope, source, summariesOnly = false, query.ifBlank { null }, cursor = null, isOngoing = true,
            createdFrom = dates.first, createdBefore = dates.second, ordering = ordering)
    }
    val ongoingRows = ongoing?.getOrNull()?.results.orEmpty()
    LaunchedEffect(searchVisible) { if (searchVisible) focus.requestFocus() }
    Scaffold(
        topBar = {
            WeMeetTopBar(stringResource(if (summariesOnly) R.string.records_minutes else R.string.records_title),
                onBack = if (onOpenNavDrawer == null) onBack else null,
                onMenu = onOpenNavDrawer, menuDescription = stringResource(R.string.meeting_navigation),
                // 记录/纪要是会议模块的抽屉一级分区(有汉堡菜单、有底部模块导航栏),
                // 固定头部用浅灰与状态栏、兄弟分区(视频会议/AI 录音)对齐。
                containerColor = MaterialTheme.colorScheme.background,
                actions = {
                    // 设计规范 §3「右侧 actions 超过 3 个收进溢出菜单」。这一页此前
                    // 最多挂 5 颗 24dp 图标(回收站 / 排序 / AI 搜索 / 搜索 / 筛选),
                    // 其中「排序」和「AI 搜索」在视觉上都是几何图形,靠内容描述才分得清。
                    // 现在只留三格:搜索会议 AI、搜索、更多;排序 / 筛选 / 回收站进菜单。
                    onSearchMeetingAi?.let { search ->
                        IconButton(onClick = search) {
                            Icon(Icons.Outlined.AutoAwesome, stringResource(R.string.cd_records_ai_search))
                        }
                    }
                    IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) { input = ""; query = "" } }) {
                        Icon(if (searchVisible) Icons.Outlined.Close else Icons.Outlined.Search,
                            stringResource(if (searchVisible) R.string.cd_records_clear_search else R.string.cd_records_search))
                    }
                    Box {
                        IconButton(onClick = { orderMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_records_more))
                        }
                        DropdownMenu(expanded = orderMenu, onDismissRequest = { orderMenu = false }) {
                            RecordOrdering.entries.forEach { value ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (value == RecordOrdering.NEWEST) R.string.records_newest else R.string.records_oldest)) },
                                    leadingIcon = { if (ordering == value) Icon(Icons.Outlined.Check, null) else null },
                                    onClick = { ordering = value; orderMenu = false },
                                )
                            }
                            // 纪要子区正文里已有带当前值的「全部智能纪要」筛选入口,
                            // 菜单里不再重复一项。
                            if (!summariesOnly) DropdownMenuItem(
                                text = { Text(stringResource(R.string.records_filters)) },
                                leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                                onClick = { orderMenu = false; filtersVisible = true },
                            )
                            if (result?.getOrNull()?.trashAvailable == true) DropdownMenuItem(
                                text = { Text(stringResource(R.string.record_trash_title)) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                onClick = { orderMenu = false; trashVisible = true },
                            )
                        }
                    }
                })
        },
        // 这一页只查、只看:录音与导入两个动作归属「AI 录音」页(抽屉里就在上一格),
        // Web 端同样是 record 页放动作、notes/minutes 两页不放 —— 列表页不再挂常驻底栏。
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (summariesOnly) {
                val scopes = listOf(RecordScope.OWNED, RecordScope.PARTICIPATED, RecordScope.SHARED)
                TabRow(selectedTabIndex = scopes.indexOf(scope).coerceAtLeast(0), containerColor = MaterialTheme.colorScheme.background) {
                    scopes.forEach { value -> Tab(selected = scope == value, onClick = { scope = value },
                        text = { Text(stringResource(minutesScopeLabel(value))) }) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { filtersVisible = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(source?.let { sourceLabel(it.wire) } ?: R.string.minutes_all), modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ExpandMore, null)
                    }
                    IconButton(onClick = { grid = !grid }) {
                        Icon(if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                            stringResource(if (grid) R.string.cd_records_list_view else R.string.cd_records_grid_view))
                    }
                }
            } else
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    listOf(RecordScope.RECENT, RecordScope.OWNED, RecordScope.SHARED).forEach { value ->
                        FilterChip(selected = scope == value, onClick = { scope = value }, shape = CircleShape,
                            border = null, colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                            label = { Text(stringResource(scopeLabel(value)), fontWeight = if (scope == value) FontWeight.SemiBold else FontWeight.Normal) })
                    }
                }
                IconButton(onClick = { grid = !grid }) {
                    Icon(if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                        stringResource(if (grid) R.string.cd_records_list_view else R.string.cd_records_grid_view))
                }
            }
            if (searchVisible) OutlinedTextField(input, onValueChange = { input = it.take(200); if (input.isEmpty()) query = "" },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding).focusRequester(focus), singleLine = true,
                placeholder = { Text(stringResource(R.string.records_search)) }, shape = MaterialTheme.shapes.large,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = {
                    TextButton(onClick = search) { Text(stringResource(R.string.records_search_action)) }
                }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
            if ((!summariesOnly && (source != null || scope == RecordScope.PARTICIPATED)) || hasDates) Row(Modifier.padding(horizontal = Dimens.ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(source?.let { stringResource(sourceLabel(it.wire)) },
                    if (scope == RecordScope.PARTICIPATED) stringResource(R.string.records_participated) else null,
                    if (hasDates) stringResource(R.string.records_date_active, dateFrom.ifEmpty { "…" }, dateThrough.ifEmpty { "…" }) else null).joinToString(" · "),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { source = null; scope = if (summariesOnly) RecordScope.OWNED else RecordScope.RECENT; dateFrom = ""; dateThrough = "" }) { Text(stringResource(R.string.records_reset_filters)) }
            }
            // 一级页的下一半:白色滚动区,加载态/空态/错误态也得把白底铺满。
            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            when {
                result == null -> WeMeetInlineLoading()
                result.isFailure -> WeMeetErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_unavailable))
                // 只剩「进行中」那几条时不算空 —— 空态会把它们一起盖掉。
                // 「进行中」读失败时同样不算空:那一段有自己的错误 + 重试(见下)。
                result.getOrThrow().results.isEmpty() && ongoingRows.isEmpty() && ongoing?.isFailure != true -> WeMeetEmptyState(stringResource(if (summariesOnly) R.string.minutes_empty else R.string.records_empty),
                    description = stringResource(if (summariesOnly) R.string.minutes_empty_hint else R.string.records_empty_hint),
                    // 空态的 action 要「给下一步」,而不是「再问一次服务器」:
                    // 有筛选条件时给「清除筛选」(真的能改变结果),否则才退回「刷新」。
                    action = {
                        val filtered = source != null || hasDates || (!summariesOnly && scope == RecordScope.PARTICIPATED) ||
                            (summariesOnly && scope != RecordScope.OWNED)
                        if (filtered) TextButton(onClick = {
                            source = null
                            scope = if (summariesOnly) RecordScope.OWNED else RecordScope.RECENT
                            dateFrom = ""; dateThrough = ""
                        }) { Text(stringResource(R.string.records_reset_filters)) }
                        else TextButton(onClick = { cursors = listOf(null); refresh++ }) { Text(stringResource(R.string.records_refresh)) }
                    })
                else -> {
                    val page = result.getOrThrow()
                    LazyVerticalGrid(columns = if (grid) GridCells.Adaptive(Dimens.RecordGridMinWidth) else GridCells.Fixed(1), state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = if (grid) PaddingValues(Dimens.ScreenPadding) else PaddingValues(),
                        // 列表模式的行自带内边距和内缩分隔线,不能再叠一层行距;网格模式才需要。
                        verticalArrangement = if (grid) Arrangement.spacedBy(Dimens.SpaceM) else Arrangement.Top,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                        // 「进行中」那一段自己读失败时必须说出来:它是用户最关心的一条,
                        // 此前失败会静默变成空数组,而主列表成功时连整屏错误态都不触发 ——
                        // 用户只会以为「录的东西不见了」。这里给它自己的内联错误 + 重试。
                        if (ongoing?.isFailure == true) item(span = { GridItemSpan(maxLineSpan) }, key = "ongoing-error") {
                            WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_ongoing_unavailable))
                        }
                        // 进行中在前:正在收音的那条不该被几十条历史压下去。
                        if (ongoingRows.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "section-ongoing") {
                                MeetingListSectionTitle(stringResource(R.string.records_ongoing))
                            }
                            items(ongoingRows, key = { "ongoing-${it.id}" }) { record ->
                                val open = { if (summariesOnly) onSummaryRecord(record.id) else onRecord(record.id) }
                                RecordLibraryItem(app, viewer, record, summariesOnly, grid, menuRecord, { menuRecord = null }, { menuRecord = it }, { refresh++ }, open)
                            }
                        }
                        if (!summariesOnly) item(span = { GridItemSpan(maxLineSpan) }, key = "section-archive") {
                            MeetingListSectionTitle(stringResource(R.string.records_archive))
                        }
                        items(page.results, key = { it.id }) { record ->
                            val open = { if (summariesOnly) onSummaryRecord(record.id) else onRecord(record.id) }
                            RecordLibraryItem(app, viewer, record, summariesOnly, grid, menuRecord, { menuRecord = null }, { menuRecord = it }, { refresh++ }, open)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            // 「单页不放这一行」由 RecordPager 自己保证（它同时是七处
                            // 翻页行的唯一定义）。
                            RecordPager(
                                hasPrevious = cursors.size > 1,
                                onPrevious = { cursors = cursors.dropLast(1) },
                                hasNext = page.nextCursor != null,
                                onNext = { page.nextCursor?.let { next -> cursors = cursors + next } },
                                onRefresh = { refresh++ },
                            )
                        }
                    }
                }
            }
            }
        }
    }
    if (trashVisible) RecordTrashSheet(viewer, repository) { trashVisible = false; refresh++ }
    if (filtersVisible) ModalBottomSheet(
        onDismissRequest = { filtersVisible = false },
        // A validation message changes content height. Keep the form expanded
        // rather than selecting a new half-height anchor that hides its footer.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val filterFocus = LocalFocusManager.current
        val filterKeyboard = LocalSoftwareKeyboardController.current
        var fromDraft by remember { mutableStateOf(dateFrom) }
        var throughDraft by remember { mutableStateOf(dateThrough) }
        var invalidDates by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            Text(stringResource(R.string.records_filters), style = MaterialTheme.typography.titleLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                RecordScope.entries.filter { !summariesOnly || it != RecordScope.RECENT }.forEach { value -> FilterChip(scope == value, onClick = { scope = value }, label = { Text(stringResource(if (summariesOnly) minutesScopeLabel(value) else scopeLabel(value))) }) }
            }
            Text(stringResource(R.string.records_source_filter), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                (listOf(null) + RecordSource.entries).forEach { value -> FilterChip(source == value, onClick = { source = value }, label = { Text(stringResource(sourceLabel(value?.wire))) }) }
            }
            OutlinedTextField(fromDraft, { fromDraft = it.take(10); invalidDates = false }, singleLine = true,
                label = { Text(stringResource(R.string.records_created_from)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(throughDraft, { throughDraft = it.take(10); invalidDates = false }, singleLine = true,
                label = { Text(stringResource(R.string.records_created_through)) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.records_date_hint), style = MaterialTheme.typography.bodySmall)
            if (invalidDates) Text(stringResource(R.string.records_date_error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { fromDraft = ""; throughDraft = ""; invalidDates = false }) { Text(stringResource(R.string.records_clear_dates)) }
            Button(onClick = {
                filterFocus.clearFocus()
                filterKeyboard?.hide()
                if (runCatching { recordDateRange(fromDraft, throughDraft) }.isSuccess) {
                    dateFrom = fromDraft; dateThrough = throughDraft; filtersVisible = false
                } else invalidDates = true
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.records_filters_done)) }
        }
    }
}

/**
 * 列表/网格里的一行(或一张卡)+ 它的长按菜单。
 *
 * 菜单挂在**行自己的 `Box`** 里,所以长按哪一行,菜单就贴在哪一行旁边 —— 用
 * `DropdownMenu`(底层是 Popup,不受 `LazyVerticalGrid` 的裁剪影响)而不是对话框。
 * 三个动作与实录详情页顶栏那颗三点按钮完全同一份([RecordMenu])。
 *
 * `app` 为 null(理论上只在测试宿主里)时仍然渲染行,只是没有长按菜单。
 */
@Composable
private fun RecordLibraryItem(
    app: WeMeetApp?,
    viewer: String,
    record: RecordDto,
    summariesOnly: Boolean,
    grid: Boolean,
    menuRecord: RecordDto?,
    onDismiss: () -> Unit,
    onOpen: (RecordDto) -> Unit,
    onChanged: () -> Unit,
    onClick: () -> Unit,
) {
    Box {
        val longPress = { onOpen(record) }
        if (grid) RecordLibraryCard(record, summariesOnly, onClick, longPress)
        else RecordLibraryRow(record, summariesOnly, onClick, longPress)
        if (app != null) RecordMenuContent(
            app = app,
            viewer = viewer,
            record = record,
            objectScope = if (summariesOnly) "minutes" else "record",
            expanded = menuRecord?.id == record.id,
            allowRename = !summariesOnly,
            onDismiss = onDismiss,
            onRenamed = onChanged,
            onChanged = onChanged,
        )
    }
}

/**
 * 列表行:与「视频会议」「AI 录音」同款骨架 —— 图标块 + 标题 + 说明,行间内缩分隔线。
 * 记录库/纪要库和它们同属会议模块的抽屉一级分区,行样式不该各说各话。
 */
@Composable
private fun RecordLibraryRow(record: RecordDto, summariesOnly: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM)
                .heightIn(min = Dimens.MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 与 [com.we.meet.ui.home.MeetingListItem] 同一块图标底,四个分区的行长得一样。
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                Box(Modifier.size(Dimens.ListLeadingIcon), contentAlignment = Alignment.Center) {
                    Icon(recordLibraryIcon(record, summariesOnly), null, Modifier.size(Dimens.IconMedium), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f).padding(start = Dimens.SpaceM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                RecordLibraryText(record, summariesOnly)
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = Dimens.ScreenPadding + Dimens.ListLeadingIcon + Dimens.SpaceM),
            thickness = Dimens.DividerThin,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** 网格卡片:白底上要靠描边立住形状,不能用和底色同色的实心卡。 */
@Composable
private fun RecordLibraryCard(record: RecordDto, summariesOnly: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(Dimens.DividerThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Icon(recordLibraryIcon(record, summariesOnly), null, Modifier.size(Dimens.IconMedium), tint = MaterialTheme.colorScheme.primary)
            RecordLibraryText(record, summariesOnly)
        }
    }
}

/**
 * 两种排布共用的文字块:标题 / 副行 / 状态签。
 *
 * 副行与「AI 录音」页逐字同构:一行「时间 · 来源 · 上传状态」。Web 端
 * `MeetingLibrary` / `RecordingOverview` 的注释把这条写死了 ——
 * 「同一条记录在两个栏目里不该是两种版式」,上传处理状态两个列表页都要看得见,
 * 因为记录库才是管理记录的入口。进行中 / 已有纪要仍是独立的状态签。
 */
@Composable
private fun RecordLibraryText(record: RecordDto, summariesOnly: Boolean) {
    Text(record.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(recordMetaLine(record, summariesOnly), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // 两个状态签**可以同时出现**:一条既在录又已有纪要的记录,Web 端两颗都显示
    // (`MeetingLibrary.tsx` 的 ongoing / hasSummary 是两段独立判断),此前这里是
    // if/else,「已有纪要」被「进行中」吃掉。
    if (!summariesOnly && record.isOngoing) Text(
        stringResource(R.string.records_ongoing),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall,
    )
    if (!summariesOnly && record.hasSummary) Text(
        stringResource(R.string.records_minutes_ready),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall,
    )
}

/** 纪要库里时间前面要写「会议时间」,否则分不清这是开会时刻还是纪要生成时刻。 */
@Composable
internal fun recordMetaLine(record: RecordDto, summariesOnly: Boolean): String = listOfNotNull(
    if (summariesOnly) stringResource(R.string.minutes_recorded_at, recordTime(record.originAt)) else recordTime(record.originAt),
    stringResource(recordSourceLabel(record)),
    // 所有者:Web 的表格有这一列,窄屏(手机)并进副行 —— 手机端只看得到这一种形态。
    // **位置与 Web 窄屏一致**:所有者在上传状态之前(`MeetingLibrary.tsx` 的
    // `narrowOnly` 段就插在来源与上传状态之间)。此前 Android 把上传状态排在前,
    // 同一条记录在两端读起来是两种顺序。
    record.owner?.takeIf { it.isNotBlank() } ?: stringResource(R.string.records_owner_unknown),
    record.upload?.let { stringResource(uploadStatusLabel(it.status)) },
).joinToString(" · ")

/** 来源图标在列表和网格里必须一致 —— 网格丢掉图标就只剩文字能区分会议/录音/导入。 */
private fun recordLibraryIcon(record: RecordDto, summariesOnly: Boolean): ImageVector = when {
    summariesOnly -> Icons.Outlined.Description
    record.sourceType == "meeting" -> Icons.Outlined.Videocam
    record.sourceType == "upload" -> Icons.Outlined.UploadFile
    else -> Icons.Outlined.GraphicEq
}

private fun minutesScopeLabel(scope: RecordScope): Int = when (scope) {
    RecordScope.OWNED -> R.string.minutes_owned
    RecordScope.PARTICIPATED -> R.string.minutes_participated
    RecordScope.SHARED -> R.string.minutes_shared
    RecordScope.RECENT -> R.string.records_recent
}
