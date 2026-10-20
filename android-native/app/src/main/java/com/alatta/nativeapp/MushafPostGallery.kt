package com.alatta.nativeapp

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    // Pages share the same edge: no labels, margins, borders or gaps between them.
    LazyColumn(modifier.heightIn(max = 510.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        items(pages.toList(), key = { it }) { page ->
            var drawing by remember(page) { mutableStateOf<SVG?>(null) }
            LaunchedEffect(page) {
                drawing = withContext(Dispatchers.IO) {
                    runCatching { loadMushafDrawing(context, page) }.getOrNull()
                }
            }
            // Match the bundled SVG's actual viewBox instead of forcing an incorrect
            // canvas height that leaves a large empty cream block above some pages.
            val aspectRatio = remember(drawing) {
                drawing?.let { svg ->
                    val bounds = svg.documentViewBox
                        ?: RectF(0f, 0f, svg.documentWidth, svg.documentHeight)
                    (bounds.width() / bounds.height().coerceAtLeast(1f))
                        .takeIf { it.isFinite() && it > 0f }
                } ?: 0.72f
            }
            AndroidView(factory = { ctx -> MushafGalleryPageView(ctx) },
                update = { it.drawing = drawing; it.invalidate() },
                modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                    .background(Color(0xFFFDF7E6)))
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
