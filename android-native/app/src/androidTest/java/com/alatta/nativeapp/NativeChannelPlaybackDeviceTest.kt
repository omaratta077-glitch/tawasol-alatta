package com.alatta.nativeapp

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NativeChannelPlaybackDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** Stalls inside Media3's real loader; never opens a socket or requests a live source. */
    private class StalledTransport : DataSource.Factory {
        val opened = CopyOnWriteArrayList<String>()
        override fun createDataSource(): DataSource = object : DataSource {
            private var uri: Uri? = null
            override fun addTransferListener(listener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long {
                uri = dataSpec.uri; opened.add(dataSpec.uri.path.orEmpty())
                return C.LENGTH_UNSET.toLong()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { throw InterruptedIOException("Fixture cancelled") }
                return C.RESULT_END_OF_INPUT
            }
            override fun getUri(): Uri? = uri
            override fun close() = Unit
        }
    }

    @Test fun stalledLoadAutoplaysThenTriesBothSourcesAndCopiesFreshRedactedState() {
        val factory = StalledTransport()
        val channel = FootballChannel("fixture", "beIN SPORTS 1", "1080p", "fixture",
            YtvPlayer.APP_PAGE, "123", "ytv", true)
        val sources = listOf("first", "second").mapIndexed { index, name -> NativeChannelSource(index,
            "https://fixture.example/$name.m3u8?token=private-source", mapOf("User-Agent" to "private-agent"), "application/x-mpegURL") }
        val session = NativePlaybackSession(channel, sources)
        val visible = mutableStateOf(true)
        val refresh = mutableIntStateOf(0)
        val dismissals = AtomicInteger(0)
        val retained = AtomicReference<String>()
        compose.setContent { MaterialTheme {
            Text("القنوات ${refresh.intValue}")
            if (visible.value) NativeChannelPlaybackDialog(session, onDismiss = {
                dismissals.incrementAndGet(); visible.value = false
            }, attemptTimeoutMs = 5_000, dataSourceFactory = factory, onFinalReport = { retained.set(it) })
        } }
        compose.waitUntil(8_000) { factory.opened.contains("/first.m3u8") }
        val windowNode = compose.onNode(isDialog()).fetchSemanticsNode().id
        fun copied(): JSONObject {
            compose.onNodeWithText("نسخ نتيجة التجربة").performClick()
            var text = ""
            compose.runOnIdle {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip!!.getItemAt(0).text.toString()
            }
            assertFalse(text.contains("private-source")); assertFalse(text.contains("private-agent"))
            assertFalse(text.contains("https://")); assertFalse(text.contains(".m3u8"))
            return JSONObject(text)
        }
        val initial = copied()
        assertEquals("native-channel-3", initial.getString("test"))
        assertEquals("fixture.example", initial.getString("sourceHost"))
        assertTrue(initial.getBoolean("playWhenReady"))
        assertTrue(initial.getJSONArray("attempts").length() > 0)
        compose.runOnIdle { refresh.intValue++ }
        assertEquals(initial.getString("sessionId"), copied().getString("sessionId"))
        compose.waitUntil(8_000) { factory.opened.contains("/second.m3u8") }
        assertEquals(windowNode, compose.onNode(isDialog()).fetchSemanticsNode().id)
        assertEquals(0, dismissals.get())
        val second = copied()
        assertEquals(1, second.getInt("sourceIndex")); assertTrue(second.getBoolean("playWhenReady"))
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText("خطأ المشغّل:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("خيار 2 من 2").assertExists()
        assertEquals(windowNode, compose.onNode(isDialog()).fetchSemanticsNode().id)
        assertEquals(0, dismissals.get())
        val final = copied()
        assertEquals(1, final.getInt("sourceIndex")); assertEquals("failed", final.getString("stage"))
        val attempts = final.getJSONArray("attempts")
        val timeouts = (0 until attempts.length()).map { attempts.getJSONObject(it) }.filter { it.optString("event") == "startup_timeout" }
        assertEquals(listOf(0, 1), timeouts.map { it.getInt("sourceIndex") })
        assertFalse(final.getBoolean("playing"))
        assertEquals(initial.getString("sessionId"), final.getString("sessionId"))
        assertTrue(final.getLong("attemptElapsedMs") <= final.getLong("elapsedMs"))
        assertEquals(2, (0 until attempts.length()).count { attempts.getJSONObject(it).optString("event") == "attempt_started" })
        android.os.SystemClock.sleep(150)
        assertTrue(copied().getLong("elapsedMs") > final.getLong("elapsedMs"))
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(2_000) { dismissals.get() == 1 }
        compose.onNode(isDialog()).assertDoesNotExist()
        val saved = JSONObject(retained.get())
        assertEquals(final.getString("sessionId"), saved.getString("sessionId"))
        assertTrue(saved.getString("dismissalReason") in setOf("system_back", "dialog_dismiss"))
        assertTrue(saved.getJSONArray("loadEvents").length() > 0)
    }
}
