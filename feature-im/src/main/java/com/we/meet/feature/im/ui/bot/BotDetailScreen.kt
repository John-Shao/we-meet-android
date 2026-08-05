package com.we.meet.feature.im.ui.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.ui.common.ImActionRow
import com.we.meet.feature.im.ui.common.ImSwitchRow
import com.we.meet.feature.im.vm.BotDetailViewModel
import com.we.meet.feature.im.vm.callbackFailureLabel
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import kotlinx.coroutines.launch

/**
 * 一个机器人:身份、webhook 地址、三道安全闸门、移除。
 *
 * 内置助手只显示「在本群启用」—— 它们随产品提供,改名或移除会影响每一个群。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BotDetailScreen(
    deps: ImDeps,
    cid: String,
    botId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRemoved: () -> Unit,
) {
    val vm: BotDetailViewModel = viewModel(
        key = "botDetail:$botId",
        factory = BotDetailViewModel.Factory(deps, cid, botId),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedLabel = stringResource(R.string.im_bots_webhook_copied)

    var confirmRemove by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var keywordDraft by remember { mutableStateOf("") }
    var ipDraft by remember { mutableStateOf<String?>(null) }
    var callbackDraft by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(ui.removed) { if (ui.removed) onRemoved() }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.im_bots_title),
                onBack = onBack,
                actions = {
                    val bot = ui.bot
                    if (bot != null && bot.canManage && !bot.isBuiltin) {
                        TextButton(onClick = onEdit) {
                            Text(stringResource(R.string.im_bots_edit))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val bot = ui.bot
        when {
            ui.loading -> WeMeetLoading(Modifier.padding(padding))
            bot == null -> WeMeetErrorState(
                onRetry = vm::refresh,
                message = stringResource(R.string.im_bots_gone),
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceL),
                ) {
                    BotAvatar(
                        name = bot.name,
                        avatarUrl = bot.avatarUrl,
                        colorIndex = bot.colorIndex,
                        cacheKey = "im-bot:${bot.id}",
                    )
                    Spacer(Modifier.width(Dimens.SpaceM))
                    Column(Modifier.weight(1f)) {
                        Text(bot.name, style = MaterialTheme.typography.titleMedium)
                        if (bot.description.isNotBlank()) {
                            Text(
                                text = bot.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()

                if (bot.isBuiltin) {
                    Text(
                        text = stringResource(R.string.im_bots_builtin_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = Dimens.SpaceXl,
                            vertical = Dimens.SpaceM,
                        ),
                    )
                    if (bot.canManage) {
                        ImSwitchRow(
                            label = stringResource(R.string.im_bots_enabled),
                            checked = bot.isActive,
                            enabled = !ui.busy,
                            onToggle = { vm.setActive(!bot.isActive) },
                        )
                    }
                } else if (!bot.canManage) {
                    Text(
                        text = stringResource(R.string.im_bots_owner_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = Dimens.SpaceXl,
                            vertical = Dimens.SpaceM,
                        ),
                    )
                } else {
                    SectionLabel(stringResource(R.string.im_bots_webhook_title))
                    Text(
                        text = bot.webhookUrl.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = Dimens.SpaceXl),
                    )
                    Row(modifier = Modifier.padding(horizontal = Dimens.SpaceL)) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(bot.webhookUrl.orEmpty()))
                            scope.launch { snackbar.showSnackbar(copiedLabel) }
                        }) {
                            Text(stringResource(R.string.im_bots_webhook_copy))
                        }
                    }
                    Hint(stringResource(R.string.im_bots_webhook_hint))
                    HorizontalDivider()

                    SectionLabel(stringResource(R.string.im_bots_security_title))
                    Hint(stringResource(R.string.im_bots_security_hint))
                    ImSwitchRow(
                        label = stringResource(R.string.im_bots_security_sign),
                        checked = bot.signVerifyEnabled,
                        enabled = !ui.busy,
                        onToggle = { vm.setSignVerify(!bot.signVerifyEnabled) },
                    )
                    if (bot.signVerifyEnabled) {
                        SectionLabel(stringResource(R.string.im_bots_security_secret))
                        Text(
                            text = ui.secret ?: "••••••••••••",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = Dimens.SpaceXl),
                        )
                        Row(modifier = Modifier.padding(horizontal = Dimens.SpaceL)) {
                            TextButton(onClick = vm::toggleSecret) {
                                Text(
                                    stringResource(
                                        if (ui.secret != null) {
                                            R.string.im_bots_security_hide
                                        } else {
                                            R.string.im_bots_security_show
                                        }
                                    )
                                )
                            }
                            ui.secret?.let { secret ->
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(secret))
                                    scope.launch { snackbar.showSnackbar(copiedLabel) }
                                }) {
                                    Text(stringResource(R.string.im_bots_webhook_copy))
                                }
                            }
                            TextButton(onClick = { confirmReset = true }) {
                                Text(stringResource(R.string.im_bots_security_reset))
                            }
                        }
                    }

                    SectionLabel(stringResource(R.string.im_bots_security_keywords))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                        modifier = Modifier.padding(horizontal = Dimens.SpaceXl),
                    ) {
                        bot.keywords.forEach { word ->
                            AssistChip(
                                onClick = { vm.setKeywords(bot.keywords - word) },
                                label = { Text(word) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = keywordDraft,
                        onValueChange = { keywordDraft = it },
                        placeholder = {
                            Text(stringResource(R.string.im_bots_security_keyword_hint))
                        },
                        singleLine = true,
                        trailingIcon = {
                            TextButton(
                                enabled = keywordDraft.isNotBlank() && !ui.busy,
                                onClick = {
                                    val word = keywordDraft.trim()
                                    if (word.isNotEmpty() && word !in bot.keywords) {
                                        vm.setKeywords(bot.keywords + word)
                                    }
                                    keywordDraft = ""
                                },
                            ) {
                                Text(stringResource(R.string.im_bots_security_save))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.SpaceXl),
                    )
                    Hint(stringResource(R.string.im_bots_security_keywords_hint))

                    SectionLabel(stringResource(R.string.im_bots_security_ip))
                    OutlinedTextField(
                        value = ipDraft ?: bot.ipAllowlist.joinToString("\n"),
                        onValueChange = { ipDraft = it },
                        minLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.SpaceXl),
                    )
                    Hint(stringResource(R.string.im_bots_security_ip_hint))
                    if (ipDraft != null) {
                        Row(modifier = Modifier.padding(horizontal = Dimens.SpaceL)) {
                            TextButton(
                                enabled = !ui.busy,
                                onClick = {
                                    vm.setIpAllowlist(
                                        ipDraft.orEmpty()
                                            .lines()
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() },
                                    )
                                    ipDraft = null
                                },
                            ) {
                                Text(stringResource(R.string.im_bots_security_save))
                            }
                        }
                    }

                    // 出站回调 (A3)。地址挂在**机器人**上而不是按钮里 ——
                    // 按钮里带 URL 等于任何拿到 webhook token 的人都能把我们的
                    // 服务器变成任意 HTTP 代理。这是 SSRF 面上最重要的一刀。
                    SectionLabel(stringResource(R.string.im_bots_callback_title))
                    OutlinedTextField(
                        value = callbackDraft ?: bot.callbackUrl,
                        onValueChange = { callbackDraft = it },
                        placeholder = {
                            Text(stringResource(R.string.im_bots_callback_placeholder))
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.SpaceXl),
                    )
                    Hint(stringResource(R.string.im_bots_callback_hint))
                    if (callbackDraft != null) {
                        Row(modifier = Modifier.padding(horizontal = Dimens.SpaceL)) {
                            TextButton(
                                enabled = !ui.busy,
                                onClick = {
                                    // 地址不合法后端 400,错误行当场亮出来 ——
                                    // 而不是配完之后每次点击都静默失败。
                                    vm.setCallbackUrl(callbackDraft.orEmpty())
                                    callbackDraft = null
                                },
                            ) {
                                Text(stringResource(R.string.im_bots_security_save))
                            }
                        }
                    }
                    if (!bot.callbackEnabled) {
                        Text(
                            text = stringResource(R.string.im_bots_callback_disabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = WeMeetTheme.extras.status.onDangerContainer,
                            modifier = Modifier
                                .padding(
                                    horizontal = Dimens.SpaceXl,
                                    vertical = Dimens.SpaceXs,
                                )
                                .background(
                                    color = WeMeetTheme.extras.status.dangerContainer,
                                    shape = RoundedCornerShape(Dimens.CornerS),
                                )
                                .padding(Dimens.SpaceM),
                        )
                    }
                    if (bot.callbackLastError.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.im_bots_callback_last_error,
                                stringResource(callbackFailureLabel(bot.callbackLastError)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = WeMeetTheme.extras.status.onDangerContainer,
                            modifier = Modifier.padding(
                                horizontal = Dimens.SpaceXl,
                                vertical = Dimens.SpaceXs,
                            ),
                        )
                    }
                    if (bot.callbackUrl.isNotEmpty()) {
                        // 没有这个字段,出站签名就只是装饰 —— 接收方拿不到密钥
                        // 就验不了,只能退回「URL 保密」。
                        SectionLabel(stringResource(R.string.im_bots_callback_secret))
                        Text(
                            text = ui.callbackSecret ?: "••••••••••••", // i18n-exempt
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = Dimens.SpaceXl),
                        )
                        Row(modifier = Modifier.padding(horizontal = Dimens.SpaceL)) {
                            TextButton(onClick = vm::toggleCallbackSecret) {
                                Text(
                                    stringResource(
                                        if (ui.callbackSecret != null) {
                                            R.string.im_bots_security_hide
                                        } else {
                                            R.string.im_bots_security_show
                                        }
                                    )
                                )
                            }
                            ui.callbackSecret?.let { secret ->
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(secret))
                                    scope.launch { snackbar.showSnackbar(copiedLabel) }
                                }) {
                                    Text(stringResource(R.string.im_bots_webhook_copy))
                                }
                            }
                        }
                        Hint(stringResource(R.string.im_bots_callback_secret_hint))
                        ImSwitchRow(
                            label = stringResource(R.string.im_bots_callback_identity),
                            checked = bot.callbackIncludeIdentity,
                            enabled = !ui.busy,
                            onToggle = {
                                vm.setCallbackIdentity(!bot.callbackIncludeIdentity)
                            },
                        )
                        Hint(stringResource(R.string.im_bots_callback_identity_hint))
                    }

                    HorizontalDivider()
                    ImActionRow(
                        text = stringResource(R.string.im_bots_remove),
                        destructive = true,
                        onClick = { confirmRemove = true },
                    )
                }

                ui.error?.let { error ->
                    Text(
                        text = stringResource(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(
                            horizontal = Dimens.SpaceXl,
                            vertical = Dimens.SpaceM,
                        ),
                    )
                }
            }
        }
    }

    if (confirmRemove) {
        val name = ui.bot?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            text = { Text(stringResource(R.string.im_bots_remove_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    vm.remove()
                }) {
                    Text(stringResource(R.string.im_bots_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            text = { Text(stringResource(R.string.im_bots_security_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetSecret()
                }) {
                    Text(stringResource(R.string.im_bots_security_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(
            start = Dimens.SpaceXl,
            end = Dimens.SpaceXl,
            top = Dimens.SpaceM,
            bottom = Dimens.SpaceXs,
        ),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            horizontal = Dimens.SpaceXl,
            vertical = Dimens.SpaceXs,
        ),
    )
}
