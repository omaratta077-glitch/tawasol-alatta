package com.alatta.nativeapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.RectF
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * يعرض الموجز غلافًا ثابتًا لصفحة أولى واحدة، مثل الصورة المصغرة للفيديو.
 * يفتح الضغط عارض صفحات عموديًا منفصلًا بملء الشاشة. لا يوجد تمرير متداخل للموجز،
 * ولا تمنع الصفحات العشر المشتركة الوصول بالتمرير إلى المنشور التالي.
 * يُعاد استخدام المعاينة المرفوعة الحالية ومرسّم SVG المحلي دون اتصال.
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
                    // صفحة واحدة بالضبط لكل سحب عمودي؛ وتبقى الصفحة داخل
                    // إطار العرض من دون فراغات بين الصفحات.
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

/** أعد استخدام مرسّم المصحف الأصلي بصيغة SVG، من دون إضافة أصول صور جديدة. */
@Composable
private fun MushafGalleryPage(page: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val width = (LocalConfiguration.current.screenWidthDp * context.resources.displayMetrics.density)
        .toInt().coerceIn(480, 1100)
    var bitmap by remember(page, width) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(page) { mutableStateOf(false) }
    DisposableEffect(page, width) {
        onDispose { bitmap?.recycle() }
    }
    LaunchedEffect(page, width) {
        bitmap = null
        failure = false
        try {
            val prepared = withContext(Dispatchers.IO) {
                val drawing = loadMushafDrawing(context, page)
                val bounds = drawing.documentViewBox
                    ?: RectF(0f, 0f, drawing.documentWidth, drawing.documentHeight)
                require(bounds.width() > 0 && bounds.height() > 0)
                val height = (width * bounds.height() / bounds.width()).toInt().coerceIn(600, 2200)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
                    val canvas = Canvas(image)
                    canvas.drawColor(AndroidColor.rgb(253, 247, 230))
                    renderMushafDrawing(canvas, drawing,
                        RectF(8f, 8f, width - 8f, height - 8f))
                }
            }
            bitmap = prepared
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure = true
        }
    }
    Box(modifier.background(Color(0xFFFDF7E6)), contentAlignment = Alignment.Center) {
        bitmap?.let { image ->
            Image(image.asImageBitmap(), "صفحة $page من المصحف", Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit)
        }
        if (failure) Text("تعذّر تحميل صفحة $page", color = Color(0xFF844424))
    }
}
