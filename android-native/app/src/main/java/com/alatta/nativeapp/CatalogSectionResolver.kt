package com.alatta.nativeapp

import java.util.ArrayDeque
import java.util.Locale

internal data class CatalogSectionDiscovery(
    val section: YacineCatalogProbe.Entry? = null,
    val explanation: String = ""
)

/**
 * Find the 1080p beIN group without relying on a hard-coded category ID.
 * The catalog provider may move the group under a parent, so inspect a small
 * bounded number of relevant child groups. Never guess between two matches.
 */
internal object CatalogSectionResolver {
    private fun key(value: String): String =
        value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun relevantParent(label: String): Boolean {
        val text = key(label)
        return text.contains("bein") || text.contains("sport") ||
            text.contains("رياض") || text.contains("1080")
    }

    internal fun categoryFailure(result: YacineCatalogProbe.Result): String = when (result.state) {
        YacineCatalogProbe.State.HTTP_ERROR ->
            "خادم قائمة ياسين أعاد HTTP ${result.code ?: 0}؛ لم يتم تغيير أي قناة."
        YacineCatalogProbe.State.NETWORK_ERROR ->
            "فشل اتصال الهاتف بقائمة ياسين: ${result.message}"
        YacineCatalogProbe.State.UNREADABLE ->
            "تعذر قراءة أقسام ياسين: ${result.message} راجع إعداد قراءة الرد على هذا الهاتف."
        YacineCatalogProbe.State.EMPTY -> "قائمة أقسام ياسين فارغة؛ الأرقام المحفوظة سليمة."
        YacineCatalogProbe.State.TOO_LARGE -> "قائمة الأقسام أكبر من الحد الآمن؛ لم تُغيّر القنوات."
        YacineCatalogProbe.State.INVALID -> "رد قائمة الأقسام غير صالح؛ لم تُغيّر القنوات."
        YacineCatalogProbe.State.LIST -> "لم يُعثر على قسم beIN SPORTS 1080P فريد في الأقسام المتاحة."
    }

    suspend fun discover(
        root: YacineCatalogProbe.Result,
        children: suspend (String) -> YacineCatalogProbe.Result
    ): CatalogSectionDiscovery {
        if (root.state != YacineCatalogProbe.State.LIST)
            return CatalogSectionDiscovery(explanation = categoryFailure(root))

        val rootMatches = root.entries.filter { entry ->
            val title = key(entry.name)
            title.contains("beinsports") && title.contains("1080") &&
                YtvPlayer.channelId(entry.id) == entry.id
        }
        if (rootMatches.size == 1) return CatalogSectionDiscovery(rootMatches.single())
        if (rootMatches.size > 1) return CatalogSectionDiscovery(
            explanation = "وجدت أكثر من قسم beIN SPORTS 1080P؛ أُوقف التحديث تجنبًا لاختيار جودة خاطئة."
        )

        data class Parent(val id: String, val path: String, val depth: Int)
        val queue = ArrayDeque<Parent>()
        root.entries.filter { relevantParent(it.name) && YtvPlayer.channelId(it.id) == it.id }
            .take(10).forEach { queue.add(Parent(it.id, it.name, 1)) }

        val visited = HashSet<String>()
        val matches = ArrayList<YacineCatalogProbe.Entry>()
        var budget = 12
        var error = ""
        while (queue.isNotEmpty() && budget > 0) {
            val parent = queue.removeFirst()
            if (!visited.add(parent.id)) continue
            budget--
            val response = children(parent.id)
            if (response.state != YacineCatalogProbe.State.LIST) {
                if (error.isEmpty()) error = categoryFailure(response)
                continue
            }
            for (entry in response.entries) {
                val leaf = key(entry.name)
                val path = key(parent.path + " " + entry.name)
                if (YtvPlayer.channelId(entry.id) != entry.id) continue
                if (path.contains("beinsports") && leaf.contains("1080") &&
                    !key(parent.path).contains("720") && !key(parent.path).contains("360")) {
                    // Child groups sometimes use only the quality as their title.
                    val label = if (leaf.contains("beinsports")) entry.name else "beIN SPORTS (1080P)"
                    matches.add(YacineCatalogProbe.Entry(entry.id, label, entry.childCount))
                } else if (parent.depth < 3 && relevantParent(entry.name) &&
                    !leaf.contains("720") && !leaf.contains("360")) {
                    queue.add(Parent(entry.id, parent.path + " / " + entry.name, parent.depth + 1))
                }
            }
        }
        return when (matches.map { it.id }.distinct().size) {
            1 -> CatalogSectionDiscovery(matches.first())
            0 -> CatalogSectionDiscovery(explanation =
                if (error.isNotEmpty()) error else
                    "لم يظهر قسم beIN SPORTS (1080P) في القائمة الرئيسية أو فروع الرياضة؛ احتُفظ بالقنوات.")
            else -> CatalogSectionDiscovery(
                explanation = "ظهرت أقسام 1080P متعددة بأرقام مختلفة؛ لن نختار رقمًا تخمينيًا."
            )
        }
    }
}
