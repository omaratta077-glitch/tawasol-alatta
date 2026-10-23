package com.alatta.nativeapp

import org.junit.Assert.*
import org.junit.Test

class DhikrShareTest {
    @Test fun currentEntryDoesNotIncludeOtherEntries() {
        val group = dhikrGroups.first()
        val result = formatDhikrShare(group, group.entries.first())
        assertTrue(result.contains(group.title))
        assertTrue(result.contains(group.entries.first().text))
        val other = group.entries.last()
        if (other.text != group.entries.first().text) assertFalse(result.contains(other.text))
    }
    @Test fun entireGroupsKeepEveryTextReferenceNoteAndRepetition() {
        for (group in dhikrGroups) {
            val result = formatDhikrShare(group)
            for (entry in group.entries) {
                assertTrue(result.contains(entry.text))
                assertTrue(result.contains(entry.source))
                assertTrue(result.contains(entry.note))
                assertTrue(result.contains("التكرار: ${entry.target}"))
            }
            assertEquals(result, composeHadithPost("", result))
        }
    }
}
