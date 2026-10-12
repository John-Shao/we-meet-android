package com.we.meet.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens

internal data class AppEntry(val id: String, val title: Int, val description: Int, val icon: ImageVector, val open: () -> Unit)

@Composable
fun AppsScreen(onOpenModule: (AppModule) -> Unit, onOpenApproval: () -> Unit,
    onOpenAssistantCall: () -> Unit, onOpenBilingualTranslation: () -> Unit,
    onFullScreenVisibilityChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    val viewer = app.tokenStore.userId.orEmpty()
    val preferences = remember { context.getSharedPreferences("app_navigation", android.content.Context.MODE_PRIVATE) }
    val favoritesStore = remember(viewer) { AppFavoritesStore(preferences, viewer) }
    var pinned by remember(favoritesStore) { mutableStateOf(favoritesStore.read()) }
    var recent by remember(viewer) {
        mutableStateOf(preferences.getString("recent:$viewer", "").orEmpty().split(',').filter { it.isNotBlank() && it != "ai" })
    }
    LaunchedEffect(viewer) {
        preferences.edit().putString("recent:$viewer", recent.joinToString(",")).apply()
    }
    var approvalCount by remember(viewer) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(viewer, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    approvalCount = app.apiClient.approvalApi.listApprovals(role = "pending", page = 1).count
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    approvalCount = 0
                }
                delay(60_000)
            }
        }
    }
    AppsContent(pinned, approvalCount, onOpenModule, onOpenApproval, onOpenAssistantCall, onOpenBilingualTranslation,
        recent = recent, onFullScreenVisibilityChanged = onFullScreenVisibilityChanged, onOpened = { id ->
            recent = (listOf(id) + recent).distinct().take(5)
            preferences.edit().putString("recent:$viewer", recent.joinToString(",")).apply()
        }) { next ->
        pinned = next
        favoritesStore.save(next)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AppsContent(
    pinned: List<String>,
    approvalCount: Int,
    onOpenModule: (AppModule) -> Unit,
    onOpenApproval: () -> Unit,
    onOpenAssistantCall: () -> Unit,
    onOpenBilingualTranslation: () -> Unit,
    recent: List<String> = emptyList(),
    onOpened: (String) -> Unit = {},
    onFullScreenVisibilityChanged: (Boolean) -> Unit = {},
    onPinnedChange: (List<String>) -> Unit,
) {
    val groups = listOf(
        R.string.apps_time_tasks to listOf(
            AppEntry("calendar", R.string.tab_calendar, R.string.apps_calendar_description, Icons.Filled.CalendarMonth) { onOpenModule(AppModule.Calendar) },
            AppEntry("tasks", R.string.tab_tasks, R.string.apps_tasks_description, Icons.Filled.TaskAlt) { onOpenModule(AppModule.Tasks) },
        ),
        R.string.apps_organization to listOf(
            AppEntry("contacts", R.string.tab_contacts, R.string.apps_contacts_description, Icons.Filled.Contacts) { onOpenModule(AppModule.Contacts) },
            AppEntry("approval", R.string.approval_title, R.string.apps_approval_description, Icons.AutoMirrored.Filled.FactCheck, onOpenApproval),
        ),
        R.string.apps_ai to listOf(
            AppEntry("ai-call", R.string.ai_hub_call_title, R.string.ai_hub_call_subtitle, Icons.Filled.Call, onOpenAssistantCall),
            AppEntry("bilingual", R.string.bilingual_title, R.string.bilingual_subtitle, Icons.Filled.Translate, onOpenBilingualTranslation),
        ),
    )
    val all = groups.flatMap { it.second }
    val favorites = pinned.mapNotNull { id -> all.find { it.id == id } }
    val categoryTitles = listOf(R.string.apps_all, R.string.apps_recent) + groups.map { it.first }
    var category by rememberSaveable { mutableIntStateOf(0) }
    var managing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(managing) { onFullScreenVisibilityChanged(managing) }
    DisposableEffect(Unit) { onDispose { onFullScreenVisibilityChanged(false) } }
    val visible = when (category) {
        0 -> all
        1 -> recent.mapNotNull { id -> all.find { it.id == id } }
        else -> groups.getOrNull(category - 2)?.second.orEmpty()
    }
    if (managing) {
        AddFavoritesPage(all, groups, pinned, recent, onBack = { managing = false }, onPinnedChange = onPinnedChange)
        return
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        WeMeetTopBar(title = stringResource(R.string.tab_apps), containerColor = MaterialTheme.colorScheme.background,
            actions = {
                IconButton(onClick = { managing = true }) {
                    Icon(Icons.Filled.Apps, contentDescription = stringResource(R.string.apps_manage))
                }
            })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = (maxWidth / (Dimens.Apps.CellMinWidth * LocalDensity.current.fontScale)).toInt().coerceIn(2, 4)
            LazyColumn(Modifier.fillMaxSize().testTag("apps-scroll"), contentPadding = PaddingValues(bottom = Dimens.SpaceXl)) {
                item { AppGroupTitle(R.string.apps_favorites) }
                val favoriteCells = favorites.map { it as AppEntry? } + listOf(null)
                items(favoriteCells.chunked(columns)) { row ->
                    AppGridRow(row, columns, "favorite", approvalCount, onOpened, onAdd = { managing = true })
                }
                stickyHeader {
                    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                        AppGroupTitle(R.string.apps_all_title)
                        ScrollableTabRow(selectedTabIndex = category, edgePadding = Dimens.ScreenPadding,
                            containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary) {
                            categoryTitles.forEachIndexed { index, title ->
                                Tab(selected = category == index, onClick = { category = index },
                                    selectedContentColor = MaterialTheme.colorScheme.primary,
                                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("apps-category-$index"),
                                    text = { Text(stringResource(title), maxLines = 1, softWrap = false) })
                            }
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item {
                        Text(stringResource(R.string.apps_recent_empty), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceXl), textAlign = TextAlign.Center)
                    }
                }
                items(visible.chunked(columns)) { row ->
                    AppGridRow(row, columns, "catalog", approvalCount, onOpened)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AddFavoritesPage(
    all: List<AppEntry>, groups: List<Pair<Int, List<AppEntry>>>, pinned: List<String>, recent: List<String>,
    onBack: () -> Unit, onPinnedChange: (List<String>) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableIntStateOf(0) }
    val favorites = pinned.mapNotNull { id -> all.find { it.id == id } }
    val titles = listOf(R.string.apps_all, R.string.apps_recent) + groups.map { it.first }
    val entries = when {
        category == 0 -> all
        category == 1 -> recent.mapNotNull { id -> all.find { it.id == id } }
        else -> groups.getOrNull(category - 2)?.second.orEmpty()
    }.filter { entry -> stringResource(entry.title).contains(query, ignoreCase = true) }
    val back: () -> Unit = { if (editing) editing = false else onBack() }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("apps-add-page")) {
        WeMeetTopBar(title = stringResource(if (editing) R.string.apps_manage else R.string.apps_add_favorite), onBack = back,
            actions = {
                if (!editing) IconButton(onClick = { searching = !searching; query = "" }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.apps_search))
                }
            })
        if (searching && !editing) OutlinedTextField(query, onValueChange = { query = it }, singleLine = true,
            label = { Text(stringResource(R.string.apps_search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding).testTag("apps-search-field"))
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(Dimens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.apps_added_count, favorites.size), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                favorites.forEach { AppSymbol(it, compact = true) }
            }
            TextButton(onClick = { editing = !editing; query = "" }, modifier = Modifier.testTag("apps-manage-toggle")) {
                Text(stringResource(if (editing) R.string.apps_done else R.string.apps_settings))
            }
        }
        Spacer(Modifier.height(Dimens.SpaceS))
        if (editing) {
            AppGroupTitle(R.string.apps_favorites)
            ReorderFavoritesList(favorites, onPinnedChange)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().testTag("apps-add-scroll"), contentPadding = PaddingValues(bottom = Dimens.SpaceXl)) {
            stickyHeader {
                Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    AppGroupTitle(R.string.apps_all_title)
                    ScrollableTabRow(category, edgePadding = Dimens.ScreenPadding,
                        containerColor = MaterialTheme.colorScheme.surface) {
                        titles.forEachIndexed { index, title ->
                            Tab(selected = category == index, onClick = { category = index },
                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("apps-add-category-$index"), text = { Text(stringResource(title), maxLines = 1) })
                        }
                    }
                }
            }
            if (entries.isEmpty()) item {
                Text(stringResource(if (query.isNotBlank()) R.string.apps_search_empty else R.string.apps_recent_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.SpaceXl))
            }
            items(entries, key = { it.id }) { entry ->
                ListItem(headlineContent = { Text(stringResource(entry.title)) },
                    supportingContent = { Text(stringResource(entry.description)) },
                    leadingContent = { AppSymbol(entry) },
                    trailingContent = {
                        when {
                            entry.id in pinned -> Text(stringResource(R.string.apps_added), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> OutlinedButton(onClick = { onPinnedChange(pinned + entry.id) },
                                shape = MaterialTheme.shapes.small, border = BorderStroke(Dimens.BorderThin, MaterialTheme.colorScheme.primary),
                                modifier = Modifier.testTag("app-pin-${entry.id}")) {
                                Text(stringResource(R.string.apps_add))
                            }
                        }
                    }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface))
            }
        }
    }
}

