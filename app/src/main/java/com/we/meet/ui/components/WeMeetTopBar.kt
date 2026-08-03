package com.we.meet.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R

/**
 * 全 App 统一的顶栏。
 *
 * 建这个组件是因为原先 21 个页面各写各的 `TopAppBar {}` —— 返回图标、标题
 * 截断方式、actions 间距全靠手写对齐,漂移是迟早的。新页面一律用它,不要
 * 再直接调 M3 的 [TopAppBar]。
 *
 * 三条约定,由组件保证、页面不用操心:
 * 1. 返回键统一 `ArrowBack` + `R.string.cd_back`(TalkBack 读得出);
 * 2. 标题 `titleLarge`,超长省略号截断,绝不换行把顶栏撑高;
 * 3. 传了 [onBack] 才有返回键 —— tab 根页面不传,自然就没有。
 *
 * @param title 页面标题。全屏浮层式页面没有标题时传空串。
 * @param onBack 返回回调;`null` 表示这是根页面,不显示返回键。
 * @param onClose 用 ✕ 代替返回箭头。适用于「全屏弹层」式页面 —— 那里的语义
 *   是「关掉它」而不是「回上一层」,图标必须跟着变。与 [onBack] 二选一。
 * @param transparent 顶栏压在内容之上(全屏摄像头预览、登录背景图)时置 true,
 *   底色透明。常规页面不要用 —— 顶栏和内容之间需要那层底色来分隔。
 * @param actions 右侧操作区,直接放 [IconButton]。超过 3 个请收进溢出菜单。
 * @param scrollBehavior 需要「滚动时顶栏收起」时传入,配合 `Scaffold` 的
 *   `Modifier.nestedScroll`。不传就是固定顶栏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeMeetTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    transparent: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    require(onBack == null || onClose == null) {
        "Pass either onBack or onClose, not both -- one nav slot, one icon"
    }
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            when {
                onBack != null -> IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
                onClose != null -> IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_close),
                    )
                }
            }
        },
        actions = actions,
        colors = if (transparent) {
            TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        } else {
            TopAppBarDefaults.topAppBarColors()
        },
        scrollBehavior = scrollBehavior,
    )
}
