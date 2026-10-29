package com.alatta.nativeapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun FootballPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, color = PremiumPanel, shape = RoundedCornerShape(16.dp), border = BorderStroke(.7.dp, PremiumBorder)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}

@Composable
internal fun FootballScreen(query: String, modifier: Modifier = Modifier,
    isAdmin: Boolean = false, token: String = "", onManage: () -> Unit = {}, callInProgress: Boolean = false,
    onBeforePlayback: () -> Unit, onYoutube: (String) -> Unit) {
    val context = LocalContext.current
    val playbackScope = rememberCoroutineScope()
    var openingChannel by remember { mutableStateOf(false) }
    val preferences = remember { context.getSharedPreferences("alatta_football", Context.MODE_PRIVATE) }
    var favorites by remember { mutableStateOf(preferences.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    var recent by remember { mutableStateOf(preferences.getString("recent", "").orEmpty().split(',').filter(String::isNotBlank)) }
    var catalog by remember { mutableStateOf(FootballCatalog()) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<FootballChannel?>(null) }
    var nativeSession by remember { mutableStateOf<NativePlaybackSession?>(null) }
    var playerFailure by remember { mutableStateOf<YtvPlayer.Failure?>(null) }
    var watchEntryRequest by remember { mutableIntStateOf(0) }
    var broadcastExpanded by rememberSaveable { mutableStateOf(false) }
    val generation = CatalogAutoUpdater.generation
    LaunchedEffect(reload, generation) {
        loading = true; error = ""
        try {
            catalog = FootballRepository.load()
            if (selected != null && catalog.channels.none { it.id == selected?.id && it.enabled }) selected = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "تعذر تحديث القائمة. يمكنك إعادة المحاولة." }
        finally { loading = false }
    }
    LaunchedEffect(Unit) {
        val lifecycle = context.mediaActivity()?.lifecycle
        while (true) { delay(CatalogAutoUpdater.FOREGROUND_REFRESH_MS); if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) reload++ }
    }
    fun play(channel: FootballChannel, nativePlayback: Boolean = false) {
        if (openingChannel) return
        openingChannel = true
        playbackScope.launch {
        try {
            when (channel.format) {
                "youtube" -> onYoutube(requireNotNull(footballYoutubeId(channel.streamUrl)))
                "external" -> {
                    WatchRoomPip.enterAndWait(context)?.let { playerFailure = it.playerFailure(); return@launch }
                    val request = Intent(Intent.ACTION_VIEW, Uri.parse(channel.streamUrl))
                    context.startActivity(if (WatchRoomController.active) WatchRoomPip.playerIntent(request) else request)
                }
                "ytv" -> {
                    if (callInProgress) { error = "أنهِ المكالمة أولًا لتشغيل القناة."; return@launch }
                    if (isAdmin && nativePlayback) {
                        val sources = NativeChannelPlayback.load(token, channel.id)
                        onBeforePlayback(); nativeSession = NativePlaybackSession(channel, sources)
                    } else YtvPlayer.openWithWatchRoom(context, channel.streamUrl, onBeforePlayback)?.let { playerFailure = it; return@launch }
                }
                else -> { onBeforePlayback(); selected = channel }
            }
            recent = footballRecent(recent, channel.id)
            preferences.edit().putString("recent", recent.joinToString(",")).apply()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: AttaApiException) { error = failure.message ?: "تعذر جلب بيانات التشغيل." }
        catch (_: Exception) { error = "تعذر تشغيل القناة. حدّث القائمة وحاول مجددًا." }
        finally { openingChannel = false }
        }
    }
    FootballContent(catalog, query, favorites, recent, loading || openingChannel, error, isAdmin, callInProgress, modifier,
        onReload = { reload++ }, onManage = onManage, onPlay = { play(it) }, onPlayDirect = { play(it, true) },
        onFavorite = { id ->
            favorites = if (id in favorites) favorites - id else favorites + id
            preferences.edit().putStringSet("favorites", favorites.toSet()).apply()
        }, onWatch = { watchEntryRequest++ },
        watchEnabled = token.isNotBlank() && !callInProgress && !WatchRoomController.closing,
        onBroadcast = { broadcastExpanded = !broadcastExpanded }, broadcastExpanded = broadcastExpanded,
        livePanel = {
            WatchRoomPanel(token, catalog.channels, callInProgress, entryRequest = watchEntryRequest,
                onPlay = { play(it) }, onBeforeJoin = onBeforePlayback)
            SportsLivePanel(token = token, isAdmin = isAdmin, expanded = broadcastExpanded,
                onExpandedChange = { broadcastExpanded = it })
        })
    playerFailure?.let { YtvPlayerErrorDialog(it, onDismiss = { playerFailure = null }) }
    nativeSession?.let { session -> key(session.id) {
        NativeChannelPlaybackDialog(session, onDismiss = {
            if (nativeSession?.id == session.id) nativeSession = null
        })
    } }
    selected?.let { channel ->
        val posts = remember(channel) { listOf(JSONObject().put("id", channel.id).put("video", channel.streamUrl)) }
        AttaPostMediaViewer(posts, channel.id, "video", onDismiss = { selected = null },
            live = LivePlayback(channel.name, channel.provider, footballMime(channel.format)))
    }
}

/** The actual sports presentation is shared with the device UI test, without network/player substitutes. */
@Composable
internal fun FootballContent(catalog: FootballCatalog, query: String, favorites: Set<String>, recent: List<String>,
    loading: Boolean, error: String, isAdmin: Boolean, callInProgress: Boolean, modifier: Modifier = Modifier,
    onReload: () -> Unit, onManage: () -> Unit, onPlay: (FootballChannel) -> Unit, onFavorite: (String) -> Unit,
    livePanel: @Composable () -> Unit = {}, onWatch: () -> Unit = {}, watchEnabled: Boolean = true,
    onBroadcast: () -> Unit = {}, broadcastExpanded: Boolean = false,
    onPlayDirect: ((FootballChannel) -> Unit)? = null) {
    var tab by rememberSaveable { mutableStateOf("القنوات") }
    var showSchedule by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableIntStateOf(0) }
    val terms = remember(query) { normalizeHadithSearch(query).split(' ').filter(String::isNotBlank) }
    val directTab = tab == "البث المباشر"
    val playVisible = if (directTab) onPlayDirect ?: onPlay else onPlay
    val visible = catalog.channels.filter { it.enabled && (!directTab || it.format in setOf("hls", "dash") || (isAdmin && it.format == "ytv")) }
    val categories = visible.map { footballChannelCategory(it) }.distinct().sorted()
    LaunchedEffect(categories) { if (category.isNotBlank() && category !in categories) category = "" }
    val channels = visible.filter { c -> terms.all { normalizeHadithSearch(c.name + " " + footballChannelCategory(c)).contains(it) } }
        .filter { c -> when (tab) { "المفضلة" -> c.id in favorites; "آخر مشاهدة" -> c.id in recent; else -> category.isBlank() || footballChannelCategory(c) == category } }
        .sortedWith(if (tab == "آخر مشاهدة") compareBy { recent.indexOf(it.id) } else footballChannelOrder())
    Column(modifier.fillMaxSize().background(PremiumBackground).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("القنوات", "البث المباشر", "المفضلة", "آخر مشاهدة").forEach { name ->
                FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PremiumGold,
                        selectedLabelColor = Color.Black, labelColor = PremiumGoldLight))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OutlinedButton(onClick = onWatch, enabled = watchEnabled,
                modifier = Modifier.weight(1f).height(40.dp).semantics { contentDescription = "شاهد مع أصحابك" },
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp)) {
                SiteIcon("users", size = 16.dp, tint = PremiumGoldLight)
                Spacer(Modifier.width(4.dp))
                Text("شاهد مع أصحابك", color = PremiumGoldLight, fontSize = 11.sp, lineHeight = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onBroadcast, modifier = Modifier.size(40.dp)
                .background(PremiumPanel, RoundedCornerShape(12.dp)).semantics {
                    contentDescription = if (broadcastExpanded) "تصغير لوحة البث" else "فتح لوحة البث"
                }) { SiteIcon("video", tint = PremiumGoldLight, size = 18.dp) }
            IconButton(enabled = !loading, onClick = onReload, modifier = Modifier.size(40.dp)
                .background(PremiumPanel, RoundedCornerShape(12.dp)).semantics { contentDescription = "تحديث القنوات والمباريات" }) {
                SiteIcon("repeat", tint = PremiumGoldLight, size = 18.dp)
            }
            if (isAdmin) IconButton(onClick = onManage, modifier = Modifier.size(40.dp)
                .background(PremiumPanel, RoundedCornerShape(12.dp)).semantics { contentDescription = "إدارة القنوات والمباريات" }) {
                SiteIcon("menu", tint = PremiumGoldLight, size = 18.dp)
            }
        }
        livePanel()
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PremiumGold)
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFB4A9), fontSize = 12.sp)
        if (directTab) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !showSchedule, onClick = { showSchedule = false }, label = { Text("بث القنوات", fontSize = 12.sp) })
            FilterChip(selected = showSchedule, onClick = { showSchedule = true }, label = { Text("جدول اليوم", fontSize = 12.sp) })
        }
        if (directTab && showSchedule) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("اليوم", "غدًا", "كل المواعيد").forEachIndexed { index, label ->
                    FilterChip(selected = day == index, onClick = { day = index }, label = { Text(label, fontSize = 12.sp) })
                }
            }
            Text("المواعيد بتوقيت جهازك • ${ZoneId.systemDefault().id}", color = PremiumMuted, fontSize = 11.sp)
            val date = if (day == 2) null else LocalDate.now().plusDays(day.toLong())
            val matches = footballMatchesOn(catalog.matches, date, query)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!loading && matches.isEmpty()) item { FootballPanel { Text("لا توجد مباريات لهذا الموعد", color = PremiumMuted) } }
                items(matches, key = { it.id }) { match ->
                    val channel = visible.firstOrNull { it.id == match.channelId }
                    FootballPanel {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(match.league, Modifier.weight(1f), color = PremiumMuted, fontSize = 12.sp)
                            Text(footballStatus(match.status), color = PremiumGoldLight, fontSize = 12.sp)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(match.home, Modifier.weight(1f), color = Color.White, fontSize = 15.sp)
                            Text(if (match.homeScore != null && match.awayScore != null) "${match.homeScore} : ${match.awayScore}" else footballTime(match.startsAt),
                                color = PremiumGoldLight, modifier = Modifier.padding(horizontal = 8.dp), fontSize = 17.sp)
                            Text(match.away, Modifier.weight(1f), color = Color.White, fontSize = 15.sp)
                        }
                        if (day == 2) Text(footballDate(match.startsAt).toString(), color = PremiumMuted, fontSize = 12.sp)
                        if (channel != null) OutlinedButton(onClick = { playVisible(channel) }, enabled = channel.format != "ytv" || !callInProgress,
                            modifier = Modifier.fillMaxWidth()) { Text("مشاهدة • ${channel.name}", color = PremiumGoldLight) }
                    }
                }
            }
        } else {
            if ((tab == "القنوات" || directTab) && categories.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("") + categories).forEach { value -> FilterChip(selected = category == value, onClick = { category = value },
                    label = { Text(value.ifBlank { "كل القنوات" }, fontSize = 11.sp) }) }
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(145.dp), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!loading && channels.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { FootballPanel {
                    Text(when (tab) { "المفضلة" -> "لم تحفظ قنوات في المفضلة بعد"; "آخر مشاهدة" -> "ستظهر القنوات التي تشغّلها هنا"; else -> "لا توجد قنوات مطابقة" }, color = PremiumMuted)
                } }
                items(channels, key = { it.id }) { channel ->
                    FootballChannelCard(channel, channel.id in favorites, channel.format != "ytv" || !callInProgress,
                        onPlay = { playVisible(channel) }, onFavorite = { onFavorite(channel.id) })
                }
            }
        }
    }
}

