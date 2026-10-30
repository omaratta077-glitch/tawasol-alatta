package com.alatta.nativeapp

import kotlinx.coroutines.CancellationException
import java.util.Locale

/** ربط محلي للمالك؛ ويميّز اسم الفئة والمصدر الكامل بين إصدارات الجودة المختلفة. */
internal data class CatalogChannelLink(val channelId: String, val categoryId: String,
    val sourceName: String, val lastPublishedId: String)

internal data class CatalogSyncResult(val catalog: FootballCatalog? = null, val changed: Int = 0,
    val checked: Int = 0, val message: String, val linked: Boolean = false)

internal fun catalogDefaultSection(result: YacineCatalogProbe.Result): YacineCatalogProbe.Entry? {
    if (result.state != YacineCatalogProbe.State.LIST) return null
    return result.entries.filter {
        val name = it.name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        name.contains("beinsports") && name.contains("1080") && YtvPlayer.channelId(it.id) == it.id
    }.singleOrNull()
}

/** حدّث سجلات YTV الموجودة فقط عبر واجهة المالك الحالية التي تتحقق من رقم المراجعة. */
internal class CatalogChannelSync(
    private val load: suspend () -> FootballCatalog,
    private val fetch: suspend (String) -> YacineCatalogProbe.Result,
    private val save: suspend (FootballCatalog, FootballChannel) -> FootballCatalog,
    private val rememberLinks: (List<CatalogChannelLink>) -> Boolean,
    private val saveBatch: (suspend (FootballCatalog, List<FootballChannel>) -> FootballCatalog)? = null
) {
    private fun key(value: String) = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** استورد القسم المحدد كاملًا ضمن مراجعة واحدة، ثم تتبّع أسماءه الثابتة. */
    suspend fun refreshSection(links: List<CatalogChannelLink>,
        chooseSection: suspend () -> YacineCatalogProbe.Entry?): CatalogSyncResult {
        var catalog: FootballCatalog? = null
        try {
            val current = load().also { catalog = it }
            val section = chooseSection()
                ?: return CatalogSyncResult(current, message = "تعذر تحديد قسم القنوات؛ احتُفظ بالقائمة الحالية.")
            if (YtvPlayer.channelId(section.id) != section.id || section.name.length !in 2..60)
                return CatalogSyncResult(current, message = "بيانات القسم غير صحيحة؛ احتُفظ بالقائمة الحالية.")
            val response = fetch(section.id)
            val rows = response.entries
            if (response.state != YacineCatalogProbe.State.LIST || rows.isEmpty() || rows.size > 40 ||
                rows.any { it.name.length !in 2..80 || YtvPlayer.channelId(it.id) != it.id } ||
                rows.map { key(it.name) }.distinct().size != rows.size || rows.map { it.id }.distinct().size != rows.size)
                return CatalogSyncResult(current, message = "لم تصل قائمة كاملة وفريدة؛ احتُفظ بالقنوات الحالية.")
            val sectionKey = key(section.name)
            val quality = Regex("(?:1080|720|360|244)").find(sectionKey)?.value
            fun sameSection(channel: FootballChannel): Boolean {
                val category = key(channel.category)
                return category == sectionKey || category in setOf("ytvpro", "ytv", "القنوات") ||
                    (quality != null && category.contains("bein") && category.contains(quality))
            }
            val used = HashSet<String>()
            val paused = HashSet<String>()
            val planned = rows.map { row ->
                val linked = links.filter { it.categoryId == section.id && key(it.sourceName) == key(row.name) }
                val candidates = current.channels.filter { it.format == "ytv" &&
                    ((key(it.name) == key(row.name) && sameSection(it)) || linked.any { link -> link.channelId == it.id }) }
                require(candidates.size <= 1) { "Ambiguous saved channel" }
                val existing = candidates.singleOrNull()
                if (existing == null) FootballChannel("", row.name, section.name, "beIN SPORTS", YtvPlayer.APP_PAGE,
                    row.id, "ytv", true)
                else {
                    require(used.add(existing.id))
                    if (!existing.enabled || linked.any { it.channelId == existing.id && it.lastPublishedId != existing.streamUrl }) {
                        paused.add(existing.id); existing
                    } else existing.copy(streamUrl = row.id)
                }
            }
            require(current.channels.size + planned.count { it.id.isBlank() } <= 40)
            val changes = planned.filter { next -> next.id.isBlank() || current.channels.first { it.id == next.id } != next }
            if (changes.isNotEmpty()) catalog = requireNotNull(saveBatch)(current, changes)
            val saved = requireNotNull(catalog)
            val refreshed = rows.zip(planned).map { (row, target) ->
                val channel = if (target.id.isNotBlank()) saved.channels.single { it.id == target.id }
                    else saved.channels.single { it.format == "ytv" && it.streamUrl == row.id }
                if (channel.id in paused) links.firstOrNull { it.channelId == channel.id }
                    ?: CatalogChannelLink(channel.id, section.id, row.name, channel.streamUrl)
                else CatalogChannelLink(channel.id, section.id, row.name, channel.streamUrl)
            }
            val mergedLinks = links.filter { old -> saved.channels.any { it.id == old.channelId } &&
                refreshed.none { it.channelId == old.channelId } } + refreshed
            if (mergedLinks != links && !rememberLinks(mergedLinks)) return CatalogSyncResult(saved,
                changes.size, rows.size, "حُفظت القنوات، لكن تعذر حفظ متابعة التحديث على الهاتف.")
            return CatalogSyncResult(saved, changes.size, rows.size,
                "القائمة محدّثة: ${rows.size} قناة • تم إضافة أو تحديث ${changes.size}.", linked = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return CatalogSyncResult(catalog, message = "تعذر مزامنة القنوات كاملة؛ احتُفظ بالقائمة الحالية دون إضافة جزء منها.") }
    }

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
            // أعد قراءة الفئة المحددة؛ ولا تُنشئ ربطًا من معاينة قديمة أو ملتبسة.
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
            // تحقّق أيضًا من صلاحية دور المالك للحساب الحالي قبل طلب المصدر.
            catalog = load()
            val responses = mutableMapOf<String, YacineCatalogProbe.Result>()
            for (link in links) {
                val current = requireNotNull(catalog)
                val channel = current.channels.singleOrNull { it.id == link.channelId }
                // يوقف تعديل المعرّف يدويًا هذا الربط؛ فلا تستبدله بصمت.
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
                // احفظ التقدم بعد كل كتابة ناجحة؛ حتى لا يفقد العطل اللاحق ما سبق حفظه.
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
