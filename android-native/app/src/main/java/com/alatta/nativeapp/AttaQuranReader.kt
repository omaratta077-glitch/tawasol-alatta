package com.alatta.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.DisposableEffect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.caverock.androidsvg.SVG
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/** A single offline Android Canvas Mushaf with golden reading controls. */
@Composable
internal fun QuranReaderScreen(modifier: Modifier = Modifier, onExit: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("atta_native_mushaf", Context.MODE_PRIVATE) }
    var page by remember { mutableIntStateOf(1) } // Open at Al-Fatiha; last read page remains saved in preferences.
    var pane by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedAyah by remember { mutableIntStateOf(0) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val cream = Color(0xFFFFF9E8)
    val gold = Color(0xFF75520F)
    val index = remember {
        runCatching { JSONObject(context.assets.open("mushaf/index.json").bufferedReader().use { it.readText() })
            .getJSONArray("pages") }.getOrNull()
    }
    val ayahDatabase = remember {
        runCatching { JSONObject(context.assets.open("mushaf/index.json").bufferedReader().use { it.readText() }).getJSONArray("ayahs") }.getOrNull()
    }
    val chapters = remember(index) {
        buildList {
            val seen = mutableSetOf<Int>()
            if (index != null) for (p in 1..604) {
                val hs = index.optJSONObject(p)?.optJSONArray("headers") ?: continue
                for (i in 0 until hs.length()) {
                    val h = hs.optJSONObject(i) ?: continue
                    val n = h.optInt("number")
                    if (n in 1..114 && seen.add(n)) add(Triple(n,h.optString("nameArabic"),p))
                }
            }
        }.sortedBy { it.first }
    }
    val juzStarts = remember { listOf(1,22,42,62,82,102,121,142,162,182,201,222,242,262,282,302,322,342,362,382,402,422,442,462,482,502,522,542,562,582) }
    val juz = (juzStarts.indexOfLast { it <= page } + 1).coerceAtLeast(1)
    // Each hizb contains four quarters. Read their real start pages from the bundled index.
    val hizbQuarterStarts = remember(index) {
        runCatching {
            val starts = JSONObject(context.assets.open("mushaf/index.json").bufferedReader().use { it.readText() })
                .getJSONArray("quarterStart")
            (1 until starts.length()).map { starts.getInt(it) }
        }.getOrDefault(emptyList())
    }
    val hizb = if (hizbQuarterStarts.size == 240) {
        (((hizbQuarterStarts.count { it <= page } - 1).coerceAtLeast(0)) / 4 + 1).coerceIn(1, 60)
    } else ((juz - 1) * 2 + 1).coerceIn(1, 60)
    val surah = chapters.lastOrNull { it.third <= page }?.second ?: "الفاتحة"
    val bookmark = prefs.getInt("bookmark", -1)
    val changePage: (Int) -> Unit = { n ->
        page = n.coerceIn(1,604); prefs.edit().putInt("page",page).apply(); error = null
        pane = null
    }
    BackHandler { if (pane != null) pane = null else onExit() }
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Column(modifier.fillMaxSize().background(cream)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { prefs.edit().putInt("bookmark",page).apply() }) {
                Text(if (bookmark == page) "◆" else "♧", color = gold)
            }
            Text("الجزء $juz", color = gold, style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { pane = "index" }) { Text("☷", color = gold) }
            TextButton(onClick = { pane = "search" }) { Text("⌕", color = gold) }
            TextButton(onClick = { pane = "index" }) { Text(surah, color = gold) }
            TextButton(onClick = onExit) { Text("رجوع", color = gold) }
        }
        key(page) {
            AndroidView(factory = {
                MushafPageView(context, page, changePage, { chosen ->
                    selectedAyah = chosen
                    playbackError = null
                }) { error = it }
            }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
        error?.let { Text(it,Modifier.padding(8.dp),color=Color(0xFF933C20)) }
        // Compact reading footer: no notes, navigation arrows or oversized controls.
        // Note editor remains accessible through the index dialog.
        Row(
            Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("صفحة $page", color = gold, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            Text("الحزب $hizb", color = gold, fontSize = 12.sp)
        }
    }
    if (pane != null) Dialog(onDismissRequest = { pane = null }) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFFFCF9F2),
            contentColor = Color(0xFF221B14)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(when(pane) {"index" -> "فهرس السور"; "parts" -> "الأجزاء";
                        "marks" -> "المرجعيات"; "notes" -> "الملاحظات"; else -> "البحث" },
                        color = Color(0xFF221B14), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { pane = null }) { Text("إغلاق", color = gold) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically) {
                    listOf("index" to "الفهرس", "parts" to "الأجزاء", "marks" to "المرجعيات", "notes" to "الملاحظات").forEach { (id, name) ->
                        TextButton(onClick = { pane = id }, modifier = Modifier.padding(end = 3.dp)) {
                            Text(name, maxLines = 1, softWrap = false,
                                color = if (pane == id) gold else Color(0xFF55483A))
                        }
                    }
                }
                when (pane) {
                    "index", "search" -> {
                        OutlinedTextField(value = query, onValueChange = { query = it },
                            label = { Text("بحث في أسماء السور") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF221B14)))
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(chapters.filter { query.isBlank() || it.second.contains(query) || it.first.toString() == query }) { (n, name, p) ->
                                Text("$n  $name     صفحة $p", Modifier.fillMaxWidth().clickable { changePage(p) }.padding(12.dp),
                                    color = Color(0xFF221B14), maxLines = 2)
                                HorizontalDivider(color = Color(0xFFE8E0D2))
                            }
                        }
                    }
                    "parts" -> LazyColumn(Modifier.weight(1f, fill = false)) {
                        items((1..30).toList()) { n ->
                            Text("الجزء $n  •  صفحة ${juzStarts[n-1]}",
                                Modifier.fillMaxWidth().clickable { changePage(juzStarts[n-1]) }.padding(12.dp), color = Color(0xFF221B14))
                        }
                    }
                    "marks" -> {
                        val saved = prefs.getInt("bookmark", -1)
                        Text(if (saved > 0) "العلامة المحفوظة: صفحة $saved" else "لا توجد مرجعيات محفوظة", color = Color(0xFF221B14))
                        if (saved > 0) TextButton(onClick = { changePage(saved) }) { Text("الانتقال إلى العلامة", color = gold) }
                        TextButton(onClick = { prefs.edit().putInt("bookmark", page).apply(); pane = null }) { Text("حفظ الصفحة الحالية", color = gold) }
                    }
                    "notes" -> {
                        LaunchedEffect(page) { note = prefs.getString("note_$page", "") ?: "" }
                        Text("ملاحظات الصفحة $page", color = Color(0xFF221B14))
                        OutlinedTextField(value = note, onValueChange = { note = it },
                            modifier = Modifier.fillMaxWidth(), minLines = 3,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF221B14)))
                        TextButton(onClick = { prefs.edit().putString("note_$page", note).apply(); pane = null }) { Text("حفظ الملاحظة", color = gold) }
                    }
                }
            }
        }
    }
    if (selectedAyah > 0) {
        val info = ayahDatabase?.optJSONObject(selectedAyah)
        val surahNumber = info?.optInt("surah", 0) ?: 0
        val verseNumber = info?.optInt("numberInSurah", 0) ?: 0
        val verseText = info?.optString("text", "") ?: ""
        AlertDialog(
            onDismissRequest = { selectedAyah = 0 },
            containerColor = Color(0xFFFFF9E8),
            title = { Text("الآية $verseNumber من السورة $surahNumber", color = gold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(verseText, color = Color(0xFF261B11), fontSize = 19.sp)
                    Text("اختر القارئ • التلاوة تحتاج اتصال إنترنت", color = gold, fontSize = 13.sp)
                    listOf(
                        "مشاري العفاسي" to "Alafasy_128kbps",
                        "محمود خليل الحصري" to "Husary_128kbps",
                        "محمد صديق المنشاوي" to "Minshawy_Murattal_128kbps"
                    ).forEach { (reader, folder) ->
                        TextButton(onClick = {
                            player?.release()
                            player = null
                            playbackError = null
                            val address = "https://everyayah.com/data/$folder/%03d%03d.mp3".format(Locale.ROOT, surahNumber, verseNumber)
                            runCatching {
                                MediaPlayer().apply {
                                    setDataSource(context, Uri.parse(address))
                                    setOnPreparedListener { it.start() }
                                    setOnErrorListener { _, _, _ -> playbackError = "تعذر تشغيل التلاوة. تحقق من اتصال الإنترنت."; true }
                                    prepareAsync()
                                    player = this
                                }
                            }.onFailure { playbackError = "تعذر بدء التلاوة: ${it.localizedMessage}" }
                        }) { Text(reader, color = gold) }
                    }
                    playbackError?.let { Text(it, color = Color(0xFF9A3324)) }
                }
            },
            confirmButton = { TextButton(onClick = { player?.release(); player = null; selectedAyah = 0 }) { Text("إغلاق", color = gold) } }
        )
    }
    }
}

