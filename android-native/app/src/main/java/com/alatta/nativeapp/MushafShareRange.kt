package com.alatta.nativeapp

import java.net.URI

internal val MUSHAF_JUZ_STARTS = listOf(1,22,42,62,82,102,121,142,162,182,201,222,242,262,282,302,322,342,362,382,402,422,442,462,482,502,522,542,562,582)

internal data class MushafShareSelection(val firstPage: Int, val lastPage: Int, val title: String) {
    init {
        require(firstPage in 1..604 && lastPage in firstPage..604) { "نطاق صفحات المصحف غير صحيح" }
        require(lastPage - firstPage < 30) { "يمكن مشاركة 30 صفحة كحد أقصى في المنشور الواحد" }
    }
    val pages: IntRange get() = firstPage..lastPage
    val pageCount: Int get() = lastPage - firstPage + 1
}

internal fun mushafPartSelection(part: Int): MushafShareSelection {
    require(part in 1..30) { "رقم الجزء من 1 إلى 30" }
    return MushafShareSelection(MUSHAF_JUZ_STARTS[part-1],
        if (part == 30) 604 else MUSHAF_JUZ_STARTS[part]-1, "الجزء $part من القرآن الكريم")
}

internal data class MushafPostAttachment(val pdfUrl: String, val pages: IntRange)

/** Links are ordinary post text, compatible with the existing deployed post API. */
internal fun parseMushafPostAttachment(text: String, siteOrigin: String): MushafPostAttachment? {
    val expected = runCatching { URI(siteOrigin) }.getOrNull() ?: return null
    for (match in Regex("https://[^\\s]+#mushaf=([0-9]{1,3})-([0-9]{1,3})(?=\\s|$)").findAll(text)) {
        val url = runCatching { URI(match.value) }.getOrNull() ?: continue
        if (url.scheme != "https" || url.host != expected.host || url.port != expected.port || url.userInfo != null) continue
        if (!Regex("/media/file/[A-Za-z0-9._-]+\\.pdf").matches(url.rawPath.orEmpty())) continue
        val first = match.groupValues[1].toIntOrNull() ?: continue
        val last = match.groupValues[2].toIntOrNull() ?: continue
        if (first !in 1..604 || last !in first..604 || last-first >= 30) continue
        return MushafPostAttachment(match.value.substringBefore('#'), first..last)
    }
    return null
}

/** Hide only the verified mushaf PDF attachment URL in feed text; links remain in stored post data. */
internal fun visibleMushafPostText(text: String, siteOrigin: String): String {
    val attachment = parseMushafPostAttachment(text, siteOrigin) ?: return text
    val marker = Regex("https://[^\\s]+#mushaf=([0-9]{1,3})-([0-9]{1,3})(?=\\s|$)")
    return marker.replace(text) { candidate ->
        val validated = parseMushafPostAttachment(candidate.value, siteOrigin)
        if (validated == attachment && candidate.value.substringBefore('#') == attachment.pdfUrl) "" else candidate.value
    }.replace(Regex("\\n{3,}"), "\n\n").trim()
}

internal fun mushafAttachmentLink(siteOrigin: String, uploadedPath: String, selection: MushafShareSelection): String {
    val link = siteOrigin.trimEnd('/') + uploadedPath + "#mushaf=${selection.firstPage}-${selection.lastPage}"
    require(parseMushafPostAttachment(link, siteOrigin) != null) { "رابط ملف المصحف غير صالح" }
    return link
}
