package com.alatta.nativeapp

import androidx.compose.runtime.*
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.Dispatchers
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
internal fun NativeChannelPlaybackDialog(channel: FootballChannel, sources: List<NativeChannelSource>, onDismiss: () -> Unit) {
    var position by remember(channel.id, sources) { mutableIntStateOf(0) }
    var attempts by remember(channel.id, sources) { mutableStateOf(emptyList<JSONObject>()) }
    var started by remember(position) { mutableStateOf(false) }
    var handled by remember(position) { mutableStateOf(false) }
    var lastError by remember(position) { mutableStateOf<String?>(null) }
    val source = sources[position]
    val attemptStarted = remember(position) { android.os.SystemClock.elapsedRealtime() }
    fun report() = JSONObject().put("test", "native-channel-1").put("version", BuildConfig.VERSION_NAME)
        .put("channel", channel.name).put("engine", "Media3").put("sourceIndex", source.index)
        .put("sourceCount", sources.size).put("playing", started)
        .put("attempts", JSONArray(attempts)).toString(2)
    key(position) {
        val posts = remember(source) { listOf(JSONObject().put("id", channel.id).put("video", source.url)) }
        AttaPostMediaViewer(posts, channel.id, "video", onDismiss,
            live = LivePlayback(channel.name, "خيار ${position + 1} من ${sources.size}", source.mimeType ?: ""),
            requestHeaders = source.headers, sourceMimeType = source.mimeType,
            playbackErrorMessage = lastError, startupTimeoutMs = 20_000,
            onPlaybackStarted = {
                if (!started) {
                    started = true; handled = false; lastError = null
                    attempts = attempts + JSONObject().put("sourceIndex", source.index).put("event", "playing")
                        .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - attemptStarted)
                }
            },
            onPlaybackFailure = { error ->
                if (!handled) {
                    handled = true
                    val (code, http) = NativeChannelPlayback.failure(error)
                    attempts = attempts + JSONObject().put("sourceIndex", source.index).put("event", "error")
                        .put("errorCode", code).put("httpStatus", http ?: JSONObject.NULL)
                        .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - attemptStarted)
                    if (position + 1 < sources.size) position++
                    else {
                        started = false
                        lastError = "تعذر تشغيل الخيارات المتاحة. " + (http?.let { "HTTP $it • " } ?: "") + "خطأ المشغّل: $code"
                    }
                }
            }, diagnosticReport = report())
    }
}
