package com.alatta.nativeapp

import org.junit.Assert.*
import org.junit.Test

class MushafShareRangeTest {
    private val origin = "https://tawasol-alatta.omaratta077-4fd.workers.dev"

    @Test fun allThirtyPartsCoverEveryPrintedPageExactlyOnce() {
        val parts = (1..30).map(::mushafPartSelection)
        assertEquals((1..604).toList(),parts.flatMap { it.pages.toList() })
        assertEquals(1..21,parts.first().pages)
        assertEquals(582..604,parts.last().pages)
        assertTrue(parts.all { it.pageCount in 1..30 })
    }

    @Test fun pageAndRangeSharingIncludeBothBoundaries() {
        assertEquals(1,MushafShareSelection(604,604,"صفحة").pageCount)
        assertEquals(30,MushafShareSelection(22,51,"ورد").pageCount)
        for ((first,last) in listOf(0 to 1,604 to 605,100 to 99,1 to 31)) {
            assertThrows(IllegalArgumentException::class.java) { MushafShareSelection(first,last,"غير صالح") }
        }
        for (part in listOf(0,31,-1)) assertThrows(IllegalArgumentException::class.java) { mushafPartSelection(part) }
    }

    @Test fun uploadedPartLinkRoundTripsThroughNormalPostText() {
        val range = mushafPartSelection(30)
        val path = "/media/file/123-abc.pdf"
        val link = mushafAttachmentLink(origin,path,range)
        val parsed = parseMushafPostAttachment("ورد اليوم\n\n$link\n",origin)!!
        assertEquals(582..604,parsed.pages)
        assertEquals(origin+path,parsed.pdfUrl)
    }

    @Test fun untrustedOrMalformedDocumentLinksAreNeverTreatedAsQuranShares() {
        val suffix = "#mushaf=1-21"
        for (link in listOf(
            "https://evil.example/media/file/a.pdf$suffix",
            "https://user@tawasol-alatta.omaratta077-4fd.workers.dev/media/file/a.pdf$suffix",
            "$origin/media/image/a.pdf$suffix",
            "$origin/media/file/a%2F..pdf$suffix",
            "$origin/media/file/a.pdf#mushaf=0-21",
            "$origin/media/file/a.pdf#mushaf=1-605",
            "$origin/media/file/a.pdf#mushaf=1-2100",
            "$origin/media/file/a.pdf#mushaf=21-1",
            "$origin/media/file/a.pdf#mushaf=1-31",
            "$origin/media/file/a.pdf#mushaf=1-21junk")) {
            assertNull(link,parseMushafPostAttachment(link,origin))
        }
    }

    @Test fun invalidUploadPathCannotCreateAnAttachmentLink() {
        assertThrows(IllegalArgumentException::class.java) {
            mushafAttachmentLink(origin,"https://evil.example/file.pdf",mushafPartSelection(1))
        }
    }
}
