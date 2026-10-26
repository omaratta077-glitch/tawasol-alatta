package com.alatta.stream

import android.content.Context
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Sources are user-supplied/licensed. Never scrape, decode tokens or proxy third-party streams. */
class ChannelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("channels_prefs", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "channels.json")
    private val favoriteKey = "favorites"
    private val recentKey = "recent"

    fun catalogUrl(): String = prefs.getString("catalog_url", "") ?: ""
    fun setCatalogUrl(url: String) = prefs.edit().putString("catalog_url", url).apply()

    fun all(): List<Channel> = try {
        if (!file.exists()) emptyList() else parseCatalog(file.readText(Charsets.UTF_8))
    } catch (_: Exception) { emptyList() }

    fun save(channels: List<Channel>) {
        val root = JSONObject().put("version", 1)
        val array = JSONArray()
        channels.forEach { array.put(it.asJson()) }
        root.put("channels", array)
        val temp = File(context.filesDir, "channels.tmp")
        temp.writeText(root.toString(), Charsets.UTF_8)
        check(temp.renameTo(file) || run { file.writeText(root.toString()); temp.delete(); true })
    }

    fun add(name: String, category: String, streamUrl: String) {
        require(name.isNotBlank() && category.isNotBlank()) { "اكتب اسم القناة والقسم" }
        require(isHttps(streamUrl)) { "الرابط لازم يبدأ بـ https://" }
        val channel = Channel(UUID.randomUUID().toString(), name.take(100), category.take(40), streamUrl.trim())
        save(all() + channel)
    }

    fun remove(id: String) = save(all().filterNot { it.id == id })

    /** Expected JSON: {"version":1,"channels":[{id,name,category,streamUrl,...}]} */
    fun parseCatalog(json: String): List<Channel> {
        val root = JSONObject(json)
        val array = root.getJSONArray("channels")
        require(array.length() <= 250) { "الحد الأقصى 250 قناة" }
        val result = linkedMapOf<String, Channel>()
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val id = entry.getString("id").trim().take(100)
            val name = entry.getString("name").trim().take(100)
            val category = entry.optString("category", "عام").trim().ifEmpty { "عام" }.take(40)
            val url = entry.getString("streamUrl").trim()
            if (id.isBlank() || name.isBlank() || !isHttps(url) || !entry.optBoolean("enabled", true)) continue
            result[id] = Channel(id, name, category, url, entry.optString("description", "").take(200), true, entry.optString("origin", "local"))
        }
        return result.values.toList()
    }

    /** Call only from a worker thread. Network timeout and response size are bounded. */
    fun download(url: String): List<Channel> {
        require(isHttps(url)) { "رابط القائمة يجب أن يكون HTTPS" }
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 9000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            check(connection.responseCode == 200) { "خطأ HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                val output = java.io.ByteArrayOutputStream()
                while (true) {
                    val length = stream.read(buffer)
                    if (length == -1) break
                    check(output.size() + length <= 1024 * 1024) { "ملف القنوات كبير" }
                    output.write(buffer, 0, length)
                }
                output.toByteArray()
            }
            return parseCatalog(String(bytes, Charsets.UTF_8)).map { it.copy(origin = "remote") }
        } finally { connection.disconnect() }
    }

    private fun isHttps(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    } catch (_: Exception) { false }

    private fun idList(key: String): List<String> = prefs.getString(key, "")
        ?.split('|')?.filter { it.isNotBlank() } ?: emptyList()

    fun favorites(): Set<String> = idList(favoriteKey).toSet()
    fun isFavorite(id: String) = id in favorites()
    fun toggleFavorite(id: String) {
        val updated = favorites().toMutableSet()
        if (!updated.add(id)) updated.remove(id)
        prefs.edit().putString(favoriteKey, updated.joinToString("|")).apply()
    }

    fun markViewed(id: String) {
        val new = (listOf(id) + idList(recentKey).filterNot { it == id }).take(24)
        prefs.edit().putString(recentKey, new.joinToString("|")).apply()
    }
    fun recent(): List<String> = idList(recentKey)
}
