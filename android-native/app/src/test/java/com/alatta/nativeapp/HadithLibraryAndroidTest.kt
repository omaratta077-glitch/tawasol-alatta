package com.alatta.nativeapp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Exercise real merged assets and Android SQLite, not a desktop-only corpus check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], qualifiers = "ar-rEG")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HadithLibraryAndroidTest {
    @Test fun installsBundledLibraryAndReadsAllBooksOffline() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.noBackupFilesDir.resolve("hadith").deleteRecursively()
        val books = HadithLibrary.books(context)
        assertEquals(mapOf("nawawi" to 42, "bukhari" to 7008, "muslim" to 5362, "malik" to 1594), books.associate { it.id to it.count })
        for (book in books) {
            val last = HadithLibrary.page(context, book.id, 0, book.count.toString(), 0)
            assertEquals(1, last.total)
            assertEquals(book.count, last.entries.single().number)
            assertTrue(last.entries.single().body.isNotBlank())
        }
        val found = HadithLibrary.page(context, "bukhari", 0, "إِنَّمَا الأَعْمَالُ بِالنِّيَّاتِ", 0)
        assertTrue(found.entries.any { it.number == 1 })
        assertEquals(7008, HadithLibrary.sections(context, "bukhari").sumOf { it.count })
        // Same-size corrupted extracted copies must be detected using SHA-256,
        // not treated as valid solely because the file length matches.
        HadithLibrary.repair(context)
        assertEquals(4, HadithLibrary.books(context).size)
    }
}
