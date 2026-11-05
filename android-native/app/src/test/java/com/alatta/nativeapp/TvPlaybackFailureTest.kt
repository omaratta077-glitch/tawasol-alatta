package com.alatta.nativeapp

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvPlaybackFailureTest {
    private val channel = FootballChannel(
        "stable-id", "beIN SPORTS1", "beIN SPORTS (1080P)", "tv",
        "https://example.org", "1424", "ytv", true
    )

    @Test fun sourceRejectionIsDistinguishedFromPlayerErrors() {
        val result = tvPlaybackFailureMessage(
            channel,
            AttaApiException(502, "تعذر جلب بيانات تشغيل القناة (HTTP 403؛ PLAYBACK_API_HTTP؛ SOURCE_HTTP_ERROR).")
        )
        assertTrue(result.contains("رفض"))
        assertTrue(result.contains("HTTP المصدر 403"))
        assertTrue(result.contains("SOURCE_HTTP_ERROR"))
        assertTrue(result.contains("1424"))
    }

    @Test fun sourceRotationAndUnsupportedStreamsAreActionable() {
        assertTrue(tvPlaybackFailureMessage(channel,
            AttaApiException(502, "FRESH_ID_UNCHANGED HTTP 404")).contains("ما زال كما هو"))
        assertTrue(tvPlaybackFailureMessage(channel,
            AttaApiException(422, "NO_SUPPORTED_NATIVE_SOURCE")).contains("ليس بها رابط"))
        assertTrue(tvPlaybackFailureMessage(channel,
            AttaApiException(502, "PLAYBACK_API_DECODE HTTP 200")).contains("قراءة بيانات"))
    }

    @Test fun privateUpstreamTextNeverAppearsInMessage() {
        val secret = "https://private.example/watch?token=DO_NOT_COPY"
        val error = tvPlaybackFailureMessage(channel, AttaApiException(502,
            "SOURCE_NETWORK_ERROR $secret cookie=DO_NOT_COPY"))
        assertTrue(error.contains("SOURCE_NETWORK_ERROR"))
        assertFalse(error.contains(secret))
        assertFalse(error.contains("DO_NOT_COPY"))
    }

    @Test fun reauthorizationAndRateLimitsHaveDistinctMessages() {
        assertTrue(tvPlaybackFailureMessage(channel,
            AttaApiException(401, "anything")).contains("سجّل الدخول"))
        assertTrue(tvPlaybackFailureMessage(channel,
            AttaApiException(429, "anything")).contains("ثانيتين"))
    }
}
