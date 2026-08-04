package com.we.meet.feature.im.ui.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.vm.BotFormEvent
import com.we.meet.feature.im.vm.BotFormViewModel
import com.we.meet.ui.components.PrimaryButton
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme

private const val NAME_MAX = 32
private const val DESC_MAX = 256

/**
 * 自定义机器人表单 —— 新建与编辑同一套字段,所以同一个屏幕。
 *
 * 头像是「挑一个预设色」而不是上传:后端把色块渲染成真图,双端和推送通知都
 * 只读 avatar_url,谁也不用再实现一遍调色板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotFormScreen(
    deps: ImDeps,
    cid: String,
    botId: String?,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    onSaved: () -> Unit,
) {
    val vm: BotFormViewModel = viewModel(
        key = "botForm:$cid:${botId ?: "new"}",
        factory = BotFormViewModel.Factory(deps, cid, botId),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is BotFormEvent.Created -> onCreated(event.botId)
                BotFormEvent.Saved -> onSaved()
            }
        }
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.im_bots_form_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceL),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        ) {
            Text(
                text = stringResource(R.string.im_bots_form_avatar),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BotAvatar(
                    name = ui.name,
                    avatarUrl = null,
                    colorIndex = ui.colorIndex,
                    cacheKey = "im-bot-preview",
                )
                Spacer(Modifier.width(Dimens.SpaceXs))
                WeMeetTheme.extras.im.botAvatarPalette.forEachIndexed { index, color ->
                    val selectedDesc = stringResource(R.string.im_bots_form_color, index + 1)
                    Column(
                        modifier = Modifier
                            .size(Dimens.AvatarXs)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (index == ui.colorIndex) {
                                    Modifier.border(
                                        width = Dimens.BorderEmphasis,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape,
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { vm.onColor(index) }
                            .semantics { contentDescription = selectedDesc },
                    ) {}
                }
            }

            OutlinedTextField(
                value = ui.name,
                onValueChange = vm::onName,
                label = { Text(stringResource(R.string.im_bots_form_name)) },
                placeholder = { Text(stringResource(R.string.im_bots_form_name_hint)) },
                supportingText = {
                    Text(stringResource(R.string.im_bots_counter, ui.name.length, NAME_MAX))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = ui.description,
                onValueChange = vm::onDescription,
                label = { Text(stringResource(R.string.im_bots_form_desc)) },
                placeholder = { Text(stringResource(R.string.im_bots_form_desc_hint)) },
                supportingText = {
                    Text(
                        stringResource(
                            R.string.im_bots_counter,
                            ui.description.length,
                            DESC_MAX,
                        )
                    )
                },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            if (ui.error != null) {
                Text(
                    text = stringResource(ui.error!!),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(Dimens.SpaceS))
            PrimaryButton(
                text = stringResource(
                    if (vm.isEdit) R.string.im_bots_form_save else R.string.im_bots_form_submit
                ),
                onClick = vm::submit,
                enabled = ui.name.isNotBlank(),
                loading = ui.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
