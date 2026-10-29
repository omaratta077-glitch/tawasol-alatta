package com.alatta.nativeapp

import androidx.media3.common.PlaybackException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeChannelPlaybackTest {
    private fun payload(url: String = "https://cdn.example/live.m3u8?token=synthetic-token",
        headers: JSONObject = JSONObject().put("User-Agent", "Synthetic Player")) = JSONObject().put("sources",
        JSONArray().put(JSONObject().put("index", 1).put("url", url).put("headers", headers).put("mimeType", "application/x-mpegURL")))

    @Test fun parsingPreservesExactSignedUrlAndUserAgentWithoutIncludingThemInToString() {
        val source = NativeChannelPlayback.parse(payload()).single()
        assertEquals("https://cdn.example/live.m3u8?token=synthetic-token", source.url)
        assertEquals("Synthetic Player", source.headers["User-Agent"])
        assertFalse(source.toString().contains("synthetic-token"))
        assertFalse(source.toString().contains("Synthetic Player"))
    }
    @Test fun observedHttpCdnIsAcceptedButUnknownCleartextAndPrivateDestinationsAreRejected() {
        assertEquals(1, NativeChannelPlayback.parse(payload("http://test.new-redirect.online/live/index.m3u8?t=synthetic")).size)
        for (url in listOf("http://other.example/live", "https://127.0.0.1/x", "https://device.local/x",
            "https://user:pass@cdn.example/x", "file:///tmp/video", "https://cdn.example:8443/live")) {
            try { NativeChannelPlayback.parse(payload(url)); fail("Expected invalid destination") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun sourceCannotSmuggleAppCredentialsOrInjectedHeaders() {
        for (headers in listOf(JSONObject().put("Authorization", "secret"), JSONObject().put("Cookie", "secret"),
            JSONObject().put("User-Agent", "agent\r\nHost: other"))) {
            try { NativeChannelPlayback.parse(payload(headers = headers)); fail("Expected invalid headers") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun errorReportUsesNumericCodeWithoutExceptionMessageOrUrls() {
        val failure = PlaybackException("https://private.example/?token=secret", null, PlaybackException.ERROR_CODE_TIMEOUT)
        assertEquals(PlaybackException.ERROR_CODE_TIMEOUT to null, NativeChannelPlayback.failure(failure))
    }
}
