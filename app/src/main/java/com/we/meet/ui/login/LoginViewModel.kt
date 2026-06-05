package com.we.meet.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.repository.AuthRepository
import com.we.meet.util.ErrorScope
import com.we.meet.util.toUserMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val PHONE_REGEX = Regex("^1[3-9]\\d{9}$")
private const val OTP_LENGTH = 6
private const val RESEND_COOLDOWN_SECONDS = 60

data class LoginUiState(
    val phone: String = "",
    val otp: String = "",
    val isSendingOtp: Boolean = false,
    val isVerifying: Boolean = false,
    val resendCooldown: Int = 0,
    val codeSent: Boolean = false,
    val errorMessage: String? = null,
    /** Phone number that the currently-running cooldown applies to. The
     *  cooldown only blocks [sendOtp] when the user is still targeting this
     *  same number; once they edit the phone input the block lifts. */
    val lastSentPhone: String? = null,
)

class LoginViewModel(
    application: Application,
    private val authRepository: AuthRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onPhoneChange(value: String) {
        _state.update { it.copy(phone = value.filter(Char::isDigit).take(11), errorMessage = null) }
    }

    fun onOtpChange(value: String) {
        _state.update { it.copy(otp = value.filter(Char::isDigit).take(OTP_LENGTH), errorMessage = null) }
    }

    fun goBackToPhone() {
        _state.update { it.copy(codeSent = false, otp = "", errorMessage = null) }
    }

    fun sendOtp() {
        val phone = _state.value.phone
        if (!PHONE_REGEX.matches(phone)) {
            _state.update { it.copy(errorMessage = ErrorKey.PHONE_FORMAT.name) }
            return
        }
        if (_state.value.isSendingOtp) return
        // Cooldown only blocks a resend when the target phone hasn't changed.
        // After 返回 + edit-phone, the user is requesting a fresh OTP for a
        // different number and should not be silently rate-limited.
        if (_state.value.resendCooldown > 0 && _state.value.lastSentPhone == phone) return

        _state.update { it.copy(isSendingOtp = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.sendOtp(phone).fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            isSendingOtp = false,
                            codeSent = true,
                            resendCooldown = RESEND_COOLDOWN_SECONDS,
                            lastSentPhone = phone,
                        )
                    }
                    runCooldown()
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(isSendingOtp = false, errorMessage = e.toUserMessage(getApplication(), ErrorScope.AUTH_SEND_OTP))
                    }
                },
            )
        }
    }

    fun verifyOtp(onSuccess: () -> Unit) {
        val (phone, otp) = _state.value.let { it.phone to it.otp }
        if (!PHONE_REGEX.matches(phone)) {
            _state.update { it.copy(errorMessage = ErrorKey.PHONE_FORMAT.name) }
            return
        }
        if (otp.length != OTP_LENGTH) {
            _state.update { it.copy(errorMessage = ErrorKey.OTP_FORMAT.name) }
            return
        }
        if (_state.value.isVerifying) return

        _state.update { it.copy(isVerifying = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.verifyOtp(phone, otp).fold(
                onSuccess = {
                    _state.update { it.copy(isVerifying = false) }
                    com.we.meet.analytics.Analytics.capture(
                        com.we.meet.analytics.Analytics.EVENT_LOGIN,
                        mapOf("method" to "phone-otp"),
                    )
                    com.we.meet.analytics.Analytics.identify(userId = phone)
                    onSuccess()
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(isVerifying = false, errorMessage = e.toUserMessage(getApplication(), ErrorScope.AUTH_VERIFY_OTP))
                    }
                },
            )
        }
    }

    private fun runCooldown() {
        viewModelScope.launch {
            while (_state.value.resendCooldown > 0) {
                delay(1000)
                _state.update { it.copy(resendCooldown = (it.resendCooldown - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Marker keys for local validation — screen translates them into
     *  localised strings. Backend errors go through [toUserMessage] and
     *  arrive as ready-to-display strings. */
    enum class ErrorKey { PHONE_FORMAT, OTP_FORMAT }

    class Factory(private val app: WeMeetApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LoginViewModel(app, app.authRepository) as T
    }
}
