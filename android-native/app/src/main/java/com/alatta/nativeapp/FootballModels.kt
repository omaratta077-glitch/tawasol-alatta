package com.alatta.nativeapp

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class FootballChannel(val id: String, val name: String, val category: String,
    val provider: String, val sourceUrl: String, val streamUrl: String, val format: String, val enabled: Boolean)
internal data class FootballMatch(val id: String, val home: String, val away: String, val league: String,
    val startsAt: Long, val status: String, val channelId: String, val homeScore: Int?, val awayScore: Int?)
internal data class FootballCatalog(val revision: Long = 0, val channels: List<FootballChannel> = emptyList(),
    val matches: List<FootballMatch> = emptyList(), val updatedAt: Long = 0)
internal data class LivePlayback(val title: String, val provider: String, val mimeType: String)

internal fun footballSafeUrl(value: String): String? = runCatching {
    require(value.length in 1..2048 && value.none { it.isWhitespace() || it.code < 32 || it.code == 127 })
    val uri = URI(value)
    val host = requireNotNull(uri.host).lowercase(Locale.ROOT)
    require(uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.port in listOf(-1, 443))
    require(host.matches(Regex("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]*[a-z0-9])?")))
    require(!host.matches(Regex("(?:.*\\.)?(?:localhost|local|internal)")))
    value.substringBefore('#')
}.getOrNull()

internal fun footballYoutubeId(value: String): String? = runCatching {
    val uri = URI(requireNotNull(footballSafeUrl(value)))
    val id = when (uri.host.lowercase(Locale.ROOT)) {
        "youtu.be" -> uri.path.removePrefix("/")
        "youtube.com", "www.youtube.com", "m.youtube.com" ->
            if (uri.path == "/watch") uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.substringAfter('=')
            else Regex("/(?:live|embed|shorts)/([A-Za-z0-9_-]{11})").matchEntire(uri.path)?.groupValues?.get(1)
        else -> null
    }
    id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
}.getOrNull()

internal fun footballMime(format: String): String = when (format) {
    "hls" -> "application/x-mpegURL"
    "dash" -> "application/dash+xml"
    else -> error("هذا المصدر يُفتح في مشغّله المخصص")
}
internal fun footballStatus(status: String) = when (status) { "live" -> "مباشر"; "finished" -> "انتهت"; else -> "لم تبدأ" }
internal fun footballDate(time: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
internal fun footballTime(time: Long): String = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
internal fun footballKickoffInput(time: Long): String = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT).format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
internal fun parseFootballKickoff(value: String, zone: ZoneId = ZoneId.systemDefault()): Long? = runCatching {
    val digits = value.map { if (Character.isDigit(it)) Character.digit(it, 10).digitToChar() else it }.joinToString("")
    val local = LocalDateTime.parse(digits.trim(), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)
        .withResolverStyle(java.time.format.ResolverStyle.STRICT))
    require(local.year in 2020..2099)
    val offsets = zone.rules.getValidOffsets(local)
    require(offsets.size == 1) { "اختر موعدًا غير ملتبس مع تغيير التوقيت" }
    local.toInstant(offsets.first()).toEpochMilli()
}.getOrNull()
internal fun footballMatchesOn(matches: List<FootballMatch>, day: LocalDate?, query: String, zone: ZoneId = ZoneId.systemDefault()): List<FootballMatch> {
    val terms = normalizeHadithSearch(query).split(' ').filter(String::isNotBlank)
    return matches.filter { (day == null || footballDate(it.startsAt, zone) == day) &&
        terms.all { term -> normalizeHadithSearch("${it.home} ${it.away} ${it.league}").contains(term) } }
        .sortedWith(compareBy<FootballMatch> { if (it.status == "live") 0 else 1 }.thenBy { it.startsAt }.thenBy { it.id })
}
internal fun footballRecent(current: List<String>, selected: String): List<String> = (listOf(selected) + current).filter(String::isNotBlank).distinct().take(20)
internal fun parseFootballScore(value: String): Int? = value.trim().takeIf { it.isNotEmpty() && it.all(Character::isDigit) }
    ?.map { Character.digit(it, 10).digitToChar() }?.joinToString("")?.toIntOrNull()?.takeIf { it in 0..99 }
