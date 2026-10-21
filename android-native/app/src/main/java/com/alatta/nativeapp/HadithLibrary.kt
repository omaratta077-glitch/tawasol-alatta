package com.alatta.nativeapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.GZIPInputStream

/** One bundled, verified SQLite database; no API requests and no full-book loads into the UI. */
internal object HadithLibrary {
    private val installLock = Mutex()
    @Volatile private var cached: SQLiteDatabase? = null

    private suspend fun open(context: Context): SQLiteDatabase = installLock.withLock {
        cached?.takeIf { it.isOpen }?.let { return@withLock it }
        val app = context.applicationContext
        val manifest = app.assets.open("hadith/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getInt("schemaVersion") == 1) { "إصدار مكتبة الأحاديث غير مدعوم" }
        val expectedBytes = manifest.getLong("dbBytes")
        val hash = manifest.getString("dbSha256")
        require(expectedBytes in 1..128L * 1024 * 1024 && hash.matches(Regex("[a-f0-9]{64}")))
        val directory = File(app.noBackupFilesDir, "hadith").apply { mkdirs() }
        val target = File(directory, "library-${hash.take(16)}.db")
        suspend fun install() {
            val coroutine = currentCoroutineContext()
            val temporary = File.createTempFile("install-", ".part", directory)
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
            } finally { inputs.forEach { runCatching { it.close() } }; temporary.delete() }
        }
        if (!target.isFile || target.length() != expectedBytes) { target.delete(); install() }
        fun checked(): SQLiteDatabase {
            val db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            try {
                require(db.version == 1)
                db.rawQuery("SELECT count(*) FROM hadiths", null).use { require(it.moveToFirst() && it.getInt(0) == manifest.getInt("total")) }
                db.rawQuery("PRAGMA quick_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") }
                return db
            } catch (error: Exception) { db.close(); throw error }
        }
        val db = try { checked() } catch (_: Exception) { target.delete(); install(); checked() }
        cached = db
        directory.listFiles()?.filter { it.name.startsWith("library-") && it.name.endsWith(".db") && it != target }?.forEach { it.delete() }
        db
    }

    suspend fun books(context: Context): List<HadithBook> = withContext(Dispatchers.IO) {
        val db = open(context)
        db.rawQuery("SELECT id,title,author,count,source,source_url,edition FROM books ORDER BY ordinal", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(HadithBook(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3), cursor.getString(4), cursor.getString(5), cursor.getString(6))) }
        }
    }

    suspend fun sections(context: Context, bookId: String): List<HadithSection> = withContext(Dispatchers.IO) {
        open(context).rawQuery("SELECT section,min(number),max(number),count(*) FROM hadiths WHERE book_id=? GROUP BY section ORDER BY section", arrayOf(bookId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(HadithSection(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3))) }
        }
    }

    suspend fun page(context: Context, bookId: String, section: Int, query: String, requestedPage: Int): HadithPage = withContext(Dispatchers.IO) {
        val db = open(context)
        val search = hadithSearch(query)
        if (query.isNotBlank() && search.text.isBlank()) return@withContext HadithPage(emptyList(), 0, 0, 0)
        val conditions = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        if (bookId.isNotBlank()) { conditions += "h.book_id=?"; arguments += bookId }
        // A new search covers the selected book, including when opened from a reading range.
        if (section > 0 && query.isBlank()) { conditions += "h.section=?"; arguments += section.toString() }
        if (search.number != null) { conditions += "h.number=?"; arguments += search.number.toString() }
        else if (search.match != null) { conditions += "hadith_search MATCH ?"; arguments += search.match }
        val joined = if (search.match != null) " JOIN hadith_search ON hadith_search.docid=h.id" else ""
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val from = " FROM hadiths h$joined$where"
        val total = db.rawQuery("SELECT count(*)$from", arguments.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }
        val pages = (total + HADITH_PAGE_SIZE - 1) / HADITH_PAGE_SIZE
        val page = requestedPage.coerceIn(0, (pages - 1).coerceAtLeast(0))
        val records = db.rawQuery("SELECT h.id,h.book_id,h.number,h.section,h.body$from ORDER BY h.id LIMIT ? OFFSET ?",
            (arguments + listOf(HADITH_PAGE_SIZE.toString(), (page * HADITH_PAGE_SIZE).toString())).toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(HadithEntry(cursor.getLong(0), cursor.getString(1), cursor.getInt(2), cursor.getInt(3), cursor.getString(4))) }
        }
        HadithPage(records, total, page, pages)
    }
}
