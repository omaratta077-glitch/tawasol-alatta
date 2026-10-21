package com.alatta.nativeapp

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * A single source for Quran SVG page bytes, shared by the native reader, posts and PDF.
 * The opening page ships with the APK. The remaining 603 pages download once and
 * persist in private app storage for offline reading on subsequent visits.
 */
internal object MushafPageStore {
    // Immutable source revision: never point downloads at a moving main branch.
    private const val SOURCE_REVISION = "ab1fda7e4657b872ec77a596f2453e0da88d1507"
    private const val SOURCE_BASE =
        "https://raw.githubusercontent.com/omaratta077-glitch/tawasol-alatta/$SOURCE_REVISION/android-native/app/src/main/assets/mushaf/pages"
    private const val MAX_PAGE_BYTES = 2_000_000L
    private val lock = Any()

    fun openPage(context: Context, page: Int): InputStream {
        require(page in 1..604)
        if (page == 1) {
            // Android's asset merger transparently inflates the 001.svg.gz source.
            return context.assets.open("mushaf/pages/001.svg")
        }
        val packed = ensureCached(context, page)
        return GZIPInputStream(packed.inputStream())
    }

    fun isDownloaded(context: Context, page: Int): Boolean {
        if (page == 1) return true
        if (page !in 2..604) return false
        val meta = metadata(context, page)
        return isValid(File(storage(context), name(page)), meta.first, meta.second)
    }

    /** Explicit optional offline download from the reader's index screen. */
    fun downloadPage(context: Context, page: Int) {
        require(page in 1..604)
        if (page > 1) ensureCached(context, page)
    }

    private fun ensureCached(context: Context, page: Int): File = synchronized(lock) {
        val dir = storage(context).apply { mkdirs() }
        val file = File(dir, name(page))
        val (expectedLength, expectedDigest) = metadata(context, page)
        if (isValid(file, expectedLength, expectedDigest)) return@synchronized file
        file.delete()

        val temporary = File(dir, name(page) + ".part")
        temporary.delete()
        val url = URL("$SOURCE_BASE/${name(page)}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("تعذر تنزيل صفحة $page (الخادم: ${connection.responseCode})")
            }
            if (connection.contentLengthLong > expectedLength || expectedLength > MAX_PAGE_BYTES) {
                throw IOException("حجم صفحة $page غير صحيح")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > expectedLength || count > MAX_PAGE_BYTES) {
                            throw IOException("حجم التنزيل تجاوز حجم صفحة المصحف")
                        }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
            if (count != expectedLength || actualDigest != expectedDigest) {
                throw IOException("فشل التحقق من سلامة صفحة $page؛ أعد المحاولة")
            }
            if (!temporary.renameTo(file)) throw IOException("تعذر حفظ صفحة $page على الجهاز")
            file
        } catch (e: IOException) {
            temporary.delete()
            throw IOException("تحتاج اتصالًا بالإنترنت لتنزيل صفحة $page لأول مرة. ${e.message}", e)
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun metadata(context: Context, page: Int): Pair<Long, String> {
        val data = JSONObject(context.assets.open("mushaf/index.json").bufferedReader().use { it.readText() })
            .getJSONArray("pages").getJSONObject(page)
        return data.getLong("bytes") to data.getString("assetSha256").lowercase(Locale.ROOT)
    }

    private fun isValid(file: File, size: Long, hash: String): Boolean {
        if (!file.isFile || file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == hash
    }

    private fun storage(context: Context) = File(context.filesDir, "mushaf-pages")
    private fun name(page: Int) = String.format(Locale.ROOT, "%03d.svg.gz", page)
}
