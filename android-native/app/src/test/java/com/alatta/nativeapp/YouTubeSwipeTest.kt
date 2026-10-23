package com.alatta.nativeapp

import android.app.Application
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class YouTubeSwipeTest {
    private fun touch(swipe: YouTubeSwipe, action: Int, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(0, 100, action, x, y, 0)
        return try { swipe.onTouch(event) } finally { event.recycle() }
    }
    @Test fun upwardSwipeAdvancesOnceAtDensityScaledDistance() {
        val swipe = YouTubeSwipe(2f)
        assertFalse(touch(swipe, MotionEvent.ACTION_DOWN, 100f, 300f))
        assertFalse(touch(swipe, MotionEvent.ACTION_MOVE, 100f, 200f))
        assertTrue(touch(swipe, MotionEvent.ACTION_MOVE, 105f, 160f))
        assertFalse(touch(swipe, MotionEvent.ACTION_MOVE, 105f, 0f))
        assertFalse(touch(swipe, MotionEvent.ACTION_UP, 105f, 0f))
    }
    @Test fun controlsAndCancelledOrMultitouchGesturesDoNotAdvance() {
        for (end in listOf(100f to 195f, 200f to 100f, 100f to 300f)) {
            val swipe = YouTubeSwipe(1f)
            touch(swipe, MotionEvent.ACTION_DOWN, 100f, 200f)
            assertFalse(touch(swipe, MotionEvent.ACTION_MOVE, end.first, end.second))
            assertFalse(touch(swipe, MotionEvent.ACTION_UP, end.first, end.second))
        }
        for (action in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
            val swipe = YouTubeSwipe(1f)
            touch(swipe, MotionEvent.ACTION_DOWN, 100f, 200f)
            touch(swipe, action, 100f, 200f)
            assertFalse(touch(swipe, MotionEvent.ACTION_MOVE, 100f, 0f))
        }
    }
}
