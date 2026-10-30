package com.alatta.nativeapp

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.roundToInt

/** إحداثيات فعلية: السحب إلى اليمين يجب أن يحرك العنصر يمينًا في تخطيط RTL أيضًا. */
internal data class MiniVideoBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun clamp(point: Offset) = Offset(point.x.coerceIn(left, right), point.y.coerceIn(top, bottom))
    val initial get() = Offset(left, bottom)

    companion object {
        fun inViewport(viewport: IntSize, player: IntSize, margin: Float, bottomGap: Float): MiniVideoBounds {
            val freeX = (viewport.width - player.width).coerceAtLeast(0).toFloat()
            val freeY = (viewport.height - player.height).coerceAtLeast(0).toFloat()
            val left = margin.coerceIn(0f, freeX)
            val top = margin.coerceIn(0f, freeY)
            return MiniVideoBounds(left, top, (freeX - margin).coerceAtLeast(left),
                (freeY - maxOf(margin, bottomGap)).coerceAtLeast(top))
        }
    }
}

/** يحدد موضع المشغّل الحالي؛ وتظل WebView مركبة عند تغيير علامات التبويب. */
@Composable
internal fun FloatingVideoContainer(
    expanded: Boolean,
    pipMode: Boolean,
    expandedTopPadding: Dp,
    content: @Composable (Modifier, ((Float, Float) -> Unit)?) -> Unit
) {
    var miniYoutubeOffset by remember { mutableStateOf<Offset?>(null) }
    var miniSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val mini = !expanded && !pipMode
    Box(Modifier.fillMaxSize().then(if (pipMode) Modifier else Modifier.safeDrawingPadding())) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // اضبط الموضع أيضًا للوضع الأفقي وتقسيم الشاشة وتغيير حجم العرض فوق لوحة المفاتيح.
            val availableWidth = (maxWidth - 16.dp).coerceAtLeast(0.dp)
            val miniWidth = minOf(240.dp, availableWidth)
            val viewport = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
            val expectedSize = with(density) {
                IntSize(miniWidth.roundToPx(), (28.dp + (miniWidth * 9f / 16f).coerceAtLeast(200.dp)).roundToPx())
            }
            val measured = miniSize.takeIf { it.width == expectedSize.width && it.height > 0 } ?: expectedSize
            val bounds = with(density) { MiniVideoBounds.inViewport(viewport, measured, 8.dp.toPx(), 86.dp.toPx()) }
            val position = bounds.clamp(miniYoutubeOffset ?: bounds.initial)
            LaunchedEffect(mini, bounds) { if (mini) miniYoutubeOffset = bounds.clamp(miniYoutubeOffset ?: bounds.initial) }
            val drag: ((Float, Float) -> Unit)? = if (mini) {
                { dx, dy -> miniYoutubeOffset = bounds.clamp((miniYoutubeOffset ?: bounds.initial) + Offset(dx, dy)) }
            } else null
            val host = when {
                pipMode -> Modifier.align(Alignment.Center).fillMaxSize()
                expanded -> Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .padding(top = expandedTopPadding, start = 8.dp, end = 8.dp)
                else -> Modifier.align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                    .width(miniWidth).onSizeChanged { miniSize = it }
            }
            content(host, drag)
        }
    }
}

/** اعترض السحب الحقيقي بإصبع واحد فقط؛ واترك النقرات العادية تصل إلى المشغّل. */
internal class MiniPlayerTouchHost(context: Context) : FrameLayout(context) {
    var onDrag: ((Float, Float) -> Unit)? = null
        set(value) {
            if (value == null && field != null) reset()
            field = value
        }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var tracking = false
    private var dragging = false
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private fun reset() {
        tracking = false
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // قد تستحوذ WebView على الإيماءة عند DOWN. واصل الرصد حتى تجاوز حد السحب،
        // ثم يرسل Android إشارة CANCEL إلى العنصر الفرعي قبل تحريك النافذة.
        if (onDrag == null || !tracking || !disallowIntercept)
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (onDrag == null) return super.onInterceptTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                tracking = true
                startX = event.rawX; startY = event.rawY
                lastX = startX; lastY = startY
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> tracking = false
            MotionEvent.ACTION_MOVE -> if (tracking && event.pointerCount == 1) {
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                val distance = hypot(dx, dy)
                if (distance > slop) {
                    dragging = true
                    lastX = event.rawX; lastY = event.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val fraction = (distance - slop) / distance
                    onDrag?.invoke(dx * fraction, dy * fraction)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onDrag == null || !dragging) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> tracking = false
            MotionEvent.ACTION_MOVE -> if (tracking && event.pointerCount == 1) {
                // تظل إحداثيات الشاشة الخام ثابتة أثناء تحرك هذا العرض نفسه.
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                lastX = event.rawX; lastY = event.rawY
                onDrag?.invoke(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }
}
