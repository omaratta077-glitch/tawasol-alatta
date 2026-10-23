package com.alatta.nativeapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val prayerTeal = Color(0xFF154A45)
private val prayerSoft = Color(0xFF83C8B6)
private val prayerShape = RoundedCornerShape(22.dp)
private val arabic = Locale("ar")

@Composable
private fun PrayerCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(prayerShape).background(PremiumPanel)
        .border(1.dp, PremiumBorder.copy(alpha = .55f), prayerShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** Small code-native symbols; no artwork, fonts, ads or code copied from the reference APK. */
@Composable
private fun PrayerSymbol(symbol: String, size: Dp = 36.dp, tint: Color = PremiumGold) {
    if (symbol == "hands") { SiteIcon("hand-heart", tint = tint, size = size); return }
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(w * .055f)
        when (symbol) {
            "sun" -> {
                drawCircle(tint, w * .20f, style = stroke)
                repeat(8) { n ->
                    val angle = n * Math.PI / 4
                    drawLine(tint, Offset(w / 2 + cos(angle).toFloat() * w * .34f, w / 2 + sin(angle).toFloat() * w * .34f),
                        Offset(w / 2 + cos(angle).toFloat() * w * .44f, w / 2 + sin(angle).toFloat() * w * .44f), w * .055f)
                }
            }
            "moon", "sleep" -> drawPath(Path().apply {
                moveTo(w * .65f, w * .10f)
                cubicTo(w * .02f, w * .02f, w * .05f, w * .98f, w * .72f, w * .86f)
                cubicTo(w * .92f, w * .82f, w * .98f, w * .66f, w * .92f, w * .51f)
                cubicTo(w * .53f, w * .65f, w * .32f, w * .29f, w * .65f, w * .10f)
                close()
            }, tint, style = stroke)
            "mosque" -> {
                drawPath(Path().apply {
                    moveTo(w * .16f, w * .88f); lineTo(w * .16f, w * .45f)
                    cubicTo(w * .20f, w * .28f, w * .38f, w * .25f, w * .45f, w * .13f)
                    cubicTo(w * .53f, w * .25f, w * .71f, w * .28f, w * .74f, w * .45f)
                    lineTo(w * .74f, w * .88f); close()
                }, tint, style = stroke)
                drawLine(tint, Offset(w * .88f, w * .88f), Offset(w * .88f, w * .22f), w * .055f)
                drawLine(tint, Offset(w * .80f, w * .23f), Offset(w * .96f, w * .23f), w * .055f)
                drawArc(tint, 180f, 180f, false, Offset(w * .34f, w * .58f), Size(w * .22f, w * .26f), style = stroke)
                drawLine(tint, Offset(w * .34f, w * .71f), Offset(w * .34f, w * .88f), w * .055f)
                drawLine(tint, Offset(w * .56f, w * .71f), Offset(w * .56f, w * .88f), w * .055f)
            }
            else -> {
                repeat(11) { n ->
                    val angle = n * Math.PI * 2 / 12
                    drawCircle(tint, w * .055f, Offset(w / 2 + cos(angle).toFloat() * w * .31f, w * .43f + sin(angle).toFloat() * w * .31f))
                }
                drawLine(tint, Offset(w * .76f, w * .59f), Offset(w * .79f, w * .86f), w * .05f)
                drawLine(tint, Offset(w * .70f, w * .90f), Offset(w * .87f, w * .90f), w * .05f)
            }
        }
    }
}

