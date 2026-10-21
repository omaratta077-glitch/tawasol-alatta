package com.alatta.nativeapp

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MushafPageStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun pageBytes(page: Int) = File("src/main/assets/mushaf/pages/" + String.format(Locale.ROOT, "%03d.svg.gz", page)).readBytes()
    @Before fun clearCache() { File(context.filesDir, "mushaf-pages").deleteRecursively() }

    private class Connection(url: URL, private val body: ByteArray, private val status: Int = 200,
        private val length: Long = body.size.toLong()) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getInputStream() = ByteArrayInputStream(body)
    }
    private fun fails(block: () -> Unit) { try { block(); fail("An invalid download must fail") } catch (_: IOException) {} }

    @Test fun downloadsVerifiesAndReusesTheSamePageOffline() {
        var requests = 0
        val bytes = pageBytes(2)
        lateinit var connection: Connection
        val cached = MushafPageStore.ensureCached(context, 2) { url ->
            requests++
            assertEquals("https", url.protocol)
            assertTrue(url.path.contains("/ab1fda7e4657b872ec77a596f2453e0da88d1507/"))
            Connection(url, bytes).also { connection = it }
        }
        assertArrayEquals(bytes, cached.readBytes())
        assertTrue(connection.disconnected)
        assertTrue(MushafPageStore.isDownloaded(context, 2))
        assertEquals(cached, MushafPageStore.ensureCached(context, 2, allowNetwork = false) { error("No second download") })
        MushafPageStore.openPage(context, 2, allowNetwork = false).use { assertTrue(it.bufferedReader().readText().contains("<svg")) }
        assertEquals(1, requests)
    }

    @Test fun rejectsCorruptTruncatedOversizedRedirectedAndInterruptedDownloadsThenRetries() {
        val bytes = pageBytes(2)
        for ((body, status, length) in listOf(
            Triple(bytes.copyOf().apply { this[100] = (this[100].toInt() xor 1).toByte() }, 200, bytes.size.toLong()),
            Triple(bytes.copyOf(bytes.size - 1), 200, (bytes.size - 1).toLong()),
            Triple(bytes + byteArrayOf(1), 200, -1L),
            Triple(bytes, 302, bytes.size.toLong()),
            Triple(bytes, 200, bytes.size + 1L)
        )) {
            fails { MushafPageStore.ensureCached(context, 2) { Connection(it, body, status, length) } }
            assertFalse(MushafPageStore.isDownloaded(context, 2))
            assertFalse(File(context.filesDir, "mushaf-pages/002.svg.gz.part").exists())
        }
        fails { MushafPageStore.ensureCached(context, 2) { throw IOException("network lost") } }
        assertTrue(MushafPageStore.ensureCached(context, 2) { Connection(it, bytes) }.isFile)
    }

    @Test fun replacesCorruptCacheAndCanSaveTheLastPage() {
        val bytes = pageBytes(2)
        val file = MushafPageStore.ensureCached(context, 2) { Connection(it, bytes) }
        file.writeBytes(bytes.copyOf().apply { this[100] = (this[100].toInt() xor 1).toByte() })
        assertFalse(MushafPageStore.isDownloaded(context, 2))
        fails { MushafPageStore.ensureCached(context, 2, allowNetwork = false) { error("Offline mode must not connect") } }
        assertArrayEquals(bytes, MushafPageStore.ensureCached(context, 2) { Connection(it, bytes) }.readBytes())
        val last = pageBytes(604)
        MushafPageStore.ensureCached(context, 604) { Connection(it, last) }
        assertTrue(MushafPageStore.isDownloaded(context, 604))
        assertFalse(MushafPageStore.isDownloaded(context, 605))
    }

    @Test fun openingPageNeedsNoConnectionAndOfflineModeDoesNotStartOne() {
        MushafPageStore.openPage(context, 1, allowNetwork = false).use { assertTrue(it.bufferedReader().readText().contains("<svg")) }
        fails { MushafPageStore.ensureCached(context, 2, allowNetwork = false) { error("Offline mode must not connect") } }
    }

    @Test fun cachedPageDoesNotWaitForAnUnrelatedNetworkDownload() {
        MushafPageStore.ensureCached(context, 2) { Connection(it, pageBytes(2)) }
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val downloading = pool.submit<java.io.File> {
                MushafPageStore.ensureCached(context, 3) {
                    started.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    Connection(it, pageBytes(3))
                }
            }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val cached = pool.submit<java.io.File> {
                MushafPageStore.ensureCached(context, 2, allowNetwork = false) { error("Must stay offline") }
            }
            assertTrue(cached.get(1, java.util.concurrent.TimeUnit.SECONDS).isFile)
            release.countDown()
            assertTrue(downloading.get(5, java.util.concurrent.TimeUnit.SECONDS).isFile)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
