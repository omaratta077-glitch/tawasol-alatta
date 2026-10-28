package com.alatta.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FloatingCallBubbleDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun bubbleDragsInPhysicalDirectionInArabicAndSnapsWithinTheViewport() {
        var clicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.size(320.dp, 560.dp).testTag("viewport")) {
                    FloatingCallBubble { host -> Box(host.testTag("call-bubble").clickable { clicks++ }) }
                }
            }
        }
        val bubble = compose.onNodeWithTag("call-bubble")
        val start = bubble.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val delta = if (start.center.x < viewport.center.x) viewport.width * .7f else -viewport.width * .7f
        bubble.performTouchInput { down(center); moveBy(Offset(delta, 80f)); up() }
        compose.waitForIdle()
        val end = bubble.fetchSemanticsNode().boundsInRoot
        assertTrue("Dragging must follow the finger in RTL", (end.left - start.left) * delta > 0)
        assertTrue(viewport.contains(end.center))
        assertTrue(end.left >= viewport.left && end.right <= viewport.right)
        assertEquals("Dragging must not open the call", 0, clicks)
        bubble.performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        bubble.performTouchInput { down(center); moveBy(Offset(-10000f, 10000f)); up() }
        compose.waitForIdle()
        val clamped = bubble.fetchSemanticsNode().boundsInRoot
        assertTrue(clamped.left >= viewport.left && clamped.bottom <= viewport.bottom)
    }
    @Test fun changingBetweenBubbleAndFullScreenKeepsTheCallMounted() {
        var mini by mutableStateOf(false)
        var starts = 0
        var stops = 0
        compose.setContent {
            FloatingCallBubble(minimized = mini) { host ->
                Box(host) { DisposableEffect(Unit) { starts++; onDispose { stops++ } } }
            }
        }
        compose.runOnIdle { mini = true }; compose.waitForIdle()
        compose.runOnIdle { mini = false }; compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, stops) }
    }
}
