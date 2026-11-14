package com.alatta.nativeapp

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** روابط إعداد لا تحتوي أسرارًا فقط؛ وتبقى إعدادات استجابة المصدر في CatalogFormatSettings. */
internal object CatalogLinkStore {
    private fun prefs(context: Context) = context.getSharedPreferences("alatta_catalog_links_v1", Context.MODE_PRIVATE)
    fun automatic(context: Context, owner: String) = owner.isNotBlank() && prefs(context).getBoolean("automatic:$owner", true)
    fun automatic(context: Context, owner: String, enabled: Boolean) = prefs(context).edit().putBoolean("automatic:$owner", enabled).commit()
    fun read(context: Context, owner: String): List<CatalogChannelLink> = runCatching {
        if (owner.isBlank()) return emptyList()
        val rows = JSONArray(prefs(context).getString(owner, "[]"))
        require(rows.length() <= 40)
        val links = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            CatalogChannelLink(row.getString("channel"), row.getString("category"), row.getString("name"), row.getString("lastId"))
        }
        require(links.all { it.channelId.isNotBlank() && it.channelId.length <= 80 &&
            it.sourceName.isNotBlank() && it.sourceName.length <= 120 &&
            YtvPlayer.channelId(it.categoryId) == it.categoryId && YtvPlayer.channelId(it.lastPublishedId) == it.lastPublishedId })
        require(links.map { it.channelId }.distinct().size == links.size)
        links
    }.getOrDefault(emptyList())
    fun write(context: Context, owner: String, links: List<CatalogChannelLink>): Boolean {
        if (owner.isBlank() || links.size > 40) return false
        val rows = JSONArray()
        links.forEach { rows.put(JSONObject().put("channel", it.channelId).put("category", it.categoryId)
            .put("name", it.sourceName).put("lastId", it.lastPublishedId)) }
        return prefs(context).edit().putString(owner, rows.toString()).commit()
    }
}

