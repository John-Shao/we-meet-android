package com.we.meet.ui.calendar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.vm.ConversationListViewModel
import com.we.meet.ui.components.PrimaryButton
import com.we.meet.ui.components.SecondaryButton
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val QR_BITMAP_SIZE = 440

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarOwnerShareScreen(calendarId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    val vm: CalendarOwnerShareViewModel = viewModel(
        factory = CalendarOwnerShareViewModel.Factory(app, calendarId),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val conversationVm: ConversationListViewModel = viewModel(
        factory = ConversationListViewModel.Factory(app),
    )
    val conversations by conversationVm.rows.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var multi by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var resetConfirm by rememberSaveable { mutableStateOf(false) }
    val imageSavedText = stringResource(R.string.calendar_share_image_saved)
    val imageFailedText = stringResource(R.string.calendar_share_image_failed)
    val sentText = stringResource(R.string.calendar_share_sent)
    val operationFailedText = stringResource(R.string.calendar_operation_failed)

    LaunchedEffect(Unit) { conversationVm.refresh() }
    val filtered = remember(conversations, query) {
        conversations.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }
    val calendar = ui.calendar
    val link = ui.link
    val qr = remember(link?.url) { link?.url?.let(::generateCalendarQr) }
    LaunchedEffect(ui.error) {
        if (ui.error && calendar != null && link != null) {
            snackbar.showSnackbar(operationFailedText)
        }
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.calendar_share_title),
                onBack = onBack,
                actions = {
                    if (tab == 0) {
                        TextButton(onClick = {
                            multi = !multi
                            if (!multi) selected = selected.take(1).toSet()
                        }) { Text(stringResource(R.string.calendar_share_multi_select)) }
                    } else if (tab == 1) {
                        IconButton(onClick = { resetConfirm = true }) {
                            Icon(Icons.Filled.MoreVert, stringResource(R.string.calendar_reset_link))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (tab == 0 && calendar != null && link != null) {
                Surface(tonalElevation = Dimens.ElevationSticky) {
                    PrimaryButton(
                        text = if (selected.isEmpty()) {
                            stringResource(R.string.calendar_share_action)
                        } else {
                            stringResource(R.string.calendar_share_selected_count, selected.size)
                        },
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            selected.forEach { cid ->
                                ImSession.get(app).sendMessageAsync(
                                    cid,
                                    buildCalendarCard(calendar, link.url),
                                    "calendar-card",
                                )
                            }
                            selected = emptySet()
                            scope.launch { snackbar.showSnackbar(sentText) }
                        },
                        modifier = Modifier.padding(Dimens.ScreenPadding),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf(
                    R.string.calendar_share_tab_conversations,
                    R.string.calendar_share_tab_link,
                    R.string.calendar_share_tab_qr,
                ).forEachIndexed { index, label ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(stringResource(label)) },
                    )
                }
            }
            when {
                ui.loading && (calendar == null || link == null) -> WeMeetLoading()
                calendar == null || link == null -> WeMeetErrorState(onRetry = vm::load)
                tab == 0 -> {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.calendar_share_search_conversations)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(Dimens.ScreenPadding),
                    )
                    if (filtered.isEmpty()) {
                        WeMeetEmptyState(title = stringResource(R.string.calendar_share_no_conversations))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = Dimens.SpaceXl),
                        ) {
                            items(filtered, key = { it.cid }) { row ->
                                val checked = row.cid in selected
                                ListItem(
                                    headlineContent = { Text(row.title) },
                                    supportingContent = row.lastMessage?.let { message -> { Text(message, maxLines = 1) } },
                                    trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                                    modifier = Modifier.clickable {
                                        selected = when {
                                            checked -> selected - row.cid
                                            multi -> selected + row.cid
                                            else -> setOf(row.cid)
                                        }
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                tab == 1 -> ShareLinkContent(
                    calendarName = calendar.displayName,
                    owner = calendar.owner?.fullName,
                    url = link.url,
                    onCopy = { copyCalendarLink(context, link.url) },
                    onShare = { shareCalendarText(context, calendar.displayName, link.url) },
                )
                else -> ShareQrContent(
                    calendarName = calendar.displayName,
                    color = calendar.color,
                    bitmap = qr,
                    onSave = {
                        scope.launch {
                            val uri = qr?.let { saveCalendarQr(context, it, calendar.id) }
                            snackbar.showSnackbar(if (uri != null) imageSavedText else imageFailedText)
                        }
                    },
                    onShare = {
                        scope.launch {
                            val uri = qr?.let { saveCalendarQr(context, it, calendar.id) }
                            if (uri != null) shareCalendarImage(context, uri, calendar.displayName, link.url)
                            else snackbar.showSnackbar(imageFailedText)
                        }
                    },
                )
            }
        }
    }

    if (resetConfirm) {
        AlertDialog(
            onDismissRequest = { resetConfirm = false },
            title = { Text(stringResource(R.string.calendar_share_reset_confirm_title)) },
            text = { Text(stringResource(R.string.calendar_share_reset_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { resetConfirm = false; vm.resetLink() }) {
                    Text(stringResource(R.string.calendar_reset_link))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirm = false }) {
                    Text(stringResource(R.string.calendar_cancel))
                }
            },
        )
    }
}

@Composable
private fun ShareLinkContent(
    calendarName: String,
    owner: String?,
    url: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        CalendarShareHeader(calendarName, owner, Icons.Filled.Link)
        Text(
            stringResource(R.string.calendar_share_link_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(shape = RoundedCornerShape(Dimens.CornerM), tonalElevation = Dimens.ElevationSubtle) {
            Text(
                url,
                modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceL),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SecondaryButton(
            text = stringResource(R.string.calendar_copy_link),
            onClick = onCopy,
        )
        PrimaryButton(
            text = stringResource(R.string.calendar_share_system),
            onClick = onShare,
        )
    }
}

@Composable
private fun ShareQrContent(
    calendarName: String,
    color: String,
    bitmap: Bitmap?,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        CalendarShareHeader(calendarName, null, Icons.Filled.QrCode, color)
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.calendar_share_qr),
                modifier = Modifier.size(Dimens.Room.QrSize),
            )
        }
        Text(
            stringResource(R.string.calendar_share_qr_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            TextButton(onClick = onSave) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Text(stringResource(R.string.calendar_share_save_image))
            }
            TextButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text(stringResource(R.string.calendar_share_system))
            }
        }
    }
}

