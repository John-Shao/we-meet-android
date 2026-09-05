package com.we.meet.feature.assistant.aicall.ui.components

import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.ui.theme.Dimens
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * The blue gradient ball at the centre of the call screen.
 *
 * [audioLevel] is read inside the Canvas draw lambda so the sphere repaints
 * each frame without recomposing the screen graph.
 */
@Composable
fun AnimatedSphere(
    audioLevel: () -> Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
) {
    val infinite = rememberInfiniteTransition(label = "sphere")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8_000, easing = LinearEasing)),
        label = "phase",
    )

    // DrawScope 不是 @Composable 作用域,颜色要在进 Canvas 之前取出来。
    val colors = WeMeetTheme.extras.aiCall

    Canvas(
        modifier = modifier
            .size(Dimens.AiCall.SphereSize)
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
    ) {
        val level = audioLevel().coerceIn(0f, 1f)
        val r = size.minDimension / 2f
        val growth = 1f + level * 0.15f

        // outer halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.sphereGlowOuter.copy(alpha = 0.30f + level * 0.30f),
                    colors.sphereGlowInner.copy(alpha = 0.12f),
                    colors.sphereGlowInner.copy(alpha = 0f),
                ),
                radius = r * 1.3f * growth,
            ),
            radius = r * 1.3f * growth,
        )
        // sphere body — sweep gradient rotated for shimmer
        rotate(phase) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        *colors.sphereGradient.toTypedArray(),
                    ),
                ),
                radius = r * 0.78f * growth,
            )
        }
        // specular highlight
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.sphereHighlight.copy(alpha = 0.35f),
                    colors.sphereHighlight.copy(alpha = 0f),
                ),
                center = Offset(r * 0.7f, r * 0.65f),
                radius = r * 0.5f,
            ),
            center = Offset(r * 0.7f, r * 0.65f),
            radius = r * 0.5f,
        )
    }
}
