package com.alatta.nativeapp

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Every signed-in Android client reads provider catalog metadata on that phone.
 * Cloudflare supplies only a protected decoding service when that phone has no
 * private format setting. No non-admin account can write the shared catalog.
 */
internal object YtvPhoneCatalog {
    internal const val UPDATE_INTERVAL_MS = 30 * 60_000L
    // Check sooner when the user chooses a channel; failures are still throttled.
    private const val ON_SELECT_CHECK_MS = 5 * 60_000L
    private const val PREFS = "alatta_ytv_phone_cache_v1"
    private val guard = Mutex()

    private fun names(value: String) =
        value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun store(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun cachedId(context: Context, owner: String, channel: FootballChannel): String? {
        if (owner.isBlank() || channel.format != "ytv" || !channel.enabled) return null
        val entry = store(context).getString("entry:" + owner + ":" + channel.id, null) ?: return null
        return runCatching {
            val obj = JSONObject(entry)
            if (obj.optString("name") != names(channel.name) ||
                obj.optString("category") != names(channel.category) ||
                // A newer admin-published channel ID always wins over local cache.
                obj.optString("publishedId") != channel.streamUrl) null
            else YtvPlayer.channelId(obj.optString("streamId"))
        }.getOrNull()
    }

    /** A stale mapping is checked again on selection, without clearing the safe fallback. */
    internal fun needsSelectionRefresh(context: Context, owner: String,
        channel: FootballChannel): Boolean {
        if (owner.isBlank() || !channel.enabled || channel.format != "ytv") return false
        if (cachedId(context, owner, channel) == null) return true
        val lastSuccess = store(context).getLong("success:" + owner, 0)
        val now = System.currentTimeMillis()
        return lastSuccess <= 0L || now < lastSuccess || now - lastSuccess >= ON_SELECT_CHECK_MS
    }

    /** Decoded data is read-only and carries no playback URL, response mask or credentials. */
    private suspend fun decodeOnServer(token: String, result: YacineCatalogProbe.Result):
        YacineCatalogProbe.Result? {
        if (result.responseText.isBlank() || result.responseTextTruncated ||
            result.timeHeader.isBlank() || result.responseText.length > 32768) return null
        return try {
            val payload = JSONObject().put("token", token)
                .put("payload", result.responseText).put("t", result.timeHeader)
            val reply = attaApi("/api/football/catalog-decode", payload)
            if (!reply.optBoolean("ok")) return null
            val list = reply.optJSONArray("entries") ?: return null
            if (list.length() > YacineCatalogProbe.MAX_ITEMS) return null
            val entries = ArrayList<YacineCatalogProbe.Entry>()
            val ids = HashSet<String>()
            for (i in 0 until list.length()) {
                val row = list.optJSONObject(i) ?: return null
                val id = (row.opt("id") as? String)?.let(YtvPlayer::channelId) ?: return null
                val name = (row.opt("name") as? String)
                    ?.filterNot { Character.isISOControl(it) }?.trim()?.take(120)
                if (name.isNullOrBlank() || !ids.add(id)) return null
                entries.add(YacineCatalogProbe.Entry(id, name))
            }
            val state = if (entries.isEmpty()) YacineCatalogProbe.State.EMPTY
                else YacineCatalogProbe.State.LIST
            result.copy(state = state, entries = entries, responseDecoded = true,
                responseText = "", responseTextTruncated = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
    }

    private suspend fun fetch(context: Context, token: String,
        kind: YacineCatalogProbe.Kind, categoryId: String? = null,
        selectedSource: String? = null): YacineCatalogProbe.Result? {
        if (selectedSource == null) YacineCatalogProbe.refreshSourceConfiguration()
        val bases = selectedSource?.let(::listOf) ?: YacineCatalogProbe.candidates()
        val privateFormat = CatalogFormatSettings.read(context)?.toByteArray(Charsets.US_ASCII)
        for (base in bases) {
            val first = try {
                YacineCatalogProbe.fetch(kind, categoryId, privateFormat, sourceBase = base)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { continue }
            if (first.state in setOf(YacineCatalogProbe.State.LIST, YacineCatalogProbe.State.EMPTY))
                return first
            if (first.state == YacineCatalogProbe.State.UNREADABLE) {
                val decoded = decodeOnServer(token, first)
                if (decoded?.state in setOf(YacineCatalogProbe.State.LIST, YacineCatalogProbe.State.EMPTY))
                    return decoded
            }
        }
        return null
    }

    /** Update only when all corresponding active channels match unique source names. */
    internal suspend fun refresh(context: Context, token: String, owner: String,
        onSelection: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            guard.withLock {
                if (token.isBlank() || owner.isBlank()) return@withLock false
                val prefs = store(context)
                val now = System.currentTimeMillis()
                val last = prefs.getLong("attempt:" + owner, 0)
                val minInterval = if (onSelection) ON_SELECT_CHECK_MS else UPDATE_INTERVAL_MS
                if (now >= last && now - last < minInterval) return@withLock false
                if (onSelection) {
                    val lastSuccess = prefs.getLong("success:" + owner, 0)
                    if (lastSuccess > 0 && now >= lastSuccess &&
                        now - lastSuccess < ON_SELECT_CHECK_MS) return@withLock false
                }
                prefs.edit().putLong("attempt:" + owner, now).apply()
                val catalog = try { FootballRepository.load() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { return@withLock false }
                val target = catalog.channels.filter {
                    it.enabled && it.format == "ytv" &&
                        names(it.category).contains("1080") &&
                        names(it.name).contains("bein")
                }
                if (target.isEmpty() || target.size > 40 ||
                    target.map { names(it.name) }.distinct().size != target.size)
                    return@withLock false
                val root = fetch(context, token, YacineCatalogProbe.Kind.CATEGORIES)
                    ?: return@withLock false
                if (root.state != YacineCatalogProbe.State.LIST) return@withLock false
                val source = root.sourceBase
                val found = CatalogSectionResolver.discover(root) { category ->
                    fetch(context, token, YacineCatalogProbe.Kind.CATEGORIES, category, source)
                        ?: YacineCatalogProbe.Result(YacineCatalogProbe.State.NETWORK_ERROR, "",
                            "Unable to load nested category")
                }.section ?: return@withLock false
                val channels = fetch(context, token, YacineCatalogProbe.Kind.CHANNELS,
                    found.id, source) ?: return@withLock false
                if (channels.state != YacineCatalogProbe.State.LIST) return@withLock false
                val byName = channels.entries.groupBy { names(it.name) }
                val mapping = target.map { channel ->
                    val matches = byName[names(channel.name)].orEmpty()
                    if (matches.size != 1) return@withLock false
                    channel to matches.single().id
                }
                if (mapping.map { it.second }.distinct().size != mapping.size)
                    return@withLock false
                val edit = prefs.edit()
                mapping.forEach { (channel, id) ->
                    edit.putString("entry:" + owner + ":" + channel.id,
                        JSONObject().put("name", names(channel.name))
                            .put("category", names(channel.category))
                            .put("publishedId", channel.streamUrl)
                            .put("streamId", id).toString())
                }
                edit.putLong("success:" + owner, now)
                edit.commit()
            }
        }
}
