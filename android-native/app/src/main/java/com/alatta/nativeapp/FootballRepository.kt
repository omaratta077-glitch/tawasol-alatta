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

    // The channel numbers below come from beIN's public channel list.
    // beIN CONNECT is a single authenticated portal: none of these cards pretends
    // to be a direct HLS stream or deep link to a specific channel.
    internal val beinOfficialChannels: List<FootballChannel> =
        (1..9).map { number ->
            FootballChannel(
                id = "official-bein-sports-$number",
                name = "beIN SPORTS $number",
                category = "بي إن سبورت",
                provider = "beIN CONNECT • اشتراك مطلوب",
                sourceUrl = "https://www.bein.com/watchbeinsports/",
                streamUrl = "https://connect.bein.com/",
                format = "external",
                enabled = true
            )
        } + FootballChannel(
            id = "official-bein-sports-news",
            name = "beIN SPORTS NEWS",
            category = "بي إن سبورت",
            provider = "beIN CONNECT • حسب الباقة",
            sourceUrl = "https://www.bein.com/watchbeinsports/",
            streamUrl = "https://connect.bein.com/",
            format = "external",
            enabled = true
        )

    // Public viewing pages served by the rightsholders. They are not raw IPTV feeds.
    // Open only at the publisher, which decides availability and regional access.
    internal val officialBroadcasters = listOf(
        onSportOfficial,
        FootballChannel(
            id = "official-alkass-online",
            name = "الكأس أونلاين",
            category = "قنوات رياضية",
            provider = "قنوات الكأس الرسمية",
            sourceUrl = "https://www.alkass.net/",
            streamUrl = "https://www.alkass.net/index?ch=online",
            format = "external",
            enabled = true
        ),
        FootballChannel(
            id = "official-dubai-sports-1",
            name = "دبي الرياضية 1",
            category = "قنوات رياضية",
            provider = "دبي للإعلام",
            sourceUrl = "https://www.dubaisports.ae/",
            streamUrl = "https://www.dubaisports.ae/content/dubaisports/livestreaming/channel-1.html",
            format = "external",
            enabled = true
        )
    ) + beinOfficialChannels

    internal fun withOfficialChannels(catalog: FootballCatalog): FootballCatalog {
        val merged = catalog.channels.toMutableList()
        for (official in officialBroadcasters) {
            // Preserve admin-defined channels; never duplicate an already configured
            // broadcaster when its link or display name is present.
            val alreadyPresent = merged.any { existing ->
                existing.id == official.id ||
                    // Different beIN channels share an authenticated landing page:
                    // identical portal URLs must not hide channels 2 through 9.
                    (!official.id.startsWith("official-bein-") &&
                        existing.streamUrl.equals(official.streamUrl, ignoreCase = true)) ||
                    existing.name.equals(official.name, ignoreCase = true) ||
                    (official.id == onSportOfficial.id &&
                        existing.sourceUrl.contains("youtube.com/@onsporteg", ignoreCase = true))
            }
            if (!alreadyPresent) merged.add(official)
        }
        return if (merged.size == catalog.channels.size) catalog
            else catalog.copy(channels = merged)
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
