package com.we.meet.ui.qrscan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.ui.theme.Dimens

/**
 * Web → app QR login confirmation flow.
 *
 *   1. Mount → launch ZXing CaptureActivity via ActivityResult (no Compose
 *      camera plumbing needed; ZXing self-hosts its scanner Activity).
 *   2. Scan returns → ViewModel parses we-meet://qr-login?token=... and
 *      calls /scan/ to fetch the {phone, name} echoed back from Keycloak.
 *   3. User taps "确认登录" → /confirm/ → web side polls success and lands
 *      in the logged-in home.
 *   4. User taps "取消" or backs out → /cancel/ best-effort.
 *
 * Confirmed and cancelled states route back through [onDone]. Errors remain
 * visible so users can retry in place or explicitly leave the flow.
 */
@Composable
fun QrScanScreen(onDone: (QrScanResult) -> Unit) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val vm: QrScanViewModel = viewModel(factory = QrScanViewModel.Factory(app))
    val state by vm.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var scannerLaunched by remember { mutableStateOf(false) }
    // Camera permission denied: ZXing returns contents==null for BOTH a denial
    // and a user back-press, so we gate on the permission ourselves and show a
    // distinct denied state instead of silently popping (which looped forever).
    var permissionDenied by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text == null) {
            // User pressed back in the scanner before reading anything.
            onDone(QrScanResult.Cancelled)
        } else {
            val token = calendarShareToken(text)
            if (token != null) onDone(QrScanResult.CalendarSubscription(token))
            else vm.onScanned(text)
        }
    }

    fun launchScanner() {
        scannerLaunched = true
        scanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("")            // we don't want the legacy footer text
                setBeepEnabled(false)
                // Respect the current device posture. Locking the scanner to
                // portrait letterboxes it on tablets and foldables.
                setOrientationLocked(false)
                setBarcodeImageEnabled(false)
                // Use our portrait subclass; ZXing's default
                // CaptureActivity is locked to sensorLandscape via its
                // own manifest entry, which would flip the device.
                setCaptureActivity(PortraitCaptureActivity::class.java)
            }
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionDenied = false
            launchScanner()
        } else {
            permissionDenied = true
        }
    }

    LaunchedEffect(Unit) {
        if (!scannerLaunched && !permissionDenied) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) launchScanner() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Terminal-state side effects — dispatch the result up to the host.
    LaunchedEffect(state) {
        when (state) {
            is QrScanState.Confirmed -> onDone(QrScanResult.Confirmed)
            is QrScanState.Cancelled -> onDone(QrScanResult.Cancelled)
            else -> {} // non-terminal — keep rendering
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(Dimens.SpaceXl), contentAlignment = Alignment.Center) {
            if (permissionDenied) {
                PermissionDeniedCard(
                    onOpenSettings = { openAppSettings(context) },
                    onBack = { onDone(QrScanResult.Cancelled) },
                )
                return@Box
            }
            when (val s = state) {
                is QrScanState.Idle, is QrScanState.Scanning -> Loading()
                is QrScanState.ReadyToConfirm -> ConfirmCard(
                    phone = s.user.phone,
                    name = s.user.name,
                    isPending = s.isPending,
                    onConfirm = vm::confirm,
                    onCancel = vm::cancel,
                )
                is QrScanState.Error -> ErrorCard(
                    reason = s.reason,
                    onRetry = {
                        if (s.reason == ErrorReason.INVALID_QR) {
                            vm.prepareForRescan()
                            launchScanner()
                        } else {
                            vm.retry()
                        }
                    },
                    onDismiss = { onDone(QrScanResult.Error(s.reason)) },
                )
                // Confirmed / Cancelled handled by the LaunchedEffect above —
                // the screen will be popped before this branch repaints.
                else -> Loading()
            }
        }
    }
}

@Composable
private fun Loading() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ConfirmCard(
    phone: String,
    name: String,
    isPending: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        Text(
            text = stringResource(R.string.qr_scan_confirm_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = name.ifBlank { phone },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (name.isNotBlank() && phone.isNotBlank()) {
            Text(
                text = phone,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(Dimens.SpaceS))
        Text(
            text = stringResource(R.string.qr_scan_confirm_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.SpaceL))
        Button(
            onClick = onConfirm,
            enabled = !isPending,
            modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeight),
        ) {
            if (isPending) {
                CircularProgressIndicator(
                    strokeWidth = Dimens.BorderEmphasis,
                    modifier = Modifier.height(Dimens.IconSmall),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.qr_scan_confirm_button))
            }
        }
        OutlinedButton(
            onClick = onCancel,
            enabled = !isPending,
            modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeight),
        ) {
            Text(stringResource(R.string.qr_scan_cancel_button))
        }
    }
}

@Composable
private fun ErrorCard(
    reason: ErrorReason,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = when (reason) {
        ErrorReason.INVALID_QR -> stringResource(R.string.qr_scan_error_invalid)
        ErrorReason.SCAN_FAILED -> stringResource(R.string.qr_scan_error_scan_failed)
        ErrorReason.CONFIRM_FAILED -> stringResource(R.string.qr_scan_error_confirm_failed)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeight),
        ) {
            Text(
                stringResource(
                    if (reason == ErrorReason.INVALID_QR) R.string.qr_scan_rescan_button
                    else R.string.qr_scan_retry_button,
                ),
            )
        }
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeight),
        ) {
            Text(stringResource(R.string.qr_scan_back_button))
        }
    }
}

@Composable
private fun PermissionDeniedCard(onOpenSettings: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        Text(
            text = stringResource(R.string.qr_scan_permission_denied),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeight),
        ) {
            Text(stringResource(R.string.preview_open_settings))
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeight),
        ) {
            Text(stringResource(R.string.qr_scan_back_button))
        }
    }
}

/** Open this app's system settings so the user can grant a denied permission. */
private fun openAppSettings(context: android.content.Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Terminal result reported to the calling navigation host. */
sealed class QrScanResult {
    object Confirmed : QrScanResult()
    object Cancelled : QrScanResult()
    data class Error(val reason: ErrorReason) : QrScanResult()
    data class CalendarSubscription(val token: String) : QrScanResult()
}

internal fun calendarShareToken(value: String): String? {
    val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return null
    val segments = uri.pathSegments.orEmpty()
    return if (
        uri.scheme in setOf("http", "https") && segments.size >= 3 &&
        segments[0] == "calendar" && segments[1] == "subscribe"
    ) {
        segments[2].takeIf { it.isNotBlank() }
    } else {
        null
    }
}
