package com.alatta.nativeapp

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class YacineCategory(val id: String, val name: String)
internal data class YacineChannel(val id: String, val name: String)
internal data class YacineStream(val name: String, val url: String, val mimeType: String,
    val headers: Map<String, String> = emptyMap())

internal enum class YacineStage(val description: String) {
    CATEGORIES("تحميل الأقسام"), CHANNELS("تحميل القنوات"), SEARCH("البحث عن القنوات"), PLAYBACK("جلب رابط البث")
}
internal enum class YacineProblem { CONNECTION, TIMEOUT, HTTP, FORMAT, TOO_LARGE, STREAM }
internal class YacineFailure(val problem: YacineProblem, val stage: YacineStage, val status: Int? = null) : IOException() {
    val explanation: String get() = when (problem) {
        YacineProblem.CONNECTION -> "تعذر الاتصال بمصدر ياسين أثناء ${stage.description}. تحقق من الإنترنت وأعد المحاولة."
        YacineProblem.TIMEOUT -> "مصدر ياسين تأخر في الرد أثناء ${stage.description}. أعد المحاولة لاحقًا."
        YacineProblem.HTTP -> when (status ?: 0) {
            401, 403 -> "مصدر ياسين رفض الاتصال أثناء ${stage.description} (رمز $status). القنوات غير متاحة من المصدر حاليًا."
            in 300..399 -> "مصدر ياسين غيّر عنوانه أثناء ${stage.description} (رمز $status). يلزم تحديث الربط."
            else -> "لم يكتمل الاتصال بمصدر ياسين أثناء ${stage.description} (رمز $status). أعد المحاولة لاحقًا."
        }
        YacineProblem.FORMAT -> "مصدر ياسين أعاد بيانات غير متوافقة مع الربط الحالي أثناء ${stage.description}. يلزم تحديث الربط قبل عرض القنوات."
        YacineProblem.TOO_LARGE -> "مصدر ياسين أعاد بيانات أكبر من الحد المسموح أثناء ${stage.description}. أعد المحاولة لاحقًا."
        YacineProblem.STREAM -> "لم يرجع مصدر ياسين رابطًا مدعومًا للتشغيل داخل العطا. جرّب قناة أخرى أو أعد فتح القناة لاحقًا."
    }
}

