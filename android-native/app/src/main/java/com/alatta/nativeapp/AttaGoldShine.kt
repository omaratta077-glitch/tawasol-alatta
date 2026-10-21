package com.alatta.nativeapp

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect

/**
 * The same effect as cloudflare-site/public/style.css "attaGoldShine":
 * ::after width:24%, height:180%, top:-40%, left:-28%,
 * linear-gradient(90deg, transparent, white .48, transparent),
 * skewX(-22deg), translateX(-180% to 260%) in 55% of 4.6 s,
 * then the strip remains beyond the button until the cycle restarts.
 *
 * Draw ONLY on already-present primary/native gold controls. One shared
 * implementation avoids separate per-screen timers or new Android views.
 * Disabled controls never create an infinite transition.
 */
internal fun Modifier.attaGoldShine(enabled: Boolean = true): Modifier =
    if (!enabled) this else this.composed {
        val clock = rememberInfiniteTransition(label = "attaGoldShine")
        val shift = clock.animateFloat(
            initialValue = -1.8f,
            targetValue = 2.6f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 4600
                    -1.8f at 0 using CubicBezierEasing(.42f, 0f, .58f, 1f)
                    2.6f at 2530
                    2.6f at 4600
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "attaGoldShine-translate"
        )
        drawWithContent {
            drawContent()
            if (size.width <= 0f || size.height <= 0f) return@drawWithContent

            val band = size.width * .24f
            val left = -size.width * .28f + shift.value * band
            val top = -size.height * .40f
            val bottom = size.height * 1.40f
            // CSS transform-origin is the strip's center.
            val skew = -0.40402623f // tan(-22 degrees)
            val topShift = skew * (top - size.height * .5f)
            val bottomShift = skew * (bottom - size.height * .5f)
            val path = Path().apply {
                moveTo(left + topShift, top)
                lineTo(left + topShift + band, top)
                lineTo(left + bottomShift + band, bottom)
                lineTo(left + bottomShift, bottom)
                close()
            }
            clipRect(0f, 0f, size.width, size.height) {
                drawPath(
                    path,
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = .48f), Color.Transparent),
                        startX = left + minOf(topShift, bottomShift),
                        endX = left + maxOf(topShift, bottomShift) + band
                    )
                )
            }
        }
    }
