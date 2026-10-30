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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect

/**
 * تأثير اللمعة الذهبية المشترك، بتنسيق يحاكي «attaGoldShine» في الموقع:
 * ::after بعرض 24% وارتفاع 180%، مع top:-40% وleft:-28%.
 * تدرّج خطي من شفاف إلى أبيض .48 ثم شفاف،
 * وميل skewX(-22deg)، ليعبر الزر كله خلال 55% من دورة مدتها 4.6 ثانية،
 * ثم يبقى الشريط خارج الزر حتى تبدأ الدورة التالية.
 *
 * يُرسم التأثير على الأزرار الذهبية الأساسية الموجودة فقط. يمنع التنفيذ المشترك
 * إنشاء مؤقتات منفصلة لكل شاشة أو إضافة عروض Android جديدة.
 * لا تنشئ الأزرار المعطلة حركة متكررة بلا نهاية.
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
        clipToBounds().drawWithContent {
            drawContent()
            if (size.width <= 0f || size.height <= 0f) return@drawWithContent

            val band = size.width * .24f
            val progress = ((shift.value + 1.8f) / 4.4f).coerceIn(0f, 1f)
            val slantMargin = size.height * .40402623f
            val left = -band - slantMargin + progress * (size.width + band + 2f * slantMargin)
            val top = -size.height * .40f
            val bottom = size.height * 1.40f
            // أصل التحويل في CSS هو مركز الشريط.
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
