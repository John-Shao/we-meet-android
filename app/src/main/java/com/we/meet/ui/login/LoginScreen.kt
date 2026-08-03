package com.we.meet.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val viewModel: LoginViewModel = viewModel(factory = LoginViewModel.Factory(app))
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (!state.codeSent) {
        PhoneInputPage(
            state = state,
            onPhoneChange = viewModel::onPhoneChange,
            onNext = viewModel::sendOtp,
        )
    } else {
        OtpInputPage(
            state = state,
            onOtpChange = viewModel::onOtpChange,
            onResend = viewModel::sendOtp,
            onVerify = { viewModel.verifyOtp(onSuccess = onLoggedIn) },
            onBack = viewModel::goBackToPhone,
        )
    }
}

// ── Phone input page ─────────────────────────────────────────────────────

@Composable
private fun PhoneInputPage(
    state: LoginUiState,
    onPhoneChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimens.SpaceXxl),
        ) {
            Spacer(Modifier.height(Dimens.SpaceXxxl * 2))

            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Dimens.SpaceXxl))

            // Phone input row: +86 | phone number | clear
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Dimens.CornerS))
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // No region picker in scope — show a plain "+86" without the
                // dropdown caret that used to imply a (non-existent) selector.
                Text(
                    text = "+86",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(Dimens.SpaceM))

                Box(modifier = Modifier.weight(1f)) {
                    if (state.phone.isEmpty()) {
                        Text(
                            text = stringResource(R.string.login_phone_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = state.phone,
                        onValueChange = onPhoneChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (state.phone.isNotEmpty()) {
                    IconButton(
                        onClick = { onPhoneChange("") },
                        // 48dp hit target (accessibility minimum) around a 20dp
                        // glyph — the visible icon stays small.
                        modifier = Modifier.size(Dimens.IconIllustration),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = stringResource(R.string.cd_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Dimens.IconSmall),
                        )
                    }
                }
            }

            Spacer(Modifier.height(Dimens.SpaceXxl))

            // Next button
            val isPhoneValid = state.phone.length == 11
            Button(
                onClick = onNext,
                enabled = isPhoneValid && !state.isSendingOtp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.ButtonHeight),
                shape = CircleShape,
            ) {
                if (state.isSendingOtp) {
                    CircularProgressIndicator(
                        strokeWidth = Dimens.BorderEmphasis,
                        modifier = Modifier.size(Dimens.IconSmall),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.login_next),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Error message
            state.errorMessage?.let { rawMessage ->
                Spacer(Modifier.height(Dimens.SpaceL))
                val text = when (rawMessage) {
                    LoginViewModel.ErrorKey.PHONE_FORMAT.name -> stringResource(R.string.login_error_phone_format)
                    else -> rawMessage
                }
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ── OTP input page ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtpInputPage(
    state: LoginUiState,
    onOtpChange: (String) -> Unit,
    onResend: () -> Unit,
    onVerify: () -> Unit,
    onBack: () -> Unit,
) {
    // Auto-verify when 6 digits entered
    LaunchedEffect(state.otp) {
        if (state.otp.length == 6 && !state.isVerifying) {
            onVerify()
        }
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = "",
                onBack = onBack,
                transparent = true,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimens.SpaceXxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Dimens.SpaceXxxl))

            Text(
                text = stringResource(R.string.login_otp_sent_title),
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(Modifier.height(Dimens.SpaceS))

            Text(
                text = stringResource(R.string.login_otp_sent_to, "+86${state.phone}"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Dimens.SpaceXxl))

            // 6 digit boxes
            OtpBoxes(
                otp = state.otp,
                onOtpChange = onOtpChange,
            )

            Spacer(Modifier.height(Dimens.SpaceXl))

            // Resend button
            Button(
                onClick = onResend,
                enabled = state.resendCooldown == 0 && !state.isSendingOtp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.ButtonHeight),
                shape = CircleShape,
            ) {
                if (state.resendCooldown > 0) {
                    Text(
                        text = stringResource(R.string.login_resend_otp, state.resendCooldown),
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.login_resend),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Loading indicator
            if (state.isVerifying) {
                Spacer(Modifier.height(Dimens.SpaceXl))
                CircularProgressIndicator(modifier = Modifier.size(Dimens.AvatarS))
            }

            // Error message
            state.errorMessage?.let { rawMessage ->
                Spacer(Modifier.height(Dimens.SpaceL))
                val text = when (rawMessage) {
                    LoginViewModel.ErrorKey.OTP_FORMAT.name -> stringResource(R.string.login_error_otp_format)
                    else -> rawMessage
                }
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun OtpBoxes(
    otp: String,
    onOtpChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Hidden text field that captures input. Tapping the visible boxes must
    // re-focus it AND re-open the keyboard — otherwise, once the keyboard is
    // dismissed (system back / swipe-down) there was no way to bring it back
    // and the user was stuck unable to finish entering the code.
    Box(
        modifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
        ) {
            focusRequester.requestFocus()
            keyboard?.show()
        },
    ) {
        BasicTextField(
            value = TextFieldValue(otp, selection = TextRange(otp.length)),
            onValueChange = { onOtpChange(it.text.filter(Char::isDigit).take(6)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .focusRequester(focusRequester)
                .size(Dimens.HiddenFocusAnchor) // invisible but focusable
                .background(Color.Transparent),
        )

        // Visual boxes
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            modifier = Modifier.fillMaxWidth(),
        ) {
            repeat(6) { index ->
                val char = otp.getOrNull(index)?.toString() ?: ""
                val isCurrent = index == otp.length

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(Dimens.ListThumbnail)
                        .border(
                            width = if (isCurrent) Dimens.BorderEmphasis else Dimens.BorderThin,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(Dimens.CornerS),
                        )
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Dimens.CornerS)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = char,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }
    }
}
