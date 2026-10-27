package com.alatta.nativeapp

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/** Explicit, read-only owner diagnostic. Never receives account tokens or edits the catalog. */
internal object YacineCatalogProbe {
    // Observed in the owner's Yacine captures; the catalog paths remain unverified live.
    const val BASE = "https://def001.ycnapi.com/api/"
    const val MAX_BYTES = 512 * 1024
    const val MAX_ITEMS = 500
    enum class Kind { CATEGORIES, CHANNELS }
    enum class State { LIST, EMPTY, HTTP_ERROR, UNREADABLE, INVALID, TOO_LARGE, NETWORK_ERROR }
    data class Entry(val id: String, val name: String)
    data class Result(
        val state: State,
        val path: String,
        val message: String,
        val code: Int? = null,
        val contentType: String = "",
        val bytes: Long = 0,
        val elapsedMs: Long = 0,
        val hasTimeHeader: Boolean = false,
        val entries: List<Entry> = emptyList(),
        val serverMessage: String = ""
    ) {
        fun report(): String = buildString {
            append("تجربة قائمة ياسين من الهاتف\nGET ").append(BASE).append(path)
            append("\nالحالة: ").append(state.name).append("\n").append(message)
            code?.let { append("\nHTTP ").append(it) }
            if (serverMessage.isNotBlank()) append("\nرسالة الجهة المجيبة: ").append(serverMessage)
            append("\nنوع الرد: ").append(contentType.ifBlank { "غير محدد" })
            append("\nالمقروء من الرد: ").append(bytes).append(" بايت")
            append("\nالمدة: ").append(elapsedMs).append(" ms")
            append("\nوجود ترويسة t: ").append(if (hasTimeHeader) "نعم" else "لا")
            if (entries.isNotEmpty()) {
                append("\nالاسم | المعرّف")
                entries.forEach { append("\n").append(it.name).append(" | ").append(it.id) }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()

    internal fun request(kind: Kind, categoryId: String? = null): Request {
        val id = categoryId?.let { requireNotNull(YtvPlayer.channelId(it)) { "رقم قسم غير صالح" } }
        val path = when (kind) {
            Kind.CATEGORIES -> if (id == null) "categories" else "categories/" + id
            Kind.CHANNELS -> "categories/" + requireNotNull(id) { "اختر قسمًا أولًا" } + "/channels"
        }
        // Separate client: no AL-ATTA cookies, tokens, headers or third-party app credentials.
        return Request.Builder().url(BASE + path).header("Accept", "application/json").get().build()
    }

    internal fun parse(text: String, code: Int, path: String): Result {
        if (code !in 200..299) return Result(State.HTTP_ERROR, path,
            if (code in 300..399) "الخادم أعاد تحويلًا؛ لم يتم اتباعه."
            else if (code == 436) "جهة الرد أعادت HTTP 436، وهو رمز غير قياسي؛ لم تصل قائمة صالحة والسبب غير محدد."
            else "الخادم أعاد خطأ HTTP؛ لم تصل قائمة صالحة.", code)
        val root = try {
            val tokener = JSONTokener(text)
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') return Result(State.INVALID, path, "الرد يحتوي بيانات زائدة وغير متوقعة.", code)
            value
        } catch (_: Exception) {
            return Result(State.UNREADABLE, path,
                "وصل رد، لكنه ليس قائمة JSON قابلة للقراءة. قد يكون مرمّزًا أو صفحة ويب؛ لم تُستخرج أرقام منه.", code)
        }
        val rows = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                if (root.opt("success") == false ||
                    (root.has("error") && !root.isNull("error") && root.optString("error").isNotBlank()))
                    return Result(State.INVALID, path, "الرد يعلن فشل الطلب؛ لم تُستخرج قائمة.", code)
                root.optJSONArray("data")
            }
            else -> return Result(State.UNREADABLE, path,
                "وصل رد، لكنه ليس قائمة JSON قابلة للقراءة. قد يكون مرمّزًا أو صفحة ويب؛ لم تُستخرج أرقام منه.", code)
        } ?: return Result(State.INVALID, path, "صيغة الرد لا تحتوي قائمة data المتوقعة.", code)
        if (rows.length() > MAX_ITEMS) return Result(State.TOO_LARGE, path, "القائمة تتجاوز حد عرض التجربة.", code)
        val entries = ArrayList<Entry>()
        val ids = HashSet<String>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index)
                ?: return Result(State.INVALID, path, "القائمة تحتوي سجلًا غير صالح.", code)
            val raw = row.opt("id")
            val value = when (raw) {
                is String -> raw
                is Long -> raw.toString()
                is Int -> raw.toString()
                else -> null
            }
            val id = value?.let(YtvPlayer::channelId)
            val name = (row.opt("name") as? String)?.filterNot { Character.isISOControl(it) }?.trim()
            if (id == null || name.isNullOrBlank() || !ids.add(id))
                return Result(State.INVALID, path, "القائمة تحتوي معرّفًا غير صالح أو مكررًا، أو اسمًا مفقودًا.", code)
            entries.add(Entry(id, name.take(120)))
        }
        return Result(if (entries.isEmpty()) State.EMPTY else State.LIST, path,
            if (entries.isEmpty()) "وصلت قائمة فارغة. لم يتم تغيير القنوات المسجّلة."
            else "وصلت قائمة قابلة للقراءة. الأرقام للمعاينة فقط؛ لم تُحفظ تلقائيًا.", code, entries = entries)
    }

    internal fun read(response: Response, path: String, elapsedMs: Long): Result {
        val type = response.header("Content-Type").orEmpty().substringBefore(';').take(80)
        val hasTime = !response.header("t").isNullOrBlank()
        if (!response.isSuccessful) {
            // Read a bounded error preview without retrying, following redirects or
            // passing an error body to the successful catalog parser.
            val preview = response.peekBody(4096).use { it.bytes() }
            return parse("", response.code, path).copy(contentType = type,
                bytes = preview.size.toLong(), elapsedMs = elapsedMs, hasTimeHeader = hasTime,
                serverMessage = errorMessage(String(preview, Charsets.UTF_8), type))
        }
        val source = response.body?.source()
            ?: return Result(State.INVALID, path, "الخادم أعاد استجابة بدون محتوى.", response.code,
                contentType = type, elapsedMs = elapsedMs, hasTimeHeader = hasTime)
        source.request(MAX_BYTES + 1L)
        val size = source.buffer.size
        if (size > MAX_BYTES) return Result(State.TOO_LARGE, path, "الرد يتجاوز حد التجربة الآمن 512 كيلوبايت.",
            response.code, type, size, elapsedMs, hasTime)
        return parse(source.buffer.readUtf8(), response.code, path)
            .copy(contentType = type, bytes = size, elapsedMs = elapsedMs, hasTimeHeader = hasTime)
    }

    private fun errorMessage(text: String, type: String): String {
        val raw = if (type.contains("json") || text.trimStart().startsWith("{")) {
            try {
                val json = JSONObject(text)
                listOf("message", "error_description", "detail", "error")
                    .firstNotNullOfOrNull { key -> (json.opt(key) as? String)?.takeIf { it.isNotBlank() } }.orEmpty()
            } catch (_: Exception) { "" }
        } else if (type == "text/plain") text else ""
        return raw.replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[رابط محذوف]")
            .replace(Regex("(authorization|token|api[_-]?key|secret|cookie)\\s*[:=]\\s*\\S+", RegexOption.IGNORE_CASE), "$1=[محذوف]")
            .replace(Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE), "Bearer [محذوف]")
            .replace(Regex("\\s+"), " ").filterNot { Character.isISOControl(it) }.trim().take(360)
    }

    suspend fun fetch(kind: Kind, categoryId: String? = null): Result {
        val request = request(kind, categoryId)
        val path = request.url.encodedPath.removePrefix("/api/")
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            fun finish(result: Result) { if (continuation.isActive) continuation.resume(result) }
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    val message = when (error) {
                        is UnknownHostException -> "تعذر العثور على عنوان الخادم."
                        is SSLException -> "تعذر التحقق من الاتصال الآمن بالخادم."
                        is SocketTimeoutException -> "انتهت مهلة الاتصال بالخادم."
                        else -> "تعذر الاتصال أو استلام الرد من الخادم."
                    }
                    finish(Result(State.NETWORK_ERROR, path, message, elapsedMs = elapsed()))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val result = try { read(it, path, elapsed()).copy(elapsedMs = elapsed()) }
                        catch (_: IOException) {
                            Result(State.NETWORK_ERROR, path, "تعذر استلام محتوى الرد بالكامل.", it.code, elapsedMs = elapsed())
                        }
                        finish(result)
                    }
                }
            })
        }
    }
}