@Composable
internal fun AppSymbol(entry: AppEntry?, compact: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val (container, foreground) = when (entry?.id) {
        "tasks", "ai-call", "bilingual" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        "contacts", "approval" -> scheme.secondaryContainer to scheme.onSecondaryContainer
        null -> scheme.surfaceContainerLow to scheme.onSurfaceVariant
        else -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Surface(shape = if (compact) MaterialTheme.shapes.small else MaterialTheme.shapes.large, color = container,
        modifier = Modifier.size(if (compact) Dimens.ControlCompact else Dimens.Apps.IconContainer)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(entry?.icon ?: Icons.Filled.Add, contentDescription = if (compact && entry != null) stringResource(entry.title) else null,
                tint = foreground, modifier = Modifier.size(if (compact) Dimens.IconSmall else Dimens.IconLarge))
        }
    }
}

@Composable
private fun AppGroupTitle(title: Int) {
    Text(stringResource(title), style = if (title == R.string.apps_all_title) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
        color = if (title == R.string.apps_all_title) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding)
            .padding(top = Dimens.SpaceXl, bottom = Dimens.SpaceS))
}

@Composable
private fun AppGridRow(
    entries: List<AppEntry?>, columns: Int, section: String, approvalCount: Int,
    onOpened: (String) -> Unit, onAdd: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceS)) {
        entries.forEach { entry ->
            val add = entry == null
            val title = stringResource(entry?.title ?: R.string.apps_add_favorite)
            Column(Modifier.weight(1f).heightIn(min = Dimens.Apps.CellMinHeight)
                .testTag(if (add) "apps-add-favorite" else "app-$section-${entry!!.id}")
                .clickable(role = Role.Button, onClick = {
                    if (entry != null) { onOpened(entry.id); entry.open() } else onAdd?.invoke()
                }).padding(vertical = Dimens.SpaceL), horizontalAlignment = Alignment.CenterHorizontally) {
                val scheme = MaterialTheme.colorScheme
                val (container, foreground) = when (entry?.id) {
                    "tasks", "ai-call", "bilingual" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
                    "contacts", "approval" -> scheme.secondaryContainer to scheme.onSecondaryContainer
                    null -> scheme.surfaceContainerLow to scheme.onSurfaceVariant
                    else -> scheme.primaryContainer to scheme.onPrimaryContainer
                }
                BadgedBox(badge = {
                    if (entry?.id == "approval" && approvalCount > 0) Badge {
                        Text(if (approvalCount > 99) "99+" else approvalCount.toString())
                    }
                }) {
                    Surface(shape = MaterialTheme.shapes.large, color = container,
                        modifier = Modifier.size(Dimens.Apps.IconContainer)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(entry?.icon ?: Icons.Filled.Add, contentDescription = null,
                                tint = foreground, modifier = Modifier.size(Dimens.IconLarge))
                        }
                    }
                }
                Spacer(Modifier.height(Dimens.SpaceS))
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Dimens.SpaceXs))
            }
        }
        repeat(columns - entries.size) { Spacer(Modifier.weight(1f)) }
    }
}
