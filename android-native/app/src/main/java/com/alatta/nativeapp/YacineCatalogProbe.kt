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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/** Explicit, read-only owner diagnostic. Never receives account tokens or edits the catalog. */
internal object YacineCatalogProbe {
    // The owner supplied a successful encoded categories response from this host.
    const val BASE = "https://def001.ycnapi.com/api/"
    const val MAX_BYTES = 512 * 1024
    const val MAX_ITEMS = 500
    const val MAX_REPORT_CHARS = 16 * 1024
    enum class Kind { CATEGORIES, CHANNELS }
    enum class State { LIST, EMPTY, HTTP_ERROR, UNREADABLE, INVALID, TOO_LARGE, NETWORK_ERROR }
    data class Entry(val id: String, val name: String, val childCount: Long? = null)
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
        val serverMessage: String = "",
        val timeHeader: String = "",
        val responseText: String = "",
        val responseTextTruncated: Boolean = false,
        val responseDecoded: Boolean = false,
        val deviceTimeUsed: String = ""
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
            if (timeHeader.isNotEmpty()) append("\nt: ").append(timeHeader)
            else if (hasTimeHeader) append("\nلم تُنسخ قيمة t لأنها ليست توقيتًا رقميًا من 10 إلى 13 خانة.")
            if (responseDecoded) append("\nفك ترميز الرد: تم تحويله إلى JSON")
            if (deviceTimeUsed.isNotEmpty()) append("\nوقت الجهاز المستخدم بدل ترويسة t الغائبة: ").append(deviceTimeUsed)
            if (entries.isNotEmpty()) {
                append(if (path.endsWith("/channels")) "\nاسم القناة | رقم القناة" else "\nاسم القسم | رقم القسم")
                entries.forEach { append("\n").append(it.name).append(" | ").append(it.id) }
            }
            if (responseText.isNotEmpty()) {
                append("\n\nنص الرد من نفس الطلب: ")
                append(if (responseTextTruncated) "مقتطف غير مكتمل؛ أول $MAX_REPORT_CHARS حرفًا فقط" else "كامل")
                append("\n--- BEGIN RESPONSE BODY ---\n").append(responseText)
                append("\n--- END RESPONSE BODY ---")
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
            val children = when (val count = row.opt("child_count")) {
                is Int -> count.toLong().takeIf { it >= 0 }
                is Long -> count.takeIf { it >= 0 }
                else -> null
            }
            entries.add(Entry(id, name.take(120), children))
        }
        return Result(if (entries.isEmpty()) State.EMPTY else State.LIST, path,
            if (entries.isEmpty()) "وصلت قائمة فارغة. لم يتم تغيير القنوات المسجّلة."
            else "وصلت قائمة قابلة للقراءة. الأرقام للمعاينة فقط؛ لم تُحفظ تلقائيًا.", code, entries = entries)
    }

    /** Response-only wire decoding. This does not add request signing or client identity. */
    private fun parseSuccessfulBody(text: String, code: Int, path: String, headerTime: String?, nowEpochSeconds: Long,
        responseMask: ByteArray?): Result {
        val trimmed = text.trim().removePrefix("\uFEFF").trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return parse(trimmed, code, path)
        val prefix = responseMask?.takeIf { it.size == 16 && it.all { byte -> byte.toInt() in 32..126 } }
            ?: return Result(State.UNREADABLE, path,
                "وصل رد مرمّز؛ اضبط إعداد قراءة القائمة على هذا الهاتف أولًا.", code)
        val time = headerTime?.trim() ?: nowEpochSeconds.toString().take(10)
        val deviceTime = if (headerTime == null) time else ""
        fun unreadable(message: String) = Result(State.UNREADABLE, path, message, code, deviceTimeUsed = deviceTime)
        if (time.length !in 10..13 || time.any { it !in '0'..'9' })
            return unreadable("وصل رد مرمّز، لكن توقيت t غير صالح لمعالجة صيغته.")
        val envelope = trimmed.filterNot { it == '\r' || it == '\n' }
        if (envelope.isEmpty() || envelope.length % 4 != 0)
            return unreadable("الرد ليس JSON مباشرًا ولا Base64 مكتملًا؛ لم تُستخرج قائمة.")
        val decoded = try {
            val bytes = Base64.getDecoder().decode(envelope)
            val mask = prefix + time.toByteArray(Charsets.US_ASCII)
            bytes.indices.forEach { index -> bytes[index] = (bytes[index].toInt() xor mask[index % mask.size].toInt()).toByte() }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: IllegalArgumentException) {
            return unreadable("تعذر قراءة ترميز Base64 للرد؛ لم تُستخرج قائمة.")
        } catch (_: java.nio.charset.CharacterCodingException) {
            return unreadable("تعذر تحويل الرد المرمّز إلى نص بالتوقيت الوارد؛ لم تُستخرج قائمة.")
        }
        if (!decoded.trimStart().let { it.startsWith("{") || it.startsWith("[") })
            return unreadable("لم ينتج JSON من معالجة الرد المرمّز؛ قد تكون الصيغة أو التوقيت غير متوافقين.")
        val result = parse(decoded, code, path)
        return result.copy(responseDecoded = result.state == State.LIST || result.state == State.EMPTY,
            deviceTimeUsed = deviceTime)
    }

    internal fun read(response: Response, path: String, elapsedMs: Long,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L, responseMask: ByteArray? = null): Result {
        val type = response.header("Content-Type").orEmpty().substringBefore(';').take(80)
        val hasTime = !response.header("t").isNullOrBlank()
        // Export only the numeric response timestamp, never arbitrary headers or credentials.
        val time = response.header("t").orEmpty().trim()
            .takeIf { it.length in 10..13 && it.all { char -> char in '0'..'9' } }.orEmpty()
        if (!response.isSuccessful) {
            // Read a bounded error preview without retrying, following redirects or
            // passing an error body to the successful catalog parser.
            val preview = response.peekBody(4096).use { it.bytes() }
            return parse("", response.code, path).copy(contentType = type,
                bytes = preview.size.toLong(), elapsedMs = elapsedMs, hasTimeHeader = hasTime, timeHeader = time,
                serverMessage = errorMessage(String(preview, Charsets.UTF_8), type))
        }
        val source = response.body?.source()
            ?: return Result(State.INVALID, path, "الخادم أعاد استجابة بدون محتوى.", response.code,
                contentType = type, elapsedMs = elapsedMs, hasTimeHeader = hasTime, timeHeader = time)
        source.request(MAX_BYTES + 1L)
        val size = source.buffer.size
        if (size > MAX_BYTES) return Result(State.TOO_LARGE, path, "الرد يتجاوز حد التجربة الآمن 512 كيلوبايت.",
            response.code, type, size, elapsedMs, hasTime, timeHeader = time)
        val text = source.buffer.readUtf8()
        // Keep a bounded sample in this result only. Copying is an explicit owner action;
        // nothing is persisted, logged, sent to AL-ATTA or automatically shared.
        return parseSuccessfulBody(text, response.code, path, response.header("t"), nowEpochSeconds, responseMask)
            .copy(contentType = type, bytes = size, elapsedMs = elapsedMs, hasTimeHeader = hasTime,
                timeHeader = time, responseText = text.take(MAX_REPORT_CHARS),
                responseTextTruncated = text.length > MAX_REPORT_CHARS)
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

    suspend fun fetch(kind: Kind, categoryId: String? = null, responseMask: ByteArray? = null): Result {
        val request = request(kind, categoryId)
        val localMask = responseMask?.copyOf()
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
                        val result = try { read(it, path, elapsed(), responseMask = localMask).copy(elapsedMs = elapsed()) }
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
