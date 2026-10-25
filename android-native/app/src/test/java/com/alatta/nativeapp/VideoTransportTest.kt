package com.alatta.nativeapp

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.ByteArrayOutputStream
import java.io.File
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class VideoTransportTest {
    private fun read(factory: DataSource.Factory, spec: DataSpec): ByteArray {
        val source = factory.createDataSource()
        val output = ByteArrayOutputStream()
        try {
            source.open(spec)
            val buffer = ByteArray(4096)
            while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; output.write(buffer, 0, n) }
        } finally { source.close() }
        return output.toByteArray()
    }
    @Test fun secondPlaybackAndSeekingReuseDownloadedBytesWithoutAnotherRequest() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.cacheDir, "test-video-cache").apply { deleteRecursively() }
        val cache = SimpleCache(folder, LeastRecentlyUsedCacheEvictor(1024 * 1024), StandaloneDatabaseProvider(context))
        val server = MockWebServer()
        server.start()
        try {
            val bytes = ByteArray(32000) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "video/mp4"))
            val uri = Uri.parse(server.url("/fixture.mp4").toString())
            val factory = VideoTransport.routedFactory(context, cache, eligible = { it == uri })
            assertArrayEquals(bytes, read(factory, DataSpec.Builder().setUri(uri).build()))
            assertArrayEquals(bytes, read(factory, DataSpec.Builder().setUri(uri).build()))
            assertArrayEquals(bytes.copyOfRange(100, 300), read(factory, DataSpec.Builder().setUri(uri).setPosition(100).setLength(200).build()))
            assertEquals(1, server.requestCount)
            assertTrue(cache.cacheSpace > 0)
            assertTrue(cache.cacheSpace <= 1024 * 1024)
            // Existing inline video attachments still use Media3's data source.
            assertArrayEquals(byteArrayOf(1, 2, 3), read(factory, DataSpec.Builder().setUri("data:video/mp4;base64,AQID").build()))
        } finally { cache.release(); server.shutdown(); folder.deleteRecursively() }
    }
    @Test fun cacheIsRestrictedToTheApplicationsOwnPublicMedia() {
        assertTrue(VideoTransport.canCache(Uri.parse("$ATTA_API_BASE/media/video/one.mp4")))
        for (url in listOf("https://youtube.com/watch?v=x", "https://external.test/live.m3u8", "$ATTA_API_BASE/api/private?token=x", "data:video/mp4;base64,AQID")) {
            assertFalse(VideoTransport.canCache(Uri.parse(url)))
        }
    }
    @Test fun providerHeadersAreScopedToTheirPlayerAndDoNotEnterTheSharedCache() {
        val context = RuntimeEnvironment.getApplication()
        val server = MockWebServer(); server.start()
        try {
            val headers = mapOf("User-Agent" to "Provider fixture", "Referer" to "https://publisher.example/watch")
            val source = VideoTransport.factory(context, live = false, requestHeaders = headers)
            val plain = VideoTransport.factory(context, live = true)
            val spec = DataSpec.Builder().setUri(server.url("/stream.mp4").toString()).build()
            repeat(3) { server.enqueue(MockResponse().setBody("fixture")) }
            read(source, spec); read(source, spec); read(plain, spec)
            val first = server.takeRequest(); val second = server.takeRequest(); val third = server.takeRequest()
            assertEquals("Provider fixture", first.getHeader("User-Agent"))
            assertEquals(headers["Referer"], first.getHeader("Referer"))
            assertEquals("Provider fixture", second.getHeader("User-Agent"))
            assertNull(third.getHeader("Referer"))
            assertNotEquals("Provider fixture", third.getHeader("User-Agent"))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
}
