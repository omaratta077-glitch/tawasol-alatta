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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    isAdmin: Boolean = false, onManage: () -> Unit = {}, onBeforePlayback: () -> Unit,
    onYoutube: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("alatta_football", Context.MODE_PRIVATE) }
    var favorites by remember { mutableStateOf(preferences.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    var recent by remember { mutableStateOf(preferences.getString("recent", "").orEmpty().split(',').filter(String::isNotBlank)) }
    var catalog by remember { mutableStateOf(FootballCatalog()) }
    var tab by rememberSaveable { mutableStateOf("المباريات") }
    var category by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<FootballChannel?>(null) }

    LaunchedEffect(reload) {
        loading = true; error = ""
        try {
            catalog = FootballRepository.load(); loaded = true
            if (selected != null && catalog.channels.none { it.id == selected?.id && it.enabled }) selected = null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // Keep the official publisher shortcut accessible if schedules API is offline.
            catalog = FootballRepository.withOfficialChannels(FootballCatalog())
            loaded = true
            error = "تعذر تحديث جدول المباريات. قناة أون سبورت الرسمية متاحة من تبويب القنوات."
        }
        finally { loading = false }
    }
    LaunchedEffect(Unit) {
        val lifecycle = context.mediaActivity()?.lifecycle
        while (true) { delay(60_000); if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) reload++ }
    }
    fun play(channel: FootballChannel) {
        try {
            when (channel.format) {
                "youtube" -> onYoutube(requireNotNull(footballYoutubeId(channel.streamUrl)))
                "external" -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(channel.streamUrl)))
                else -> { onBeforePlayback(); selected = channel }
            }
            recent = footballRecent(recent, channel.id)
            preferences.edit().putString("recent", recent.joinToString(",")).apply()
        } catch (_: Exception) { error = "تعذر فتح هذا المصدر. جرّب قناة أخرى أو حدّث القائمة." }
    }
    fun favorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        preferences.edit().putStringSet("favorites", favorites.toSet()).apply()
    }
    val terms = remember(query) { normalizeHadithSearch(query).split(' ').filter(String::isNotBlank) }
    val categories = catalog.channels.map { it.category }.distinct().sorted()
    LaunchedEffect(categories, loaded) { if (loaded && category.isNotBlank() && category !in categories) category = "" }
    val channels = catalog.channels.filter { c -> terms.all { normalizeHadithSearch("${c.name} ${c.category} ${c.provider}").contains(it) } }
        .filter { c -> when (tab) { "المفضلة" -> c.id in favorites; "آخر مشاهدة" -> c.id in recent; else -> category.isBlank() || c.category == category } }
        .sortedWith(if (tab == "آخر مشاهدة") compareBy<FootballChannel> { recent.indexOf(it.id) } else compareBy<FootballChannel> { it.category }.thenBy { it.name })

    Column(modifier.fillMaxSize().background(PremiumBackground).padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("المباريات", "القنوات", "المفضلة", "آخر مشاهدة").forEach { name ->
                    FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name, fontSize = 12.sp) })
                }
            }
            IconButton(enabled = !loading, onClick = { reload++ }) { SiteIcon("refresh-cw", tint = PremiumGoldLight, size = 20.dp) }
        }
        if (isAdmin) TextButton(onClick = onManage) { Text("إدارة القنوات وجدول المباريات", color = PremiumGoldLight, fontSize = 12.sp) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PremiumGold)
        if (error.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(error, color = Color(0xFFFFB4A9), fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(enabled = !loading, onClick = { reload++ }) { Text("إعادة المحاولة", color = PremiumGoldLight) }
        }
        if (tab == "المباريات") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("اليوم", "غدًا", "كل المواعيد").forEachIndexed { index, label -> FilterChip(selected = day == index,
                    onClick = { day = index }, label = { Text(label, fontSize = 12.sp) }) }
            }
            Text("المواعيد بتوقيت جهازك • ${ZoneId.systemDefault().id}", color = PremiumMuted, fontSize = 11.sp)
            val date = if (day == 2) null else LocalDate.now().plusDays(day.toLong())
            val matches = footballMatchesOn(catalog.matches, date, query)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loaded && matches.isEmpty()) item { FootballPanel {
                    Text(if (query.isBlank()) "لا توجد مباريات مضافة لهذا الموعد" else "لا توجد مباريات مطابقة للبحث", color = PremiumGoldLight)
                    Text(if (catalog.channels.isEmpty()) "يُحدّث مدير التطبيق مواعيد المباريات ونتائجها. البث غير مفعّل حاليًا."
                        else "يُحدّث مدير التطبيق الجدول والقنوات الناقلة. يمكن مشاهدة القنوات المتاحة من تبويب القنوات.", color = PremiumMuted, fontSize = 13.sp)
                } }
                items(matches, key = { it.id }) { match ->
                    val channel = catalog.channels.firstOrNull { it.id == match.channelId }
                    FootballPanel {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(match.league, Modifier.weight(1f), color = PremiumMuted, fontSize = 12.sp)
                            Text(footballStatus(match.status), color = if (match.status == "live") Color(0xFFFF7777) else PremiumGoldLight, fontSize = 12.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(match.home, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(if (match.homeScore != null && match.awayScore != null) "${match.homeScore} : ${match.awayScore}" else footballTime(match.startsAt), color = PremiumGoldLight,
                                modifier = Modifier.padding(horizontal = 10.dp), fontSize = 17.sp)
                            Text(match.away, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        if (day == 2) Text(footballDate(match.startsAt).toString(), color = PremiumMuted, fontSize = 12.sp)
                        Text(channel?.let { "القناة الناقلة: ${it.name} • ${it.provider}" } ?: "لم تُضف قناة ناقلة لهذه المباراة", color = PremiumMuted, fontSize = 12.sp)
                        if (channel != null) OutlinedButton(onClick = { play(channel) }, modifier = Modifier.fillMaxWidth()) { Text("مشاهدة القناة", color = PremiumGoldLight) }
                    }
                }
            }
        } else {
            if (tab == "القنوات") Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("") + categories).forEach { value -> FilterChip(selected = category == value, onClick = { category = value }, label = { Text(value.ifBlank { "كل الأقسام" }, fontSize = 12.sp) }) }
            }
            if (loaded && channels.isEmpty()) FootballPanel(Modifier.padding(vertical = 12.dp)) {
                Text(when (tab) { "المفضلة" -> "لم تحفظ قناة في المفضلة بعد"; "آخر مشاهدة" -> "ستظهر هنا القنوات التي تفتحها"; else -> "لا توجد قنوات متاحة في هذا القسم" }, color = PremiumGoldLight)
                Text("تظهر القنوات بعد إضافتها من الإدارة. ابحث من الشريط العلوي أو اختر قسمًا آخر.", color = PremiumMuted, fontSize = 12.sp)
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(145.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(channels, key = { it.id }) { channel ->
                    FootballPanel(Modifier.clickable { play(channel) }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SiteIcon("play", size = 28.dp, tint = PremiumGoldLight, modifier = Modifier.weight(1f))
                            IconButton(onClick = { favorite(channel.id) }, modifier = Modifier.size(36.dp)) {
                                SiteIcon("heart", size = 20.dp, tint = if (channel.id in favorites) PremiumGold else PremiumMuted)
                            }
                        }
                        Text(channel.name, color = PremiumGoldLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(channel.category, color = PremiumMuted, fontSize = 12.sp)
                        Text(channel.provider, color = Color.White, fontSize = 12.sp)
                        Text(when {
                            channel.id.startsWith("official-bein-") -> "فتح beIN CONNECT • اختر القناة باشتراكك"
                            channel.id == FootballRepository.onSportOfficial.id -> "فتح صفحة البث الرسمية على يوتيوب إن توفرت"
                            channel.id.startsWith("official-") -> "مشاهدة القناة عبر موقع الناشر الرسمي"
                            channel.format == "external" -> "المشاهدة لدى الناشر"
                            else -> "مشاهدة القناة"
                        }, color = PremiumGoldLight, fontSize = 12.sp)
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(channel.sourceUrl))) } }, contentPadding = PaddingValues(0.dp)) {
                            Text("مصدر البث", color = PremiumMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
    selected?.let { channel ->
        val posts = remember(channel) { listOf(JSONObject().put("id", channel.id).put("video", channel.streamUrl)) }
        AttaPostMediaViewer(posts, channel.id, "video", onDismiss = { selected = null },
            live = LivePlayback(channel.name, channel.provider, footballMime(channel.format)))
    }
}
