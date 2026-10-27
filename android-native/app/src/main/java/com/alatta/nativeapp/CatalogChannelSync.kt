package com.alatta.nativeapp

import kotlinx.coroutines.CancellationException
import java.util.Locale

/** A local owner binding. Category and full source name distinguish quality variants. */
internal data class CatalogChannelLink(val channelId: String, val categoryId: String,
    val sourceName: String, val lastPublishedId: String)

internal data class CatalogSyncResult(val catalog: FootballCatalog? = null, val changed: Int = 0,
    val checked: Int = 0, val message: String, val linked: Boolean = false)

/** Only updates existing YTV records, through the existing revision-checked owner API. */
internal class CatalogChannelSync(
    private val load: suspend () -> FootballCatalog,
    private val fetch: suspend (String) -> YacineCatalogProbe.Result,
    private val save: suspend (FootballCatalog, FootballChannel) -> FootballCatalog,
    private val rememberLinks: (List<CatalogChannelLink>) -> Boolean
) {
    private fun name(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    private fun exact(result: YacineCatalogProbe.Result, sourceName: String): YacineCatalogProbe.Entry? {
        if (result.state != YacineCatalogProbe.State.LIST) return null
        return result.entries.filter { name(it.name) == name(sourceName) }.singleOrNull()
            ?.takeIf { YtvPlayer.channelId(it.id) == it.id }
    }

    suspend fun bind(links: List<CatalogChannelLink>, channelId: String, categoryId: String,
        selected: YacineCatalogProbe.Entry): CatalogSyncResult {
        var catalog: FootballCatalog? = null
        try {
            val category = YtvPlayer.channelId(categoryId)
                ?: return CatalogSyncResult(message = "اختر قسمًا صحيحًا أولًا.")
            val current = load().also { catalog = it }
            val channel = current.channels.singleOrNull { it.id == channelId && it.format == "ytv" }
                ?: return CatalogSyncResult(current, message = "القناة المحفوظة لم تعد متاحة للربط.")
            // Re-read the selected category; never bind from a stale or ambiguous preview.
            val entry = exact(fetch(category), selected.name)?.takeIf { it.id == selected.id }
                ?: return CatalogSyncResult(current, message = "تغيرت القائمة أو لم يصل اسم فريد؛ حدّث القائمة واختر القناة مجددًا.")
            val changed = if (channel.streamUrl == entry.id) 0 else 1
            if (changed != 0) catalog = save(current, channel.copy(streamUrl = entry.id))
            val link = CatalogChannelLink(channel.id, category, entry.name, entry.id)
            if (!rememberLinks(links.filterNot { it.channelId == channel.id } + link))
                return CatalogSyncResult(catalog, changed, message = "تم حفظ الرقم، لكن تعذر حفظ الربط على الهاتف. أعد الربط.")
            return CatalogSyncResult(catalog, changed, 1, "تم الربط. يُحدّث الرقم تلقائيًا أثناء فتح العطا على هاتف المالك.", linked = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return CatalogSyncResult(catalog, message = "تعذر إكمال الربط؛ حدّث القائمة وحاول مجددًا.") }
    }

    suspend fun refresh(links: List<CatalogChannelLink>): CatalogSyncResult {
        if (links.isEmpty()) return CatalogSyncResult(message = "اربط القناة المحفوظة من قائمة ياسين أولًا.")
        var catalog: FootballCatalog? = null
        var changed = 0
        var checked = 0
        var skipped = 0
        var remembered = links
        try {
            // Also verifies the current account's owner role before any source request.
            catalog = load()
            val responses = mutableMapOf<String, YacineCatalogProbe.Result>()
            for (link in links) {
                val current = requireNotNull(catalog)
                val channel = current.channels.singleOrNull { it.id == link.channelId }
                // A manual ID edit pauses this binding; never overwrite it silently.
                if (channel == null || channel.format != "ytv" || !channel.enabled ||
                    channel.streamUrl != link.lastPublishedId || YtvPlayer.channelId(link.categoryId) != link.categoryId) {
                    skipped++; continue
                }
                val response = responses[link.categoryId] ?: fetch(link.categoryId).also { responses[link.categoryId] = it }
                val entry = exact(response, link.sourceName)
                if (entry == null) { skipped++; continue }
                checked++
                if (entry.id == channel.streamUrl) continue
                catalog = save(current, channel.copy(streamUrl = entry.id))
                changed++
                remembered = remembered.map { if (it.channelId == link.channelId) it.copy(lastPublishedId = entry.id) else it }
                // Commit progress per successful write; a later failure cannot lose earlier work.
                if (!rememberLinks(remembered)) return CatalogSyncResult(catalog, changed, checked,
                    "تم تحديث $changed قناة، لكن تعذر حفظ متابعة الربط على الهاتف. أعد ربطها.")
            }
            return CatalogSyncResult(catalog, changed, checked, when {
                skipped > 0 -> "تم فحص $checked قناة وتحديث $changed. تعذر تحديث $skipped ربط؛ احتُفظ بالأرقام الحالية."
                changed > 0 -> "تم تحديث أرقام $changed قناة وإتاحتها للمستخدمين."
                else -> "تم التحقق؛ أرقام القنوات المرتبطة محدّثة."
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return CatalogSyncResult(catalog, changed, checked,
            "تعذر إكمال التحديث؛ احتُفظ بالأرقام الحالية. سيُعاد التحقق لاحقًا.") }
    }
}
