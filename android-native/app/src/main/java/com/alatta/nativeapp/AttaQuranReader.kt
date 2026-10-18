package com.alatta.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.RectF
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
    var page by remember { mutableIntStateOf(prefs.getInt("page", 1).coerceIn(1, 604)) }
    var pane by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val cream = Color(0xFFFFF9E8)
    val gold = Color(0xFF75520F)
    val index = remember {
        runCatching { JSONObject(context.assets.open("mushaf/index.json").bufferedReader().use { it.readText() })
            .getJSONArray("pages") }.getOrNull()
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
    val surah = chapters.lastOrNull { it.third <= page }?.second ?: "الفاتحة"
    val bookmark = prefs.getInt("bookmark", -1)
    val changePage: (Int) -> Unit = { n ->
        page = n.coerceIn(1,604); prefs.edit().putInt("page",page).apply(); error = null
        pane = null
    }
    BackHandler { if (pane != null) pane = null else onExit() }
    Column(modifier.fillMaxSize().background(cream)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
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
            AndroidView(factory = { MushafPageView(context,page,changePage) { error = it } },
                modifier = Modifier.fillMaxWidth().weight(1f))
        }
        error?.let { Text(it,Modifier.padding(8.dp),color=Color(0xFF933C20)) }
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp, vertical=5.dp),
            horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={changePage(page-1)},enabled=page>1) { Text("‹",color=gold) }
            TextButton(onClick={pane="notes"}) { Text("ملاحظاتي",color=gold) }
            Text("$page / 604",color=gold)
            TextButton(onClick={pane="parts"}) { Text("الحزب • الجزء $juz",color=gold) }
            TextButton(onClick={changePage(page+1)},enabled=page<604) { Text("›",color=gold) }
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
}

private class MushafPageView(
    context: Context,
    private val page: Int,
    private val navigate: (Int) -> Unit,
    private val reportError: (String) -> Unit
) : View(context) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var svg: SVG? = null
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            if (kotlin.math.abs(e2.x - start.x) < 80f || kotlin.math.abs(velocityX) < 350f) return false
            // Arabic book: swiping from right to left moves forward.
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
        val inset = 8f * resources.displayMetrics.density
        val ratio = minOf(((width - 2 * inset).coerceAtLeast(1f)) / viewport.width(), ((height - 2 * inset).coerceAtLeast(1f)) / viewport.height())
        val destW = viewport.width() * ratio
        val destH = viewport.height() * ratio
        val left = (width - destW) / 2f
        val top = (height - destH) / 2f
        // AndroidSVG applies its own viewport transform; don't pre-scale the Canvas.
        // The previous extra scale made glyphs enormous and clipped the printed page.
        drawing.renderToCanvas(canvas, RectF(left, top, left + destW, top + destH))
    }
}
