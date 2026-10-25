package com.alatta.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun YacineProviderPanel(query: String, modifier: Modifier = Modifier, callInProgress: Boolean = false,
    refreshRequest: Int = 0, onBeforePlayback: () -> Unit, repository: YacineRepository = remember { YacineRepository() }) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val inCall by rememberUpdatedState(callInProgress)
    val beforePlayback by rememberUpdatedState(onBeforePlayback)
    var active by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf<YacineCategory?>(null) }
    var categories by remember { mutableStateOf(emptyList<YacineCategory>()) }
    var channels by remember { mutableStateOf(emptyList<YacineChannel>()) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<YacineFailure?>(null) }
    var retryChannel by remember { mutableStateOf<YacineChannel?>(null) }
    var resolveJob by remember { mutableStateOf<Job?>(null) }
    var choices by remember { mutableStateOf(emptyList<YacineStream>()) }
    var playing by remember { mutableStateOf<YacineStream?>(null) }
    val search = query.trim().take(150)
    val searching = search.length >= 2

    fun play(stream: YacineStream) {
        if (inCall) return
        beforePlayback()
        choices = emptyList()
        playing = stream
    }
    fun resolve(channel: YacineChannel) {
        if (inCall || resolving) return
        resolveJob?.cancel()
        retryChannel = channel; failure = null; resolving = true
        resolveJob = scope.launch {
            try {
                val streams = repository.streams(channel.id)
                if (streams.size == 1) play(streams.first()) else choices = streams
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { failure = e as? YacineFailure ?: YacineFailure(YacineProblem.CONNECTION, YacineStage.PLAYBACK) }
            finally { if (currentCoroutineContext().isActive) resolving = false }
        }
    }

    LaunchedEffect(refreshRequest) {
        if (refreshRequest > 0) { active = true; revision++ }
    }
    LaunchedEffect(active, category, search, revision) {
        resolveJob?.cancel(); resolving = false; retryChannel = null; choices = emptyList()
        listState.scrollToItem(0)
        if (!active) return@LaunchedEffect
        loading = true; loaded = false; failure = null; categories = emptyList(); channels = emptyList()
        try {
            when {
                searching -> { delay(400); channels = repository.search(search) }
                category != null -> channels = repository.channels(requireNotNull(category).id)
                else -> categories = repository.categories()
            }
            loaded = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            failure = e as? YacineFailure ?: YacineFailure(YacineProblem.CONNECTION,
                if (searching) YacineStage.SEARCH else if (category != null) YacineStage.CHANNELS else YacineStage.CATEGORIES)
        } finally { if (currentCoroutineContext().isActive) loading = false }
    }
    LaunchedEffect(callInProgress) {
        if (callInProgress) { resolveJob?.cancel(); resolving = false; choices = emptyList(); playing = null }
    }
    LaunchedEffect(resolving, failure) {
        if (resolving || failure != null) listState.scrollToItem(2 + if (callInProgress) 1 else 0)
    }
    BackHandler(enabled = category != null && playing == null) { category = null }

    // Keep actions, status, errors and results in the same bounded scrolling area.
    // A fixed header plus a weighted inner list hid the error below the bottom bar on phones.
    LazyColumn(modifier.fillMaxWidth().testTag("yacine-provider-list"), state = listState,
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "heading") { Text("ياسين — ربط تجريبي", color = PremiumGoldLight, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        if (!active) {
            item(key = "start") { Button(onClick = { active = true }, modifier = Modifier.fillMaxWidth()) { Text("تحميل قنوات ياسين") } }
            item(key = "intro") { Text("المشاهدة داخل العطا تعتمد على استجابة مصدر القنوات. قد يتعذر الاتصال أو يحتاج الربط إلى تحديث.", color = PremiumMuted, fontSize = 12.sp) }
        }
        if (active) {
            item(key = "actions") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (category != null) TextButton(onClick = { category = null }, enabled = !resolving) { Text("رجوع للأقسام", color = PremiumGoldLight) }
                TextButton(onClick = { revision++ }, enabled = !loading && !resolving) { Text("تحديث", color = PremiumGoldLight) }
            } }
            if (callInProgress) item(key = "call") { Text("أنهِ المكالمة أولًا لتشغيل القنوات.", color = PremiumMuted, fontSize = 12.sp) }
            if (loading || resolving) {
                item(key = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth(), color = PremiumGold) }
                item(key = "loading") { Text(if (resolving) "جارٍ جلب رابط البث من المصدر…" else "جارٍ الاتصال بمصدر القنوات…", color = PremiumMuted, fontSize = 12.sp) }
            }
            failure?.let { error ->
                item(key = "error-title") { Text("تعذر إكمال الربط مع مصدر ياسين", color = PremiumGoldLight, fontWeight = FontWeight.Bold) }
                item(key = "error-reason") { Text(error.explanation, color = PremiumMuted, fontSize = 13.sp) }
                item(key = "retry") { OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !loading && !resolving && !(retryChannel != null && callInProgress), onClick = {
                    val target = retryChannel
                    if (target == null) revision++ else resolve(target)
                }) { Text("إعادة المحاولة", color = PremiumGoldLight) } }
                item(key = "error-help") { Text("تثبيت ياسين أو YTV PRO وحده لا يربط القنوات بالعطا؛ يلزم نجاح الاتصال بالمصدر هنا.", color = PremiumMuted, fontSize = 12.sp) }
            }
            if (loaded && categories.isEmpty() && channels.isEmpty()) item(key = "empty") { Text("لم يرجع المصدر قنوات أو أقسامًا مطابقة حاليًا.", color = PremiumMuted, fontSize = 13.sp) }
            if (!searching && category != null) item(key = "category") { Text(requireNotNull(category).name, color = PremiumGoldLight, fontSize = 14.sp) }
            items(categories, key = { "category-${it.id}" }) { item ->
                OutlinedButton(onClick = { category = item }, modifier = Modifier.fillMaxWidth(), enabled = !resolving) { Text(item.name, color = PremiumGoldLight) }
            }
            items(channels, key = { "channel-${it.id}" }) { item ->
                OutlinedButton(onClick = { resolve(item) }, modifier = Modifier.fillMaxWidth(), enabled = !resolving && !callInProgress) { Text(item.name, color = PremiumGoldLight) }
            }
        }
    }
    if (choices.isNotEmpty()) AlertDialog(onDismissRequest = { choices = emptyList() }, title = { Text("اختر مصدر التشغيل") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) { items(choices) { stream ->
            TextButton(onClick = { play(stream) }, enabled = !callInProgress) { Text(stream.name) }
        } }
    }, confirmButton = { TextButton(onClick = { choices = emptyList() }) { Text("إلغاء") } })
    playing?.let { stream ->
        val posts = remember(stream) { listOf(JSONObject().put("id", "yacine-stream").put("video", stream.url)) }
        AttaPostMediaViewer(posts, "yacine-stream", "video", onDismiss = { playing = null },
            live = if (stream.mimeType == "video/mp4") null else LivePlayback(stream.name, "ياسين", stream.mimeType),
            sourceMimeType = stream.mimeType, requestHeaders = stream.headers,
            playbackErrorMessage = "تعذر تشغيل بث ياسين من المصدر. قد يكون الرابط انتهى أو رُفض تشغيله؛ ارجع وأعد فتح القناة لتحديث رابطها.")
    }
}
