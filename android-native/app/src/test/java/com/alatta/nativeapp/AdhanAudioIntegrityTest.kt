package com.alatta.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class AdhanAudioIntegrityTest {
    private val recording = ByteArray(50000) { (it % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(recording).joinToString("") { "%02x".format(it) }
    private fun copy(bytes: ByteArray, size: Long = recording.size.toLong(), sha: String = hash): ByteArray = ByteArrayOutputStream().use { out ->
        copyVerifiedAdhan(ByteArrayInputStream(bytes), out, size, sha); out.toByteArray()
    }
    @Test fun completeRecordingIsCopiedWithoutChange() { assertArrayEquals(recording, copy(recording)) }
    @Test fun truncatedDownloadCannotBecomeSelectedRecording() { assertTrue(runCatching { copy(recording.copyOf(49000)) }.isFailure) }
    @Test fun wrongOrOverlongRecordingIsRejected() {
        assertTrue(runCatching { copy(recording.copyOf().apply { this[30] = 99 }) }.isFailure)
        assertTrue(runCatching { copy(recording + byteArrayOf(1)) }.isFailure)
    }
    @Test fun invalidSizeAndDigestAreRejectedBeforeReading() {
        for (size in listOf(0L, -1L, ADHAN_MAX_BYTES + 1L)) assertTrue(runCatching { copy(recording, size) }.isFailure)
        assertTrue(runCatching { copy(recording, sha = "not-a-hash") }.isFailure)
    }
    @Test fun cancellationDoesNotCommitTheDownload() {
        var calls = 0
        assertTrue(runCatching { copyVerifiedAdhan(ByteArrayInputStream(recording), ByteArrayOutputStream(), recording.size.toLong(), hash) {
            if (++calls == 2) throw CancellationException()
        } }.exceptionOrNull() is CancellationException)
    }
    @Test fun morningAndEveningHaveTheExpandedCollectionAndKeepExistingIds() {
        val morning = dhikrGroups.first { it.id == "morning" }.entries
        val evening = dhikrGroups.first { it.id == "evening" }.entries
        assertEquals(24, morning.size); assertEquals(21, evening.size)
        for ((id, entries) in listOf("morning" to morning, "evening" to evening)) {
            assertTrue(entries.map { it.id }.containsAll(listOf("kursi", "ikhlas", "falaq", "nas", "sayyid", "protection", id, "tasbih")))
            assertEquals(4, entries.first { it.id == "witness" }.target)
            assertEquals(3, entries.first { it.id == "health" }.target)
            assertEquals(7, entries.first { it.id == "reliance" }.target)
        }
        assertTrue(morning.first { it.id == "kingdom" }.text.contains("هَذَا الْيَوْمِ"))
        assertTrue(evening.first { it.id == "kingdom" }.text.contains("هَذِهِ اللَّيْلَةِ"))
        assertFalse(evening.any { it.id == "knowledge" || it.id == "praise-three" })
        assertEquals(3, evening.first { it.id == "refuge" }.target)
    }
}
