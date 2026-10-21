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
        assertEquals(34, morning.size); assertEquals(30, evening.size)
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

    @Test fun dailyAdditionsAreDistinctAndDoNotInflateCountsWithRepeatedCards() {
        val additions = setOf("fatiha", "baqarah-ending", "salawat-daily", "worry-debt", "four-phrases", "hawqala", "beloved-words", "all-good", "lasting-blessing")
        fun canonical(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("[\\p{M}\\p{P}\\p{Z}\\s]"), "")
        for (group in dhikrGroups.filter { it.id in setOf("morning", "evening") }) {
            val newEntries = group.entries.filter { it.id in additions }
            assertEquals(additions, newEntries.map { it.id }.toSet())
            assertEquals(9, newEntries.map { canonical(it.text) }.toSet().size)
            val existing = group.entries.filterNot { it.id in additions }.map { canonical(it.text) }.toSet()
            assertTrue(newEntries.none { canonical(it.text) in existing })
            assertTrue(newEntries.all { it.target == 1 && it.note.isNotBlank() })
            // The previously offered one-time and daily hundred Tawhid remain separate goals.
            assertEquals(group.entries.size, group.entries.map { canonical(it.text) to it.target }.toSet().size)
        }
    }

    @Test fun generalReadingsExplainTheirTimingAndPreserveTheFullQuranPassages() {
        for (id in listOf("morning", "evening")) {
            val entries = dhikrGroups.first { it.id == id }.entries
            val fatiha = entries.first { it.id == "fatiha" }
            assertEquals(6, fatiha.text.count { it == '۝' })
            assertTrue(fatiha.note.contains("عامة"))
            val ending = entries.first { it.id == "baqarah-ending" }
            assertTrue(ending.text.startsWith("آمَنَ الرَّسُولُ"))
            assertTrue(ending.text.endsWith("الْقَوْمِ الْكَافِرِينَ"))
            assertTrue(ending.note.contains("الليل"))
            assertEquals(1, entries.first { it.id == "salawat-daily" }.target)
            assertTrue(entries.first { it.id == "salawat-daily" }.note.contains("ليست عددًا مخصوصًا"))
        }
    }
}
