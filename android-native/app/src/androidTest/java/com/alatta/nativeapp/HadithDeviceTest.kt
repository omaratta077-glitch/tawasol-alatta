package com.alatta.nativeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** يُشغّل داخل Android نفسه، بما في ذلك فك الضغط والتنسيق في libcore وقاعدة SQLite الخاصة بالنظام. */
@RunWith(AndroidJUnit4::class)
class HadithDeviceTest {
    @Test fun arabicPhoneInstallsAllFourBooksAndSearchesOffline() = runBlocking {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val books = HadithLibrary.books(context)
            assertEquals(mapOf("nawawi" to 42, "bukhari" to 7008, "muslim" to 5362, "malik" to 1594), books.associate { it.id to it.count })
            for (book in books) {
                val first = HadithLibrary.page(context, book.id, 0, "١", 0)
                assertEquals(1, first.entries.single().number)
                val last = HadithLibrary.page(context, book.id, 0, book.count.toString(), 0)
                assertEquals(book.count, last.entries.single().number)
            }
            assertTrue(HadithLibrary.page(context, "bukhari", 0, "إِنَّمَا الأَعْمَالُ بِالنِّيَّاتِ", 0).entries.any { it.number == 1 })
            assertEquals(7008, HadithLibrary.sections(context, "bukhari").sumOf { it.count })
            // مسار الإصلاح نفسه الذي يراه المستخدم، من دون مسح بيانات الحساب.
            HadithLibrary.repair(context)
            assertEquals(14006, HadithLibrary.books(context).sumOf { it.count })
            // تثبت القراءة الثانية أن قاعدة البيانات المثبّتة تظل مفتوحة، من دون
            // استدعاءات PRAGMA للتحقق السريع أو إصدار SQLite تعتمد على الجهاز.
            assertEquals(4, HadithLibrary.books(context).size)
            // اختبر مسار العمل دون اتصال نفسه المستخدم عندما لا يدعم الهاتف FTS4.
            val fallback = HadithLibrary.page(context, "bukhari", 0, "إِنَّمَا الأَعْمَالُ بِالنِّيَّاتِ", 0, preferFullText = false)
            assertTrue(fallback.entries.any { it.number == 1 })
        } finally { Locale.setDefault(previous) }
    }
}