@Composable
internal fun AzkarPrayerScreen(query: String, modifier: Modifier = Modifier, onSharePost: (String) -> Unit) {
    val context = LocalContext.current
    val local = remember { context.getSharedPreferences("alatta_azkar", Context.MODE_PRIVATE) }
    var settings by remember { mutableStateOf(PrayerStore.read(context)) }
    var page by rememberSaveable { mutableStateOf("home") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var message by remember { mutableStateOf("") }
    var chooseCity by remember { mutableStateOf(false) }
    var chooseMethod by remember { mutableStateOf(false) }
    var customCity by remember { mutableStateOf(false) }
    var showCredits by remember { mutableStateOf(false) }
    var pendingEnable by rememberSaveable { mutableStateOf(false) }
    var permissionAction by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var fontSize by remember { mutableIntStateOf(local.getInt("font_size", 23).coerceIn(18, 32)) }
    val counts = remember { mutableStateMapOf<String, Int>() }
    val playing by AdhanPlaybackService.playing.collectAsState()
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    val zone = remember(settings.place.zone) { ZoneId.of(settings.place.zone) }
    val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val deviceDate = LocalDate.now().toString()
    LaunchedEffect(lifecycle) {
        lifecycle?.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = System.currentTimeMillis(); delay(1000) }
        }
    }
    LaunchedEffect(deviceDate) {
        if (local.getString("count_date", "") != deviceDate) {
            local.edit().clear().putString("count_date", deviceDate).putInt("font_size", fontSize).apply()
            counts.clear()
        }
    }
    val calculated = remember(settings, date) { runCatching {
        PrayerCalculation.day(settings, date) + PrayerCalculation.day(settings, date.plusDays(1))
    } }
    val moments = calculated.getOrDefault(emptyList())
    val next = moments.firstOrNull { it.id != "sunrise" && it.at > now }
    fun save(value: PrayerSettings) {
        runCatching {
            val normalized = value.copy(enabled = value.enabled && value.prayers.intersect(alarmPrayerIds).isNotEmpty())
            val scheduled = AdhanScheduler.update(context, normalized)
            if (normalized.enabled && !scheduled) {
                settings = normalized.copy(enabled = false)
                AdhanScheduler.update(context, settings)
                message = "تعذر جدولة الأذان؛ راجع إذن التنبيهات والمنبّهات وطريقة حساب المدينة."
            } else settings = normalized
            refresh++
        }.onFailure { message = it.message ?: "تعذر حفظ الإعدادات" }
    }
    fun exactSetup() {
        if (!settings.confirmedPlace) { chooseCity = true; message = "اختر مدينتك أولًا لضبط المواقيت"; return }
        AdhanScheduler.createChannel(context)
        if (!AdhanScheduler.notificationAllowed(context)) { message = "اسمح بتنبيهات مواقيت الصلاة من إعدادات أندرويد، ثم فعّل الأذان."; return }
        if (!AdhanScheduler.exactAllowed(context) && Build.VERSION.SDK_INT >= 31) {
            pendingEnable = true
            message = "اسمح بالمنبّهات والتذكيرات لتشغيل الأذان في موعده، ثم ارجع للتطبيق."
            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
                .onFailure { pendingEnable = false; message = "افتح إعدادات التطبيق ثم المنبّهات والتذكيرات للسماح بها." }
        } else {
            save(settings.copy(enabled = true)); message = if (settings.enabled) "تم تفعيل أذان الصلوات المحددة" else message
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { if (permissionAction == "preview") AdhanScheduler.preview(context) else exactSetup() }
        else message = "الأذان يحتاج إذن التنبيهات لعرض زر إيقاف الصوت. يمكنك السماح به لاحقًا."
        permissionAction = ""; refresh++
    }
    fun startAction(action: String) {
        if (action == "enable" && !settings.confirmedPlace) { chooseCity = true; message = "اختر مدينتك أولًا"; return }
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionAction = action; notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (action == "preview") {
            if (AdhanScheduler.notificationAllowed(context)) AdhanScheduler.preview(context)
            else message = "اسمح بتنبيهات مواقيت الصلاة من إعدادات أندرويد لتجربة الصوت."
        } else exactSetup()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            settings = PrayerStore.read(context); refresh++; now = System.currentTimeMillis()
            if (pendingEnable) {
                pendingEnable = false
                if (AdhanScheduler.exactAllowed(context) && AdhanScheduler.notificationAllowed(context)) save(settings.copy(enabled = true))
                else message = "لم يُفعّل الأذان؛ يلزم السماح بالتنبيهات والمنبّهات والتذكيرات."
            } else AdhanScheduler.restore(context)
        } }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    BackHandler(enabled = page != "home") { page = "home" }
    val active = remember(settings, refresh) { settings.enabled && AdhanScheduler.exactAllowed(context) && AdhanScheduler.notificationAllowed(context) && PrayerStore.prefs(context).getLong("scheduled_at", 0L) > now }
    val group = dhikrGroups.find { it.id == page }
    val filteredGroups = dhikrGroups.filter { matchesDhikrQuery(it.title, query) || it.entries.any { e -> matchesDhikrQuery(e.title, query) || matchesDhikrQuery(e.text, query) } }
    Column(modifier.fillMaxSize().background(PremiumBackground)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != "home") IconButton(onClick = { page = "home" }) { SiteIcon("arrow-left", size = 22.dp) }
            Text(group?.title ?: when (page) { "times" -> "مواقيت الصلاة"; "settings" -> "إعدادات الأذان"; else -> "الأذكار والعبادات" },
                Modifier.weight(1f), color = PremiumGoldLight, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            IconButton(onClick = { page = if (page == "settings") "home" else "settings" }) { SiteIcon("bell", size = 23.dp) }
        }
        if (message.isNotBlank()) Row(Modifier.fillMaxWidth().background(prayerTeal).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = Color.White, fontSize = 13.sp)
            IconButton(onClick = { message = "" }, modifier = Modifier.size(32.dp)) { SiteIcon("x", size = 18.dp) }
        }
        if (playing) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("صوت الأذان يعمل الآن", Modifier.weight(1f), color = prayerSoft)
            TextButton(onClick = { context.stopService(Intent(context, AdhanPlaybackService::class.java)) }) { Text("إيقاف الأذان", color = PremiumGoldLight) }
        }
        if (group != null) {
            val entries = group.entries.filter { matchesDhikrQuery(it.title, query) || matchesDhikrQuery(it.text, query) || matchesDhikrQuery(group.title, query) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${entries.size} ذكرًا ودعاءً • التقدّم محفوظ لليوم", Modifier.weight(1f), color = PremiumMuted, fontSize = 12.sp)
                        TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(18); local.edit().putInt("font_size", fontSize).apply() }) { Text("أ−", color = PremiumGold) }
                        TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(32); local.edit().putInt("font_size", fontSize).apply() }) { Text("أ+", color = PremiumGold) }
                    }
                }
                itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                    val key = "${group.id}:${entry.id}"
                    val count = counts[key] ?: local.getInt(key, 0).coerceIn(0, entry.target)
                    PrayerCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1} • ${entry.title}", Modifier.weight(1f), color = PremiumGoldLight, fontWeight = FontWeight.Bold)
                            if (count >= entry.target) SiteIcon("check", tint = prayerSoft, size = 22.dp)
                        }
                        Text(entry.text, color = Color.White, fontSize = fontSize.sp, lineHeight = (fontSize * 1.75).sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Text(entry.source, color = PremiumMuted, fontSize = 12.sp)
                        if (entry.note.isNotBlank()) Text(entry.note, color = prayerSoft, fontSize = 12.sp)
                        val sharedText = listOf(group.title, entry.title, entry.text, entry.source, entry.note)
                            .filter { it.isNotBlank() }.joinToString("\n\n")
                        Button(onClick = { onSharePost(sharedText) },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).attaGoldShine(),
                            colors = ButtonDefaults.buttonColors(containerColor = PremiumGold, contentColor = PremiumBackground),
                            shape = RoundedCornerShape(13.dp)) {
                            SiteIcon("share", tint = PremiumBackground, size = 20.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("مشاركة كمنشور داخل التطبيق", fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, sharedText)
                                context.startActivity(Intent.createChooser(share, "مشاركة الذكر"))
                            }) { SiteIcon("share", size = 21.dp) }
                            Box(Modifier.size(108.dp).clip(CircleShape).background(prayerTeal).clickable(enabled = count < entry.target) {
                                val nextCount = (count + 1).coerceAtMost(entry.target)
                                counts[key] = nextCount; local.edit().putInt(key, nextCount).apply()
                            }.semantics { contentDescription = "تكرار ${entry.title}: $count من ${entry.target}" }, contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(progress = { count.toFloat() / entry.target }, modifier = Modifier.fillMaxSize().padding(5.dp), color = PremiumGold, trackColor = Color.White.copy(alpha = .10f), strokeWidth = 3.dp)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$count / ${entry.target}", color = PremiumGoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text(if (count >= entry.target) "اكتمل الذكر" else "اضغط للتكرار", color = Color.White, fontSize = 11.sp)
                                }
                            }
                            TextButton(onClick = { counts[key] = 0; local.edit().putInt(key, 0).apply() }) { SiteIcon("repeat", size = 21.dp) }
                        }
                    }
                }
                if (entries.isEmpty()) item { Text("لا توجد أذكار تطابق البحث", color = PremiumMuted) }
            }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (page == "home" || page == "times") {
                item {
                    Column(Modifier.fillMaxWidth().clip(prayerShape).background(Brush.linearGradient(listOf(prayerTeal, Color(0xFF0B2425)))).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (settings.confirmedPlace) settings.place.name else "اختر مدينتك", Modifier.weight(1f).clickable { chooseCity = true }, color = prayerSoft, fontSize = 14.sp)
                            PrayerSymbol("mosque", 48.dp)
                        }
                        Text(if (settings.confirmedPlace && next != null) "الصلاة القادمة • ${next.title}" else "مواقيت الصلاة والأذان", color = Color.White, fontSize = 17.sp)
                        if (settings.confirmedPlace && next != null) {
                            Text(formatPrayerTime(next.at, zone), color = PremiumGoldLight, fontSize = 39.sp, fontWeight = FontWeight.Bold)
                            val remaining = ((next.at - now).coerceAtLeast(0) / 1000)
                            Text(String.format(arabic, "المتبقي %02d:%02d:%02d", remaining / 3600, remaining / 60 % 60, remaining % 60), color = Color.White, fontSize = 16.sp)
                        } else Text("حدد المدينة لعرض المواقيت الصحيحة", color = PremiumGoldLight, fontSize = 18.sp)
                        Text(date.format(DateTimeFormatter.ofPattern("EEEE، d MMMM", arabic)) + " • " + runCatching { HijrahDate.from(date).format(DateTimeFormatter.ofPattern("d MMMM yyyy", arabic)) + " هـ" }.getOrDefault(""), color = prayerSoft, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (active) "الأذان مفعّل" else "الأذان متوقف", Modifier.weight(1f), color = Color.White, fontSize = 13.sp)
                            Switch(active, onCheckedChange = { if (it) startAction("enable") else save(settings.copy(enabled = false)) })
                        }
                    }
                }
                if (calculated.isFailure && settings.confirmedPlace) item { Text("تعذر حساب المواقيت؛ راجع الموقع وطريقة الحساب.", color = Color(0xFFFFB4AB)) }
            }
            if (page == "home") {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { page = "times" }, modifier = Modifier.weight(1f)) { Text("مواقيت الصلاة", color = PremiumGoldLight) }
                        OutlinedButton(onClick = { page = "settings" }, modifier = Modifier.weight(1f)) { Text("إعدادات الأذان", color = PremiumGoldLight) }
                    }
                }
                item { Text("أذكارك اليومية", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                items(filteredGroups.chunked(2)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { category ->
                            Column(Modifier.weight(1f).clip(prayerShape).background(PremiumPanel)
                                .border(1.dp, PremiumBorder.copy(alpha = .40f), prayerShape).clickable { page = category.id }.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Box(Modifier.size(68.dp).background(prayerTeal.copy(alpha = .7f), CircleShape), contentAlignment = Alignment.Center) { PrayerSymbol(category.symbol, 35.dp) }
                                Text(category.title, color = PremiumGoldLight, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(category.subtitle, color = PremiumMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (filteredGroups.isEmpty()) item { Text("لا توجد نتائج تطابق البحث", color = PremiumMuted) }
            }
            if (page == "times") {
                item { TextButton(onClick = { chooseCity = true }) { Text("تغيير المدينة • ${settings.place.zone}", color = PremiumGoldLight) } }
                items(prayerNames.keys.toList(), key = { it }) { id ->
                    val moment = moments.firstOrNull { it.id == id }
                    PrayerCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PrayerSymbol(if (id in setOf("fajr", "maghrib", "isha")) "moon" else "sun", 26.dp, if (id == next?.id) PremiumGold else prayerSoft)
                            Text(prayerNames.getValue(id), Modifier.weight(1f).padding(start = 10.dp), color = Color.White, fontSize = 17.sp)
                            Text(if (settings.confirmedPlace && moment != null) formatPrayerTime(moment.at, zone) else "—", color = PremiumGoldLight, fontSize = 18.sp)
                            if (id != "sunrise") Checkbox(id in settings.prayers, onCheckedChange = { checked -> save(settings.copy(prayers = if (checked) settings.prayers + id else settings.prayers - id)) })
                        }
                    }
                }
                item { Text("الشروق للعرض فقط. الحساب حسب ${prayerMethods[settings.method]} والمنطقة الزمنية المختارة؛ قد تختلف دقائق عن تقويم المسجد المحلي.", color = PremiumMuted, fontSize = 12.sp) }
                item { Button(onClick = { page = "settings" }, modifier = Modifier.fillMaxWidth()) { Text("ضبط الأذان وطريقة الحساب") } }
            }
            if (page == "settings") {
                item { PrayerCard {
                    Text("الموقع والحساب", color = PremiumGoldLight, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { chooseCity = true }) { Text(if (settings.confirmedPlace) "المدينة: ${settings.place.name}" else "اختيار المدينة", color = Color.White) }
                    Text("${settings.place.zone} • تتبع المنطقة الزمنية والتوقيت الصيفي تلقائيًا", color = PremiumMuted, fontSize = 12.sp)
                    TextButton(onClick = { chooseMethod = true }) { Text("طريقة الحساب: ${prayerMethods[settings.method]}", color = Color.White) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("حساب العصر الحنفي", Modifier.weight(1f), color = Color.White)
                        Switch(settings.hanafi, onCheckedChange = { save(settings.copy(hanafi = it)) })
                    }
                    Text("الحساب الافتراضي للعصر: الشافعي/المالكي/الحنبلي. أم القرى تضيف 30 دقيقة للعشاء في رمضان حسب التقويم الهجري المحسوب.", color = PremiumMuted, fontSize = 12.sp)
                } }
                item { PrayerCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("تفعيل الأذان", Modifier.weight(1f), color = PremiumGoldLight, fontWeight = FontWeight.Bold)
                        Switch(active, onCheckedChange = { if (it) startAction("enable") else save(settings.copy(enabled = false)) })
                    }
                    Text("اختر الصلوات من صفحة المواقيت. يعمل الأذان محليًا دون إنترنت وبمستوى صوت المنبّه الذي تحدده في الهاتف.", color = PremiumMuted, fontSize = 13.sp)
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text("إعدادات التنبيهات في أندرويد", color = PremiumGoldLight) }
                    if (Build.VERSION.SDK_INT >= 31 && !AdhanScheduler.exactAllowed(context)) TextButton(onClick = { startAction("enable") }) { Text("السماح بالمنبّهات والتذكيرات", color = PremiumGoldLight) }
                    Text("على بعض الهواتف، راجع تشغيل التطبيق في الخلفية إذا تأخر الأذان. لا يغيّر التطبيق وضع عدم الإزعاج أو مستوى صوتك.", color = PremiumMuted, fontSize = 12.sp)
                    TextButton(onClick = { page = "times" }) { Text("اختيار الصلوات", color = PremiumGoldLight) }
                } }
                item { PrayerCard {
                    Text("صوت الأذان", color = PremiumGoldLight, fontWeight = FontWeight.Bold)
                    Text(when { settings.soundUri.isNotBlank() -> "ملف صوتي اخترته من الهاتف"
                        settings.voiceName.isNotBlank() -> "${settings.voiceName} • محفوظ دون إنترنت"
                        else -> "صوت الأذان المضمّن • متاح دون إنترنت" }, color = Color.White)
                    AdhanVoiceSelector(settings, onSelect = { voice -> save(settings.copy(soundUri = "", voiceId = voice.id, voiceName = voice.name, voiceSha256 = voice.sha256)) }, onMessage = { message = it })
                    PrayerAudioPicker(settings.soundUri, onPick = { save(settings.copy(soundUri = it, voiceId = "", voiceName = "", voiceSha256 = "")) }, onMessage = { message = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (playing) context.stopService(Intent(context, AdhanPlaybackService::class.java)) else startAction("preview") }) { Text(if (playing) "إيقاف الصوت" else "تجربة الأذان") }
                        TextButton(onClick = { save(settings.copy(soundUri = "", voiceId = "", voiceName = "", voiceSha256 = "")) }) { Text("الصوت الافتراضي", color = PremiumGoldLight) }
                    }
                } }
                item { PrayerCard {
                    Text("تعديل الدقائق", color = PremiumGoldLight, fontWeight = FontWeight.Bold)
                    Text("للمطابقة مع تقويم مسجدك؛ لا تغيّر القيم إلا عند الحاجة.", color = PremiumMuted, fontSize = 12.sp)
                    alarmPrayerIds.forEach { id ->
                        val offset = settings.offsets[id] ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(prayerNames.getValue(id), Modifier.weight(1f), color = Color.White)
                            IconButton(onClick = { save(settings.copy(offsets = settings.offsets + (id to (offset - 1).coerceAtLeast(-30)))) }) { Text("−", color = PremiumGold, fontSize = 23.sp) }
                            Text("$offset د", color = Color.White, modifier = Modifier.width(49.dp), textAlign = TextAlign.Center)
                            IconButton(onClick = { save(settings.copy(offsets = settings.offsets + (id to (offset + 1).coerceAtMost(30)))) }) { Text("+", color = PremiumGold, fontSize = 23.sp) }
                        }
                    }
                } }
                item { TextButton(onClick = { showCredits = true }) { Text("مصادر الحساب والصوت", color = PremiumMuted) } }
            }
        }
    }
    if (chooseCity) PrayerCityDialog(onDismiss = { chooseCity = false }, onCustom = { chooseCity = false; customCity = true }) { place ->
        save(settings.copy(place = place, confirmedPlace = true, method = place.method)); chooseCity = false
    }
    if (customCity) PrayerCustomCityDialog(settings.place, onDismiss = { customCity = false }) { place ->
        save(settings.copy(place = place, confirmedPlace = true)); customCity = false
    }
    if (chooseMethod) AlertDialog(onDismissRequest = { chooseMethod = false }, title = { Text("طريقة حساب المواقيت") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) { prayerMethods.forEach { (id, title) ->
            TextButton(onClick = { save(settings.copy(method = id)); chooseMethod = false }, modifier = Modifier.fillMaxWidth()) { Text(title) }
        } }
    }, confirmButton = { TextButton(onClick = { chooseMethod = false }) { Text("رجوع") } })
    if (showCredits) AlertDialog(onDismissRequest = { showCredits = false }, title = { Text("مصادر الحساب والصوت") }, text = {
        Text("المواقيت: مكتبة Adhan من Batoul Apps بترخيص MIT.\n\nالصوت المضمّن: Beautiful adhan — Adam-synagda، من Wikimedia Commons بترخيص CC0.\n\nتسجيلات المؤذنين بأسمائهم: روابط الاستماع والتنزيل من دار الإسلام، مع رابط المصدر لكل تسجيل. تسجيلات الإدارة تضاف من لوحة المدير. يتم تنزيل الصوت عند اختيارك وحفظه محليًا.\n\nالمدينة والإحداثيات والعدادات والصوت المختار تُحفظ على الجهاز، ولا تُرفع إلى خادم التطبيق.")
    }, confirmButton = { TextButton(onClick = { showCredits = false }) { Text("حسنًا") } })
}

