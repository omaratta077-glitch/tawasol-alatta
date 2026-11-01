package com.alatta.nativeapp

internal fun formatDhikrShare(group: DhikrGroup, entry: Dhikr? = null): String =
    (listOf(group.title) + (entry?.let { listOf(it) } ?: group.entries).map { item ->
        listOf(item.title, item.text, "التكرار: ${item.target}", item.source, item.note)
            .filter { it.isNotBlank() }.joinToString("\n\n")
    }).joinToString("\n\n")


/**
 * ينشئ صورة ذكر سوداء وذهبية لرفعها داخل منشور تواصل العطا نفسه.
 * لا يفتح المشاركة الخارجية ولا يرسل المحتوى قبل موافقة المستخدم على النشر.
 */
internal fun createDhikrGoldPostImage(context: android.content.Context, group: DhikrGroup, entry: Dhikr): android.net.Uri {
    val width = 1080
    val margin = 70f
    val gold = android.graphics.Color.rgb(240, 202, 111)
    val background = android.graphics.Color.rgb(8, 10, 10)
    val white = android.graphics.Color.WHITE

    fun layout(text: String, textSize: Float, textColor: Int, bold: Boolean = false):
        Pair<android.text.StaticLayout, android.text.TextPaint> {
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = textColor
            this.textSize = textSize
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
        return android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, width - 2 * margin.toInt())
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(android.text.TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .setLineSpacing(7f, 1.12f)
            .build() to paint
    }

    val brand = layout("تواصل العطا  •  AL-ATTA", 40f, gold, true)
    val title = layout(entry.title, 50f, gold, true)
    val verse = layout(entry.text, if (entry.text.length > 700) 42f else 57f, white)
    val repeat = layout("عدد التكرار: ${entry.target}", 36f, gold, true)
    val source = layout(entry.source, 31f, gold)
    val footer = layout("من ${group.title}  •  تواصل العطا", 30f, gold)
    val blocks = listOf(brand, title, verse, repeat, source, footer)
    val spaces = listOf(46f, 48f, 62f, 30f, 52f, 0f)
    val contentHeight = blocks.sumOf { it.first.height } + spaces.sum()
    val height = (contentHeight + 200f).toInt().coerceAtLeast(1080)
    require(height <= 10000) { "نص الذكر طويل جدًا للمشاركة كصورة واحدة؛ شاركه كمنشور نصّي." }

    val bitmap = android.graphics.Bitmap.createBitmap(width, height,
        android.graphics.Bitmap.Config.ARGB_8888)
    try {
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(background)
        val border = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = gold
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
        }
        val outer = android.graphics.RectF(22f, 22f, width - 22f, height - 22f)
        canvas.drawRoundRect(outer, 44f, 44f, border)
        border.strokeWidth = 1.5f
        canvas.drawRoundRect(
            android.graphics.RectF(40f, 40f, width - 40f, height - 40f), 32f, 32f, border
        )

        var y = 100f
        blocks.forEachIndexed { index, (block, _) ->
            canvas.save()
            canvas.translate(margin, y)
            block.draw(canvas)
            canvas.restore()
            y += block.height + spaces[index]
            if (index == 0 || index == 2) {
                val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = gold
                    alpha = 155
                    strokeWidth = 2f
                }
                canvas.drawLine(125f, y - 20f, width - 125f, y - 20f, line)
            }
        }

        val folder = java.io.File(context.cacheDir, "share_cards")
        check(folder.exists() || folder.mkdirs()) { "تعذر تجهيز مجلد المشاركة" }
        val image = java.io.File(folder, "alatta_dhikr_${entry.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.png")
        java.io.FileOutputStream(image).use { out ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                "تعذر حفظ بطاقة الذكر"
            }
        }
        // نفس Uri.fromFile المستخدم عند مشاركة المصحف في مُنشئ منشورات العطا.
        return android.net.Uri.fromFile(image)
    } finally {
        bitmap.recycle()
    }
}