@Composable
private fun CalendarShareHeader(
    calendarName: String,
    owner: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: String? = null,
) {
    Surface(
        shape = RoundedCornerShape(Dimens.CornerM),
        color = (parseCalendarColor(color) ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.14f),
    ) {
        ListItem(
            headlineContent = { Text(calendarName, style = MaterialTheme.typography.titleMedium) },
            supportingContent = owner?.let { name -> { Text(name) } },
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        )
    }
}

private fun buildCalendarCard(calendar: com.we.meet.data.api.dto.UnifiedCalendarDto, url: String): String =
    JSONObject()
        .put("v", 1)
        .put("calendar_id", calendar.id)
        .put("name", calendar.displayName)
        .put("owner_name", calendar.owner?.fullName.orEmpty())
        .put("description", calendar.description)
        .put("subscriber_count", calendar.subscriberCount)
        .put("subscribe_url", url)
        .toString()

private fun generateCalendarQr(value: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, QR_BITMAP_SIZE, QR_BITMAP_SIZE)
    Bitmap.createBitmap(QR_BITMAP_SIZE, QR_BITMAP_SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until QR_BITMAP_SIZE) for (y in 0 until QR_BITMAP_SIZE) {
            bitmap.setPixel(
                x,
                y,
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
            )
        }
    }
}.getOrNull()

private fun copyCalendarLink(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("calendar", value))
}

private fun shareCalendarText(context: Context, name: String, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, name)
        putExtra(Intent.EXTRA_TEXT, "$name\n$url")
    }
    context.startActivity(Intent.createChooser(intent, null))
}

private suspend fun saveCalendarQr(context: Context, bitmap: Bitmap, calendarId: String): Uri? =
    withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "WeMeet-calendar-$calendarId-${Instant.now().epochSecond}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WeMeet")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            val written = resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            } == true
            if (!written) {
                resolver.delete(uri, null, null)
                return@runCatching null
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }.getOrNull()
    }

private fun shareCalendarImage(context: Context, uri: Uri, name: String, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "$name\n$url")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
