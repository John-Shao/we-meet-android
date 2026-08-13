package com.we.meet.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.UnifiedCalendarDto
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

/** Login-protected, live-permission preview for a signed calendar share link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarShareScreen(token: String, onBack: () -> Unit, onSubscribed: () -> Unit) {
    val api = (LocalContext.current.applicationContext as WeMeetApp).apiClient.calendarApi
    val scope = rememberCoroutineScope()
    var calendar by remember { mutableStateOf<UnifiedCalendarDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var subscribing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(token) {
        runCatching { api.previewCalendarShare(token) }
            .onSuccess { calendar = it }
            .onFailure { error = true }
        loading = false
    }
    Scaffold(topBar = { WeMeetTopBar(title = stringResource(R.string.calendar_subscribe_title), onBack = onBack) }) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).padding(Dimens.ScreenPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator()
                error || calendar == null -> Text(
                    stringResource(R.string.calendar_share_invalid),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Column(
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(calendar!!.displayName, style = MaterialTheme.typography.headlineSmall)
                    calendar!!.owner?.let {
                        Text(stringResource(R.string.calendar_owner, it.fullName ?: it.shortName.orEmpty()))
                    }
                    if (calendar!!.description.isNotBlank()) Text(calendar!!.description)
                    Text(stringResource(R.string.calendar_share_permission_live))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !subscribing,
                        onClick = {
                            subscribing = true
                            scope.launch {
                                runCatching { api.subscribeCalendarShare(token) }
                                    .onSuccess { onSubscribed() }
                                    .onFailure { error = true; subscribing = false }
                            }
                        },
                    ) { Text(stringResource(R.string.calendar_subscribe_confirm)) }
                }
            }
        }
    }
}
