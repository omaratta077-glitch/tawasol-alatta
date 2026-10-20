package com.alatta.nativeapp

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Show every selected page from the existing offline mushaf, not only the post cover. */
@Composable
internal fun MushafPostGallery(pages: IntRange, modifier: Modifier = Modifier) {
    if (pages.first !in 1..604 || pages.last !in pages.first..604) return
    val context = LocalContext.current
    LazyColumn(modifier.heightIn(max = 510.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(pages.toList(), key = { it }) { page ->
            var drawing by remember(page) { mutableStateOf<SVG?>(null) }
            LaunchedEffect(page) {
                drawing = withContext(Dispatchers.IO) {
                    runCatching { loadMushafDrawing(context, page) }.getOrNull()
                }
            }
            Column(Modifier.fillMaxWidth()) {
                Text("صفحة $page", color = Color(0xFFF6D776), fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 5.dp))
                AndroidView(factory = { ctx -> MushafGalleryPageView(ctx) },
                    update = { it.drawing = drawing; it.invalidate() },
                    modifier = Modifier.fillMaxWidth().aspectRatio(.72f)
                        .border(1.dp, Color(0xFFB8944E), RoundedCornerShape(10.dp)))
            }
        }
    }
}

private class MushafGalleryPageView(context: android.content.Context) : View(context) {
    var drawing: SVG? = null
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(AndroidColor.rgb(253, 247, 230))
        drawing?.let {
            renderMushafDrawing(canvas, it, RectF(10f, 10f,
                width.toFloat() - 10f, height.toFloat() - 10f))
        }
    }
}
