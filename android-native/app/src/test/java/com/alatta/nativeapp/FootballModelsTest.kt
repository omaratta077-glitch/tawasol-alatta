package com.alatta.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FootballModelsTest {
    @Test fun onlyPublicHttpsSourcesCanReachThePlayer() {
        assertEquals("https://cdn.publisher.example/live?id=1", footballSafeUrl("https://cdn.publisher.example/live?id=1#unused"))
        for (bad in listOf("http://cdn.example/a", "file:///movie", "javascript:alert(1)", "https://u:p@cdn.example/a", "https://localhost/a", "https://192.168.1.1/a", "https://[::1]/a", "https://x.local/a", "https://x.internal/a", "https://cdn.example:8443/a")) assertNull(bad, footballSafeUrl(bad))
    }
    @Test fun youtubeLinksUseTheExistingPlayerAndRejectLookalikeHosts() {
        for (url in listOf("https://youtu.be/abcdefghijk", "https://www.youtube.com/live/abcdefghijk", "https://youtube.com/watch?v=abcdefghijk&feature=share")) assertEquals("abcdefghijk", footballYoutubeId(url))
        for (url in listOf("https://youtube.com.evil.example/watch?v=abcdefghijk", "https://youtube.com/watch?v=abc", "https://youtube.com/@channel", "https://evil.example/abcdefghijk")) assertNull(footballYoutubeId(url))
        assertEquals("application/x-mpegURL", footballMime("hls"))
        assertEquals("application/dash+xml", footballMime("dash"))
        assertThrows(IllegalStateException::class.java) { footballMime("youtube") }
    }
    @Test fun localKickoffRoundTripsArabicDigitsAndRejectsInvalidDates() {
        val zone = ZoneId.of("Asia/Riyadh")
        val time = parseFootballKickoff("٢٠٢٦-١٠-٢١ ٢٣:٣٠", zone)!!
        assertEquals(LocalDate.of(2026, 10, 21), footballDate(time, zone))
        assertEquals(parseFootballKickoff("2026-10-21 23:30", zone), time)
        for (bad in listOf("2026-02-30 12:00", "2026-10-21 25:00", "2026-10-21", "garbage")) assertNull(parseFootballKickoff(bad, zone))
        assertNull(parseFootballKickoff("2026-03-29 02:30", ZoneId.of("Europe/Berlin")))
        assertNull(parseFootballKickoff("2026-10-25 02:30", ZoneId.of("Europe/Berlin")))
    }
    @Test fun dayFiltersUseDeviceTimeAndLiveMatchesSortFirst() {
        val zone = ZoneId.of("Asia/Riyadh")
        val start = parseFootballKickoff("2026-10-21 00:30", zone)!!
        val planned = FootballMatch("one", "الأهلي", "الاتحاد", "بطولة", start, "scheduled", "", null, null)
        val live = planned.copy(id = "two", startsAt = start + 3600000, status = "live")
        val next = planned.copy(id = "three", startsAt = start + 86400000)
        val list = footballMatchesOn(listOf(planned, next, live), LocalDate.of(2026,10,21), "الْأَهْلِي", zone)
        assertEquals(listOf("two", "one"), list.map { it.id })
        assertEquals(LocalDate.of(2026,10,20), footballDate(start, ZoneId.of("UTC")))
        assertEquals(3, footballMatchesOn(listOf(planned, next, live), null, "", zone).size)
    }
    @Test fun viewingHistoryIsDistinctOrderedAndBounded() {
        val history = footballRecent((1..30).map { "channel-$it" }, "channel-3")
        assertEquals("channel-3", history.first()); assertEquals(20, history.size)
        assertEquals(history.size, history.distinct().size)
    }
    @Test fun scoreZeroIsRetainedAndMalformedScoresAreRejected() {
        assertEquals(0, parseFootballScore("٠")); assertEquals(12, parseFootballScore("۱۲"))
        for (bad in listOf("-1", "+1", "1.2", "100", "abc", "")) assertNull(parseFootballScore(bad))
    }
}
