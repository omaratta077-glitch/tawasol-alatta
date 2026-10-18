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
import java.util.zip.GZIPInputStream

/** Offline native Mushaf. Exactly one renderer; no WebView and no network access. */
@Composable
internal fun QuranReaderScreen(modifier: Modifier = Modifier, onExit: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("atta_native_mushaf", Context.MODE_PRIVATE) }
    var page by remember { mutableIntStateOf(prefs.getInt("page", 1).coerceIn(1, 604)) }
    var indexOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val headings = remember {
        runCatching {
            val data = JSONObject(context.assets.open("mushaf/index.json").bufferedReader().use { it.readText() })
            val pages = data.getJSONArray("pages")
            buildList {
                val seen = mutableSetOf<Int>()
                for (p in 1..604) {
                    val headers = pages.optJSONObject(p)?.optJSONArray("headers") ?: continue
                    for (i in 0 until headers.length()) {
                        val h = headers.optJSONObject(i) ?: continue
                        val n = h.optInt("number")
                        if (n in 1..114 && seen.add(n)) add(Triple(n, h.optString("nameArabic"), p))
                    }
                }
            }.sortedBy { it.first }
        }.getOrDefault(emptyList())
    }
    val changePage: (Int) -> Unit = { newPage ->
        page = newPage.coerceIn(1, 604)
        prefs.edit().putInt("page", page).apply()
        error = null
    }
    BackHandler { if (indexOpen) indexOpen = false else onExit() }
    Column(modifier.fillMaxSize().background(Color(0xFFFDF7E6))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF21180F)).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onExit) { Text("رجوع", color = Color(0xFFF4D18B)) }
            TextButton(onClick = { indexOpen = true }) { Text("فهرس السور", color = Color(0xFFF4D18B)) }
            Text("$page / 604", color = Color.White)
        }
        key(page) {
            AndroidView(factory = { MushafPageView(context, page, changePage) { error = it } },
                modifier = Modifier.fillMaxWidth().weight(1f))
        }
        error?.let { Text(it, Modifier.padding(8.dp), color = Color(0xFF8E3516)) }
        Row(Modifier.fillMaxWidth().background(Color(0xFF21180F)).padding(5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { changePage(page - 1) }, enabled = page > 1) { Text("السابق", color = Color(0xFFF4D18B)) }
            Text("صفحة $page", color = Color.White, modifier = Modifier.align(Alignment.CenterVertically))
            TextButton(onClick = { changePage(page + 1) }, enabled = page < 604) { Text("التالي", color = Color(0xFFF4D18B)) }
        }
    }
    if (indexOpen) AlertDialog(onDismissRequest = { indexOpen = false },
        title = { Text("فهرس السور") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(headings) { (number, name, startPage) ->
                    Text("$number. $name — صفحة $startPage", Modifier.fillMaxWidth()
                        .clickable { changePage(startPage); indexOpen = false }.padding(10.dp))
                }
            }
        }, confirmButton = { TextButton(onClick = { indexOpen = false }) { Text("إغلاق") } })
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
        val asset = String.format(Locale.ROOT, "mushaf/pages/%03d.svg.gz", page)
        executor.execute {
            val result = runCatching {
                context.assets.open(asset).use { input -> GZIPInputStream(input).use { SVG.getFromInputStream(it) } }
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
