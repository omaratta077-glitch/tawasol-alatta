package com.alatta.nativeapp
import org.junit.Assert.*
import org.junit.Test
class PostTextContentTest {
    @Test fun wholeDhikrGroupsKeepAllTextButUseCompactFeedPreviews() {
        for (group in dhikrGroups) {
            val full = formatDhikrShare(group)
            assertTrue(needsPostReader(full))
            val preview = postTextPreview(full)
            assertTrue(preview.length <= 320)
            assertTrue(preview.lines().size <= 6)
            assertTrue(full.startsWith(preview))
            for (entry in group.entries) assertTrue(full.contains(entry.text))
            assertEquals(full, composeHadithPost("", full))
        }
    }
    @Test fun shortPostsStayOrdinaryAndLongMultilinePostsUseReader() {
        assertFalse(needsPostReader("منشور قصير"))
        assertFalse(needsPostReader("x".repeat(500)))
        assertTrue(needsPostReader("x".repeat(501)))
        assertTrue(needsPostReader((1..10).joinToString("\n") { "ذكر" }))
        assertFalse(postTextPreview("x".repeat(319) + "😀").last().isHighSurrogate())
    }
}
