package com.we.meet.ui.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.we.meet.ui.theme.WeMeetTheme
import kotlin.math.max
import kotlin.math.min

private const val LIGHT_EVENT_CONTAINER_ALPHA = 0.12f
private const val DARK_EVENT_CONTAINER_ALPHA = 0.22f
private const val PAST_EVENT_STRENGTH = 0.45f
private const val MIN_ACCENT_CONTRAST = 3f

/** Stable payload values accepted by the existing calendar subscription API. */
val CALENDAR_COLOR_PALETTE = listOf(
    "#3370ff",
    "#5b8ff9",
    "#34c724",
    "#5ad8a6",
    "#f5a623",
    "#f6bd16",
    "#f54a45",
    "#e8684a",
    "#8b5cf6",
    "#9270ca",
    "#6dc8ec",
    "#5d7092",
)

/** Parses only six-digit RGB payloads. Invalid server data never reaches Color constructors. */
fun parseCalendarColor(value: String?): Color? {
    val normalized = value?.trim()?.removePrefix("#") ?: return null
    if (normalized.length != 6) return null
    val rgb = normalized.toLongOrNull(16) ?: return null
    // design-exempt: 这不是在调用处凭空写一个色值,而是把**服务端下发的**日历
    // 颜色解析成 Color(上面已校验长度与进制,非法输入返回 null)。token 化在
    // 这里无从谈起 —— 值是运行时才知道的。
    return Color(
        red = ((rgb shr 16) and 0xff).toFloat() / 255f,
        green = ((rgb shr 8) and 0xff).toFloat() / 255f,
        blue = (rgb and 0xff).toFloat() / 255f,
        alpha = 1f,
    )
}

fun validCalendarColorOrDefault(value: String?): String =
    value?.takeIf { parseCalendarColor(it) != null } ?: CALENDAR_COLOR_PALETTE.first()

/** Resolved calendar identity colors placed on top of semantic theme surfaces. */
data class CalendarEventColors(
    val accent: Color,
    val container: Color,
    val pastAccent: Color,
    val pastContainer: Color,
    val content: Color,
    val supportingContent: Color,
)

/**
 * Turns a user/server calendar color into a predictable event-card palette.
 *
 * The hue still identifies the calendar. Like Web, containers stay translucent
 * so the grid's working-hours shading remains visible through an event. Past
 * events retain the hue at 45% of the normal strength instead of switching to
 * a neutral surface. The accent is adjusted toward the theme foreground only
 * when needed to reach WCAG's 3:1 non-text boundary.
 */
@Composable
@ReadOnlyComposable
fun calendarEventColors(value: String?): CalendarEventColors {
    val calendar = WeMeetTheme.extras.calendar
    val rawAccent = parseCalendarColor(value) ?: MaterialTheme.colorScheme.primary
    return resolveCalendarEventColors(
        rawAccent = rawAccent,
        // In dark mode the grid and page share the same near-black surface.
        // Lift event cards one surface step before applying the calendar hue so
        // their bounds remain visible without adding a decorative outline.
        containerBase = if (WeMeetTheme.isDark) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            calendar.gridBackground
        },
        content = calendar.eventContent,
        supportingContent = calendar.eventSupportingContent,
        fillAlpha = if (WeMeetTheme.isDark) {
            DARK_EVENT_CONTAINER_ALPHA
        } else {
            LIGHT_EVENT_CONTAINER_ALPHA
        },
    )
}

/** Foreground for the solid color swatches in the calendar color picker. */
@Composable
@ReadOnlyComposable
fun calendarSwatchContentColor(value: String?): Color {
    val swatch = parseCalendarColor(value) ?: MaterialTheme.colorScheme.primary
    val lightCandidate = MaterialTheme.colorScheme.onPrimary
    val darkCandidate = MaterialTheme.colorScheme.onSurface
    return if (
        colorContrastRatio(lightCandidate, swatch) >=
        colorContrastRatio(darkCandidate, swatch)
    ) {
        lightCandidate
    } else {
        darkCandidate
    }
}

internal fun resolveCalendarEventColors(
    rawAccent: Color,
    containerBase: Color,
    content: Color,
    supportingContent: Color,
    fillAlpha: Float,
): CalendarEventColors {
    val container = rawAccent.copy(alpha = fillAlpha.coerceIn(0f, 1f))
    val resolvedContainer = container.compositeOver(containerBase)
    val accent = ensureContrast(
        source = rawAccent,
        background = resolvedContainer,
        toward = content,
        minimum = MIN_ACCENT_CONTRAST,
    )
    val safeSupportingContent = ensureContrast(
        source = supportingContent,
        background = resolvedContainer,
        toward = content,
        minimum = 4.5f,
    )
    return CalendarEventColors(
        accent = accent,
        container = container,
        pastAccent = accent.copy(alpha = PAST_EVENT_STRENGTH),
        pastContainer = rawAccent.copy(
            alpha = (fillAlpha * PAST_EVENT_STRENGTH).coerceIn(0f, 1f),
        ),
        content = content,
        supportingContent = safeSupportingContent,
    )
}

internal fun colorContrastRatio(first: Color, second: Color): Float {
    val lighter = max(first.luminance(), second.luminance())
    val darker = min(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun ensureContrast(
    source: Color,
    background: Color,
    toward: Color,
    minimum: Float,
): Color {
    if (colorContrastRatio(source, background) >= minimum) return source

    var low = 0f
    var high = 1f
    repeat(12) {
        val amount = (low + high) / 2f
        if (colorContrastRatio(lerp(source, toward, amount), background) >= minimum) {
            high = amount
        } else {
            low = amount
        }
    }
    return lerp(source, toward, high)
}
