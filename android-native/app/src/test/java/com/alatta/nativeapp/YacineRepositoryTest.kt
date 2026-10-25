package com.alatta.nativeapp

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YacineRepositoryTest {
    private fun repo(catalog: MockWebServer, playback: MockWebServer = catalog, client: OkHttpClient = OkHttpClient()) =
        YacineRepository(catalog.url("/api/"), playback.url("/api/"), client)

    private fun failure(block: suspend () -> Unit): YacineFailure = runBlocking {
        try { block(); error("Expected provider failure") } catch (e: YacineFailure) { e }
    }

    @Test fun catalogSearchAndPlaybackUseTheirOwnRoutesWithoutAppCredentials() = runBlocking {
        val catalog = MockWebServer(); val playback = MockWebServer()
        catalog.start(); playback.start()
        try {
            val repository = repo(catalog, playback)
            catalog.enqueue(MockResponse().setBody("""{"data":[{"id":7,"name":"رياضة"},{"id":7,"name":"مكرر"}]}"""))
            assertEquals(listOf(YacineCategory("7", "رياضة")), repository.categories())
            catalog.enqueue(MockResponse().setBody("""{"channels":[{"id":41,"name":"قناة تجريبية"}]}"""))
            assertEquals("41", repository.channels("7").single().id)
            catalog.enqueue(MockResponse().setBody("""{"data":{"channels":[{"id":41,"name":"قناة تجريبية"}]}}"""))
            assertEquals("41", repository.search("قناة & test").single().id)
            playback.enqueue(MockResponse().setBody("""{"data":{"id":41,"name":"قناة تجريبية"},"servers":[{"name":"HD","url":"https://cdn.publisher.example/live.m3u8?token=fixture","userAgent":"Fixture Player","headers":{"Referer":"https://publisher.example/watch"}}]}"""))
            val stream = repository.streams("41").single()
            assertEquals("application/x-mpegURL", stream.mimeType)
            assertEquals("Fixture Player", stream.headers["User-Agent"])
            assertEquals("https://publisher.example/watch", stream.headers["Referer"])
            val requests = listOf(catalog.takeRequest(), catalog.takeRequest(), catalog.takeRequest(), playback.takeRequest())
            assertEquals(listOf("/api/categories", "/api/categories/7/channels", "/api/search", "/api/channel/41"), requests.map { it.requestUrl!!.encodedPath })
            assertEquals("قناة & test", requests[2].requestUrl!!.queryParameter("query"))
            assertTrue(requests.all { it.method == "GET" && it.getHeader("Authorization") == null && it.getHeader("Cookie") == null })
        } finally { catalog.shutdown(); playback.shutdown() }
    }

    @Test fun deniedAndUnknownHttpStatusesRemainExplicitWithoutDisplayingTheResponseBody() {
        val server = MockWebServer(); server.start()
        try {
            for (status in listOf(401, 403, 436, 503)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("<html>private-provider-detail</html>"))
                val error = failure { repo(server).categories() }
                assertEquals(YacineProblem.HTTP, error.problem)
                assertEquals(status, error.status)
                assertEquals(YacineStage.CATEGORIES, error.stage)
                assertTrue(error.explanation.contains(status.toString()))
                assertFalse(error.explanation.contains("private-provider-detail"))
            }
        } finally { server.shutdown() }
    }

    @Test fun htmlEncryptedAndMalformedPayloadsAreNotReportedAsAnEmptyWorkingCatalog() {
        val server = MockWebServer(); server.start()
        try {
            for (body in listOf("<html>Renew domain</html>", """{"data":"encoded-fixture"}""", """{"data":[{"name":"Missing ID"}]}""")) {
                server.enqueue(MockResponse().setBody(body))
                assertEquals(YacineProblem.FORMAT, failure { repo(server).categories() }.problem)
            }
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            assertTrue(runBlocking { repo(server).categories() }.isEmpty())
        } finally { server.shutdown() }
    }

    @Test fun redirectsAreNotFollowedAndOversizedRepliesAreRejected() {
        val server = MockWebServer(); val destination = MockWebServer()
        server.start(); destination.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/parking")))
            assertEquals(302, failure { repo(server).categories() }.status)
            assertEquals(0, destination.requestCount)
            server.enqueue(MockResponse().setBody("[]").setHeader("Content-Length", YacineRepository.MAX_RESPONSE_BYTES + 1))
            assertEquals(YacineProblem.TOO_LARGE, failure { repo(server).categories() }.problem)
        } finally { server.shutdown(); destination.shutdown() }
    }

    @Test fun unsupportedProtectionUnsafeUrlsAndHeaderInjectionNeverReachThePlayer() {
        val server = MockWebServer(); server.start()
        try {
            val fixtures = listOf(
                """{"url":"http://cdn.publisher.example/live.m3u8"}""",
                """{"url":"https://localhost/live.m3u8"}""",
                """{"url":"https://publisher.example/watch.html"}""",
                """{"url":"https://cdn.publisher.example/live.mpd","drm":{"type":"fixture"}}""",
                """{"url":"https://cdn.publisher.example/live.m3u8","userAgent":"bad\r\nInjected: value"}""",
                """{"url":"https://cdn.publisher.example/live.m3u8","headers":{"Authorization":"fixture"}}"""
            )
            for (item in fixtures) {
                server.enqueue(MockResponse().setBody("{\"servers\":[$item]}"))
                val error = failure { repo(server).streams("41") }
                assertEquals(YacineProblem.STREAM, error.problem)
                assertEquals(YacineStage.PLAYBACK, error.stage)
            }
            server.enqueue(MockResponse().setBody("""{"servers":[{"url":"https://cdn.publisher.example/live.mpd"},{"url":"https://cdn.publisher.example/video.mp4"}]}"""))
            assertEquals(listOf("application/dash+xml", "video/mp4"), runBlocking { repo(server).streams("41") }.map { it.mimeType })
        } finally { server.shutdown() }
    }

    @Test fun invalidIdentifiersDoNotCreateRequestsAndCancellationStopsTheCall() = runBlocking {
        val server = MockWebServer(); server.start()
        val client = OkHttpClient()
        try {
            for (id in listOf("../config", "1?x=2", "")) {
                try { repo(server).channels(id); fail("Expected invalid ID") } catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("[]").setHeadersDelay(2, TimeUnit.SECONDS))
            val job = async(Dispatchers.IO) { repo(server, client = client).categories() }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            withTimeout(3000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
            assertTrue(job.isCancelled)
        } finally { server.shutdown() }
    }
}
