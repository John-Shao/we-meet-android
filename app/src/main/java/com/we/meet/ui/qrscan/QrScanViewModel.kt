package com.we.meet.ui.qrscan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.QrScanUser
import com.we.meet.data.repository.QrLoginRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the QR-login confirmation flow after the user has scanned a code.
 *
 * State graph:
 *
 *     Idle  ── onScanned(qr) ──▶  Scanning ── server rejects ─▶ Error
 *                                   │                              │
 *                                   ├── ok ──▶ ReadyToConfirm ─────┘
 *                                   │                │
 *                                   │           confirm() │ cancel()
 *                                   │                ▼        ▼
 *                                   │            Confirmed  Cancelled  Error
 *
 * The screen calls [onScanned] from inside the ActivityResult callback,
 * [confirm] / [cancel] from button presses, and observes [state] to render
 * the right UI. Terminal states are absorbing — the screen routes back to
 * Home on any of them.
 */
class QrScanViewModel(
    private val qrLoginRepository: QrLoginRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<QrScanState>(QrScanState.Idle)
    val state: StateFlow<QrScanState> = _state.asStateFlow()

    /**
     * Called with the raw text decoded from the QR. We accept the
     * web → app handshake URL  `we-meet://qr-login?token=<32hex>` only;
     * anything else (random URL, plain text, wrong scheme) lands in the
     * Error state without burning a /scan/ call on the backend.
     */
    fun onScanned(rawText: String) {
        val token = parseQrToken(rawText)
        if (token == null) {
            _state.value = QrScanState.Error(reason = ErrorReason.INVALID_QR)
            return
        }
        _state.value = QrScanState.Scanning(token = token)
        viewModelScope.launch {
            qrLoginRepository.scan(token).fold(
                onSuccess = { user ->
                    _state.value = QrScanState.ReadyToConfirm(token = token, user = user)
                },
                onFailure = {
                    _state.value = QrScanState.Error(reason = ErrorReason.SCAN_FAILED)
                },
            )
        }
    }

    /** Confirm the login. Server mints fresh web tokens via Token Exchange. */
    fun confirm() {
        val current = _state.value as? QrScanState.ReadyToConfirm ?: return
        _state.update { (it as QrScanState.ReadyToConfirm).copy(isPending = true) }
        viewModelScope.launch {
            qrLoginRepository.confirm(current.token).fold(
                onSuccess = { _state.value = QrScanState.Confirmed },
                onFailure = {
                    _state.value = QrScanState.Error(reason = ErrorReason.CONFIRM_FAILED)
                },
            )
        }
    }

    /** Decline the login. Best-effort fire-and-forget on the server. */
    fun cancel() {
        val current = _state.value as? QrScanState.ReadyToConfirm
        // Either way the screen closes; cancel on the server is best-effort.
        _state.value = QrScanState.Cancelled
        if (current != null) {
            viewModelScope.launch { qrLoginRepository.cancel(current.token) }
        }
    }

    class Factory(private val app: WeMeetApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            QrScanViewModel(qrLoginRepository = app.qrLoginRepository) as T
    }
}

/** Discriminated state for the QR confirmation screen. */
sealed class QrScanState {
    /** Mounted but scanner activity hasn't returned yet. */
    object Idle : QrScanState()

    /** Scanner returned a token; calling /scan/ to fetch the user. */
    data class Scanning(val token: String) : QrScanState()

    /** /scan/ succeeded — render confirmation card with user info. */
    data class ReadyToConfirm(
        val token: String,
        val user: QrScanUser,
        val isPending: Boolean = false,
    ) : QrScanState()

    object Confirmed : QrScanState()
    object Cancelled : QrScanState()
    data class Error(val reason: ErrorReason) : QrScanState()
}

enum class ErrorReason { INVALID_QR, SCAN_FAILED, CONFIRM_FAILED }

private val TOKEN_RE = Regex("[a-f0-9]{32}")

/**
 * Extract the QR token from a raw scan result. Returns null if the input
 * isn't a recognised we-meet handshake URL.
 *
 * Accepts both the canonical `we-meet://qr-login?token=...` and the same
 * shape served from a normal https URL, in case we later add App Links and
 * the same QR is opened by the system browser → us via deep link.
 */
internal fun parseQrToken(raw: String): String? {
    val trimmed = raw.trim()
    val token = when {
        trimmed.startsWith("we-meet://qr-login") ||
            trimmed.contains("/qr-login") -> {
            // Pull `token=<hex>` out without a full URL parser — the format is
            // tightly constrained on the backend (secrets.token_hex(16)) so a
            // regex match is both faster and more forgiving of non-RFC URIs.
            Regex("[?&]token=([a-f0-9]{32})").find(trimmed)?.groupValues?.getOrNull(1)
        }
        else -> null
    }
    return token?.takeIf { TOKEN_RE.matches(it) }
}