private fun formatPrayerTime(at: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("h:mm a", arabic).withZone(zone).format(Instant.ofEpochMilli(at))

@Composable
private fun PrayerAudioPicker(uri: String, onPick: (String) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        if (chosen != null) runCatching {
            require(chosen.scheme == "content")
            context.contentResolver.takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uri.isNotBlank() && uri != chosen.toString()) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onPick(chosen.toString())
        }.onFailure { onMessage("تعذر الاحتفاظ بإذن الملف. اختر ملف صوت من مساحة الهاتف؛ الصوت الافتراضي متاح.") }
    }
    TextButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("اختيار صوت من الهاتف", color = PremiumGoldLight) }
}

@Composable
private fun PrayerCityDialog(onDismiss: () -> Unit, onCustom: () -> Unit, onSelect: (PrayerPlace) -> Unit) {
    var search by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("اختر مدينتك") }, text = {
        Column {
            OutlinedTextField(search, { search = it }, label = { Text("البحث عن المدينة") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(prayerPlaces.filter { it.name.contains(search, true) }) { place ->
                    TextButton(onClick = { onSelect(place) }, modifier = Modifier.fillMaxWidth()) { Text(place.name) }
                }
            }
            TextButton(onClick = onCustom) { Text("مدينة أخرى / إحداثيات يدوية") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("رجوع") } })
}

