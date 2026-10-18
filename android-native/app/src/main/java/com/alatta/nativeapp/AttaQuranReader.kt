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
    if (pane != null) AlertDialog(onDismissRequest={pane=null},
        title={ Text(when(pane){"index"->"فهرس السور";"parts"->"الأجزاء";"marks"->"المرجعيات";"notes"->"الملاحظات";else->"البحث"}) },
        text={ Column(Modifier.heightIn(max=520.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                listOf("index" to "الفهرس","parts" to "الأجزاء","marks" to "المرجعيات","notes" to "الملاحظات").forEach { (id,name) ->
                    TextButton(onClick={pane=id}) { Text(name,color=gold) }
                }
            }
            when (pane) {
                "index","search" -> {
                    OutlinedTextField(value=query,onValueChange={query=it},label={Text("بحث في أسماء السور")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    LazyColumn { items(chapters.filter{query.isBlank() || it.second.contains(query) || it.first.toString()==query}) { (n,name,p) ->
                        Text("$n  $name      صفحة $p",Modifier.fillMaxWidth().clickable{changePage(p)}.padding(12.dp))
                    } }
                }
                "parts" -> LazyColumn { items((1..30).toList()) { n ->
                    Text("الجزء $n    •    صفحة ${juzStarts[n-1]}",Modifier.fillMaxWidth().clickable{changePage(juzStarts[n-1])}.padding(12.dp))
                } }
                "marks" -> {
                    val saved=prefs.getInt("bookmark",-1)
                    Text(if(saved>0) "العلامة المحفوظة: صفحة $saved" else "لا توجد مرجعيات محفوظة")
                    if(saved>0) TextButton(onClick={changePage(saved)}){Text("الانتقال إلى العلامة")}
                    TextButton(onClick={prefs.edit().putInt("bookmark",page).apply();pane=null}){Text("حفظ الصفحة الحالية")}
                }
                "notes" -> {
                    LaunchedEffect(page){ note=prefs.getString("note_$page","") ?: "" }
                    Text("ملاحظات الصفحة $page")
                    OutlinedTextField(value=note,onValueChange={note=it},modifier=Modifier.fillMaxWidth(),minLines=3)
                    TextButton(onClick={prefs.edit().putString("note_$page",note).apply();pane=null}){Text("حفظ الملاحظة")}
                }
            }
        } }, confirmButton={TextButton(onClick={pane=null}){Text("إغلاق")}})
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
        val ratio = minOf((width - 2 * inset) / viewport.width(), (height - 2 * inset) / viewport.height())
        val destW = viewport.width() * ratio
        val destH = viewport.height() * ratio
        val left = (width - destW) / 2f
        val top = (height - destH) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(ratio, ratio)
        drawing.renderToCanvas(canvas)
        canvas.restore()
    }
}
