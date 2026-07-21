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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
            TopAppBar(
                title = { Text(stringResource(R.string.settings_account_security)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!backPending) { backPending = true; onBack() }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                onDeregisterClick = { showDeregisterDialog = true },
                errorMessage = deregisterError,
            )
        }
    }

    if (showDeregisterDialog) {
        DeregisterDialog(
            expectedPhone = phone,
            inFlight = deregistering,
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
                            showDeregisterDialog = false
                        }
                }
            },
            onDismiss = {
                if (!deregistering) showDeregisterDialog = false
            },
        )
    }
}

@Composable
private fun PhoneSection(phone: String) {
    Spacer(Modifier.height(8.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
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

    Spacer(Modifier.height(16.dp))
}

@Composable
private fun DeregisterSection(
    onDeregisterClick: () -> Unit,
    errorMessage: String?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDeregisterClick)
                .padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
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

    if (errorMessage != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
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
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val matches = input.trim() == expectedPhone

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_deregister_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = matches && !inFlight,
            ) {
                if (inFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
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
