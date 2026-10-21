package com.alatta.nativeapp

import org.junit.Assert.*
import org.junit.Test

class HadithPostShareTest {
    @Test fun keepsEntireNarrationNumberAndSource() {
        val original = "إنما الأعمال بالنيات\n\nصحيح البخاري • الحديث 1\nالطبعة المعتمدة"
        assertEquals(original, composeHadithPost("", original))
        assertEquals("تعليق من المستخدم\n\n$original", composeHadithPost(" تعليق من المستخدم ", original))
    }

    @Test fun neverSilentlyTruncatesLongHadithOrAnExistingDraft() {
        assertNotNull(composeHadithPost("", "ح".repeat(5000)))
        assertNull(composeHadithPost("", "ح".repeat(5001)))
        assertNull(composeHadithPost("م".repeat(100), "ح".repeat(4900)))
        assertNull(composeHadithPost("", "  "))
    }
}
