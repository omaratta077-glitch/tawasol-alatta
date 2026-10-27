@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.alatta.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

private fun FootballChannel.json() = JSONObject().put("id", id).put("name", name).put("category", category)
    .put("provider", provider).put("sourceUrl", sourceUrl).put("streamUrl", streamUrl).put("format", format).put("enabled", enabled)

@Composable
internal fun AdminFootballPanel(token: String) {
    var open by remember { mutableStateOf(false) }
    var probeOpen by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(FootballCatalog()) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var channelForm by remember { mutableStateOf(false) }
    var matchForm by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf<FootballChannel?>(null) }
    var match by remember { mutableStateOf<FootballMatch?>(null) }
    var remove by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    fun load() { scope.launch {
        busy = true
        try { catalog = FootballRepository.load(token); loaded = true; message = "" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "تعذر تحميل القائمة" }
        finally { busy = false }
    } }
    fun change(action: String, data: JSONObject) { scope.launch {
        busy = true; message = ""
        try {
            catalog = FootballRepository.change(token, catalog.revision, action, data)
            loaded = true; channelForm = false; matchForm = false; remove = null; message = "تم حفظ التعديل"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            message = e.message ?: "تعذر حفظ التعديل"
            // Refresh the revision after a conflict without discarding the entered form.
            try { catalog = FootballRepository.load(token); loaded = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the form and last known catalog for an explicit retry. */ }
        } finally { busy = false }
    } }
    OutlinedButton(onClick = { open = true }) { Text("إدارة القنوات والمباريات", color = PremiumGoldLight) }
    if (open) {
        LaunchedEffect(Unit) { load() }
        Dialog(onDismissRequest = { if (!busy) { probeOpen = false; open = false } }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = PremiumBackground) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("القنوات وجدول المباريات", Modifier.weight(1f), color = PremiumGoldLight, fontSize = 19.sp)
                        TextButton(enabled = !busy, onClick = { probeOpen = false; open = false }) { Text("إغلاق") }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (message.isNotBlank()) Text(message, color = PremiumGoldLight, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = loaded && !busy, onClick = { channel = null; channelForm = true; message = "" }) { Text("إضافة قناة") }
                        TextButton(enabled = loaded && !busy, onClick = { match = null; matchForm = true; message = "" }) { Text("إضافة مباراة") }
                        TextButton(enabled = !busy, onClick = { load() }) { Text("تحديث") }
                    }
                    TextButton(enabled = loaded && !busy, onClick = { probeOpen = true }) {
                        Text("تجربة قائمة ياسين من الهاتف", color = PremiumGoldLight)
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Text("القنوات • ${catalog.channels.size}/40", color = PremiumGoldLight) }
                        if (loaded && catalog.channels.isEmpty()) item { Text("أضف قناة ورابط بث يتيحه الناشر للعرض في التطبيق. لا توجد قنوات مضافة بعد.", color = PremiumMuted, fontSize = 13.sp) }
                        items(catalog.channels, key = { it.id }) { item -> FootballPanel {
                            Text(item.name, color = Color.White)
                            Text("${item.category} • ${item.provider} • ${if (item.enabled) "مفعلة" else "موقوفة"}", color = PremiumMuted, fontSize = 12.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(enabled = !busy, onClick = { channel = item; channelForm = true; message = "" }) { Text("تعديل") }
                                TextButton(enabled = !busy, onClick = { change("saveChannel", JSONObject().put("channel", item.copy(enabled = !item.enabled).json())) }) { Text(if (item.enabled) "إيقاف" else "تفعيل") }
                                TextButton(enabled = !busy, onClick = { remove = "deleteChannel" to item.id }) { Text("إزالة") }
                            }
                        } }
                        item { Text("المباريات • ${catalog.matches.size}/80", color = PremiumGoldLight) }
                        items(catalog.matches.sortedBy { it.startsAt }, key = { it.id }) { item -> FootballPanel {
                            Text("${item.home} — ${item.away}", color = Color.White)
                            Text("${item.league} • ${footballKickoffInput(item.startsAt)} • ${footballStatus(item.status)}", color = PremiumMuted, fontSize = 12.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(enabled = !busy, onClick = { match = item; matchForm = true; message = "" }) { Text("تعديل / تحديث النتيجة") }
                                TextButton(enabled = !busy, onClick = { remove = "deleteMatch" to item.id }) { Text("إزالة") }
                            }
                        } }
                    }
                }
            }
        }
        if (probeOpen && loaded) YacineCatalogProbeDialog { probeOpen = false }
        if (channelForm) FootballChannelEditor(channel, busy, message, onDismiss = { channelForm = false },
            onSave = { change("saveChannel", JSONObject().put("channel", it)) })
        if (matchForm) FootballMatchEditor(match, catalog.channels, busy, message, onDismiss = { matchForm = false },
            onSave = { change("saveMatch", JSONObject().put("match", it)) })
        remove?.let { target -> AlertDialog(onDismissRequest = { if (!busy) remove = null },
            title = { Text(if (target.first == "deleteChannel") "إزالة القناة؟" else "إزالة المباراة؟") },
            text = { Text(if (target.first == "deleteChannel") "تُزال القناة من القائمة، وتبقى مبارياتها بدون قناة ناقلة حتى تختار بديلًا." else "تُزال المباراة من جدول التطبيق.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { change(target.first, JSONObject().put("id", target.second)) }) { Text("إزالة") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { remove = null }) { Text("رجوع") } }) }
    }
}

@Composable
internal fun FootballChannelEditor(item: FootballChannel?, busy: Boolean, message: String,
    onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var name by remember(item?.id) { mutableStateOf(item?.name.orEmpty()) }
    var category by remember(item?.id) { mutableStateOf(item?.category.orEmpty()) }
    var provider by remember(item?.id) { mutableStateOf(item?.provider.orEmpty()) }
    var source by remember(item?.id) { mutableStateOf(item?.sourceUrl.orEmpty()) }
    var stream by remember(item?.id) { mutableStateOf(item?.streamUrl.orEmpty()) }
    var ytvId by remember(item?.id) { mutableStateOf(if (item?.format == "ytv") item.streamUrl else "") }
    var format by remember(item?.id) { mutableStateOf(item?.format ?: "hls") }
    var enabled by remember(item?.id) { mutableStateOf(item?.enabled ?: true) }
    var validation by remember(item?.id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (item == null) "إضافة قناة" else "تعديل القناة") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("اختر المشغّل. قنوات YTV PRO تفتح في التطبيق المثبّت على الجهاز باستخدام رقم القناة الذي تضيفه هنا.", fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("hls" to "HLS", "dash" to "DASH", "youtube" to "يوتيوب", "external" to "موقع الناشر", "ytv" to "YTV PRO").forEach { (value, label) ->
                    FilterChip(selected = format == value, enabled = !busy, onClick = {
                        format = value; validation = ""
                        if (value == "ytv") {
                            if (category.isBlank()) category = "YTV PRO"
                            if (provider.isBlank()) provider = "YTV PRO"
                            if (source.isBlank()) source = YtvPlayer.APP_PAGE
                        }
                    }, label = { Text(label, fontSize = 12.sp) })
                }
            }
            FootballField(name, "اسم القناة", !busy, 80) { name = it }
            FootballField(category, "القسم / البطولة", !busy, 60) { category = it }
            if (format != "ytv") {
                FootballField(provider, "اسم الناشر أو مزود البث", !busy, 120) { provider = it }
                FootballField(source, "رابط صفحة المصدر HTTPS", !busy, 2048, KeyboardType.Uri) { source = it }
            }
            if (format == "ytv") {
                FootballField(ytvId, "رقم القناة لدى YTV PRO", !busy, 40, KeyboardType.Number) { ytvId = it; validation = "" }
                Text("استخدم الرقم الحقيقي للقناة لدى المشغّل، وليس ترتيبها في القائمة. تظهر القناة للمستخدمين بعد حفظها.", fontSize = 12.sp)
            } else FootballField(stream, "رابط البث أو المشاهدة HTTPS", !busy, 2048, KeyboardType.Uri) { stream = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(enabled, onCheckedChange = { enabled = it }, enabled = !busy); Text("إظهار القناة للمستخدمين") }
            if (message.isNotBlank()) Text(message, fontSize = 12.sp)
            if (validation.isNotBlank()) Text(validation, color = Color(0xFFFFB4A9), fontSize = 12.sp)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        val target = if (format == "ytv") YtvPlayer.channelId(ytvId) else stream.trim()
        if (target == null) validation = "أدخل رقم قناة صحيحًا لدى YTV PRO"
        else onSave(FootballChannel(item?.id.orEmpty(), name.trim(), category.trim(), provider.trim(), source.trim(), target, format, enabled).json())
    }) { Text("حفظ") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("رجوع") } })
}