internal object CatalogAutoUpdater {
    const val FOREGROUND_REFRESH_MS = 30_000L
    // كل المستخدمين يسحبون الكتالوج المنشور من Cloudflare؛ لا يطلبون بيانات المصدر ولا ينشرونها.
    const val SHARED_CATALOG_REFRESH_MS = 10_000L
    private val mutex = Mutex()
    private val attempts = mutableMapOf<String, Long>()
    var generation by mutableIntStateOf(0)
        private set
    private var messages by mutableStateOf(emptyMap<String, String>())
    fun configurationChanged() { generation++ }
    fun message(owner: String) = messages[owner].orEmpty()
    private fun service(context: Context, token: String, owner: String,
        selectedSource: () -> String? = { null }) = CatalogChannelSync(
        load = { FootballRepository.load(token) },
        fetch = { category ->
            val mask = CatalogFormatSettings.read(context)?.toByteArray(Charsets.US_ASCII)
            YacineCatalogProbe.fetch(YacineCatalogProbe.Kind.CHANNELS, category, mask,
                sourceBase = selectedSource())
        },
        save = { catalog, channel -> FootballRepository.change(token, catalog.revision, "saveChannel",
            JSONObject().put("channel", FootballRepository.channelJson(channel))) },
        rememberLinks = { links -> CatalogLinkStore.write(context, owner, links).also { if (it) generation++ } },
        saveBatch = { catalog, channels ->
            val rows = JSONArray(); channels.forEach { rows.put(FootballRepository.channelJson(it)) }
            FootballRepository.change(token, catalog.revision, "saveChannels", JSONObject().put("channels", rows))
        }
    )
    suspend fun refresh(context: Context, token: String, owner: String, force: Boolean = false): CatalogSyncResult? =
        withContext(Dispatchers.IO) { mutex.withLock {
            // Restore the original device-owned catalog fetch, as in v203/v206.
            // Only the owner explicitly presses refresh; no background phone writer.
            if (!force) return@withLock null
            if (owner.isBlank() || token.isBlank()) return@withLock null
            val links = CatalogLinkStore.read(context, owner)
            if (!force && (!CatalogLinkStore.automatic(context, owner) || !CatalogFormatSettings.configured(context))) return@withLock null
            val now = SystemClock.elapsedRealtime()
            if (!force && attempts[owner]?.let { now - it < FOREGROUND_REFRESH_MS } == true) return@withLock null
            attempts[owner] = now
            var selectedSource: String? = null
            val sync = service(context, token, owner) { selectedSource }
            var discovery: CatalogSectionDiscovery? = null
            val attempt = sync.refreshSection(links, ownerReconcile = force) {
                val mask = CatalogFormatSettings.read(context)?.toByteArray(Charsets.US_ASCII)
                val root = YacineCatalogProbe.fetch(YacineCatalogProbe.Kind.CATEGORIES, responseMask = mask)
                selectedSource = root.sourceBase.takeIf { root.state == YacineCatalogProbe.State.LIST }
                CatalogSectionResolver.discover(root) { categoryId ->
                    YacineCatalogProbe.fetch(YacineCatalogProbe.Kind.CATEGORIES, categoryId, mask,
                        sourceBase = selectedSource)
                }.also { discovery = it }.section
            }
            // Only fall back to already linked channels: never import an unverified
            // category or replace the 20 saved channels with an incomplete response.
            val outcome = if (discovery?.section == null &&
                attempt.message.startsWith("تعذر تحديد قسم القنوات")) {
                val diagnostic = discovery?.explanation.orEmpty()
                // If even the root categories failed on every candidate host, an
                // old category ID must NOT be tried against another unknown host.
                if (links.isEmpty() || selectedSource == null)
                    attempt.copy(message = diagnostic.ifBlank { attempt.message })
                else {
                    val linked = sync.refresh(links)
                    val status = if (linked.checked > 0)
                        "تمت مراجعة ${linked.checked} قناة مرتبطة وتحديث ${linked.changed} دون إضافة قنوات جديدة."
                    else "لم يمكن فحص القنوات المرتبطة؛ الأرقام المحفوظة كما هي."
                    linked.copy(message = "${diagnostic} ${status} ${linked.message}".trim())
                }
            } else attempt
            val sourceMessage = selectedSource?.let { " • مصدر القنوات: $it" }.orEmpty()
            val reported = outcome.copy(message = outcome.message + sourceMessage)
            messages = messages + (owner to reported.message)
            reported
        } }
    suspend fun bind(context: Context, token: String, owner: String, channelId: String,
        categoryId: String, entry: YacineCatalogProbe.Entry,
        sourceBase: String? = null): CatalogSyncResult = withContext(Dispatchers.IO) { mutex.withLock {
            service(context, token, owner) { sourceBase }
                .bind(CatalogLinkStore.read(context, owner), channelId, categoryId, entry)
                .also { messages = messages + (owner to it.message); attempts.remove(owner) }
        } }
    suspend fun unlink(context: Context, owner: String, channelId: String): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        CatalogLinkStore.write(context, owner, CatalogLinkStore.read(context, owner).filterNot { it.channelId == channelId })
            .also { if (it) { generation++; messages = messages + (owner to "تم إلغاء الربط؛ بقي رقم القناة كما هو.") } }
    } }
    suspend fun automatic(context: Context, owner: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        CatalogLinkStore.automatic(context, owner, enabled).also {
            if (it) { generation++; attempts.remove(owner); messages = messages + (owner to if (enabled) "تم تفعيل تحديث قائمة القنوات تلقائيًا" else "تم إيقاف التحديث التلقائي") }
        }
    } }
}

/**
 * Every signed-in app instance refreshes its own read-only channel-ID cache.
 * Android JobScheduler also retries in the background, subject to OS limits.
 * The owner-only shared catalog update remains a separate explicit operation.
 */
@Composable
internal fun CatalogAutoRefreshEffect(token: String, owner: String, enabled: Boolean) {
    val application = LocalContext.current.applicationContext
    LaunchedEffect(token, owner, enabled) {
        val active = enabled && token.isNotBlank() && owner.isNotBlank()
        withContext(Dispatchers.IO) {
            CatalogRefreshJob.schedule(application, owner, active)
        }
        if (!active) return@LaunchedEffect
        while (isActive) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(70_000L) {
                    YtvPhoneCatalog.refresh(application, token, owner)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Last known-good local IDs remain available. */ }
            delay(YtvPhoneCatalog.UPDATE_INTERVAL_MS)
        }
    }
}
