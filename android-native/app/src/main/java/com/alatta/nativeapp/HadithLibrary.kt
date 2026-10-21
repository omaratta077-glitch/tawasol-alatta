package com.alatta.nativeapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.GZIPInputStream

/** One bundled, verified SQLite database; no API requests and no full-book loads into the UI. */
/** Safe, actionable UI error; the original cause is logged for diagnosis. */
internal class HadithPreparationException(
    message: String, cause: Throwable? = null, val stage: String = "PREPARE"
) : IOException(message, cause)

internal object HadithLibrary {
    private val installLock = Mutex()
    @Volatile private var cached: SQLiteDatabase? = null

    /** Never trust only a matching file length: an interrupted or stale copy may
     * have the same length. Hash the full APK-derived file once per cold start.
     * This avoids OEM-specific SQLite PRAGMA checks during installation.
     */
    private fun verifiedCopy(file: File, bytes: Long, sha256: String): Boolean {
        if (!file.isFile || file.length() != bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }

    /**
     * Only clear this library's own extracted SQLite files; never touch user
     * accounts, posts, settings, messages or other app storage.
     * Calling books() after repair will reinstall from the signed APK assets.
     */
    suspend fun repair(context: Context) = withContext(Dispatchers.IO) {
        installLock.withLock {
            cached?.let { runCatching { it.close() } }
            cached = null
            val directory = File(context.applicationContext.noBackupFilesDir, "hadith")
            directory.listFiles()?.filter { file ->
                (file.name.startsWith("library-") && file.name.endsWith(".db")) ||
                    (file.name.startsWith("install-") && file.name.endsWith(".part"))
            }?.forEach { file ->
                if (!file.delete() && file.exists()) {
                    throw HadithPreparationException("تعذر حذف النسخة المحلية التالفة من مكتبة الأحاديث.")
                }
            }
        }
    }

    private suspend fun open(context: Context): SQLiteDatabase = installLock.withLock {
        cached?.takeIf { it.isOpen }?.let { return@withLock it }
        val app = context.applicationContext
        val manifest = app.assets.open("hadith/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getInt("schemaVersion") == 1) { "إصدار مكتبة الأحاديث غير مدعوم" }
        val expectedBytes = manifest.getLong("dbBytes")
        val hash = manifest.getString("dbSha256")
        require(expectedBytes in 1..128L * 1024 * 1024 && hash.matches(Regex("[a-f0-9]{64}")))
        val directory = File(app.noBackupFilesDir, "hadith").apply { mkdirs() }
        if (!directory.isDirectory) throw HadithPreparationException("تعذر الوصول لمساحة تخزين مكتبة الأحاديث على الهاتف.")
        val target = File(directory, "library-${hash.take(16)}.db")
        suspend fun install() {
            // The compressed assets stay in the APK; only the 37 MB SQLite file is extracted.
            // File.usableSpace reads the same private volume used for extraction.
            // A zero reading can also mean "unknown" (notably in JVM/Robolectric tests);
            // in that case try writing and let the actual I/O error explain a full disk.
            val availableBytes = directory.usableSpace
            if (availableBytes > 0L && availableBytes < expectedBytes + 2L * 1024 * 1024) {
                throw HadithPreparationException("المساحة الداخلية غير كافية لتجهيز الأحاديث. وفر نحو 40 ميجابايت ثم أعد المحاولة.")
            }
            val coroutine = currentCoroutineContext()
            val temporary = try { File.createTempFile("install-", ".part", directory) }
                catch (error: IOException) { throw HadithPreparationException("تعذر إنشاء ملف مكتبة الأحاديث على الهاتف.", error) }
            val inputs = mutableListOf<InputStream>()
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                val parts = manifest.getJSONArray("parts")
                require(parts.length() in 1..64)
                for (index in 0 until parts.length()) {
                    val name = parts.getJSONObject(index).getString("file")
                    require(name.matches(Regex("library\\.db\\.gz\\.[0-9]{3}")))
                    inputs += app.assets.open("hadith/$name")
                }
                GZIPInputStream(SequenceInputStream(Collections.enumeration(inputs))).use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= expectedBytes) { "تعذر تجهيز مكتبة الأحاديث" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                } }
                require(total == expectedBytes && digest.digest().joinToString("") { "%02x".format(it) } == hash) { "ملف مكتبة الأحاديث غير مكتمل" }
                require(temporary.renameTo(target)) { "تعذر حفظ مكتبة الأحاديث على الهاتف" }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("AlattaHadith", "Bundled library extraction failed", error)
                if (error is HadithPreparationException) throw error
                throw HadithPreparationException("فشل استخراج مكتبة الأحاديث من ملفات التطبيق. أعد المحاولة؛ وإذا استمر الخطأ أعد تثبيت الإصدار.", error)
            } finally { inputs.forEach { runCatching { it.close() } }; temporary.delete() }
        }
        // Verify the exact bundled SQLite bytes, not Android/OEM PRAGMA output.
        // The archive is separately checked during CI by PRAGMA integrity_check,
        // user_version and all 14,006 source rows. Do not run quick_check on every
        // Honor/Android cold start: some vendor SQLite builds throw
        // IllegalArgumentException while evaluating PRAGMA, despite valid data.
        if (!verifiedCopy(target, expectedBytes, hash)) {
            if (!target.delete() && target.exists()) {
                throw HadithPreparationException("تعذر استبدال نسخة الأحاديث غير المكتملة.", stage = "FILE_REPLACE")
            }
            install()
        }
        val db = try {
            // No localized collation override and no redundant PRAGMA queries.
            // The first user-facing SELECT books is the actual usability check.
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (error: Exception) {
            Log.e("AlattaHadith", "Verified SQLite bytes rejected by Android SQLite", error)
            throw HadithPreparationException(
                "ملف الأحاديث سليم وبصمته مطابقة، لكن الهاتف رفض فتح SQLite. انسخ التشخيص للدعم.",
                error, "SQLITE_OPEN"
            )
        }
        cached = db
        directory.listFiles()?.filter { it.name.startsWith("library-") && it.name.endsWith(".db") && it != target }?.forEach { it.delete() }
        db
    }

