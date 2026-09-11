package com.we.meet.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.design.R
import com.we.meet.ui.theme.Dimens

/**
 * 搜索的共享策略常量。
 *
 * 单独放一个 object 而不是散在调用处的魔数,是为了让「几个字才值得发一次请求」
 * 这件事只有一个答案。原先只有消息与文档要求 2 个字,联系人和会议 1 个字就发 ——
 * 同一个输入框里换个分类,行为就跟着变。
 */
object SearchPolicy {

    /**
     * **服务端**搜索的最小关键词长度。
     *
     * 只约束会发出网络请求的搜索。本地即时过滤(如「我的群组」直接过滤已加载的
     * 会话列表)不该套这个下限 —— 它没有请求成本,输一个字就出结果是优点,
     * 卡到 2 个字反而显得迟钝。
     */
    const val MinQueryLength = 2
}

/**
 * 全 App 统一的搜索输入框。
 *
 * 原先四处搜索各写各的:全局搜索是顶栏里的下划线输入框(「清空」按钮单独摆在
 * actions 里)、部门内搜索是无前置图标的描边框、群组搜索是带图标的描边框、
 * 任务搜索是第四种写法。同一件事(输入关键词)四种长相,用户每换一处都要重新
 * 认一次;更糟的是其中两处**没有**清空按钮,想改词只能一个字一个字删。
 *
 * 所以这里的契约是三件套,不允许调用方各减一件:前置放大镜(说明这是搜索)、
 * 占位文案(说明搜什么)、非空时的清空按钮。清空走 [onValueChange](""),
 * 调用方不必再接一个 trailing icon。
 *
 * 底色取 `surface` + `outlineVariant` 描边,是唯一在两种页面区域上都成立的
 * 组合:一级页头部是浅灰(`background`),二级及更深页面头部是白色(`surface`)。
 * 靠**描边**定形而不是靠填充色与底色拉对比,换区域就不必换样式 —— 见
 * `docs/page-backgrounds.md`。
 *
 * ```
 * WeMeetSearchField(
 *     value = ui.query,
 *     onValueChange = vm::onQueryChange,
 *     placeholder = stringResource(R.string.contacts_search_hint),
 * )
 * ```
 *
 * @param value 当前输入。
 * @param onValueChange 输入回调;清空按钮也走这里(传空串)。
 * @param placeholder 占位文案。**必填** —— 搜索框不说清搜什么,用户只能靠试。
 * @param autoFocus 进入页面即请求焦点并拉起键盘。
 * @param imeAction 软键盘右下角动作,默认 [ImeAction.Search]。
 * @param clearContentDescription 清空按钮的无障碍描述,不传则用内置文案。
 */
@Composable
fun WeMeetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    imeAction: ImeAction = ImeAction.Search,
    clearContentDescription: String? = null,
) {
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        // 进了搜索页却还要再点一次输入框才能打字,是纯粹的多余动作。
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
    val clearLabel = clearContentDescription ?: stringResource(R.string.cd_clear_search)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                text = placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                // 纯装饰:占位文案已经说明这是搜索框,再读一遍是噪音。
                contentDescription = null,
            )
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = clearLabel,
                    )
                }
            }
        } else {
            null
        },
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        colors = OutlinedTextFieldDefaults.colors(
            // 填充色跟着页面区域走:两种区域都是浅底,取 surface 后在一级页是
            // 白块压浅灰、在二级页是白底 + 描边,都成立。
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier.focusRequester(focusRequester),
    )
}

/**
 * [WeMeetSearchField] 的**只读孪生**:看着一样,但不可编辑 —— 点一下跳到搜索页。
 *
 * 有些页面的搜索是「跳走」而不是「就地过滤」,通讯录第一屏就是:真塞一个输入框在
 * 那里,就得在这一页里再接一套搜索逻辑,而全 App 的搜索已经收在统一搜索页里了。
 * 用胶囊既保住了「第一屏看得见搜索框」这个心智(飞书/微信都是这么做的),又没有
 * 引进第二套实现 —— 壳留着,逻辑收走。
 *
 * 和 [WeMeetSearchField] 同文件、共用同一套几何取值,是为了让两者不会各自漂移:
 * 一旦长得不一样,用户就会当成两个东西。
 *
 * @param label 显示文案。传当前范围说明(如「在产品部内搜索」)比干写「搜索」有用 ——
 *   这句话是用户点之前唯一能知道"会搜到哪"的地方。
 * @param onClick 点击后去哪。
 */
@Composable
fun WeMeetSearchEntry(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceL),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.IconMedium),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Dimens.SpaceM),
            )
        }
    }
}
