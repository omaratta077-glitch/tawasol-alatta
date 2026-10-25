package com.alatta.nativeapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Parcel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YtvPlayerTest {
    @Test fun channelHandoffMatchesTheObservedContractAcrossParceling() {
        val intent = requireNotNull(YtvPlayer.channelIntent("9223372036854775807"))
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            val received = Intent.CREATOR.createFromParcel(parcel)
            assertEquals(Intent.ACTION_VIEW, received.action)
            assertEquals("com.ytv.pronew", received.component?.packageName)
            assertEquals("dev.google.ytvplayer.ui.splash.SplashActivity", received.component?.className)
            assertEquals(Long.MAX_VALUE, received.getLongExtra("id", -1L))
            assertEquals(1, received.getIntExtra("type", -1))
            assertEquals(setOf("id", "type"), received.extras!!.keySet())
            assertNull(received.data)
        } finally { parcel.recycle() }
    }

    @Test fun channelIdsNormalizeArabicDigitsAndRejectUrlsMalformedValuesAndOverflow() {
        assertEquals("41", YtvPlayer.channelId(" ٠٠٤١ "))
        assertEquals("4294967296", YtvPlayer.channelId("۴۲۹۴۹۶۷۲۹۶"))
        for (bad in listOf("", "0", "-1", "+1", "1.2", "1e2", "4 1", "9223372036854775808", "12345678901234567890",
            "https://example.org/41", "intent:#Intent;end", "41\nInjected")) {
            assertNull(bad, YtvPlayer.channelIntent(bad))
        }
    }

    private class LaunchContext(base: Context, val error: RuntimeException? = null) : ContextWrapper(base) {
        var received: Intent? = null
        override fun startActivity(intent: Intent) {
            received = intent
            error?.let { throw it }
        }
    }

    @Test fun launchAndExpectedFailurePathsDoNotFallBackToAnotherAppOrSendAccountData() {
        val base = RuntimeEnvironment.getApplication()
        val context = LaunchContext(base)
        var before = 0
        assertEquals(YtvPlayer.Failure.INVALID_ID, YtvPlayer.open(context, "0") { before++ })
        assertNull(context.received); assertEquals(0, before)
        assertNull(YtvPlayer.open(context, "4294967296") { before++ })
        assertEquals(1, before)
        assertEquals(4294967296L, context.received!!.getLongExtra("id", -1))
        assertTrue(context.received!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(setOf("id", "type"), context.received!!.extras!!.keySet())
        assertEquals(YtvPlayer.Failure.UNAVAILABLE, YtvPlayer.open(LaunchContext(base, ActivityNotFoundException()), "41"))
        assertEquals(YtvPlayer.Failure.NOT_ALLOWED, YtvPlayer.open(LaunchContext(base, SecurityException()), "41"))
    }

    @Test fun sharedCatalogKeepsYtvIdsExactWithoutTreatingThemAsVideoUrls() {
        fun record(id: String, stream: String, format: String = "ytv", source: String = YtvPlayer.APP_PAGE) = JSONObject()
            .put("id", id).put("name", "قناة الاختبار").put("category", "كرة القدم").put("provider", "YTV PRO")
            .put("sourceUrl", source).put("streamUrl", stream).put("format", format).put("enabled", true)
        val data = JSONObject().put("channels", JSONArray()
            .put(record("valid", "9223372036854775807"))
            .put(record("invalid-zero", "0"))
            .put(record("invalid-url", "https://example.org/video.m3u8"))
            .put(record("invalid-number-type", "41").put("streamUrl", 41))
            .put(record("invalid-source", "41", source = "http://example.org/"))
            .put(record("hls", "https://example.org/video.m3u8", "hls")))
        val channels = FootballRepository.parse(data).channels
        assertEquals(listOf("valid", "hls"), channels.map { it.id })
        assertEquals("9223372036854775807", channels.first().streamUrl)
        assertEquals("ytv", channels.first().format)
    }
}
