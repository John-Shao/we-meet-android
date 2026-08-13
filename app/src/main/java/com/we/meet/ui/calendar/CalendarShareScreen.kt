package com.we.meet.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.UnifiedCalendarDto
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.components.PrimaryButton
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** Login-protected, live-permission preview for a signed calendar share link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarShareScreen(token: String, onBack: () -> Unit, onSubscribed: () -> Unit) {
    val api = (LocalContext.current.applicationContext as WeMeetApp).apiClient.calendarApi
    val scope = rememberCoroutineScope()
    var calendar by remember { mutableStateOf<UnifiedCalendarDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var subscribing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var invalidLink by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(token, reload) {
        loading = true
        calendar = null
        loadError = false
        invalidLink = false
        runCatching { api.previewCalendarShare(token) }
            .onSuccess { calendar = it }
            .onFailure { failure ->
                invalidLink = failure is HttpException && failure.code() in setOf(404, 410)
                loadError = true
            }
        loading = false
    }
    Scaffold(
        topBar = {
            WeMeetTopBar(title = stringResource(R.string.calendar_subscribe_title), onBack = onBack)
        },
        bottomBar = {
            calendar?.let { value ->
                Surface(tonalElevation = Dimens.ElevationSticky) {
                    PrimaryButton(
                        text = stringResource(
                            if (value.subscribed) R.string.calendar_subscribed
                            else R.string.calendar_subscribe_confirm,
                        ),
                        enabled = !value.subscribed && !subscribing,
                        loading = subscribing,
                        onClick = {
                            subscribing = true
                            submitError = false
                            scope.launch {
                                runCatching { api.subscribeCalendarShare(token) }
                                    .onSuccess { onSubscribed() }
                                    .onFailure { failure ->
                                        if (failure is HttpException && failure.code() == 409) {
                                            calendar = calendar?.copy(subscribed = true)
                                        } else {
                                            submitError = true
                                        }
                                        subscribing = false
                                    }
                            }
                        },
                        modifier = Modifier.padding(Dimens.ScreenPadding),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
        ) {
            when {
                loading -> WeMeetLoading()
                loadError || calendar == null -> WeMeetErrorState(
                    onRetry = { reload++ },
                    message = stringResource(
                        if (invalidLink) R.string.calendar_share_invalid
                        else R.string.calendar_operation_failed,
                    ),
                )
                else -> {
                    val value = calendar!!
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimens.CornerM),
                        tonalElevation = Dimens.ElevationSubtle,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceL),
                            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
                        ) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                CalendarAvatar(value.displayName, value.color)
                                Column(Modifier.padding(start = Dimens.SpaceM)) {
                                    Text(value.displayName, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                                    value.owner?.let {
                                        Text(stringResource(R.string.calendar_owner, it.fullName ?: it.shortName.orEmpty()))
                                    }
                                }
                            }
                            if (value.description.isNotBlank()) Text(value.description)
                        }
                    }
                    Text(stringResource(R.string.calendar_share_permission_live))
                    if (submitError) {
                        Text(
                            stringResource(R.string.calendar_operation_failed),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
