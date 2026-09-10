package com.we.meet.ui.locale

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * 应用内语言(我的 → 设置 → 语言,由 AppCompatDelegate 写进资源配置),**不是**
 * `Locale.getDefault()` 的设备语言。
 *
 * 资源串本来就跟着应用内语言走,格式化日期/星期就得取同一个 locale,否则同一条
 * 文案会串成两种语言:应用切英文、系统是中文时 `Locale.getDefault()` 会把
 * 「Monday」配成「9月11日」,反过来是「Yesterday 周一」。
 *
 * - Compose:`appLocale()`
 * - 非 Compose(格式化器、工具函数):`context.appLocale()`
 *
 * 两者读的是同一份 Activity 配置,区别只是拿不拿得到 context。先例:TaskScreen
 * 的 `LocalConfiguration.current.locales[0]`,以及 feature-im 的 ImTimeLabels 调用点。
 */
@Composable
fun appLocale(): Locale = LocalConfiguration.current.locales[0]

/**
 * 非 Compose 代码里的应用内语言。传 Activity / Compose 的 context
 * ([androidx.compose.ui.platform.LocalContext] 那个)—— 它的 resources 已被
 * AppCompat 按 per-app locale 包过一层;applicationContext 在 API < 33 上
 * 不一定有这层包装。
 */
fun Context.appLocale(): Locale = resources.configuration.locales[0]
