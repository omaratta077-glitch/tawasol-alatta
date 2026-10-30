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

/**
 * مصدر واحد لبايتات صفحات المصحف بصيغة SVG، مشترك بين القارئ الأصلي والمنشورات وPDF.
 * تأتي صفحة البداية مع APK. وتُنزّل الصفحات الـ603 المتبقية مرة واحدة،
 * ثم تُحفظ في مساحة التطبيق الخاصة لقراءتها دون اتصال في الزيارات التالية.
 */
internal object MushafPageStore {
    // ثبّت مراجعة المصدر؛ ولا توجّه التنزيلات إلى فرع main متغير المحتوى.
    private const val SOURCE_REVISION = "ab1fda7e4657b872ec77a596f2453e0da88d1507"
    private const val SOURCE_BASE =
        "https://raw.githubusercontent.com/omaratta077-glitch/tawasol-alatta/$SOURCE_REVISION/android-native/app/src/main/assets/mushaf/pages"
    private const val MAX_PAGE_BYTES = 2_000_000L
    // تُسلسَل الطلبات للصفحة نفسها فقط. وتظل الصفحة المحفوظة دون اتصال قابلة للاستخدام
    // أثناء تنزيل صفحة أخرى عبر اتصال بطيء.
    private val pageLocks = Array(605) { Any() }
    private val metadataLock = Any()
    @Volatile private var pageMetadata: List<Pair<Long, String>>? = null

    fun openPage(context: Context, page: Int, allowNetwork: Boolean = true): InputStream {
        require(page in 1..604)
        if (page == 1) {
            // يوسّع Android ملف المصدر 001.svg.gz تلقائيًا أثناء دمج الأصول.
            return context.assets.open("mushaf/pages/001.svg")
        }
        val packed = ensureCached(context, page, allowNetwork)
        return GZIPInputStream(packed.inputStream())
    }

    fun isDownloaded(context: Context, page: Int): Boolean {
        if (page == 1) return true
        if (page !in 2..604) return false
        val meta = metadata(context, page)
        return isValid(File(storage(context), name(page)), meta.first, meta.second)
    }

    /** تنزيل اختياري صريح للاستخدام دون اتصال من شاشة فهرس القارئ. */
    fun downloadPage(context: Context, page: Int) {
        require(page in 1..604)
        if (page > 1) ensureCached(context, page)
    }

    internal fun ensureCached(context: Context, page: Int, allowNetwork: Boolean = true,
        connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
    ): File {
        require(page in 2..604)
        return synchronized(pageLocks[page]) {
            val dir = storage(context).apply { mkdirs() }
            val file = File(dir, name(page))
            val (expectedLength, expectedDigest) = metadata(context, page)
            if (isValid(file, expectedLength, expectedDigest)) return@synchronized file
            if (!allowNetwork) throw IOException("هذه الصفحة غير محفوظة بعد. افتحها أثناء الاتصال أو نزّل المصحف كاملًا من الفهرس.")
            file.delete()

            val temporary = File(dir, name(page) + ".part")
            temporary.delete()
            val url = URL("$SOURCE_BASE/${name(page)}")
            val connection = connect(url).apply {
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
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Download cancelled")
                            count += n
                            if (count > expectedLength || count > MAX_PAGE_BYTES) {
                                throw IOException("حجم التنزيل تجاوز حجم صفحة المصحف")
                            }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (count != expectedLength || !MessageDigest.isEqual(digest.digest(), digestBytes(expectedDigest))) {
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
    }

    private fun metadata(context: Context, page: Int): Pair<Long, String> {
        val entries = pageMetadata ?: synchronized(metadataLock) {
            pageMetadata ?: run {
                val pages = MushafIndexStore.load(context).pages
                require(pages.length() == 605)
                List(605) { number ->
                    if (number == 0) 0L to "" else {
                        val data = pages.getJSONObject(number)
                        val size = data.getLong("bytes")
                        val hash = data.getString("assetSha256").lowercase(Locale.ROOT)
                        require(size in 1..MAX_PAGE_BYTES && hash.matches(Regex("[a-f0-9]{64}")))
                        size to hash
                    }
                }.also { pageMetadata = it }
            }
        }
        return entries[page]
    }

    private fun isValid(file: File, size: Long, hash: String): Boolean = try {
        if (!file.isFile || file.length() != size) false else {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        MessageDigest.isEqual(digest.digest(), digestBytes(hash))
        }
    } catch (_: IOException) { false }

    private fun digestBytes(hash: String) = hash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun storage(context: Context) = File(context.filesDir, "mushaf-pages")
    private fun name(page: Int) = String.format(Locale.ROOT, "%03d.svg.gz", page)
}
