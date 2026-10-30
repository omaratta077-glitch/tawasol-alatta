package com.alatta.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class MushafPostDraft(
    val selection: MushafShareSelection,
    val preview: File,
    val document: File
) {
    fun discard() { preview.delete(); document.delete() }
}

/** صدّر مسارات SVG الأساسية نفسها التي يستخدمها القارئ، من دون إعادة كتابة الآيات. */
internal suspend fun prepareMushafPost(context: Context, selection: MushafShareSelection): MushafPostDraft {
    val directory = File(context.cacheDir, "mushaf-share").apply { mkdirs() }
    val prefix = UUID.randomUUID().toString()
    val preview = File(directory, "$prefix.png")
    val document = File(directory, "$prefix.pdf")
    val pdf = PdfDocument()
    try {
        for ((position, pageNumber) in selection.pages.withIndex()) {
            currentCoroutineContext().ensureActive()
            val drawing = loadMushafDrawing(context, pageNumber)
            if (position == 0) {
                val box = drawing.documentViewBox ?: RectF(0f, 0f, drawing.documentWidth, drawing.documentHeight)
                require(box.width() > 0 && box.height() > 0) { "تعذّر تحديد أبعاد الصفحة" }
                val width = 1200
                val height = (width * box.height() / box.width()).toInt().coerceIn(600, 2400)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.rgb(253,247,230))
                    renderMushafDrawing(canvas, drawing, RectF(8f,8f,width-8f,height-8f))
                    preview.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                } finally { bitmap.recycle() }
            }
            val pdfPage = pdf.startPage(PdfDocument.PageInfo.Builder(595,842,position+1).create())
            try {
                pdfPage.canvas.drawColor(Color.rgb(253,247,230))
                renderMushafDrawing(pdfPage.canvas, drawing, RectF(16f,16f,579f,826f))
            } finally { pdf.finishPage(pdfPage) }
        }
        currentCoroutineContext().ensureActive()
        document.outputStream().use { pdf.writeTo(it) }
        require(document.length() <= 50L*1024*1024) { "ملف الصفحات أكبر من 50 ميجابايت؛ اختر نطاقًا أصغر" }
        return MushafPostDraft(selection,preview,document)
    } catch (failure: Throwable) {
        preview.delete(); document.delete(); throw failure
    } finally { pdf.close() }
}
