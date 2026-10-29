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
    // Observed fallback in the supplied YTV PRO PlayerActivity constructor.
    internal const val FALLBACK_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.54 Safari/537.36"

    internal fun transport(context: android.content.Context, headers: Map<String, String>): androidx.media3.datasource.DataSource.Factory =
        androidx.media3.datasource.DefaultDataSource.Factory(context.applicationContext,
            androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8_000).setReadTimeoutMs(8_000)
                .setUserAgent(headers["User-Agent"] ?: FALLBACK_USER_AGENT)
                .setDefaultRequestProperties(headers))

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
            NativeChannelSource(row.getInt("index"), url,
                if ("User-Agent" in headers) headers else headers + ("User-Agent" to FALLBACK_USER_AGENT), mime)
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

/** One immutable source selection and one clock for the lifetime of the open viewer.
 * Main-thread callbacks carry an attempt token so a released player cannot alter its successor.
 */
internal class NativePlaybackSession(channel: FootballChannel, initialSources: List<NativeChannelSource>,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime) {
    val id: String = java.util.UUID.randomUUID().toString()
    val channel = channel.copy()
    private val sources = initialSources.map { it.copy(headers = it.headers.toMap()) }
    private val createdAt = clock()
    private var attemptStartedAt = createdAt
    var position by mutableIntStateOf(0)
        private set
    var started by mutableStateOf(false)
        private set
    var terminal by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    private var snapshot = MediaPlaybackSnapshot("selected")
    private val attempts = mutableListOf<JSONObject>()
    private var events = emptyList<JSONObject>()
    private var loadEvents = emptyList<JSONObject>()
    private var dismissalReason = "active"
    val attemptToken: Int get() = position
    val source: NativeChannelSource get() = sources[position]
    val sourceCount: Int get() = sources.size

    init {
        require(sources.isNotEmpty())
        recordAttempt("attempt_started", createdAt)
    }

    fun attemptElapsedMs(): Long = (clock() - attemptStartedAt).coerceAtLeast(0)
    private fun recordAttempt(event: String, now: Long): JSONObject = JSONObject()
        .put("sourceIndex", source.index).put("event", event)
        .put("elapsedMs", (now - attemptStartedAt).coerceAtLeast(0))
        .put("sessionElapsedMs", (now - createdAt).coerceAtLeast(0))
        .also { attempts.add(it) }

    fun onSnapshot(token: Int, value: MediaPlaybackSnapshot) {
        if (token != attemptToken || snapshot == value) return
        snapshot = value
        val now = clock()
        events = (events + JSONObject().put("sourceIndex", source.index).put("event", value.stage)
            .put("elapsedMs", (now - attemptStartedAt).coerceAtLeast(0))
            .put("sessionElapsedMs", (now - createdAt).coerceAtLeast(0))
            .put("playerState", value.state).put("playWhenReady", value.playWhenReady)
            .put("isLoading", value.isLoading).put("isPlaying", value.isPlaying)
            .put("width", value.width).put("height", value.height)).takeLast(30)
    }

    fun onLoadEvent(token: Int, value: MediaLoadTrace) {
        if (token != attemptToken) return
        val now = clock()
        loadEvents = (loadEvents + value.json().put("sourceIndex", source.index)
            .put("elapsedMs", (now - attemptStartedAt).coerceAtLeast(0))
            .put("sessionElapsedMs", (now - createdAt).coerceAtLeast(0))).takeLast(40)
    }

    fun onDismiss(reason: String) { dismissalReason = reason }

    fun onStarted(token: Int) {
        if (token != attemptToken || terminal || started) return
        started = true
        recordAttempt("playing", clock())
    }

