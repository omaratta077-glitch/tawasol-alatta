package com.alatta.nativeapp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedStreamsTest {
    @Test fun exactLimitAndChunkBoundariesPreserveBytes() {
        for (size in listOf(0, 1, 65535, 65536, 65537, 150000)) {
            val bytes = ByteArray(size) { (it % 251).toByte() }
            val output = ByteArrayOutputStream()
            assertEquals(size.toLong(), copyMediaBounded(ByteArrayInputStream(bytes), output, size.toLong()))
            assertArrayEquals(bytes, output.toByteArray())
        }
    }
    @Test fun neverBuffersOrWritesPastTheLimitForAnUnboundedProvider() {
        var reads = 0
        var largestRead = 0
        val infinite = object : InputStream() {
            override fun read(): Int = error("Use bounded chunks")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++; largestRead = maxOf(largestRead, len)
                java.util.Arrays.fill(b, off, off + len, 1.toByte())
                return len
            }
        }
        val output = ByteArrayOutputStream()
        try { copyMediaBounded(infinite, output, 100000L); fail("Must reject oversized media") }
        catch (_: IllegalArgumentException) { }
        assertEquals(2, reads)
        assertTrue(largestRead <= 64 * 1024)
        assertEquals(65536, output.size())
    }
    @Test fun interruptedUploadsStopBeforeReadingOrWriting() {
        val output = ByteArrayOutputStream()
        Thread.currentThread().interrupt()
        try {
            try { copyMediaBounded(ByteArrayInputStream(byteArrayOf(1)), output, 10); fail() }
            catch (_: java.io.InterruptedIOException) { }
            assertEquals(0, output.size())
        } finally { Thread.interrupted() }
    }
}
