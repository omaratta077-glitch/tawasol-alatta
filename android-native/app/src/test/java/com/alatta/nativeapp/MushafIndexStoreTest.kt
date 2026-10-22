package com.alatta.nativeapp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MushafIndexStoreTest {
    @Test fun realReaderIndexIsSharedWithCompleteChapterAndVerseMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val first = MushafIndexStore.load(context)
        assertSame(first, MushafIndexStore.load(context))
        assertEquals(605, first.pages.length())
        assertEquals(114, first.chapters.size)
        assertEquals(240, first.quarterStarts.size)
        assertEquals(6237, first.ayahs.length()) // index zero is the sentinel
        assertEquals(1, first.chapters.first().first)
        assertEquals(114, first.chapters.last().first)
        assertTrue(first.pages.getJSONObject(604).getJSONArray("markers").length() > 0)
    }
    @Test fun indexSearchAcceptsArabicNumbersAndUnvocalizedNames() {
        assertEquals("114", mushafIndexSearch("١١٤"))
        assertEquals("604", mushafIndexSearch("۶۰۴"))
        assertEquals("الفاتحة", mushafIndexSearch("ٱلْفَاتِحَة"))
        assertEquals("٢٤٠", mushafArabicNumber(240))
        val index = MushafIndexStore.load(RuntimeEnvironment.getApplication())
        assertTrue(index.chapters.any { mushafIndexSearch(it.second).contains(mushafIndexSearch("الفاتحة")) })
        assertEquals(604, index.chapters.last().third)
        assertTrue(index.quarterStarts.all { it in 1..604 })
    }
    @Test fun concurrentRequestsRunOneLoaderAndFailedLoadsCanRetry() {
        val cache = SingleLoadCache<Any>()
        val count = AtomicInteger()
        try { cache.get { throw java.io.IOException("retry") }; fail() } catch (_: java.io.IOException) { }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..24).map { executor.submit<Any> { start.await(); cache.get { count.incrementAndGet(); Any() } } }
            start.countDown()
            val objects = jobs.map { it.get(5, TimeUnit.SECONDS) }
            objects.forEach { assertSame(objects[0], it) }
            assertEquals(1, count.get())
        } finally { executor.shutdownNow() }
    }
}
