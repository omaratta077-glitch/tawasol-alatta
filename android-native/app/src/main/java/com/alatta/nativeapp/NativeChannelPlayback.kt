package com.alatta.nativeapp

import androidx.compose.runtime.*
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Ephemeral playback data. Never saved in the catalog, preferences or copied diagnostics. */
internal data class NativeChannelSource(val index: Int, val url: String,
    val headers: Map<String, String>, val mimeType: String?) {
    override fun toString() = "NativeChannelSource(index=$index)"
}

internal object NativeChannelPlayback {
    internal fun parse(json: JSONObject): List<NativeChannelSource> {
        val rows = json.optJSONArray("sources") ?: error("لم تصل خيارات تشغيل")
        require(rows.length() in 1..10) { "عدد خيارات التشغيل غير صالح" }
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val url = row.getString("url")
            val parsed = requireNotNull(url.toHttpUrlOrNull()) { "رابط تشغيل غير صالح" }
            require(url.length <= 8192 && parsed.username.isEmpty() && parsed.password.isEmpty() &&
                parsed.host.contains('.') && !parsed.host.matches(Regex("[0-9.]+")) && !parsed.host.contains(':') &&
                listOf("localhost", "local", "internal", "invalid", "test").none { parsed.host == it || parsed.host.endsWith(".$it") })
            require(parsed.port == if (parsed.isHttps) 443 else 80)
            if (!parsed.isHttps) require(listOf("new-redirect.online", "cdnarvo.buzz")
                .any { parsed.host == it || parsed.host.endsWith(".$it") })
            val rawHeaders = row.getJSONObject("headers")
            val headers = rawHeaders.keys().asSequence().associateWith { name ->
                require(name in listOf("User-Agent", "Referer", "Origin"))
                rawHeaders.getString(name).also { value -> require(value.length in 1..2048 && value.all { it.code in 32..126 }) }
            }
            val mime = if (row.isNull("mimeType")) null else row.getString("mimeType")
            require(mime == null || mime in listOf("application/x-mpegURL", "application/dash+xml"))
            NativeChannelSource(row.getInt("index"), url, headers, mime)
        }.also { require(it.map { source -> source.index }.distinct().size == it.size) }
    }

    suspend fun load(token: String, stableChannelId: String): List<NativeChannelSource> = withContext(Dispatchers.IO) {
        // Only our normal owner session goes to AL-ATTA; no admin/decoder secret enters the APK.
        parse(attaApi("/api/admin/football/playback-test", JSONObject().put("token", token).put("channelId", stableChannelId)))
    }

    internal fun failure(error: PlaybackException): Pair<Int, Int?> {
        var cause: Throwable? = error
        repeat(10) {
            if (cause is HttpDataSource.InvalidResponseCodeException)
                return error.errorCode to (cause as HttpDataSource.InvalidResponseCodeException).responseCode
            cause = cause?.cause
        }
        return error.errorCode to null
    }
}

