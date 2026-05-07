package com.we.meet.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.WeMeetApp
import com.we.meet.R

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
                .padding(horizontal = 32.dp),
        ) {
            Spacer(Modifier.height(100.dp))

            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(40.dp))

            // Phone input row: +86 | phone number | clear
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "+86",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = " ▾",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))

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
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // Next button
            val isPhoneValid = state.phone.length == 11
            Button(
                onClick = onNext,
                enabled = isPhoneValid && !state.isSendingOtp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
            ) {
                if (state.isSendingOtp) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
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
                Spacer(Modifier.height(16.dp))
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
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
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
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            Text(
                text = stringResource(R.string.login_otp_sent_title),
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.login_otp_sent_to, "+86${state.phone}"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            // 6 digit boxes
            OtpBoxes(
                otp = state.otp,
                onOtpChange = onOtpChange,
            )

            Spacer(Modifier.height(24.dp))

            // Resend button
            Button(
                onClick = onResend,
                enabled = state.resendCooldown == 0 && !state.isSendingOtp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
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
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
            }

            // Error message
            state.errorMessage?.let { rawMessage ->
                Spacer(Modifier.height(16.dp))
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

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Hidden text field that captures input
    Box {
        BasicTextField(
            value = TextFieldValue(otp, selection = TextRange(otp.length)),
            onValueChange = { onOtpChange(it.text.filter(Char::isDigit).take(6)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .focusRequester(focusRequester)
                .size(1.dp) // invisible but focusable
                .background(Color.Transparent),
        )

        // Visual boxes
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            repeat(6) { index ->
                val char = otp.getOrNull(index)?.toString() ?: ""
                val isCurrent = index == otp.length

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .border(
                            width = if (isCurrent) 2.dp else 1.dp,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = char,
                        style = MaterialTheme.typography.headlineSmall,
                        fontSize = 24.sp,
                    )
                }
            }
        }
    }
}