@Composable
private fun PrayerCustomCityDialog(initial: PrayerPlace, onDismiss: () -> Unit, onSelect: (PrayerPlace) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var lat by remember { mutableStateOf(initial.latitude.toString()) }
    var lon by remember { mutableStateOf(initial.longitude.toString()) }
    var zone by remember { mutableStateOf(initial.zone) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("إضافة مدينة") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("اسم المدينة") }, singleLine = true)
            OutlinedTextField(lat, { lat = it }, label = { Text("خط العرض • 24.7136") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(lon, { lon = it }, label = { Text("خط الطول • 46.6753") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(zone, { zone = it.trim() }, label = { Text("المنطقة الزمنية • Asia/Riyadh") }, singleLine = true)
            Text("استخدم إحداثيات المدينة والمنطقة الزمنية الصحيحة لضبط التوقيت الصيفي تلقائيًا. لا نطلب إذن موقع الهاتف.", color = PremiumMuted, fontSize = 12.sp)
            if (error.isNotBlank()) Text(error, color = Color(0xFFFFB4AB))
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { PrayerPlace(name.trim(), lat.toDouble(), lon.toDouble(), zone, initial.method).validate() }
            .onSuccess(onSelect).onFailure { error = it.message ?: "راجع الإحداثيات والمنطقة الزمنية" }
    }) { Text("حفظ المدينة") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}