private class MushafPageView(
    context: Context,
    private val page: Int,
    private val navigate: (Int) -> Unit,
    private val selectAyah: (Int) -> Unit,
    private val reportError: (String) -> Unit
) : View(context) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var svg: SVG? = null
    private val markers = runCatching {
        JSONObject(context.assets.open("mushaf/index.json").bufferedReader().use { it.readText() })
            .getJSONArray("pages").getJSONObject(page).getJSONArray("markers")
    }.getOrNull()
    private var displayedViewport: RectF? = null
    private var pageCoordinates: RectF? = null
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val dst = displayedViewport ?: return false
            val src = pageCoordinates ?: return false
            val points = markers ?: return false
            if (!dst.contains(e.x, e.y) || points.length() == 0) return false
            val x = src.left + (e.x - dst.left) * src.width() / dst.width()
            val y = src.top + (e.y - dst.top) * src.height() / dst.height()
            var nearest = 0
            var minDistance = Float.MAX_VALUE
            for (i in 0 until points.length()) {
                val m = points.optJSONArray(i) ?: continue
                val dx = (m.optDouble(1) - x).toFloat()
                val dy = (m.optDouble(2) - y).toFloat()
                val dist = dx * dx + dy * dy
                if (dist < minDistance) { minDistance = dist; nearest = m.optInt(0) }
            }
            if (nearest > 0) selectAyah(nearest)
            return nearest > 0
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            if (kotlin.math.abs(e2.x - start.x) < 80f || kotlin.math.abs(velocityX) < 350f) return false
            // RTL book: swipe right to left for the next page.
            if (e2.x < start.x) navigate(page + 1) else navigate(page - 1)
            return true
        }
    })
    init {
        setBackgroundColor(AndroidColor.rgb(253, 247, 230))
        val asset = String.format(Locale.ROOT, "mushaf/pages/%03d.svg", page)
        executor.execute {
            val result = runCatching {
                context.assets.open(asset).use { SVG.getFromInputStream(it) }
            }
            post {
                if (result.isSuccess) { svg = result.getOrNull(); invalidate() }
                else reportError("تعذّر قراءة صفحة $page من الملفات المحلية: ${result.exceptionOrNull()?.message.orEmpty()}")
            }
            executor.shutdown()
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = detector.onTouchEvent(event) || super.onTouchEvent(event)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawing = svg ?: return
        val viewport = drawing.documentViewBox ?: RectF(0f, 0f, drawing.documentWidth, drawing.documentHeight)
        if (viewport.width() <= 0f || viewport.height() <= 0f) return
        val inset = 2f * resources.displayMetrics.density
        val ratio = minOf(((width - 2 * inset).coerceAtLeast(1f)) / viewport.width(), ((height - 2 * inset).coerceAtLeast(1f)) / viewport.height())
        val destW = viewport.width() * ratio
        val destH = viewport.height() * ratio
        val left = (width - destW) / 2f
        val top = (height - destH) / 2f
        // AndroidSVG applies its own viewport transform; don't pre-scale the Canvas.
        // The previous extra scale made glyphs enormous and clipped the printed page.
        val target = RectF(left, top, left + destW, top + destH)
        pageCoordinates = RectF(viewport)
        displayedViewport = target
        drawing.renderToCanvas(canvas, target)
    }
}
