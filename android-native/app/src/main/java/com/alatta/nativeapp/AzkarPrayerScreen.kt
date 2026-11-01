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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val prayerTeal = Color(0xFF24190E)
private val prayerSoft = Color(0xFFDEC98C)
private val prayerShape = RoundedCornerShape(22.dp)
private val arabic = Locale("ar")

@Composable
private fun PrayerCard(
    modifier: Modifier = Modifier,
    innerPadding: Dp = 16.dp,
    goldStyle: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    // صباحًا ومساءً: الأسود والذهبي فقط، دون تغيير محتوى الذكر أو واجهات الصلاة الأخرى.
    val panelColor = if (goldStyle) Color(0xFF090A0A) else PremiumPanel
    val borderColor = if (goldStyle) PremiumGold else PremiumBorder.copy(alpha = .55f)
    Column(modifier.fillMaxWidth().clip(prayerShape).background(panelColor)
        .border(1.dp, borderColor, prayerShape).padding(innerPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** زخرفة مطابقة لفكرة الإطار والخطوط الذهبية بالصورة، في الهيدر فقط. */
@Composable
private fun AzkarHeaderPlaque(modifier: Modifier = Modifier, label: String = "الأذكار", inCard: Boolean = false) {
    @Composable fun Ornament() {
        Canvas(Modifier.size(20.dp, 20.dp)) {
            val cy = size.height / 2f
            val cx = size.width / 2f
            val thickness = 1.25.dp.toPx()
            drawLine(PremiumGold, Offset(0f, cy), Offset(size.width, cy), thickness)
            drawCircle(PremiumGoldLight, radius = 3.2.dp.toPx(),
                center = Offset(cx, cy), style = Stroke(thickness))
            drawLine(PremiumGoldLight, Offset(cx, cy - 5.dp.toPx()),
                Offset(cx + 4.dp.toPx(), cy), thickness)
            drawLine(PremiumGoldLight, Offset(cx, cy + 5.dp.toPx()),
                Offset(cx - 4.dp.toPx(), cy), thickness)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(PremiumGold))
        Ornament()
        Box(Modifier.widthIn(max = if (inCard) 230.dp else 300.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF090A0A))
            .border(1.dp, PremiumGoldLight, RoundedCornerShape(50))
            .padding(horizontal = if (inCard) 12.dp else 18.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center) {
            Text(label, color = PremiumGoldLight, fontWeight = FontWeight.Bold,
                fontSize = if (inCard) 15.sp else 18.sp,
                lineHeight = if (inCard) 20.sp else 23.sp,
                textAlign = TextAlign.Center,
                maxLines = if (inCard) 4 else 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Ornament()
        Box(Modifier.weight(1f).height(1.dp).background(PremiumGold))
    }
}

/** زخارف الصفحة الرئيسية للأذكار تُرسم بالكود لتظل خفيفة ولا تضيف صورًا إلى APK. */
@Composable
private fun AzkarHomeLantern() {
    Canvas(Modifier.size(width = 84.dp, height = 132.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(2.dp.toPx())
        val center = w / 2f
        val gold = PremiumGoldLight
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0x77F2BE44), Color(0x10E6B94E), Color.Transparent),
                center = Offset(center, h * .58f), radius = w * .70f
            ),
            radius = w * .70f, center = Offset(center, h * .58f)
        )
        drawLine(gold, Offset(center, 0f), Offset(center, h * .12f), stroke.width)
        drawCircle(gold, radius = w * .045f, center = Offset(center, h * .13f), style = stroke)
        drawPath(Path().apply {
            moveTo(w * .25f, h * .31f)
            quadraticBezierTo(center, h * .07f, w * .75f, h * .31f)
            lineTo(w * .75f, h * .80f)
            lineTo(w * .25f, h * .80f)
            close()
        }, gold, style = stroke)
        drawRect(
            brush = Brush.verticalGradient(listOf(Color(0x55F8C951), Color(0x22C78D26))),
            topLeft = Offset(w * .29f, h * .34f),
            size = Size(w * .42f, h * .44f)
        )
        for (i in 1..3) {
            val x = w * (0.25f + i * .125f)
            drawLine(gold.copy(alpha = .75f), Offset(x, h * .34f), Offset(x, h * .78f), stroke.width * .65f)
        }
        drawLine(gold, Offset(w * .19f, h * .82f), Offset(w * .81f, h * .82f), stroke.width * 2f)
        drawLine(gold, Offset(w * .12f, h * .89f), Offset(w * .88f, h * .89f), stroke.width * 2f)
    }
}

@Composable
private fun AzkarHomeAction(label: String, icon: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(Modifier.fillMaxWidth().height(54.dp).clip(shape)
        .background(Brush.horizontalGradient(listOf(Color(0xFF11100D), Color(0xFF08090A))))
        .border(1.3.dp, PremiumGoldLight, shape)
        .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        SiteIcon(icon, tint = PremiumGoldLight, size = 20.dp)
        Spacer(Modifier.width(7.dp))
        Text(label, color = PremiumGoldLight, fontSize = 14.sp,
            fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** رموز صغيرة مرسومة بالكود؛ بلا رسوم أو خطوط أو إعلانات أو كود منسوخ من التطبيق المرجعي. */
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
            "moon" -> drawPath(Path().apply {
                moveTo(w * .65f, w * .10f)
                cubicTo(w * .02f, w * .02f, w * .05f, w * .98f, w * .72f, w * .86f)
                cubicTo(w * .92f, w * .82f, w * .98f, w * .66f, w * .92f, w * .51f)
                cubicTo(w * .53f, w * .65f, w * .32f, w * .29f, w * .65f, w * .10f)
                close()
            }, tint, style = stroke)
            "sleep" -> {
                drawLine(tint, Offset(w * .12f, w * .28f), Offset(w * .12f, w * .85f), w * .06f)
                drawLine(tint, Offset(w * .12f, w * .65f), Offset(w * .88f, w * .65f), w * .06f)
                drawLine(tint, Offset(w * .88f, w * .65f), Offset(w * .88f, w * .85f), w * .06f)
                drawCircle(tint, w * .09f, Offset(w * .28f, w * .51f), style = stroke)
                drawLine(tint, Offset(w * .39f, w * .52f), Offset(w * .85f, w * .52f), w * .10f)
            }
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
internal fun AzkarPrayerScreen(modifier: Modifier = Modifier, onSharePost: (String) -> Unit) {
    val context = LocalContext.current
    val local = remember { context.getSharedPreferences("alatta_azkar", Context.MODE_PRIVATE) }
    var settings by remember { mutableStateOf(PrayerStore.read(context)) }
    var page by rememberSaveable { mutableStateOf("home") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var message by remember { mutableStateOf("") }
    var shareChoice by remember { mutableStateOf<Pair<DhikrGroup, Dhikr>?>(null) }
    shareChoice?.let { (selectedGroup, selectedEntry) ->
        fun share(wholeGroup: Boolean) {
            val text = formatDhikrShare(selectedGroup, if (wholeGroup) null else selectedEntry)
            shareChoice = null
            onSharePost(text)
        }
        AlertDialog(onDismissRequest = { shareChoice = null },
            title = { Text("مشاركة الأذكار") },
            text = { Text("اختر النص الذي تريد مشاركته") },
            confirmButton = { TextButton(onClick = { share(true) }) { Text("${selectedGroup.title} كاملة") } },
            dismissButton = { TextButton(onClick = { share(false) }) { Text("الذكر الحالي فقط") } },
            containerColor = PremiumBackground, titleContentColor = PremiumGoldLight, textContentColor = PremiumMuted)
    }
    var chooseCity by remember { mutableStateOf(false) }
    var chooseMethod by remember { mutableStateOf(false) }
    var customCity by remember { mutableStateOf(false) }
    var showCredits by remember { mutableStateOf(false) }
    var pendingEnable by rememberSaveable { mutableStateOf(false) }
    var permissionAction by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val locationScope = rememberCoroutineScope()
    var locationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var locating by remember { mutableStateOf(false) }
    var enableAfterLocation by rememberSaveable { mutableStateOf(false) }
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
    fun locateNow() {
        locationJob?.cancel()
        locationJob = locationScope.launch {
            locating = true
            try {
                val place = PrayerLocation.current(context)
                val latest = PrayerStore.read(context)
                save(latest.copy(place = place, confirmedPlace = true, automaticLocation = true,
                    method = if (latest.confirmedPlace) latest.method else place.method))
                PrayerStore.prefs(context).edit().putLong("location_updated_at", System.currentTimeMillis()).apply()
                message = "تم تحديد موقعك وحساب المواقيت. تأكد أن المنطقة الزمنية للهاتف صحيحة."
                if (enableAfterLocation) { enableAfterLocation = false; startAction("enable") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { enableAfterLocation = false; message = error.message ?: "تعذر تحديد الموقع؛ اختر المدينة يدويًا" }
            finally { locating = false }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (PrayerLocation.permitted(context)) locateNow()
        else { enableAfterLocation = false; message = "لم يُسمح بالموقع. يمكنك اختيار المدينة يدويًا، ولن يتغير الأذان الحالي." }
    }
    fun automaticLocation(enableAdhan: Boolean = false) {
        enableAfterLocation = enableAdhan
        if (PrayerLocation.permitted(context)) locateNow()
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    fun enableAdhan() { if (!settings.confirmedPlace) automaticLocation(true) else startAction("enable") }
    DisposableEffect(context) {
        val prefs = PrayerStore.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "settings") { settings = PrayerStore.read(context); refresh++ }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); locationJob?.cancel() }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { if (locating) enableAfterLocation = false; locationJob?.cancel() }
            if (event == Lifecycle.Event.ON_RESUME) {
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
    Column(modifier.fillMaxSize().background(PremiumBackground)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (page == "home") {
                Image(painter = painterResource(id = R.drawable.logo_premium),
                    contentDescription = "شعار تواصل العطا",
                    modifier = Modifier.size(46.dp).clip(CircleShape)
                        .border(1.dp, PremiumGold, CircleShape))
                Spacer(Modifier.width(8.dp))
                AzkarHeaderPlaque(Modifier.weight(1f), label = "الأذكار والعبادات")
            } else if (group != null) {
                // بجانب أيقونة التطبيق، يُكتب «الأذكار» فقط داخل الزخرفة في الأعلى.
                Image(painter = painterResource(id = R.drawable.logo_premium),
                    contentDescription = "شعار تواصل العطا",
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                        .border(1.dp, PremiumGold, CircleShape))
                Spacer(Modifier.width(7.dp))
                AzkarHeaderPlaque(Modifier.weight(1f))
                IconButton(onClick = { page = "home" }) {
                    SiteIcon("arrow-left", tint = PremiumGoldLight, size = 22.dp)
                }
            } else {
                if (page != "home") IconButton(onClick = { page = "home" }) {
                    SiteIcon("arrow-left", size = 22.dp)
                }
                Text(when (page) { "times" -> "مواقيت الصلاة"; "settings" -> "إعدادات الأذان"; else -> "الأذكار والعبادات" },
                    Modifier.weight(1f), color = PremiumGoldLight,
                    fontWeight = FontWeight.Bold, fontSize = 19.sp,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (group != null) Spacer(Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            .height(1.dp).background(PremiumGold.copy(alpha = .35f)))
        if (message.isNotBlank()) Row(Modifier.fillMaxWidth().background(prayerTeal).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = Color.White, fontSize = 13.sp)
            IconButton(onClick = { message = "" }, modifier = Modifier.size(32.dp)) { SiteIcon("x", size = 18.dp) }
        }
        if (playing) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("صوت الأذان يعمل الآن", Modifier.weight(1f), color = prayerSoft)
            TextButton(onClick = { context.stopService(Intent(context, AdhanPlaybackService::class.java)) }) { Text("إيقاف الأذان", color = PremiumGoldLight) }
        }
        if (group != null) {
            val entries = group.entries
            val goldStyle = true // كل أقسام الأذكار بنفس التنسيق الذهبي، دون تغيير البيانات أو العدادات
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        // الترتيب الأول في RTL يعرض الأزرار الدائرية على اليمين.
                        IconButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(32); local.edit().putInt("font_size", fontSize).apply() },
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(PremiumPanel)
                                .border(1.dp, PremiumGold, CircleShape).semantics { contentDescription = "تكبير خط الذكر" }) {
                            Text("+", color = PremiumGoldLight, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        }
                        IconButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(18); local.edit().putInt("font_size", fontSize).apply() },
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(PremiumPanel)
                                .border(1.dp, PremiumGold, CircleShape).semantics { contentDescription = "تصغير خط الذكر" }) {
                            Text("−", color = PremiumGoldLight, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        }
                        Text("${entries.size} ذكرًا ودعاءً • التقدّم محفوظ لليوم", Modifier.weight(1f),
                            color = PremiumMuted, fontSize = 11.sp, textAlign = TextAlign.End)
                    }
                }
                itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                    val key = "${group.id}:${entry.id}"
                    val count = counts[key] ?: local.getInt(key, 0).coerceIn(0, entry.target)
                    PrayerCard(innerPadding = 10.dp, goldStyle = goldStyle) {
                        if (goldStyle) {
                            // العنوان نفسه في المنتصف دون إخفاء علامة إكمال الذكر داخل البطاقة.
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                AzkarHeaderPlaque(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 27.dp),
                                    label = entry.title,
                                    inCard = true
                                )
                                if (count >= entry.target) {
                                    SiteIcon("check", modifier = Modifier.align(Alignment.CenterEnd),
                                        tint = PremiumGoldLight, size = 22.dp)
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1} • ${entry.title}",
                                    Modifier.wrapContentWidth().clip(RoundedCornerShape(12.dp))
                                        .background(prayerTeal.copy(alpha = .55f))
                                        .border(1.dp, PremiumBorder, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    color = PremiumGoldLight, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                if (count >= entry.target) SiteIcon("check", tint = prayerSoft, size = 22.dp)
                            }
                        }
                        Text(entry.text, color = Color.White, fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.75f).sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth())
                        Text(entry.source, color = if (goldStyle) PremiumGoldLight.copy(alpha = .8f) else PremiumMuted, fontSize = 12.sp)
                        if (entry.note.isNotBlank()) Text(entry.note,
                            color = if (goldStyle) PremiumGoldLight.copy(alpha = .7f) else prayerSoft, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { shareChoice = group to entry }) {
                                SiteIcon("share", tint = if (goldStyle) PremiumGoldLight else PremiumGold, size = 21.dp)
                            }
                            Box(Modifier.size(108.dp).clip(CircleShape)
                                .background(if (goldStyle) Color(0xFF11100D) else prayerTeal)
                                .then(if (goldStyle) Modifier.border(1.dp, PremiumGold, CircleShape) else Modifier)
                                .clickable(enabled = count < entry.target) {
                                val nextCount = (count + 1).coerceAtMost(entry.target)
                                counts[key] = nextCount; local.edit().putInt(key, nextCount).apply()
                            }.semantics { contentDescription = "تكرار ${entry.title}: $count من ${entry.target}" }, contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(progress = { count.toFloat() / entry.target }, modifier = Modifier.fillMaxSize().padding(5.dp), color = PremiumGold, trackColor = Color.White.copy(alpha = .10f), strokeWidth = 3.dp)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$count / ${entry.target}", color = PremiumGoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text(if (count >= entry.target) "اكتمل الذكر" else "اضغط للتكرار", color = Color.White, fontSize = 11.sp)
                                }
                            }
                            TextButton(onClick = { counts[key] = 0; local.edit().putInt(key, 0).apply() }) {
                                SiteIcon("repeat", tint = if (goldStyle) PremiumGoldLight else PremiumGold, size = 21.dp)
                            }
                        }
                    }
                }
            }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (page == "home" || page == "times") {
                item {
                    val goldHome = true // البيت ومواقيت الصلاة يشتركان في الخلفية الذهبية
                    val summaryShape = RoundedCornerShape(28.dp)
                    Box(Modifier.fillMaxWidth().clip(summaryShape)
                        .background(Color(0xFF0B0B0A))
                        .border(1.6.dp, PremiumGoldLight, summaryShape)) {
                        // أصل مرئي مخزّن داخل APK: مصحف وفانوس ونافذة مضيئة، يعمل بلا إنترنت.
                        Image(painter = painterResource(R.drawable.azkar_prayer_backdrop),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.matchParentSize())
                        Column(Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            PrayerSymbol("mosque", if (goldHome) 32.dp else 48.dp,
                                if (goldHome) PremiumGoldLight else PremiumGold)
                            Column(Modifier.weight(1f)) {
                                if (goldHome) Text("موقعي الحالي", color = PremiumGoldLight,
                                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(if (settings.confirmedPlace) settings.place.name else "اختر مدينتك",
                                    Modifier.clickable { chooseCity = true },
                                    color = if (goldHome) PremiumGoldLight else prayerSoft, fontSize = 14.sp)
                            }
                        }
                        if (goldHome) Spacer(Modifier.fillMaxWidth().height(1.dp)
                            .background(PremiumGold.copy(alpha = .5f)))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(if (settings.confirmedPlace && next != null)
                                    "الصلاة القادمة • ${next.title}" else "مواقيت الصلاة والأذان",
                                    color = Color.White, fontSize = if (goldHome) 18.sp else 17.sp,
                                    fontWeight = if (goldHome) FontWeight.Bold else FontWeight.Normal)
                                if (settings.confirmedPlace && next != null) {
                                    Text(formatPrayerTime(next.at, zone), color = PremiumGoldLight,
                                        fontSize = if (goldHome) 36.sp else 39.sp,
                                        fontWeight = FontWeight.Bold)
                                    val remaining = ((next.at - now).coerceAtLeast(0) / 1000)
                                    Text(String.format(arabic, "المتبقي %02d:%02d:%02d",
                                        remaining / 3600, remaining / 60 % 60, remaining % 60),
                                        color = Color.White, fontSize = 15.sp)
                                } else Text("حدد المدينة لعرض المواقيت الصحيحة",
                                    color = PremiumGoldLight, fontSize = 16.sp)
                            }
                            // تبقى صورة المصحف والفانوس في الجانب المقابل للنص الحي.
                            Spacer(Modifier.width(106.dp))
                        }
                        Text(date.format(DateTimeFormatter.ofPattern("EEEE، d MMMM", arabic)) +
                            " • " + runCatching {
                                HijrahDate.from(date).format(DateTimeFormatter.ofPattern("d MMMM yyyy", arabic)) + " هـ"
                            }.getOrDefault(""),
                            color = if (goldHome) PremiumGoldLight else prayerSoft, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (active) "الأذان مفعّل" else "الأذان متوقف",
                                Modifier.weight(1f), color = Color.White, fontSize = 14.sp)
                            Switch(active,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF141007),
                                    checkedTrackColor = PremiumGoldLight,
                                    checkedBorderColor = PremiumGold,
                                    uncheckedThumbColor = PremiumMuted,
                                    uncheckedTrackColor = Color(0xFF101112),
                                    uncheckedBorderColor = PremiumBorder
                                ),
                                onCheckedChange = {
                                    if (it) enableAdhan()
                                    else { enableAfterLocation = false; save(settings.copy(enabled = false)) }
                                })
                        }
                        }
                    }
                }
                if (calculated.isFailure && settings.confirmedPlace) item { Text("تعذر حساب المواقيت؛ راجع الموقع وطريقة الحساب.", color = Color(0xFFFFB4AB)) }
            }
            if (page == "home") {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.weight(1f)) {
                            AzkarHomeAction("مواقيت الصلاة", "book-open") { page = "times" }
                        }
                        Box(Modifier.weight(1f)) {
                            AzkarHomeAction("إعدادات الأذان", "menu") { page = "settings" }
                        }
                    }
                }
                item {
                    AzkarHeaderPlaque(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        label = "أذكارك اليومية")
                }
                items(dhikrGroups.chunked(2)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { category ->
                            val featured = category.id == "morning"
                            val tileShape = RoundedCornerShape(24.dp)
                            Column(Modifier.weight(1f).heightIn(min = 165.dp).clip(tileShape)
                                .background(if (featured)
                                    Brush.verticalGradient(listOf(Color(0xFFFFDE77), Color(0xFFE3A52D)))
                                else Brush.verticalGradient(listOf(Color(0xFF141413), Color(0xFF07090A))))
                                .border(1.3.dp, PremiumGoldLight, tileShape)
                                .attaGoldShine(featured)
                                .clickable { page = category.id }
                                .padding(horizontal = 8.dp, vertical = 15.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Box(Modifier.size(65.dp).clip(CircleShape)
                                    .background(if (featured) Color(0xFF15110D) else Color(0xFF17140F))
                                    .border(1.1.dp, PremiumGold, CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    PrayerSymbol(category.symbol, 34.dp, PremiumGoldLight)
                                }
                                Text(category.title,
                                    color = if (featured) Color(0xFF100F0A) else PremiumGoldLight,
                                    fontWeight = FontWeight.Bold, fontSize = 17.sp,
                                    maxLines = 2, textAlign = TextAlign.Center)
                                Text(category.subtitle,
                                    color = if (featured) Color(0xFF3C2C13) else PremiumMuted,
                                    fontSize = 12.sp, textAlign = TextAlign.Center,
                                    maxLines = 3)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (page == "times") {
                item { TextButton(onClick = { chooseCity = true }) { Text("تغيير المدينة • ${settings.place.zone}", color = PremiumGoldLight) } }
                items(prayerNames.keys.toList(), key = { it }) { id ->
                    val moment = moments.firstOrNull { it.id == id }
                    PrayerCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PrayerSymbol(if (id in setOf("fajr", "maghrib", "isha")) "moon" else "sun", 26.dp, PremiumGoldLight)
                            Text(prayerNames.getValue(id), Modifier.weight(1f).padding(start = 10.dp), color = Color.White, fontSize = 17.sp)
                            Text(if (settings.confirmedPlace && moment != null) formatPrayerTime(moment.at, zone) else "—", color = PremiumGoldLight, fontSize = 18.sp)
                            if (id != "sunrise") Checkbox(id in settings.prayers,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = PremiumGold, uncheckedColor = PremiumGoldLight,
                                    checkmarkColor = Color(0xFF0B0A08)),
                                onCheckedChange = { checked ->
                                    save(settings.copy(prayers =
                                        if (checked) settings.prayers + id else settings.prayers - id))
                                })
                        }
                    }
                }
                item { Text("الشروق للعرض فقط. الحساب حسب ${prayerMethods[settings.method]} والمنطقة الزمنية المختارة؛ قد تختلف دقائق عن تقويم المسجد المحلي.", color = PremiumMuted, fontSize = 12.sp) }
                item {
                    Button(onClick = { page = "settings" }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PremiumGold,
                            contentColor = Color(0xFF0A0907))) {
                        Text("ضبط الأذان وطريقة الحساب", fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (page == "settings") {
                item { PrayerCard {
                    Text("الموقع والحساب", color = PremiumGoldLight, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("تحديد موقعي تلقائيًا", Modifier.weight(1f), color = Color.White)
                        Switch(settings.automaticLocation, enabled = !locating, onCheckedChange = {
                            if (it) automaticLocation() else { locationJob?.cancel(); save(settings.copy(automaticLocation = false)) }
                        })
                    }
                    Text("بإذنك، يُحدَّد موقعك أثناء استخدام التطبيق ويُحفظ محليًا لحساب المواقيت. يتحدث عند فتح التطبيق، ولا يُرسل إلى خادم العطا. يعمل الموقع التقريبي أيضًا.", color = PremiumMuted, fontSize = 12.sp)
                    TextButton(onClick = { automaticLocation() }, enabled = !locating) { Text(if (locating) "جارٍ تحديد الموقع…" else "تحديد موقعي الآن", color = PremiumGoldLight) }

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
                        Switch(active, onCheckedChange = { if (it) enableAdhan() else { enableAfterLocation = false; save(settings.copy(enabled = false)) } })
                    }
                    Text("اختر الصلوات من صفحة المواقيت. يعمل الأذان محليًا دون إنترنت وبمستوى صوت المنبّه الذي تحدده في الهاتف.", color = PremiumMuted, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("عرض الأذان فوق شاشة القفل", Modifier.weight(1f), color = Color.White)
                        Switch(settings.lockScreen, onCheckedChange = { save(settings.copy(lockScreen = it)) })
                    }
                    Text("يظهر عند موعد الصلاة حتى والشاشة مطفّية، حسب أذونات الهاتف. زر الإيقاف متاح على شاشة الأذان وفي التنبيه.", color = PremiumMuted, fontSize = 12.sp)
                    if (settings.lockScreen && Build.VERSION.SDK_INT >= 34 && !AdhanScheduler.fullScreenAllowed(context)) TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))) }
                            .onFailure { message = "افتح إعدادات التطبيق واسمح بتنبيهات ملء الشاشة للأذان" }
                    }) { Text("السماح بالظهور على شاشة القفل", color = PremiumGoldLight) }

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
        locationJob?.cancel(); enableAfterLocation = false
        save(settings.copy(place = place, confirmedPlace = true, method = place.method, automaticLocation = false)); chooseCity = false
    }
    if (customCity) PrayerCustomCityDialog(settings.place, onDismiss = { customCity = false }) { place ->
        locationJob?.cancel(); enableAfterLocation = false
        save(settings.copy(place = place, confirmedPlace = true, automaticLocation = false)); customCity = false
    }
    if (chooseMethod) AlertDialog(onDismissRequest = { chooseMethod = false }, title = { Text("طريقة حساب المواقيت") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) { prayerMethods.forEach { (id, title) ->
            TextButton(onClick = { save(settings.copy(method = id)); chooseMethod = false }, modifier = Modifier.fillMaxWidth()) { Text(title) }
        } }
    }, confirmButton = { TextButton(onClick = { chooseMethod = false }) { Text("رجوع") } })
    if (showCredits) AlertDialog(onDismissRequest = { showCredits = false }, title = { Text("مصادر الحساب والصوت") }, text = {
        Text("المواقيت: مكتبة Adhan من Batoul Apps بترخيص MIT.\n\nالصوت المضمّن: Beautiful adhan — Adam-synagda، من Wikimedia Commons بترخيص CC0.\n\nتسجيلات المؤذنين بأسمائهم: روابط الاستماع والتنزيل من دار الإسلام، مع رابط المصدر لكل تسجيل. تسجيلات الإدارة تضاف من لوحة المدير. يتم تنزيل الصوت عند اختيارك وحفظه محليًا.\n\nالموقع التلقائي اختياري ويستخدم إذن موقع الهاتف أثناء الاستخدام فقط؛ لا توجد متابعة دائمة في الخلفية. المدينة والإحداثيات والعدادات والصوت المختار تُحفظ على الجهاز، ولا تُرفع إلى خادم التطبيق.")
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