    fun fail(token: Int, error: PlaybackException) {
        if (token != attemptToken || terminal) return
        val now = clock()
        val (code, http) = NativeChannelPlayback.failure(error)
        recordAttempt(if (code == PlaybackException.ERROR_CODE_TIMEOUT) "startup_timeout" else "error", now)
            .put("errorCode", code).put("httpStatus", http ?: JSONObject.NULL)
        started = false
        if (position + 1 < sources.size) {
            position++
            attemptStartedAt = now
            snapshot = MediaPlaybackSnapshot("selected")
            recordAttempt("attempt_started", now)
        } else {
            terminal = true
            lastError = "تعذر تشغيل الخيارات المتاحة. " + (http?.let { "HTTP $it • " } ?: "") + "خطأ المشغّل: $code"
        }
    }

    fun report(): String {
        val now = clock()
        return JSONObject().put("test", "native-channel-3").put("version", BuildConfig.VERSION_NAME)
            .put("sessionId", id).put("channel", channel.name).put("engine", "Media3")
            .put("sourceIndex", source.index).put("attemptNumber", position + 1).put("sourceCount", sources.size)
            .put("playing", !terminal && snapshot.isPlaying)
            .put("elapsedMs", (now - createdAt).coerceAtLeast(0))
            .put("attemptElapsedMs", (now - attemptStartedAt).coerceAtLeast(0))
            .put("stage", if (terminal) "failed" else snapshot.stage)
            .put("playerState", snapshot.state).put("playWhenReady", snapshot.playWhenReady)
            .put("isLoading", snapshot.isLoading).put("suppressionReason", snapshot.suppressionReason)
            .put("width", snapshot.width).put("height", snapshot.height)
            .put("urlScheme", android.net.Uri.parse(source.url).scheme)
            .put("sourceHost", safeMediaHost(source.url.toHttpUrlOrNull()?.host) ?: JSONObject.NULL)
            .put("headerNames", JSONArray(source.headers.keys.sorted())).put("dismissalReason", dismissalReason)
            .put("attempts", JSONArray(attempts)).put("events", JSONArray(events))
            .put("loadEvents", JSONArray(loadEvents)).toString(2)
    }
}

/** Source changes reset the transport/player inside a single fullscreen window. */
@Composable
internal fun NativeChannelPlaybackDialog(session: NativePlaybackSession, onDismiss: () -> Unit,
    attemptTimeoutMs: Long = 20_000, dataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null,
    onFinalReport: (String) -> Unit = {}) {
    val token = session.attemptToken
    val source = session.source
    val context = androidx.compose.ui.platform.LocalContext.current
    val transport = remember(session.id, token, dataSourceFactory) { dataSourceFactory ?: NativeChannelPlayback.transport(context, source.headers) }
    LaunchedEffect(session.id, token) {
        delay((attemptTimeoutMs.coerceIn(1, 20_000) - session.attemptElapsedMs()).coerceAtLeast(0))
        if (!session.started && !session.terminal)
            session.fail(token, PlaybackException("Startup timeout", null, PlaybackException.ERROR_CODE_TIMEOUT))
    }
    val posts = remember(session.id, token) { listOf(JSONObject().put("id", session.channel.id).put("video", source.url)) }
    AttaPostMediaViewer(posts, session.channel.id, "video", { onFinalReport(session.report()); onDismiss() },
        playbackKey = "${session.id}:$token",
        validatedChannelUrl = source.url,
        live = LivePlayback(session.channel.name, "خيار ${session.position + 1} من ${session.sourceCount}", source.mimeType ?: ""),
        requestHeaders = source.headers, sourceMimeType = source.mimeType,
        playbackErrorMessage = session.lastError, stopPlayback = session.terminal,
        dataSourceFactory = transport,
        onPlaybackLoadEvent = { session.onLoadEvent(token, it) },
        onViewerDismissReason = session::onDismiss,
        onPlaybackSnapshot = { session.onSnapshot(token, it) },
        onPlaybackStarted = { session.onStarted(token) },
        onPlaybackFailure = { session.fail(token, it) }, diagnosticReport = session::report)
}
