package com.we.meet.feature.im.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.we.meet.ui.theme.Dimens

/**
 * IM 设置页的三种行:导航行 / 开关行 / 动作行。
 *
 * 抽出来的直接原因:`SwitchRow` 在 GroupInfoScreen 与 DirectChatSettingsScreen
 * 里已经是**逐字节相同的两份**,群机器人详情页还要第三份。
 *
 * 刻意不放 core-design —— 这是 IM 设置页的「行文法」(左标签右控件、SpaceXl
 * 横边距),不是全 App 通用件;通用解是 M3 `ListItem`,换它会动到整个群设置页
 * 的观感,是另一件事。
 */

/** 带 `>` 的二级页入口行。[value] 是右侧灰色附注(计数 / 当前值),null 则不显示。 */
@Composable
internal fun ImNavRow(label: String, value: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
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

/** 布尔设置行:左标签右开关,整行可点。 */
@Composable
internal fun ImSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
            // The parent row owns the single switch semantic and 48dp target.
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** 单行动作。[destructive] 用 error 色 —— 不可逆或会造成损失的操作。 */
@Composable
internal fun ImActionRow(
    text: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
    )
}
