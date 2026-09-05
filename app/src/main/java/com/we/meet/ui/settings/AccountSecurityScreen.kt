package com.we.meet.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * 账号与安全 — the account-scoped surface: the read-only bound phone number and
 * the destructive account-deregister flow, split out of Settings so those
 * account knobs live together rather than mixed in with device-wide
 * preferences (theme / language / codec).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSecurityScreen(
    onBack: () -> Unit,
    onAccountDeregistered: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val phone = app.tokenStore.phone.orEmpty()
    var showDeregisterDialog by remember { mutableStateOf(false) }
    var deregistering by remember { mutableStateOf(false) }
    var deregisterError by remember { mutableStateOf<String?>(null) }
    val deregisterFailedText = stringResource(R.string.profile_deregister_error)
    val scope = rememberCoroutineScope()

    var backPending by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.settings_account_security),
                onBack = { if (!backPending) { backPending = true; onBack() } },
                transparent = true,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            PhoneSection(phone = phone)
            DeregisterSection(
                onDeregisterClick = {
                    deregisterError = null
                    showDeregisterDialog = true
                },
            )
        }
    }

    if (showDeregisterDialog) {
        DeregisterDialog(
            expectedPhone = phone,
            inFlight = deregistering,
            errorMessage = deregisterError,
            onConfirm = {
                deregistering = true
                deregisterError = null
                scope.launch {
                    app.profileRepository.deregister()
                        .onSuccess {
                            showDeregisterDialog = false
                            deregistering = false
                            com.we.meet.analytics.Analytics.reset()
                            onAccountDeregistered()
                        }
                        .onFailure {
                            deregistering = false
                            deregisterError = deregisterFailedText
                        }
                }
            },
            onDismiss = {
                if (!deregistering) {
                    deregisterError = null
                    showDeregisterDialog = false
                }
            },
        )
    }
}

@Composable
private fun PhoneSection(phone: String) {
    Spacer(Modifier.height(Dimens.SpaceS))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.profile_phone),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = phone.ifBlank { stringResource(R.string.profile_not_set) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(Dimens.SpaceL))
}

@Composable
private fun DeregisterSection(
    onDeregisterClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDeregisterClick)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.profile_deregister),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Spacer(Modifier.height(Dimens.SpaceL))
}

/**
 * High-friction account-delete dialog — the confirm button only enables when
 * the user re-types their bound phone number, matching the desktop client's
 * destructive-action gate.
 */
@Composable
private fun DeregisterDialog(
    expectedPhone: String,
    inFlight: Boolean,
    errorMessage: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val matches = input.trim() == expectedPhone

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_deregister_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                Text(
                    text = stringResource(R.string.profile_deregister_warning),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.profile_deregister_phone_hint))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !inFlight,
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = matches && !inFlight,
            ) {
                if (inFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.IconSmall),
                        strokeWidth = Dimens.BorderEmphasis,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.profile_deregister_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
