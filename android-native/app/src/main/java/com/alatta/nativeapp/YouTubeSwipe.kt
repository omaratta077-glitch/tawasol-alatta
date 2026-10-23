package com.alatta.nativeapp

import android.view.MotionEvent
import kotlin.math.abs

/** Observe the existing WebView; taps, horizontal seeking and multitouch stay with YouTube. */
internal class YouTubeSwipe(private val density: Float) {
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y; tracking = true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val up = startY - event.y
                val across = abs(event.x - startX)
                if (across > 32f * density || event.y - startY > 32f * density) tracking = false
                else if (up >= 64f * density && up > across * 1.5f) {
                    tracking = false
                    return true
                }
            }
            MotionEvent.ACTION_UP -> tracking = false
        }
        return false
    }
}
