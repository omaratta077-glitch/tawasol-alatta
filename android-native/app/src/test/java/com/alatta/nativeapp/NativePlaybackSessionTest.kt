package com.alatta.nativeapp

import androidx.media3.common.PlaybackException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativePlaybackSessionTest {
    private val channel = FootballChannel("fixture", "Fixture channel", "1080p", "fixture",
        YtvPlayer.APP_PAGE, "123", "ytv", true)
    private fun sources() = (0..1).map { NativeChannelSource(it,
        "https://fixture.example/$it.m3u8?token=private-source", mapOf("User-Agent" to "private-agent"), "application/x-mpegURL") }
    private fun timeout() = PlaybackException("private-message", null, PlaybackException.ERROR_CODE_TIMEOUT)

    @Test fun attemptsShareOneClockAndRetainBothFailuresWithoutClosingTheSession() {
        var now = 1_000L
        val session = NativePlaybackSession(channel, sources()) { now }
        assertEquals(1, JSONObject(session.report()).getJSONArray("attempts").length())
        now += 20_000; session.fail(0, timeout())
        assertEquals(1, session.position); assertFalse(session.terminal)
        now += 20_000; session.fail(1, timeout())
        assertTrue(session.terminal)
        val report = JSONObject(session.report())
        assertEquals(40_000L, report.getLong("elapsedMs"))
        assertEquals(20_000L, report.getLong("attemptElapsedMs"))
        assertEquals(1, report.getInt("sourceIndex")); assertEquals("failed", report.getString("stage"))
        val rows = report.getJSONArray("attempts")
        assertEquals(listOf("attempt_started", "startup_timeout", "attempt_started", "startup_timeout"),
            (0 until rows.length()).map { rows.getJSONObject(it).getString("event") })
        assertEquals(listOf(0, 0, 1, 1), (0 until rows.length()).map { rows.getJSONObject(it).getInt("sourceIndex") })
        now += 150
        assertEquals(40_150L, JSONObject(session.report()).getLong("elapsedMs"))
        assertFalse(session.report().contains("private-")); assertFalse(session.report().contains(".m3u8"))
        assertEquals("fixture.example", JSONObject(session.report()).getString("sourceHost"))
    }

    @Test fun releasedPlayerCallbacksAndDuplicateSnapshotsCannotResetTheActiveAttempt() {
        var now = 0L
        val session = NativePlaybackSession(channel, sources()) { now }
        now = 20_000; session.fail(0, timeout())
        session.onStarted(0)
        session.onSnapshot(0, MediaPlaybackSnapshot("stale", isPlaying = true))
        session.fail(0, timeout())
        assertFalse(session.started); assertFalse(session.terminal); assertEquals(1, session.position)
        val loading = MediaPlaybackSnapshot("player_event", state = 2, playWhenReady = true, isLoading = true)
        repeat(100) { now++; session.onSnapshot(1, loading) }
        val report = JSONObject(session.report())
        assertEquals(1, report.getJSONArray("events").length())
        assertEquals(3, report.getJSONArray("attempts").length())
        assertEquals("player_event", report.getString("stage")); assertFalse(report.getBoolean("playing"))
        session.onStarted(1); session.onStarted(1)
        assertTrue(session.started)
        assertEquals(4, JSONObject(session.report()).getJSONArray("attempts").length())
    }

    @Test fun sourceSelectionIsFrozenUntilTheOwnerClosesAndOpensAnotherChannel() {
        val headers = mutableMapOf("User-Agent" to "original-agent")
        val original = sources().toMutableList()
        original[0] = original[0].copy(headers = headers)
        val session = NativePlaybackSession(channel, original) { 0L }
        val id = session.id
        headers["User-Agent"] = "replacement-agent"
        original.clear()
        assertEquals(2, session.sourceCount)
        assertEquals("original-agent", session.source.headers["User-Agent"])
        session.fail(0, timeout())
        assertEquals(id, JSONObject(session.report()).getString("sessionId"))
        assertEquals(1, session.source.index)
    }
}
