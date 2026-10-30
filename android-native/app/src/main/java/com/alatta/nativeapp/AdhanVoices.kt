package com.alatta.nativeapp

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class AdhanVoice(val id: String, val name: String, val mosque: String, val url: String,
    val size: Long, val sha256: String, val source: String, val sourceUrl: String = "")

/** لا تفتح خدمة المنبّه إلا ملفًا محليًا تم التحقق منه؛ ولا تنزّل صوتًا ولا تبثّه. */
internal object AdhanVoices {
    private val downloadLock = Mutex()
    private fun directory(context: Context) = File(context.filesDir, "adhan-voices").apply { mkdirs() }
    private fun file(context: Context, hash: String): File? = hash.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        ?.let { File(directory(context), "$it.audio") }
    fun selectedFile(context: Context, settings: PrayerSettings): File? = file(context, settings.voiceSha256)?.takeIf { it.isFile && it.length() in 1..ADHAN_MAX_BYTES.toLong() }
    fun downloaded(context: Context, voice: AdhanVoice) = file(context, voice.sha256)?.let { it.isFile && it.length() == voice.size } == true

    internal fun parse(value: JSONObject): AdhanVoice {
        val raw = value.getString("url")
        val audio = if (raw.startsWith("/media/adhan/")) ATTA_API_BASE + raw else raw
        val parsed = java.net.URI(audio)
        require(parsed.scheme == "https" && parsed.userInfo == null && parsed.fragment == null)
        // خادما الصوت الوحيدان هما الناشر المضمّن وملفات الصوت التي يرفعها مدير التطبيق.
        require((parsed.host == "d1.islamhouse.com" && parsed.path.startsWith("/data/ar/ih_sounds/chain/Athan/")) ||
            (parsed.host == java.net.URI(ATTA_API_BASE).host && parsed.path.startsWith("/media/adhan/")))
        val id = value.getString("id"); val name = value.getString("name").trim()
        val size = value.getLong("size"); val hash = value.getString("sha256")
        require(id.matches(Regex("[a-zA-Z0-9-]{1,100}")) && name.length in 2..80 && size in 1..ADHAN_MAX_BYTES.toLong() && hash.matches(Regex("[a-f0-9]{64}")))
        return AdhanVoice(id, name, value.optString("mosque").take(120), audio, size, hash,
            value.optString("source").take(160), value.optString("sourceUrl").takeIf { it.startsWith("https://") }.orEmpty())
    }
    private fun parseList(json: String): List<AdhanVoice> {
        val entries = JSONObject(json).optJSONArray("voices") ?: JSONArray()
        return (0 until minOf(entries.length(), 50)).mapNotNull { index -> runCatching { parse(entries.getJSONObject(index)) }.getOrNull() }.distinctBy { it.id }
    }
    fun catalog(context: Context): List<AdhanVoice> {
        val builtIn = context.assets.open("adhan-voices.json").bufferedReader().use { parseList(it.readText()) }
        val added = runCatching { parseList(File(directory(context), "catalog.json").readText()) }.getOrDefault(emptyList())
        return (builtIn + added).distinctBy { it.id }
    }
    suspend fun refresh(context: Context): List<AdhanVoice> = withContext(Dispatchers.IO) {
        val json = attaApi("/api/adhan-voices").toString()
        parseList(json) // Validate before replacing the last known catalog.
        File(directory(context), "catalog.json").writeText(json)
        catalog(context)
    }

    suspend fun download(context: Context, voice: AdhanVoice): Unit = withContext(Dispatchers.IO) {
        downloadLock.withLock {
            val target = requireNotNull(file(context, voice.sha256))
            if (downloaded(context, voice)) return@withLock
            val temporary = File.createTempFile("voice-", ".part", directory(context))
            val conn = (URL(voice.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 25_000; instanceFollowRedirects = false
                setRequestProperty("Accept", "audio/*")
                setRequestProperty("User-Agent", "TawasolAlattaAndroid/1.0")
            }
            try {
                require(conn.responseCode == 200) { "تعذر تنزيل التسجيل؛ حاول مرة أخرى" }
                require(conn.contentLengthLong <= ADHAN_MAX_BYTES) { "التسجيل أكبر من 20 ميجابايت" }
                val coroutine = currentCoroutineContext()
                conn.inputStream.use { input -> temporary.outputStream().use { out ->
                    copyVerifiedAdhan(input, out, voice.size, voice.sha256) { coroutine.ensureActive() }
                } }
                validateAudio(temporary.path)
                require(temporary.renameTo(target)) { "تعذر حفظ التسجيل على الهاتف" }
            } finally { conn.disconnect(); temporary.delete() }
        }
    }

    fun validateAudio(path: String) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration in 1_000..600_000) { "اختر تسجيل أذان صوتي مدته من ثانية إلى 10 دقائق" }
        } finally { retriever.release() }
    }
}