    suspend fun books(context: Context): List<HadithBook> = withContext(Dispatchers.IO) {
        val db = open(context)
        try {
            db.rawQuery("SELECT id,title,author,count,source,source_url,edition FROM books ORDER BY ordinal", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(HadithBook(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3), cursor.getString(4), cursor.getString(5), cursor.getString(6))) }
            }
        } catch (error: Exception) {
            Log.e("AlattaHadith", "SQLite opened but book listing failed", error)
            throw HadithPreparationException(
                "القاعدة سليمة، لكن تعذرت قراءة قائمة الكتب على هذا الهاتف. انسخ التشخيص للدعم.",
                error, "BOOKS_QUERY"
            )
        }
    }

    suspend fun sections(context: Context, bookId: String): List<HadithSection> = withContext(Dispatchers.IO) {
        open(context).rawQuery("SELECT section,min(number),max(number),count(*) FROM hadiths WHERE book_id=? GROUP BY section ORDER BY section", arrayOf(bookId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(HadithSection(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3))) }
        }
    }

    /**
     * If the device's SQLite build cannot query FTS4, search the normalized text column.
     * The fallback is slower but works offline and never loses the original Arabic content.
     */
    suspend fun page(
        context: Context, bookId: String, section: Int, query: String,
        requestedPage: Int, preferFullText: Boolean = true
    ): HadithPage = withContext(Dispatchers.IO) {
        val db = open(context)
        val search = hadithSearch(query)
        if (query.isNotBlank() && search.text.isBlank()) return@withContext HadithPage(emptyList(), 0, 0, 0)

        fun execute(useFts: Boolean): HadithPage {
            val conditions = mutableListOf<String>()
            val arguments = mutableListOf<String>()
            if (bookId.isNotBlank()) { conditions += "h.book_id=?"; arguments += bookId }
            if (section > 0 && query.isBlank()) { conditions += "h.section=?"; arguments += section.toString() }
            if (search.number != null) {
                conditions += "h.number=?"; arguments += search.number.toString()
            } else if (search.match != null) {
                if (useFts) {
                    conditions += "hadith_search MATCH ?"; arguments += search.match
                } else {
                    // All normalized terms must occur, with parameters (no SQL/FTS operators).
                    for (term in search.text.split(' ').filter(String::isNotBlank).take(8)) {
                        conditions += "INSTR(h.search, ?) > 0"
                        arguments += term
                    }
                }
            }
            val joined = if (useFts && search.match != null) " JOIN hadith_search ON hadith_search.docid=h.id" else ""
            val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
            val from = " FROM hadiths h$joined$where"
            val total = db.rawQuery("SELECT count(*)$from", arguments.toTypedArray()).use {
                it.moveToFirst(); it.getInt(0)
            }
            val pages = (total + HADITH_PAGE_SIZE - 1) / HADITH_PAGE_SIZE
            val selectedPage = requestedPage.coerceIn(0, (pages - 1).coerceAtLeast(0))
            val records = db.rawQuery(
                "SELECT h.id,h.book_id,h.number,h.section,h.body$from ORDER BY h.id LIMIT ? OFFSET ?",
                (arguments + listOf(HADITH_PAGE_SIZE.toString(), (selectedPage * HADITH_PAGE_SIZE).toString())).toTypedArray()
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(
                        HadithEntry(cursor.getLong(0), cursor.getString(1), cursor.getInt(2),
                            cursor.getInt(3), cursor.getString(4))
                    )
                }
            }
            return HadithPage(records, total, selectedPage, pages)
        }

        if (preferFullText && search.match != null) {
            try { execute(true) }
            catch (error: SQLiteException) {
                Log.w("AlattaHadith", "FTS query failed; using normalized SQL fallback", error)
                execute(false)
            }
        } else execute(false)
    }

}
