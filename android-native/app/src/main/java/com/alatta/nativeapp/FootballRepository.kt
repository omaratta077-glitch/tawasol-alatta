package com.alatta.nativeapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

internal object FootballRepository {
    internal fun channelJson(channel: FootballChannel) = with(channel) {
        JSONObject().put("id", id).put("name", name).put("category", category)
            .put("provider", provider).put("sourceUrl", sourceUrl).put("streamUrl", streamUrl)
            .put("format", format).put("enabled", enabled)
    }
    private fun records(json: JSONObject, name: String): List<JSONObject> {
        val array = json.optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull(array::optJSONObject)
    }
    internal fun parse(json: JSONObject): FootballCatalog {
        val channels = records(json, "channels").mapNotNull { c ->
            val format = c.optString("format")
            val stream = if (format == "ytv") (c.opt("streamUrl") as? String)?.let(YtvPlayer::channelId)
                else footballSafeUrl(c.optString("streamUrl"))
            if (stream == null) return@mapNotNull null
            val source = footballSafeUrl(c.optString("sourceUrl")) ?: return@mapNotNull null
            if (format !in setOf("hls", "dash", "youtube", "external", "ytv") || (format == "youtube" && footballYoutubeId(stream) == null)) return@mapNotNull null
            FootballChannel(c.getString("id"), c.getString("name"), c.getString("category"), c.getString("provider"), source, stream, format, c.optBoolean("enabled", true))
        }
        val matches = records(json, "matches").map { m -> FootballMatch(m.getString("id"), m.getString("home"), m.getString("away"), m.getString("league"),
            m.getLong("startsAt"), m.getString("status"), m.optString("channelId"),
            if (m.isNull("homeScore")) null else m.getInt("homeScore"), if (m.isNull("awayScore")) null else m.getInt("awayScore")) }
        return FootballCatalog(json.optLong("revision"), channels, matches, json.optLong("updatedAt"))
    }
    suspend fun load(token: String? = null): FootballCatalog = withContext(Dispatchers.IO) {
        parse(attaApi(if (token == null) "/api/football" else "/api/admin/football?token=${URLEncoder.encode(token, "UTF-8")}"))
    }
    suspend fun change(token: String, revision: Long, action: String, data: JSONObject): FootballCatalog = withContext(Dispatchers.IO) {
        parse(attaApi("/api/admin/football", data.put("token", token).put("revision", revision).put("action", action)))
    }
}
