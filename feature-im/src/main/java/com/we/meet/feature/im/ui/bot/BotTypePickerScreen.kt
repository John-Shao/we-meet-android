package com.we.meet.feature.im.ui.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.feature.im.R
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens

/**
 * 添加机器人 —— 选类型(对标飞书的机器人市场那一页)。
 *
 * 目前只有「自定义机器人」一项,但它是个坑位:第二种(开放平台的应用机器人)
 * 出现时就是多一行,而不是把这一步塞进表单里再拆出来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotTypePickerScreen(
    onBack: () -> Unit,
    onPickCustom: () -> Unit,
) {
    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.im_bots_add_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPickCustom)
                    .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
            ) {
                Icon(
                    imageVector = Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dimens.AvatarS),
                )
                Spacer(Modifier.width(Dimens.SpaceM))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.im_bots_catalog_custom),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.im_bots_catalog_custom_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
