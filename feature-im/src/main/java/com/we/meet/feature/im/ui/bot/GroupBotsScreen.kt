package com.we.meet.feature.im.ui.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.vm.GroupBotsViewModel
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens

/**
 * 群机器人列表(对标飞书:群设置 → 群机器人)。
 *
 * 非群主看得到群里有哪些机器人,但没有「添加」按钮、也拿不到凭据 —— 知道谁在
 * 群里说话,和能以它的身份说话,是两件事。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupBotsScreen(
    deps: ImDeps,
    cid: String,
    onBack: () -> Unit,
    onAddBot: () -> Unit,
    onOpenBot: (String) -> Unit,
) {
    val vm: GroupBotsViewModel = viewModel(
        key = "bots:$cid",
        factory = GroupBotsViewModel.Factory(deps, cid),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    var helpOpen by remember { mutableStateOf(false) }

    // NavHost 只组合栈顶目的地,所以从表单/详情返回时这里会重新进入组合 ——
    // 列表靠这次重跑刷新,而不是靠一个会过期的缓存。
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.im_bots_title),
                onBack = onBack,
                actions = {
                    TextButton(onClick = { helpOpen = true }) {
                        Text(stringResource(R.string.im_bots_help))
                    }
                    if (ui.canManage) {
                        TextButton(onClick = onAddBot) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(Dimens.SpaceXs))
                            Text(stringResource(R.string.im_bots_add))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> WeMeetLoading()
                ui.error != null -> WeMeetErrorState(
                    onRetry = vm::refresh,
                    message = stringResource(ui.error!!),
                )
                ui.bots.isEmpty() -> WeMeetEmptyState(
                    title = stringResource(R.string.im_bots_empty),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.bots, key = { it.id }) { bot ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenBot(bot.id) }
                                .padding(
                                    horizontal = Dimens.SpaceXl,
                                    vertical = Dimens.SpaceM,
                                ),
                        ) {
                            BotAvatar(
                                name = bot.name,
                                avatarUrl = bot.avatarUrl,
                                colorIndex = bot.colorIndex,
                                cacheKey = "im-bot:${bot.id}",
                            )
                            Spacer(Modifier.width(Dimens.SpaceM))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = if (bot.isActive) {
                                        bot.name
                                    } else {
                                        "${bot.name} · ${stringResource(R.string.im_bots_disabled_chip)}"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (bot.description.isNotBlank()) {
                                    Text(
                                        text = bot.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    if (!ui.canManage) {
                        item {
                            Text(
                                text = stringResource(R.string.im_bots_owner_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = Dimens.SpaceXl,
                                    vertical = Dimens.SpaceM,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    if (helpOpen) {
        AlertDialog(
            onDismissRequest = { helpOpen = false },
            title = { Text(stringResource(R.string.im_bots_help_title)) },
            text = {
                Text(
                    text = stringResource(R.string.im_bots_help_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { helpOpen = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}