@Composable
internal fun FootballChannelCard(channel: FootballChannel, favorite: Boolean, enabled: Boolean,
    onPlay: () -> Unit, onFavorite: () -> Unit) {
    val bein = footballIsBein(channel.name)
    Surface(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onPlay).semantics {
        contentDescription = "تشغيل ${channel.name}"
    }, color = PremiumPanel, shape = RoundedCornerShape(18.dp), border = BorderStroke(.7.dp, PremiumBorder)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(112.dp).background(
                Brush.linearGradient(if (bein) listOf(Color(0xFF6D3495), Color(0xFF351154)) else listOf(Color(0xFF302A1E), Color(0xFF151511))), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                if (bein) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("beIN", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(channel.name.replace(Regex("(?i)^beIN\\s*"), "").replace(Regex("(?i)SPORTS(?=\\d)"), "SPORTS "),
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else SiteIcon("play", size = 34.dp, tint = PremiumGoldLight)
                IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd).size(32.dp).semantics {
                    contentDescription = if (favorite) "إزالة ${channel.name} من المفضلة" else "إضافة ${channel.name} للمفضلة"
                }) { SiteIcon("heart", size = 18.dp, tint = if (favorite) PremiumGold else Color.White) }
            }
            if (!bein) Text(channel.name, color = PremiumGoldLight, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SiteIcon("play", size = 16.dp, tint = PremiumGoldLight)
                    Text("تشغيل", color = PremiumGoldLight, fontSize = 12.sp)
                }
                footballQuality(channel.category).takeIf { it.isNotBlank() }?.let { Text(it, color = PremiumMuted, fontSize = 11.sp) }
            }
        }
    }
}