/** Bounded retry across returned alternatives, never a refresh loop or an external player. */
@Composable
internal fun NativeChannelPlaybackDialog(channel: FootballChannel, sources: List<NativeChannelSource>, onDismiss: () -> Unit,
    attemptTimeoutMs: Long = 20_000, dataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null,
    onFinalReport: (String) -> Unit = {}) {
    var position by remember(channel.id, sources) { mutableIntStateOf(0) }
    var attempts by remember(channel.id, sources) { mutableStateOf(emptyList<JSONObject>()) }
    var events by remember(channel.id, sources) { mutableStateOf(emptyList<JSONObject>()) }
    var loadEvents by remember(channel.id, sources) { mutableStateOf(emptyList<JSONObject>()) }
    val sessionStarted = remember(channel.id, sources) { android.os.SystemClock.elapsedRealtime() }
    var started by remember(position) { mutableStateOf(false) }
    var handled by remember(position) { mutableStateOf(false) }
    var lastError by remember(position) { mutableStateOf<String?>(null) }
    var snapshot by remember(position) { mutableStateOf(MediaPlaybackSnapshot("selected")) }
    val attemptPosition = position
    val source = sources[position]
    val attemptStarted = remember(position) { android.os.SystemClock.elapsedRealtime() }
    var dismissalReason by remember(channel.id, sources) { mutableStateOf("active") }
    fun appendLoadEvent(stage: String, type: Int, status: Int?) {
        loadEvents = (loadEvents + JSONObject().put("sourceIndex", source.index).put("event", stage)
            .put("dataType", type).put("httpStatus", status ?: JSONObject.NULL)
            .put("elapsedMs", (android.os.SystemClock.elapsedRealtime() - attemptStarted).coerceAtLeast(0))).takeLast(40)
    }
    fun elapsed() = (android.os.SystemClock.elapsedRealtime() - attemptStarted).coerceAtLeast(0)
    fun fail(error: PlaybackException) {
        if (position != attemptPosition || handled) return
        handled = true
        val (code, http) = NativeChannelPlayback.failure(error)
        attempts = attempts + JSONObject().put("sourceIndex", source.index)
            .put("event", if (code == PlaybackException.ERROR_CODE_TIMEOUT) "startup_timeout" else "error")
            .put("errorCode", code).put("httpStatus", http ?: JSONObject.NULL).put("elapsedMs", elapsed())
        if (position + 1 < sources.size) position++
        else {
            started = false
            lastError = "تعذر تشغيل الخيارات المتاحة. " + (http?.let { "HTTP $it • " } ?: "") + "خطأ المشغّل: $code"
        }
    }
    // The deadline covers transport/player setup as well as network loading.
    // It does not restart when a player or its data-source factory is recreated.
    LaunchedEffect(attemptPosition) {
        attempts = attempts + JSONObject().put("sourceIndex", source.index).put("event", "attempt_started").put("elapsedMs", 0)
        delay(attemptTimeoutMs.coerceIn(1, 20_000))
        if (!started && !handled) fail(PlaybackException("Startup timeout", null, PlaybackException.ERROR_CODE_TIMEOUT))
    }
    fun report() = JSONObject().put("test", "native-channel-2").put("version", BuildConfig.VERSION_NAME)
        .put("channel", channel.name).put("engine", "Media3").put("sourceIndex", source.index)
        .put("sourceCount", sources.size).put("playing", !handled && snapshot.isPlaying)
        .put("elapsedMs", (android.os.SystemClock.elapsedRealtime() - sessionStarted).coerceAtLeast(0))
        .put("attemptElapsedMs", elapsed()).put("stage", if (handled) "failed" else snapshot.stage)
        .put("playerState", snapshot.state).put("playWhenReady", snapshot.playWhenReady)
        .put("isLoading", snapshot.isLoading).put("suppressionReason", snapshot.suppressionReason)
        .put("width", snapshot.width).put("height", snapshot.height)
        .put("urlScheme", android.net.Uri.parse(source.url).scheme)
        .put("headerNames", JSONArray(source.headers.keys.sorted()))
        .put("dismissalReason", dismissalReason)
        .put("attempts", JSONArray(attempts)).put("events", JSONArray(events))
        .put("loadEvents", JSONArray(loadEvents)).toString(2)
    key(position) {
        val posts = remember(source) { listOf(JSONObject().put("id", channel.id).put("video", source.url)) }
        AttaPostMediaViewer(posts, channel.id, "video", { onFinalReport(report()); onDismiss() },
            live = LivePlayback(channel.name, "خيار ${position + 1} من ${sources.size}", source.mimeType ?: ""),
            requestHeaders = source.headers, sourceMimeType = source.mimeType,
            playbackErrorMessage = lastError, stopPlayback = handled && lastError != null,
            dataSourceFactory = dataSourceFactory,
            onPlaybackSnapshot = { value ->
                if (position == attemptPosition) {
                    snapshot = value
                    events = (events + JSONObject().put("sourceIndex", source.index).put("event", value.stage)
                        .put("elapsedMs", elapsed()).put("playerState", value.state).put("playWhenReady", value.playWhenReady)
                        .put("isLoading", value.isLoading).put("isPlaying", value.isPlaying)
                        .put("width", value.width).put("height", value.height)).takeLast(30)
                }
            },
            onPlaybackStarted = {
                if (position == attemptPosition && !handled && !started) {
                    started = true; handled = false; lastError = null
                    attempts = attempts + JSONObject().put("sourceIndex", source.index).put("event", "playing")
                        .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - attemptStarted)
                }
            },
            onPlaybackFailure = ::fail,
            onPlaybackLoadEvent = ::appendLoadEvent,
            onViewerDismissReason = { reason -> dismissalReason = reason },
            diagnosticReport = ::report)
    }
}
