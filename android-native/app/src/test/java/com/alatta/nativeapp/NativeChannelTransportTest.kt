package com.alatta.nativeapp

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeChannelTransportTest {
    @Test fun nativePlatformTransportUsesFallbackAgentAndPreservesExplicitHeadersAndExactPath() {
        val server = MockWebServer(); server.start()
        try {
            val context = RuntimeEnvironment.getApplication()
            val spec = DataSpec.Builder().setUri(Uri.parse(server.url("/live/index.m3u8?t=synthetic%2Btoken&e=123").toString())).build()
            val variants = listOf(emptyMap(), mapOf("User-Agent" to "Explicit fixture", "Referer" to "https://publisher.example/watch"))
            for (headers in variants) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n"))
                val source = NativeChannelPlayback.transport(context, headers).createDataSource()
                try { source.open(spec); assertTrue(source.read(ByteArray(64), 0, 64) > 0) }
                finally { source.close() }
                val request = server.takeRequest()
                assertEquals("/live/index.m3u8?t=synthetic%2Btoken&e=123", request.path)
                assertEquals(headers["User-Agent"] ?: NativeChannelPlayback.FALLBACK_USER_AGENT, request.getHeader("User-Agent"))
                assertEquals(headers["Referer"], request.getHeader("Referer"))
            }
        } finally { server.shutdown() }
    }

    @Test fun actualHttpFailureKeepsOnlyStatusAndFixedLabels() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403).setBody("private-response"))
            val source = NativeChannelPlayback.transport(RuntimeEnvironment.getApplication(), emptyMap()).createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(server.url("/secret-path?token=private-token").toString()).build())
                fail("Expected HTTP failure")
            } catch (error: java.io.IOException) {
                val report = MediaLoadTrace("load_error", C.DATA_TYPE_MANIFEST, 0, 100, failure = safeMediaFailure(error)).json()
                assertEquals(403, report.getInt("httpStatus")); assertEquals("manifest", report.getString("dataKind"))
                assertTrue(report.getJSONArray("errorKinds").toString().contains("HTTP_STATUS"))
                assertFalse(report.toString().contains("private-")); assertFalse(report.toString().contains("secret-path"))
            } finally { source.close() }
        } finally { server.shutdown() }
    }

    @Test fun connectionAndParserFailuresAreDistinctWithoutCopyingTheirMessages() {
        val cases = listOf(
            java.net.UnknownHostException("private-dns") to "DNS",
            javax.net.ssl.SSLHandshakeException("private-tls") to "TLS_HANDSHAKE",
            java.net.SocketTimeoutException("private-timeout") to "TIMEOUT",
            java.net.ConnectException("private-connect") to "CONNECT",
            java.io.EOFException("private-body") to "EOF")
        for ((error, expected) in cases) {
            val report = MediaLoadTrace("load_error", C.DATA_TYPE_MANIFEST, 0, 10,
                failure = safeMediaFailure(java.io.IOException("private-wrapper", error))).json()
            assertTrue(report.getJSONArray("errorKinds").toString().contains(expected))
            assertTrue(report.isNull("httpStatus")); assertFalse(report.toString().contains("private-"))
        }
    }
}