/** Opt-in trial of the APK's catalog/stream routes. No app account or session is sent to the provider. */
internal class YacineRepository(
    private val catalogBase: HttpUrl = "https://deft.yacinelive.com/api/".toHttpUrl(),
    private val playbackBase: HttpUrl = "https://def.yacinelive.com/api/".toHttpUrl(),
    client: OkHttpClient = AttaHttp.apiClient
) {
    companion object { internal const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024 }
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(12, TimeUnit.SECONDS).build()

    suspend fun categories(): List<YacineCategory> {
        val stage = YacineStage.CATEGORIES
        return records(fetch(catalogBase, listOf("categories"), stage), "categories", stage)
            .mapNotNull { c -> identity(c)?.let { YacineCategory(it.first, it.second) } }
            .distinctBy { it.id }
    }
    suspend fun channels(categoryId: String): List<YacineChannel> {
        requireId(categoryId)
        val stage = YacineStage.CHANNELS
        return channelRecords(fetch(catalogBase, listOf("categories", categoryId, "channels"), stage), stage)
    }
    suspend fun search(query: String): List<YacineChannel> {
        val stage = YacineStage.SEARCH
        return channelRecords(fetch(catalogBase, listOf("search"), stage, query.trim().take(150)), stage)
    }
    suspend fun streams(channelId: String): List<YacineStream> {
        requireId(channelId)
        val stage = YacineStage.PLAYBACK
        val payload = fetch(playbackBase, listOf("channel", channelId), stage)
        val raw = when (payload) {
            is JSONArray -> payload
            is JSONObject -> payload.optJSONArray("servers") ?: payload.optJSONArray("streams")
                ?: payload.optJSONObject("data")?.let { it.optJSONArray("servers") ?: it.optJSONArray("streams") }
                ?: payload.optJSONArray("data")
                ?: if (payload.has("url")) JSONArray().put(payload) else null
            else -> null
        } ?: throw YacineFailure(YacineProblem.FORMAT, stage)
        val streams = (0 until raw.length().coerceAtMost(100)).mapNotNull { i ->
            raw.optJSONObject(i)?.let(::parseStream)
        }.distinctBy { it.url to it.headers }
        if (streams.isEmpty()) throw YacineFailure(YacineProblem.STREAM, stage)
        return streams
    }

    private fun identity(json: JSONObject): Pair<String, String>? {
        val id = json.optString("id").trim()
        val name = json.optString("name").ifBlank { json.optString("title") }.trim().take(160)
        return if (id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && id != "null" && name.isNotBlank() && name != "null") id to name else null
    }
    private fun requireId(id: String) { require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
    private fun records(payload: Any, key: String, stage: YacineStage): List<JSONObject> {
        val array = when (payload) {
            is JSONArray -> payload
            is JSONObject -> payload.optJSONArray(key) ?: payload.optJSONArray("data") ?: payload.optJSONObject("data")?.optJSONArray(key)
            else -> null
        } ?: throw YacineFailure(YacineProblem.FORMAT, stage)
        if (array.length() > 2000) throw YacineFailure(YacineProblem.TOO_LARGE, stage)
        val rows = (0 until array.length()).mapNotNull(array::optJSONObject)
        if (array.length() > 0 && rows.none { identity(it) != null }) throw YacineFailure(YacineProblem.FORMAT, stage)
        return rows
    }
    private fun channelRecords(payload: Any, stage: YacineStage): List<YacineChannel> = records(payload, "channels", stage)
        .mapNotNull { identity(it)?.let { pair -> YacineChannel(pair.first, pair.second) } }.distinctBy { it.id }

    private fun parseStream(json: JSONObject): YacineStream? = runCatching {
        require(json.isNull("drm") || json.optJSONObject("drm")?.length() == 0)
        val url = requireNotNull(footballSafeUrl(json.optString("url")))
        val path = URI(url).path.orEmpty().lowercase(Locale.ROOT)
        val mime = when {
            path.endsWith(".m3u8") || json.optString("mimeType") in setOf("application/x-mpegURL", "application/vnd.apple.mpegurl") -> "application/x-mpegURL"
            path.endsWith(".mpd") || json.optString("mimeType") == "application/dash+xml" -> "application/dash+xml"
            path.endsWith(".mp4") || json.optString("mimeType") == "video/mp4" -> "video/mp4"
            else -> error("Unsupported stream")
        }
        val headers = linkedMapOf<String, String>()
        val allowed = mapOf("user-agent" to "User-Agent", "referer" to "Referer", "origin" to "Origin")
        val rawHeaders = json.optJSONObject("headers")
        if (!json.isNull("headers")) require(rawHeaders != null)
        rawHeaders?.keys()?.forEach { key ->
            val canonical = requireNotNull(allowed[key.lowercase(Locale.ROOT)])
            val value = rawHeaders.getString(key)
            require(value.length in 1..2048 && value.none { it.code < 32 || it.code == 127 })
            if (canonical != "User-Agent") requireNotNull(footballSafeUrl(value))
            headers[canonical] = value
        }
        val agent = json.optString("userAgent").ifBlank { json.optString("user_agent") }
        if (agent.isNotBlank()) {
            require(agent.length <= 2048 && agent.none { it.code < 32 || it.code == 127 })
            headers["User-Agent"] = agent
        }
        YacineStream(json.optString("name").ifBlank { "تشغيل البث" }.take(160), url, mime, headers.toMap())
    }.getOrNull()

    private suspend fun fetch(base: HttpUrl, path: List<String>, stage: YacineStage, query: String? = null): Any = suspendCancellableCoroutine { continuation ->
        val url = base.newBuilder().apply {
            path.forEach { addPathSegment(it) }
            if (query != null) addQueryParameter("query", query)
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "okhttp/3.12.8").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(YacineFailure(
                    if (e is SocketTimeoutException || e is java.io.InterruptedIOException) YacineProblem.TIMEOUT else YacineProblem.CONNECTION, stage))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val data = response.use {
                        if (!it.isSuccessful) throw YacineFailure(YacineProblem.HTTP, stage, it.code)
                        val body = it.body ?: throw YacineFailure(YacineProblem.FORMAT, stage)
                        if (body.contentLength() > MAX_RESPONSE_BYTES) throw YacineFailure(YacineProblem.TOO_LARGE, stage)
                        val buffer = Buffer()
                        val source = body.source()
                        while (source.read(buffer, 32 * 1024L) >= 0) {
                            if (buffer.size > MAX_RESPONSE_BYTES) throw YacineFailure(YacineProblem.TOO_LARGE, stage)
                        }
                        val value = try { JSONTokener(buffer.readUtf8().trimStart('\uFEFF')).nextValue() }
                        catch (_: Exception) { throw YacineFailure(YacineProblem.FORMAT, stage) }
                        if (value !is JSONObject && value !is JSONArray) throw YacineFailure(YacineProblem.FORMAT, stage)
                        value
                    }
                    if (continuation.isActive) continuation.resume(data)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure as? YacineFailure
                        ?: YacineFailure(if (failure is IOException) YacineProblem.CONNECTION else YacineProblem.FORMAT, stage))
                }
            }
        })
    }
}
