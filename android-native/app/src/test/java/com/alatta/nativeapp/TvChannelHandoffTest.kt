package com.alatta.nativeapp

import android.content.Intent
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
}
