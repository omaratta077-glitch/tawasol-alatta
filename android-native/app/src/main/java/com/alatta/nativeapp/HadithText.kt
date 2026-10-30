package com.alatta.nativeapp

import java.text.Normalizer
import java.util.Locale

internal const val HADITH_PAGE_SIZE = 12
internal data class HadithBook(val id: String, val title: String, val author: String, val count: Int,
    val source: String, val sourceUrl: String, val edition: String)
internal data class HadithSection(val id: Int, val first: Int, val last: Int, val count: Int) {
    val title get() = "الأحاديث $first–$last"
}
internal data class HadithEntry(val id: Long, val bookId: String, val number: Int, val section: Int, val body: String)
internal data class HadithPage(val entries: List<HadithEntry>, val total: Int, val page: Int, val pages: Int)
internal data class HadithSearch(val text: String, val match: String?, val number: Int?)

/** لا يغيّر توحيد النص للبحث النص العربي الظاهر للقارئ. */
internal fun normalizeHadithSearch(value: String): String {
    val expanded = value.replace("ﷺ", " صلى الله عليه وسلم ").replace("ﷲ", "الله")
        .replace('ٱ', 'ا').replace('ى', 'ي').replace("ـ", "").lowercase(Locale.ROOT)
    val output = StringBuilder()
    for (char in Normalizer.normalize(expanded, Normalizer.Form.NFD)) {
        if (Character.getType(char) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())) continue
        when {
            Character.isDigit(char) -> output.append(Character.digit(char, 10))
            Character.isLetter(char) -> output.append(char)
            else -> output.append(' ')
        }
    }
    return output.toString().trim().replace(Regex("\\s+"), " ")
}

internal fun hadithSearch(value: String): HadithSearch {
    val normalized = normalizeHadithSearch(value.take(200))
    val numeric = normalized.takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it > 0 }
    // لا تصل إلى MATCH إلا الحروف والأرقام. تتحول علامات الاقتباس وشذرات SQL وعوامل FTS إلى مصطلحات عادية.
    val match = normalized.split(' ').filter(String::isNotBlank).take(8).joinToString(" ") { "$it*" }.ifBlank { null }
    return HadithSearch(normalized, if (numeric == null) match else null, numeric)
}