@Composable
private fun FootballMatchEditor(item: FootballMatch?, channels: List<FootballChannel>, busy: Boolean, message: String,
    onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var home by remember(item?.id) { mutableStateOf(item?.home.orEmpty()) }
    var away by remember(item?.id) { mutableStateOf(item?.away.orEmpty()) }
    var league by remember(item?.id) { mutableStateOf(item?.league.orEmpty()) }
    var kickoff by remember(item?.id) { mutableStateOf(footballKickoffInput(item?.startsAt ?: System.currentTimeMillis())) }
    var channelId by remember(item?.id) { mutableStateOf(item?.channelId.orEmpty()) }
    var status by remember(item?.id) { mutableStateOf(item?.status ?: "scheduled") }
    var homeScore by remember(item?.id) { mutableStateOf(item?.homeScore?.toString().orEmpty()) }
    var awayScore by remember(item?.id) { mutableStateOf(item?.awayScore?.toString().orEmpty()) }
    var channelMenu by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (item == null) "إضافة مباراة" else "تعديل المباراة") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FootballField(home, "الفريق الأول", !busy, 60) { home = it }
            FootballField(away, "الفريق الثاني", !busy, 60) { away = it }
            FootballField(league, "البطولة", !busy, 80) { league = it }
            FootballField(kickoff, "الموعد بتوقيت هاتفك: 2026-10-21 20:30", !busy, 16) { kickoff = it; validation = "" }
            Box {
                OutlinedButton(enabled = !busy, onClick = { channelMenu = true }) { Text(channels.firstOrNull { it.id == channelId }?.name ?: "بدون قناة ناقلة") }
                DropdownMenu(channelMenu, onDismissRequest = { channelMenu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                    DropdownMenuItem(text = { Text("بدون قناة ناقلة") }, onClick = { channelId = ""; channelMenu = false })
                    channels.forEach { c -> DropdownMenuItem(text = { Text(c.name + if (c.enabled) "" else " • موقوفة") }, onClick = { channelId = c.id; channelMenu = false }) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("scheduled", "live", "finished").forEach { value -> FilterChip(selected = status == value, enabled = !busy, onClick = { status = value }, label = { Text(footballStatus(value), fontSize = 12.sp) }) }
            }
            if (status != "scheduled") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(homeScore, onValueChange = { homeScore = it.take(2) }, label = { Text("الأول") }, modifier = Modifier.weight(1f), singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(awayScore, onValueChange = { awayScore = it.take(2) }, label = { Text("الثاني") }, modifier = Modifier.weight(1f), singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            if (validation.isNotBlank()) Text(validation, color = Color(0xFFFFB4A9), fontSize = 12.sp)
            if (message.isNotBlank()) Text(message, fontSize = 12.sp)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        val time = parseFootballKickoff(kickoff)
        val first = parseFootballScore(homeScore)
        val second = parseFootballScore(awayScore)
        when {
            time == null -> validation = "اكتب تاريخًا وموعدًا صحيحين بالشكل الموضح"
            status != "scheduled" && ((homeScore.isNotBlank() && first == null) || (awayScore.isNotBlank() && second == null) || (first == null) != (second == null)) -> validation = "أدخل نتيجتي الفريقين معًا، أو اتركهما فارغتين"
            else -> onSave(JSONObject().put("id", item?.id.orEmpty()).put("home", home.trim()).put("away", away.trim()).put("league", league.trim())
                .put("startsAt", time).put("status", status).put("channelId", channelId)
                .put("homeScore", if (status == "scheduled") JSONObject.NULL else first ?: JSONObject.NULL)
                .put("awayScore", if (status == "scheduled") JSONObject.NULL else second ?: JSONObject.NULL))
        }
    }) { Text("حفظ") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("رجوع") } })
}

@Composable
private fun FootballField(value: String, label: String, enabled: Boolean, maximum: Int,
    keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = { onChange(it.take(maximum)) }, label = { Text(label, fontSize = 12.sp) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
