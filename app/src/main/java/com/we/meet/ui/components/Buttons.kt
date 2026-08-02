package com.we.meet.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme

/**
 * 按钮三件套:主按钮、次按钮、危险按钮。
 *
 * 一屏之内**只能有一个** [PrimaryButton] —— 主按钮的意义在于告诉用户「就点
 * 这个」,出现两个就等于没有。其余动作用 [SecondaryButton];会造成损失的用
 * [DangerButton]。
 *
 * 三者共用 [Dimens.ButtonHeight],高度不许在调用处改。
 */

/**
 * 主按钮:一屏一个,承载该页最主要的动作(登录、加入会议、创建)。
 *
 * @param loading 为 true 时按钮变成转圈并自动禁用 —— 不用在外面再写一遍
 *   `enabled = !loading`,重复写就会漏。
 * @param enabled 业务上的可用性(比如表单没填完)。与 [loading] 取与。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.ButtonHeight),
        enabled = enabled && !loading,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimens.IconSmall),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 次按钮:与 [PrimaryButton] 并列时的次要动作,或页面里的非主要动作。 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.ButtonHeight),
        enabled = enabled,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 危险按钮:不可逆的破坏性动作(结束会议、删除、移出成员)。
 *
 * 用它就意味着这一步会造成损失 —— 所以除极少数场景外,点击后应当再出一个
 * 二次确认对话框。不要因为「想要个红按钮」而用它。
 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.ButtonHeight),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = WeMeetTheme.extras.status.danger,
            contentColor = WeMeetTheme.extras.status.onDanger,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
