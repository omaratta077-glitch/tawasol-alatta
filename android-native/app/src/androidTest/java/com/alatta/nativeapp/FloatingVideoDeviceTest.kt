package com.alatta.nativeapp

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FloatingVideoDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aPostFillsTheCardWithoutExtraLabelsAndReleasesItsInlinePlayerWhenLeaving() {
        val visible = mutableStateOf(true)
        var stops = 0
        var disposedPlayers = 0
        compose.setContent {
            Box(Modifier.width(320.dp).testTag("post-width")) {
            if (visible.value) YoutubePostMedia("dQw4w9WgXcQ", true, onPlay = {},
                onStop = { stops++ },
                playerContent = { id ->
                    androidx.compose.runtime.DisposableEffect(id) {
                        onDispose { disposedPlayers++ }
                    }
                    Box(Modifier.fillMaxWidth().height(200.dp).testTag("inline-content")) { Text("inline-player-$id") }
                })
            }
        }
        compose.onNodeWithText("inline-player-dQw4w9WgXcQ").assertIsDisplayed()
        compose.onAllNodesWithText("إيقاف").assertCountEquals(0)
        compose.onAllNodesWithText("يوتيوب").assertCountEquals(0)
        val card = compose.onNodeWithTag("post-width").fetchSemanticsNode().boundsInRoot
        val player = compose.onNodeWithTag("inline-content").fetchSemanticsNode().boundsInRoot
        assertEquals(card.left, player.left, .5f)
        assertEquals(card.right, player.right, .5f)
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("inline-player-dQw4w9WgXcQ").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, stops); assertEquals(1, disposedPlayers) }
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("inline-player-dQw4w9WgXcQ").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, disposedPlayers); assertEquals(2, stops) }
    }

    @Test fun dragAcrossAnEmbeddedViewMovesRightInArabicAndStaysInsideTheViewport() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize().testTag("viewport")) {
                    FloatingVideoContainer(false, false, 0.dp) { host, drag ->
                        Box(host.height(228.dp).testTag("mini")) {
                            AndroidView(factory = { context ->
                                MiniPlayerTouchHost(context).apply {
                                    addView(object : View(context) {
                                        override fun onTouchEvent(event: MotionEvent): Boolean {
                                            if (event.actionMasked == MotionEvent.ACTION_DOWN)
                                                parent.requestDisallowInterceptTouchEvent(true)
                                            return true
                                        }
                                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                }
                            }, update = { it.onDrag = drag }, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val before = compose.onNodeWithTag("mini").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("viewport").performTouchInput {
            down(before.center - viewport.topLeft)
            moveBy(Offset(45f, -45f))
            moveBy(Offset(30f, -30f))
            up()
        }
        val after = compose.onNodeWithTag("mini").fetchSemanticsNode().boundsInRoot
        assertTrue("Rightward drag must move right in RTL", after.left > before.left)
        assertTrue("Upward drag must move up", after.top < before.top)
        compose.onNodeWithTag("viewport").performTouchInput {
            down(after.center - viewport.topLeft)
            moveBy(Offset(-5000f, -5000f))
            up()
        }
        val clamped = compose.onNodeWithTag("mini").fetchSemanticsNode().boundsInRoot
        assertTrue(clamped.left >= viewport.left && clamped.top >= viewport.top)
        assertTrue(clamped.right <= viewport.right && clamped.bottom <= viewport.bottom)
    }
}
