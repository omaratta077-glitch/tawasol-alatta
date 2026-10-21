package com.alatta.nativeapp

import org.junit.Assert.*
import org.junit.Test

class HadithTextTest {
    @Test fun vocalizedArabicAndPlainArabicHaveTheSameSearchTerms() {
        assertEquals("انما الاعمال بالنيات", normalizeHadithSearch("إِنَّمَا الأَعْمَالُ بِالنِّيَّاتِ"))
        assertEquals("الله صلي الله عليه وسلم", normalizeHadithSearch("ﷲ ﷺ"))
        assertEquals("الي الله", normalizeHadithSearch("إِلَـى ٱللَّهِ"))
    }

    @Test fun arabicAndPersianNumbersNavigateToTheOriginalEditionNumber() {
        for (value in listOf("7008", "٧٠٠٨", "۷۰۰۸", " ٠٠٧٠٠٨ ")) {
            val search = hadithSearch(value)
            assertEquals(7008, search.number)
            assertNull(search.match)
        }
    }

    @Test fun zeroAndOversizedNumbersDoNotOverflowOrSelectTheFirstHadith() {
        for (value in listOf("0", "٠", "999999999999999999999999")) {
            assertNull(hadithSearch(value).number)
        }
    }

    @Test fun searchPrefixesEveryTermWithoutChangingTheDisplayedText() {
        val original = "إِنَّمَا الأَعْمَالُ"
        assertEquals("انما* الاعمال*", hadithSearch(original).match)
        assertEquals("إِنَّمَا الأَعْمَالُ", original)
    }

    @Test fun ftsAndSqlPunctuationCannotBecomeOperators() {
        val search = hadithSearch("\"الله\" OR (رحمة*) ; DROP TABLE hadiths --")
        assertEquals("الله* or* رحمة* drop* table* hadiths*", search.match)
        assertFalse(search.match!!.contains('"'))
        assertFalse(search.match!!.contains(';'))
        assertNull(hadithSearch("\"*():;- ").match)
        assertEquals("", hadithSearch("\"*():;- ").text)
    }

    @Test fun longQueriesHaveBoundedTermsAndLength() {
        val search = hadithSearch((1..50).joinToString(" ") { "كلمة$it" })
        assertEquals(8, search.match!!.split(' ').size)
        assertTrue(search.text.length <= 200)
    }
}
