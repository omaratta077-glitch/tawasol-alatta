package com.alatta.nativeapp

import java.net.URI
import java.net.URLEncoder

/**
 * روابط فيديوهات فيسبوك العامة فقط؛ لا يتم تنزيل المحتوى أو استخراج روابط بث خاصة.
 * تُقبل روابط الريلز، الفيديو، watch، والمشاركة المباشرة، مع رفض أي مضيف مختلف.
 */
private val facebookUrlPattern = Regex("""https?://[^\s<>"'،؛]+""", RegexOption.IGNORE_CASE)

internal fun facebookReelUrl(text: String): String? =
    facebookUrlPattern.findAll(text).mapNotNull { candidate ->
        val raw = candidate.value.trimEnd('.', ',', ')', ']', '}', '!', '?', '؛', '،')
        val uri = runCatching { URI(raw) }.getOrNull() ?: return@mapNotNull null
        val host = uri.host?.lowercase() ?: return@mapNotNull null
        val path = uri.path.orEmpty()
        val validHost = host in setOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "fb.watch")
        val validPath = when {
            host == "fb.watch" -> path.trim('/').isNotEmpty()
            path.matches(Regex("""/(?i:reel)/[A-Za-z0-9_-]+/?""")) -> true
            path.matches(Regex("""/(?i:share)/(?i:r|v)/[A-Za-z0-9_-]+/?""")) -> true
            path.matches(Regex("""/[^/?]+/(?i:videos)/[A-Za-z0-9_-]+/?""")) -> true
            path.equals("/watch", ignoreCase = true) || path.equals("/watch/", ignoreCase = true) ||
                path.equals("/video.php", ignoreCase = true) -> Regex("""(?:^|&)v=[0-9]+(?:&|$)""").containsMatchIn(uri.rawQuery.orEmpty())
            else -> false
        }
        if (uri.scheme != "https" || !validHost || !validPath || uri.userInfo != null || raw.length > 2048)
            return@mapNotNull null
        val normalizedHost = if (host == "fb.watch") "fb.watch" else "www.facebook.com"
        val query = if (path.equals("/watch", true) || path.equals("/watch/", true) || path.equals("/video.php", true)) {
            uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }.orEmpty()
        } else ""
        "https://$normalizedHost$path" + if (query.isNotEmpty()) "?$query" else ""
    }.firstOrNull()

/** استخدام Facebook Embedded Video Player الرسمي بدلاً من استخراج ملف MP4 أو HLS. */
internal fun facebookEmbedUrl(publicVideoUrl: String): String =
    "https://www.facebook.com/plugins/video.php?href=" +
        URLEncoder.encode(publicVideoUrl, "UTF-8") + "&show_text=false&width=500"
