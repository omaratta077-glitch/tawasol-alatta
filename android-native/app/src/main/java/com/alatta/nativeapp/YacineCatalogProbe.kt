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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/** فحص تشخيصي صريح للمالك وللقراءة فقط؛ لا يتلقى رموز الحسابات ولا يحرر الكتالوج. */
internal object YacineCatalogProbe {
    // New server observed from the owner's YTV traffic (Nov 13, 2026).
    // Try TLS first; the observed cleartext endpoint is confined to public catalog
    // GET requests only and gets its own narrowly scoped Android network exception.
    const val BASE = "https://def111.ycnapi.com/api/"
    const val OBSERVED_HTTP_BASE = "http://def111.ycnapi.com/api/"
    const val LEGACY_BASE = "https://def001.ycnapi.com/api/"
    private val catalogBases = listOf(BASE, OBSERVED_HTTP_BASE, LEGACY_BASE)
    private const val REMOTE_CONFIG_PATH = "/catalog-source-config.json"
    private const val CONFIG_REFRESH_MS = 30 * 60_000L
    private val allowedRemoteHost = Regex("^[a-z0-9-]+\\.ycnapi\\.com$")
    @Volatile private var remoteBases: List<String> = emptyList()
    @Volatile private var lastConfigCheckMs: Long = 0

    // The app's own HTTPS origin controls priority, not Firebase or a third-party private endpoint.
    // Restrict remotely supplied hosts to the approved HTTPS catalog domain.
    internal fun candidates(): List<String> =
        (remoteBases + catalogBases).distinct()

