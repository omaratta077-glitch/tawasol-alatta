package com.alatta.nativeapp

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttaHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    @Before fun start() {
        server = MockWebServer(); server.start()
        client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(2, TimeUnit.SECONDS).build()
    }
    @After fun stop() { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.shutdown() }
    @Test fun safeGetRecoversOnceAfterConnectionClosesBeforeAResponse() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("{\"ok\":true,\"value\":\"restored\"}"))
        val result = AttaHttp.json(server.url("/read").newBuilder().host("127.0.0.1").build().toString(), client = client)
        assertEquals("restored", result.data.getString("value"))
        assertEquals(2, server.requestCount)
    }
    @Test fun anAmbiguousPostIsNeverReplayed() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        try { AttaHttp.json(server.url("/publish").toString(), JSONObject().put("text", "once"), client); fail("Must report the disconnect") }
        catch (_: IOException) { }
        assertEquals(1, server.requestCount)
        assertEquals("once", JSONObject(server.takeRequest().body.readUtf8()).getString("text"))
    }
    @Test fun reusesTheConnectionAndDoesNotRetryHttpErrorsOrOversizedReplies() {
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"ok\":false,\"error\":\"busy\"}"))
        AttaHttp.json(server.url("/first").toString(), client = client)
        assertEquals(503, AttaHttp.json(server.url("/second").toString(), client = client).code)
        assertEquals(0, server.takeRequest().sequenceNumber)
        assertEquals(1, server.takeRequest().sequenceNumber)
        server.enqueue(MockResponse().setChunkedBody("x".repeat(100), 8))
        try { AttaHttp.json(server.url("/large").toString(), client = client, maxBytes = 16); fail("Must bound unknown-length bodies") }
        catch (_: ResponseTooLarge) { }
        assertEquals(3, server.requestCount)
    }
    @Test fun serverAccountErrorsRemainExactWhileTransportErrorsAreReadable() {
        val message = "اسم المستخدم أو كلمة المرور غير صحيحة"
        assertEquals(message, loadingFailureMessage(AttaApiException(401, message)))
        assertTrue(loadingFailureMessage(java.net.SocketException("Connection reset")).contains("انقطع الاتصال"))
    }
}
