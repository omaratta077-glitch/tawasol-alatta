package com.alatta.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A standalone AL-ATTA TV destination. It does not replace the sports section.
 * Inline playback uses the legacy ExoPlayer 2 engine. The external YTV Activity
 * remains a manual fallback when the provider has no authorized direct source.
 */
@Composable
internal fun AttaTvScreen(
    token: String,
    modifier: Modifier = Modifier,
    requestedChannelId: String? = null,
    onRequestedChannelConsumed: () -> Unit = {},
    onBeforePlayback: () -> Unit = {},
    onExit: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    var catalog by remember { mutableStateOf(FootballCatalog()) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<FootballChannel?>(null) }
    var session by remember { mutableStateOf<NativePlaybackSession?>(null) }
    var error by remember { mutableStateOf("") }
    var legacyFailure by remember { mutableStateOf<YtvPlayer.Failure?>(null) }
    var goldHomeVisible by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(reload) {
        loading = true
        try {
            catalog = FootballRepository.load()
            error = ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "تعذر تحميل القنوات. اضغط تحديث للمحاولة مرة أخرى."
        } finally {
            loading = false
        }
    }

    // Try a legitimate direct HLS/DASH stream inside the tab first. The gold
    // engine accepts a numeric YTV channel, not a URL. When direct-source
    // negotiation fails, gracefully open the installed gold engine. Android
    // cannot render another application's Activity inside this Compose tab.
    fun play(channel: FootballChannel, tryInline: Boolean = true) {
        if (busy) return
        busy = true
        error = ""
        legacyFailure = null
        selected = channel
        session = null
        scope.launch {
            suspend fun openGoldEngine() {
                onBeforePlayback()
                val failure = YtvPlayer.openWorkingEngine(context, channel.streamUrl)
                if (failure != null) {
                    legacyFailure = failure
                    error = failure.explanation
                }
            }
            try {
                if (channel.format == "ytv" && (!tryInline || token.isBlank())) {
                    // No account required to use the existing installed player.
                    openGoldEngine()
                } else {
                    val sources = if (channel.format == "ytv") {
                        NativeChannelPlayback.load(token, channel.id)
                    } else {
                        NativeChannelPlayback.fromPublishedChannel(channel)
                    }
                    onBeforePlayback()
                    session = NativePlaybackSession(channel, sources, engineName = "ExoPlayer2")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AttaApiException) {
                if (channel.format == "ytv") {
                    // Source is unavailable, not necessarily the video decoder.
                    openGoldEngine()
                } else {
                    error = "لم يصل رابط بث مباشر صالح. حدّث القائمة وحاول مجددًا."
                }
            } catch (_: Exception) {
                error = "تعذر بدء القناة. جرّب إعادة فتحها أو تحديث القائمة."
            } finally {
                busy = false
            }
        }
    }

    // If the provider gives a URL that fails after decoding/timeout, do not
    // strand the user on a black player. Use the installed working gold app once.
    val failedSession = session?.takeIf { it.terminal && it.channel.format == "ytv" }
    LaunchedEffect(failedSession?.id) {
        val current = failedSession ?: return@LaunchedEffect
        if (session?.id != current.id) return@LaunchedEffect
        session = null
        selected = current.channel
        error = ""
        val failure = YtvPlayer.openWorkingEngine(context, current.channel.streamUrl)
        if (failure != null) {
            legacyFailure = failure
            error = failure.explanation
        }
    }

    LaunchedEffect(requestedChannelId, catalog, loading) {
        val id = requestedChannelId ?: return@LaunchedEffect
        if (loading) return@LaunchedEffect
        val channel = catalog.channels.firstOrNull {
            it.enabled && it.id == id && it.format in setOf("ytv", "hls", "dash")
        }
        onRequestedChannelConsumed()
        goldHomeVisible = false
        if (channel == null) {
            error = "القناة المختارة غير موجودة. حدّث قائمة القنوات."
        } else {
            play(channel)
            scroll.animateScrollToItem(0)
        }
    }

    val channels = remember(catalog, search) {
        val keyword = search.trim()
        catalog.channels.asSequence()
            .filter { it.enabled && it.format in setOf("ytv", "hls", "dash") }
            .filter { channel ->
                keyword.isBlank() || channel.name.contains(keyword, ignoreCase = true) ||
                    channel.category.contains(keyword, ignoreCase = true) ||
                    (channel.format == "ytv" && channel.streamUrl.contains(keyword))
            }
            .sortedWith(footballChannelOrder())
            .toList()
    }
    if (goldHomeVisible) {
        AttaTvGoldHome(
            onOnlineVideos = { goldHomeVisible = false },
            onExit = onExit,
            modifier = modifier
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().background(PremiumBackground),
        state = scroll,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "tv-player") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        goldHomeVisible = true
                        session = null
                        selected = null
                        error = ""
                    }) { Text("الرئيسية", color = PremiumGoldLight) }
                    SiteIcon("monitor", tint = PremiumGoldLight, size = 29.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("AL-ATTA TV", color = PremiumGoldLight, fontWeight = FontWeight.Bold,
                        fontSize = 22.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { reload++ }, enabled = !loading) {
                        Text("تحديث", color = PremiumGoldLight)
                    }
                }
                Text("قنوات AL-ATTA TV • اختر القناة لمشاهدة البث.",
                    color = PremiumMuted, fontSize = 12.sp)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Black,
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, PremiumBorder)
                ) {
                    Column {
                        if (session == null) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (selected == null) "اختَر قناة لبدء البث"
                                    else if (selected?.format == "ytv")
                                        "القناة: " + selected?.name + "\nيُفتح المحرك الذهبي المثبّت عند تعذّر المصدر المباشر."
                                    else "جارٍ تجهيز المشغّل...",
                                    color = PremiumGoldLight, fontSize = 13.sp
                                )
                            }
                        } else {
                            LegacyInlineStreamPlayer(session!!)
                        }
                        selected?.let { channel ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(channel.name, color = PremiumGoldLight,
                                    modifier = Modifier.weight(1f), fontSize = 13.sp)
                                if (session != null) TextButton(onClick = { session = null }) {
                                    Text("إيقاف", color = PremiumGoldLight)
                                }
                            }
                            if (session?.terminal == true) {
                                Text(session?.lastError ?: "تعذّر بدء البث.",
                                    color = Color(0xFFFFB4A9), fontSize = 12.sp,
                                    modifier = Modifier.padding(8.dp))
                            }
                            if (channel.format == "ytv") {
                                OutlinedButton(
                                    onClick = { play(channel, tryInline = false) },
                                    enabled = !busy, modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 8.dp)
                                ) { Text("فتح المشغّل الذهبي الأصلي", color = PremiumGoldLight) }
                                TextButton(
                                    onClick = { play(channel, tryInline = true) },
                                    enabled = !busy && token.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("تشغيل المصدر المباشر داخل التبويب", color = PremiumGoldLight) }
                            }
                        }
                    }
                }
                if (loading || busy) LinearProgressIndicator(
                    Modifier.fillMaxWidth(), color = PremiumGold)
                if (error.isNotBlank()) Text(error, color = Color(0xFFFFB4A9), fontSize = 13.sp)
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text("ابحث عن قناة أو رقمها") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text("القنوات (" + channels.size + ")", color = PremiumGoldLight,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        items(channels, key = { it.id }) { channel ->
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = !busy) { play(channel) },
                color = PremiumPanel,
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(.7.dp, PremiumBorder)
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(channel.name, color = PremiumGoldLight,
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        val number = if (channel.format == "ytv") channel.streamUrl else ""
                        Text(
                            if (number.isNotBlank()) "رقم القناة: " + number
                            else channel.category.ifBlank { "بث مباشر" },
                            color = PremiumMuted, fontSize = 11.sp
                        )
                    }
                    SiteIcon("play-circle", tint = PremiumGold, size = 25.dp)
                }
            }
        }
        if (!loading && channels.isEmpty()) item(key = "tv-empty") {
            Text("لا توجد قنوات مطابقة. استخدم تحديث أو غيّر البحث.",
                color = PremiumMuted, fontSize = 13.sp)
        }
    }
    legacyFailure?.let { YtvPlayerErrorDialog(it, onDismiss = { legacyFailure = null }) }
}