    internal suspend fun refreshSourceConfiguration() {
        val now = System.currentTimeMillis()
        if (now >= lastConfigCheckMs && now - lastConfigCheckMs < CONFIG_REFRESH_MS) return
        lastConfigCheckMs = now
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(ATTA_API_BASE + REMOTE_CONFIG_PATH)
                    .header("Accept", "application/json").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful || response.body == null) return@use
                    val bytes = response.body!!.bytes()
                    if (bytes.size > 8192) return@use
                    val json = JSONObject(String(bytes, Charsets.UTF_8))
                    if (json.optInt("version") < 1) return@use
                    val list = json.optJSONArray("sources") ?: return@use
                    if (list.length() !in 1..8) return@use
                    val updated = (0 until list.length()).map { i ->
                        val source = list.optString(i)
                        val url = okhttp3.HttpUrl.parse(source) ?: return@use
                        if (url.scheme != "https" || url.port != 443 ||
                            !allowedRemoteHost.matches(url.host) ||
                            url.encodedPath != "/api/" ||
                            url.query != null || url.fragment != null ||
                            url.username.isNotEmpty() || url.password.isNotEmpty()) return@use
                        source
                    }
                    if (updated.distinct().size != updated.size) return@use
                    remoteBases = updated
                }
            } catch (_: Exception) {
                // Keep the last known-good remote selection and built-in fallback.
            }
        }
    }
    const val MAX_BYTES = 512 * 1024
    const val MAX_ITEMS = 500
    const val MAX_REPORT_CHARS = 16 * 1024
    enum class Kind { CATEGORIES, CHANNELS }
    enum class State { LIST, EMPTY, HTTP_ERROR, UNREADABLE, INVALID, TOO_LARGE, NETWORK_ERROR }
    data class Entry(val id: String, val name: String, val childCount: Long? = null)
    /** Diagnostics only: public catalog host, response code and timing. No credentials or body. */
    data class Attempt(
        val sourceBase: String, val state: State, val code: Int?, val message: String,
        val elapsedMs: Long, val bytes: Long
    ) {
        fun summary(): String = buildString {
            append(sourceBase).append(" • ")
            append(code?.let { "HTTP $it" } ?: state.name)
            if (state !in setOf(State.LIST, State.EMPTY)) append(" • ").append(message)
            append(" • ").append(elapsedMs).append(" ms")
            append(" • ").append(bytes).append(" bytes")
        }
    }
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
        val deviceTimeUsed: String = "",
        val sourceBase: String = "",
        val attempts: List<Attempt> = emptyList()
    ) {
        fun report(): String = buildString {
            append("تجربة قائمة ياسين من الهاتف\nGET ").append(sourceBase.ifBlank { BASE }).append(path)
            append("\nالحالة: ").append(state.name).append("\n").append(message)
            if (attempts.isNotEmpty()) {
                append("\n\nنتائج جميع خوادم جلب القائمة:")
                attempts.forEachIndexed { index, attempt ->
                    append("\n").append(index + 1).append(". ").append(attempt.summary())
                }
            }
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

    internal fun request(kind: Kind, categoryId: String? = null, sourceBase: String = BASE): Request {
        require(sourceBase in candidates()) { "عنوان قائمة القنوات غير معتمد" }
        val id = categoryId?.let { requireNotNull(YtvPlayer.channelId(it)) { "رقم قسم غير صالح" } }
        val path = when (kind) {
            Kind.CATEGORIES -> if (id == null) "categories" else "categories/" + id
            Kind.CHANNELS -> "categories/" + requireNotNull(id) { "اختر قسمًا أولًا" } + "/channels"
        }
        // عميل مستقل: بلا ملفات تعريف ارتباط أو رموز أو ترويسات AL-ATTA أو بيانات دخول تطبيق خارجي.
        return Request.Builder().url(sourceBase + path).header("Accept", "application/json").get().build()
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

    /** فك ترميز استجابة المصدر فقط؛ ولا يضيف توقيعًا للطلب أو هوية للعميل. */
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
        // صدّر الطابع الزمني الرقمي للاستجابة فقط؛ ولا تصدّر ترويسات عشوائية أو بيانات اعتماد.
        val time = response.header("t").orEmpty().trim()
            .takeIf { it.length in 10..13 && it.all { char -> char in '0'..'9' } }.orEmpty()
        if (!response.isSuccessful) {
            // اقرأ معاينة محدودة للخطأ من دون إعادة المحاولة أو اتباع التحويلات،
            // ومن دون تمرير نص الخطأ إلى محلل الكتالوج المخصص للنجاح.
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
        // احتفظ بعينة محدودة في هذه النتيجة فقط. نسخها إجراء صريح من المالك؛
        // ولا تُحفظ ولا تُسجّل ولا تُرسل إلى AL-ATTA ولا تُشارك تلقائيًا.
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

    /** Never mix categories from one host with channel IDs from another host. */
    suspend fun fetch(kind: Kind, categoryId: String? = null, responseMask: ByteArray? = null,
        sourceBase: String? = null): Result {
        if (sourceBase == null) refreshSourceConfiguration()
        val bases = if (sourceBase == null) candidates() else listOf(sourceBase.also {
            require(it in candidates()) { "عنوان قائمة القنوات غير معتمد" }
        })
        val history = ArrayList<Result>(bases.size)
        for (base in bases) {
            val current = fetchSingle(kind, categoryId, responseMask, base)
            history.add(current)
            if (current.state == State.LIST || current.state == State.EMPTY)
                return summarizeAttempts(history)
        }
        // A response on the first host may contain a useful HTTP code or an
        // undecodable body. Never hide that information behind a later DNS error.
        return summarizeAttempts(history)
    }

    internal fun summarizeAttempts(history: List<Result>): Result {
        require(history.isNotEmpty())
        val successful = history.firstOrNull { it.state == State.LIST || it.state == State.EMPTY }
        val usefulFailure = history.firstOrNull { it.code != null && it.code in 200..299 }
            ?: history.firstOrNull { it.code != null }
            ?: history.first()
        return (successful ?: usefulFailure).copy(attempts = history.take(3).map {
            Attempt(it.sourceBase, it.state, it.code, it.message, it.elapsedMs, it.bytes)
        })
    }

    private suspend fun fetchSingle(kind: Kind, categoryId: String?, responseMask: ByteArray?,
        sourceBase: String): Result {
        val request = request(kind, categoryId, sourceBase)
        val localMask = responseMask?.copyOf()
        val path = request.url.encodedPath.removePrefix("/api/")
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            fun finish(result: Result) {
                if (continuation.isActive) continuation.resume(result.copy(sourceBase = sourceBase))
            }
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
