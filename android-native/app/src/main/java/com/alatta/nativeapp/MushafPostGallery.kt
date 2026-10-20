package com.alatta.nativeapp

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The feed shows ONE fixed first-page cover, like a video thumbnail.
 * Tapping opens a separate fullscreen vertical page viewer. No nested feed
 * scrolling; ten shared pages never block scrolling to the next post.
 * The existing uploaded preview and local offline SVG renderer are reused.
 */
@Composable
internal fun MushafPostGallery(
    pages: IntRange,
    coverModel: Any? = null,
    modifier: Modifier = Modifier
) {
    if (pages.first !in 1..604 || pages.last !in pages.first..604) return
    var showing by remember(pages.first, pages.last) { mutableStateOf(false) }

    Box(modifier.fillMaxWidth().height(360.dp)
        .background(Color(0xFFFDF7E6), RoundedCornerShape(12.dp))
        .clickable { showing = true }, contentAlignment = Alignment.Center) {
        if (coverModel != null) {
            AsyncImage(model = coverModel, contentDescription = "الصفحة الأولى من المصحف",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            MushafGalleryPage(pages.first, Modifier.fillMaxSize())
        }
        Text(
            "اضغط لعرض الصفحات  •  ${pages.first}–${pages.last}",
            color = Color(0xFFF6D776), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xDD080B0D))
                .padding(horizontal = 10.dp, vertical = 9.dp)
        )
    }

    if (showing) {
        Dialog(onDismissRequest = { showing = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val count = pages.last - pages.first + 1
            val pager = rememberPagerState(pageCount = { count })
            Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("صفحة ${pages.first + pager.currentPage} من ${pages.last}",
                        color = Color(0xFFF6D776), modifier = Modifier.weight(1f), fontSize = 16.sp)
                    TextButton(onClick = { showing = false }) {
                        Text("إغلاق ✕", color = Color(0xFFF6D776), fontSize = 15.sp)
                    }
                }
                VerticalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(),
                    beyondViewportPageCount = 1) { position ->
                    // Exactly one page per vertical swipe; the page stays within
                    // its viewport and does not insert gaps between pages.
                    Box(Modifier.fillMaxSize().background(Color(0xFFFDF7E6)),
                        contentAlignment = Alignment.Center) {
                        if (position == 0 && coverModel != null) {
                            AsyncImage(model = coverModel, contentDescription = "صفحة ${pages.first}",
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        } else {
                            MushafGalleryPage(pages.first + position, Modifier.fillMaxSize())
                        }
                    }
                }
                Text(if (pager.currentPage < count - 1) "اسحب لأعلى للصفحة التالية" else "نهاية الصفحات المشتركة",
                    color = Color(0xFFF6D776), fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp))
            }
        }
    }
}

/** Reuse the original SVG-based mushaf renderer, without new image assets. */
@Composable
private fun MushafGalleryPage(page: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var drawing by remember(page) { mutableStateOf<SVG?>(null) }
    LaunchedEffect(page) {
        try {
            drawing = withContext(Dispatchers.IO) { loadMushafDrawing(context, page) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            drawing = null
        }
    }
    AndroidView(factory = { ctx -> MushafGalleryPageView(ctx) },
        update = { it.drawing = drawing; it.invalidate() },
        modifier = modifier.background(Color(0xFFFDF7E6)))
}

private class MushafGalleryPageView(context: android.content.Context) : View(context) {
    var drawing: SVG? = null
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(AndroidColor.rgb(253, 247, 230))
        drawing?.let {
            renderMushafDrawing(canvas, it, RectF(8f, 8f,
                width.toFloat() - 8f, height.toFloat() - 8f))
        }
    }
}
