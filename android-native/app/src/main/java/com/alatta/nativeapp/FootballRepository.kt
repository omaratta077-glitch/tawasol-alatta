package com.alatta.nativeapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

internal object FootballRepository {
    // Official broadcaster destination, NOT a third-party IPTV relay.
    // The broadcaster controls whether a live event is available on this page.
    internal val onSportOfficial = FootballChannel(
        id = "official-on-sport-youtube",
        name = "أون سبورت ON Sport",
        category = "قنوات رياضية",
        provider = "ON Sport • يوتيوب الرسمي",
        sourceUrl = "https://www.youtube.com/@onsporteg",
        streamUrl = "https://www.youtube.com/@onsporteg/live",
        format = "external",
        enabled = true
    )

    internal fun withOfficialChannels(catalog: FootballCatalog): FootballCatalog {
        val alreadyPresent = catalog.channels.any {
            it.id == onSportOfficial.id ||
                it.sourceUrl.contains("youtube.com/@onsporteg", ignoreCase = true) ||
                (it.name.contains("أون سبورت") && it.provider.contains("ON Sport", ignoreCase = true))
        }
        return if (alreadyPresent) catalog
            else catalog.copy(channels = listOf(onSportOfficial) + catalog.channels)
    }

    private fun records(json: JSONObject, name: String): List<JSONObject> {
        val array = json.optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull(array::optJSONObject)
    }
    private fun parse(json: JSONObject): FootballCatalog {
        val channels = records(json, "channels").mapNotNull { c ->
            val format = c.optString("format")
            val stream = footballSafeUrl(c.optString("streamUrl")) ?: return@mapNotNull null
            val source = footballSafeUrl(c.optString("sourceUrl")) ?: return@mapNotNull null
            if (format !in setOf("hls", "dash", "youtube", "external") || (format == "youtube" && footballYoutubeId(stream) == null)) return@mapNotNull null
            FootballChannel(c.getString("id"), c.getString("name"), c.getString("category"), c.getString("provider"), source, stream, format, c.optBoolean("enabled", true))
        }
        val matches = records(json, "matches").map { m -> FootballMatch(m.getString("id"), m.getString("home"), m.getString("away"), m.getString("league"),
            m.getLong("startsAt"), m.getString("status"), m.optString("channelId"),
            if (m.isNull("homeScore")) null else m.getInt("homeScore"), if (m.isNull("awayScore")) null else m.getInt("awayScore")) }
        return FootballCatalog(json.optLong("revision"), channels, matches, json.optLong("updatedAt"))
    }
    suspend fun load(token: String? = null): FootballCatalog = withContext(Dispatchers.IO) {
        val catalog = parse(attaApi(if (token == null) "/api/football" else "/api/admin/football?token=${URLEncoder.encode(token, "UTF-8")}"))
        if (token == null) withOfficialChannels(catalog) else catalog
    }
    suspend fun change(token: String, revision: Long, action: String, data: JSONObject): FootballCatalog = withContext(Dispatchers.IO) {
        parse(attaApi("/api/admin/football", data.put("token", token).put("revision", revision).put("action", action)))
    }
}
