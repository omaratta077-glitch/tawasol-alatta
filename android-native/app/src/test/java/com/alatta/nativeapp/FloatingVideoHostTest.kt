package com.alatta.nativeapp

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingVideoHostTest {
    @Test fun boundsUseMeasuredHeightAndClampAfterRotationOrKeyboardResize() {
        val portrait = MiniVideoBounds.inViewport(IntSize(400, 800), IntSize(240, 228), 8f, 86f)
        assertEquals(Offset(8f, 486f), portrait.initial)
        assertEquals(Offset(152f, 8f), portrait.clamp(Offset(900f, -900f)))
        assertEquals(Offset(8f, 486f), portrait.clamp(Offset(-1f, 1000f)))
        val landscape = MiniVideoBounds.inViewport(IntSize(800, 260), IntSize(240, 228), 8f, 86f)
        val moved = landscape.clamp(portrait.initial)
        assertEquals(8f, moved.y, 0f)
        assertTrue(moved.y + 228 <= 260)
        val tiny = MiniVideoBounds.inViewport(IntSize(0, 0), IntSize(240, 228), 8f, 86f)
        assertEquals(Offset.Zero, tiny.clamp(Offset(100f, 100f)))
    }

    private class Player(context: android.content.Context) : View(context) {
        val actions = mutableListOf<Int>()
        override fun onTouchEvent(event: MotionEvent): Boolean {
            actions += event.actionMasked
            if (event.actionMasked == MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
            return true
        }
    }

    private fun host(): Pair<MiniPlayerTouchHost, Player> {
        val context = RuntimeEnvironment.getApplication()
        val host = MiniPlayerTouchHost(context)
        val player = Player(context)
        host.addView(player, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 320, 240)
        return host to player
    }

    private var time = 1L
    private fun send(host: MiniPlayerTouchHost, action: Int, x: Float, y: Float, origin: Offset = Offset.Zero) {
        val event = MotionEvent.obtain(0, time++, action, x, y, 0)
        event.offsetLocation(-origin.x, -origin.y)
        try { host.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun aNormalTapReachesThePlayerWithoutMovingTheWindow() {
        val (host, player) = host()
        var drags = 0
        host.onDrag = { _, _ -> drags++ }
        send(host, MotionEvent.ACTION_DOWN, 60f, 80f)
        send(host, MotionEvent.ACTION_MOVE, 61f, 81f)
        send(host, MotionEvent.ACTION_UP, 61f, 81f)
        assertEquals(0, drags)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), player.actions)
    }

    @Test fun draggingCancelsPlayerTouchAndUsesScreenCoordinatesWhileTheWindowMoves() {
        val (host, player) = host()
        val moves = mutableListOf<Offset>()
        host.onDrag = { x, y -> moves += Offset(x, y) }
        val origin = Offset(90f, 90f)
        send(host, MotionEvent.ACTION_DOWN, 100f, 100f, origin)
        send(host, MotionEvent.ACTION_MOVE, 150f, 140f, origin)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), player.actions)
        assertEquals(1, moves.size)
        val shiftedOrigin = origin + moves.single()
        send(host, MotionEvent.ACTION_MOVE, 160f, 145f, shiftedOrigin)
        assertEquals(Offset(10f, 5f), moves.last())
        send(host, MotionEvent.ACTION_UP, 160f, 145f, shiftedOrigin)
        assertFalse(player.actions.contains(MotionEvent.ACTION_UP))
        val count = moves.size
        send(host, MotionEvent.ACTION_DOWN, 110f, 110f, origin)
        send(host, MotionEvent.ACTION_CANCEL, 110f, 110f, origin)
        assertEquals(count, moves.size)
    }

    @Test fun expandedModeLeavesAllGesturesToThePlayer() {
        val (host, player) = host()
        send(host, MotionEvent.ACTION_DOWN, 30f, 30f)
        send(host, MotionEvent.ACTION_MOVE, 150f, 130f)
        send(host, MotionEvent.ACTION_UP, 150f, 130f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), player.actions)
    }
}
