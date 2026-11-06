package com.alatta.nativeapp

import android.content.Intent
import android.content.ComponentName
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvChannelHandoffTest {
    @Test fun acceptsOnlyStableCatalogId() {
        val action = TvChannelHandoff.ACTION
        val key = TvChannelHandoff.CHANNEL_ID
        val valid = Intent(action).putExtra(key, "fc-12345678-1234-1234-1234-123456789abc")
        assertEquals("fc-12345678-1234-1234-1234-123456789abc", TvChannelHandoff.read(valid))
        for (bad in listOf("", "../secret", "https://example.com/a.m3u8", "a b",
            "abc;def", "a".repeat(81), "قناة")) {
            assertNull(TvChannelHandoff.read(Intent(action).putExtra(key, bad)))
        }
        assertNull(TvChannelHandoff.read(Intent(Intent.ACTION_VIEW).putExtra(key, "fc-5")))
        assertNull(TvChannelHandoff.read(Intent(action).putExtra("url", "https://x.test/a.m3u8")))
    }
    @Test fun oldTvRequestNeverReopensTheLegacySportsTab() {
        val context = RuntimeEnvironment.getApplication()
        val request = Intent(TvChannelHandoff.ACTION)
            .putExtra(TvChannelHandoff.CHANNEL_ID, "fc-1424")
        val forwarded = TvChannelHandoff.legacyReceiverIntent(context, request)
        assertEquals(ComponentName("com.alatta.nativeapp",
            "com.alatta.nativeapp.GoldPlaybackReceiverActivity"), forwarded?.component)
        assertEquals(GoldPlaybackReceiverActivity.ACTION_LEGACY_CATALOG, forwarded?.action)
        assertEquals("fc-1424",
            forwarded?.getStringExtra(GoldPlaybackReceiverActivity.EXTRA_CATALOG_ID))
        assertFalse(forwarded?.hasExtra(GoldPlaybackReceiverActivity.EXTRA_SOURCE_NUMBER) ?: true)
        assertNull(TvChannelHandoff.legacyReceiverIntent(context, Intent(Intent.ACTION_VIEW)))
    }

    @Test fun onlyEnabledYtvCatalogChannelMayBecomeGoldEngineNumber() {
        fun ch(id: String, value: String, format: String = "ytv", enabled: Boolean = true) =
            FootballChannel(id, "Channel", "TV", "provider",
                "https://example.com/source", value, format, enabled)
        val catalog = FootballCatalog(channels = listOf(
            ch("fc-valid", "1424"),
            ch("fc-disabled", "1425", enabled = false),
            ch("fc-hls", "https://example.com/live.m3u8", format = "hls"),
            ch("fc-bad", "1;malformed")
        ))
        assertEquals("1424", TvChannelHandoff.engineNumber(catalog, "fc-valid"))
        assertNull(TvChannelHandoff.engineNumber(catalog, "fc-disabled"))
        assertNull(TvChannelHandoff.engineNumber(catalog, "fc-hls"))
        assertNull(TvChannelHandoff.engineNumber(catalog, "fc-bad"))
        assertNull(TvChannelHandoff.engineNumber(catalog, "fc-missing"))
    }

}
