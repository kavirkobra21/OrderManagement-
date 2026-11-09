package ir.kavir.ordernotebook.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import ir.kavir.ordernotebook.util.Fmt

/** Everything that is written on the notebook page. */
data class PageData(
    val date: String = "",
    val invoice: String = "",
    val name: String = "",
    val phone: String = "",
    val address: String = "",
    val delivery: String = "",
    val notes: String = "",
    val description: String = "",
    val total: Long = 0,
    val deposit: Long = 0,
    val remaining: Long = 0,
    val ready: Boolean = false,
    val settled: Boolean = false,
    val delivered: Boolean = false,
)

/**
 * Draws the order-notebook page (pastel blue sheet, yellow/green/pink areas, speech-bubble labels,
 * stickers) in a fixed 1000 x 1400 coordinate space and writes the customer data into the
 * fields. The same code produces the on-screen page and the exported high-resolution image,
 * so what you see is exactly what is sent.
 */
object NotebookPage {
    const val W = 1000f
    const val H = 1400f

    private const val INK = 0xFF3B3A3F.toInt()
    private val BLUE = Color.parseColor("#C8E0EA")
    private val YELLOW = Color.parseColor("#EFEDA8")
    private val YELLOW_SOFT = Color.parseColor("#F6F4C6")
    private val GREEN = Color.parseColor("#A8D2A8")
    private val GREEN_LABEL = Color.parseColor("#BFE5C2")
    private val PINK = Color.parseColor("#F2B6CB")
    private val PINK_DARK = Color.parseColor("#E98DAB")

    fun render(d: PageData, scale: Float = 1f): Bitmap {
        val bmp = Bitmap.createBitmap((W * scale).toInt(), (H * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        draw(c, d)
        return bmp
    }

    // ---------------------------------------------------------------- paints
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private fun stroke(color: Int, w: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.color = color; strokeWidth = w; strokeJoin = Paint.Join.ROUND }

    private fun softBlob(c: Canvas, r: RectF, radius: Float, color: Int) {
        // lighter feathered edge, then the solid body (like the printed gradient in the notebook)
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = YELLOW_SOFT.takeIf { color == YELLOW } ?: color
            maskFilter = android.graphics.BlurMaskFilter(18f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            alpha = 150
        }
        c.drawRoundRect(RectF(r.left - 8, r.top - 8, r.right + 8, r.bottom + 8), radius, radius, halo)
        val inner = RectF(r.left + 6, r.top + 6, r.right - 6, r.bottom - 6)
        c.drawRoundRect(inner, radius, radius, fill(color))
    }

    private fun stickerOutlineText(c: Canvas, text: String, x: Float, baseline: Float, size: Float, align: Paint.Align = Paint.Align.RIGHT, outline: Int = Color.WHITE) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = align
            style = Paint.Style.STROKE; strokeWidth = size * 0.22f; color = outline; strokeJoin = Paint.Join.ROUND
        }
        c.drawText(text, x, baseline, p)
        p.style = Paint.Style.FILL; p.color = INK
        c.drawText(text, x, baseline, p)
    }

    private fun bubble(c: Canvas, r: RectF, tailLeft: Boolean, color: Int = Color.WHITE) {
        val path = Path()
        path.addRoundRect(r, r.height() * 0.42f, r.height() * 0.42f, Path.Direction.CW)
        val tx = if (tailLeft) r.left + r.width() * 0.22f else r.right - r.width() * 0.22f
        val t = Path().apply {
            moveTo(tx - 16, r.bottom - 8); lineTo(tx + (if (tailLeft) -26f else 26f), r.bottom + 34); lineTo(tx + 24, r.bottom - 10); close()
        }
        path.addPath(t)
        c.save(); c.translate(3f, 5f)
        c.drawPath(path, fill(Color.argb(55, 0, 0, 0)))
        c.restore()
        c.drawPath(path, fill(color))
        c.drawPath(path, stroke(Color.WHITE, 5f))
    }

    // ----------------------------------------------------------------- text
    /** Draws [text] inside [r], shrinking font until it fits (never leaves the box). */
    private fun fitText(
        c: Canvas, text: String, r: RectF, maxSize: Float, minSize: Float = 14f,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, bold: Boolean = false,
        centerV: Boolean = false, color: Int = INK,
    ) {
        if (text.isBlank()) return
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }
        var size = maxSize
        var layout: StaticLayout
        while (true) {
            p.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, p, r.width().toInt())
                .setAlignment(align)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                .setLineSpacing(0f, 1.12f)
                .build()
            if (layout.height <= r.height() || size <= minSize) break
            size -= 2f
        }
        c.save()
        c.clipRect(r)
        val dy = if (centerV) (r.height() - layout.height) / 2f else 0f
        c.translate(r.left, r.top + dy.coerceAtLeast(0f))
        layout.draw(c)
        c.restore()
    }

    private fun dottedLine(c: Canvas, x1: Float, x2: Float, y: Float) {
        val p = stroke(Color.argb(110, 90, 90, 60), 2.5f).apply {
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 9f), 0f); strokeCap = Paint.Cap.ROUND
        }
        c.drawLine(x1, y, x2, y, p)
    }

    // ---------------------------------------------------------------- stickers
    private fun heart(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val p = Path().apply {
            moveTo(cx, cy + s * 0.9f)
            cubicTo(cx - s * 1.6f, cy - s * 0.1f, cx - s * 0.7f, cy - s * 1.1f, cx, cy - s * 0.3f)
            cubicTo(cx + s * 0.7f, cy - s * 1.1f, cx + s * 1.6f, cy - s * 0.1f, cx, cy + s * 0.9f)
            close()
        }
        c.drawPath(p, stroke(Color.WHITE, 8f)); c.drawPath(p, fill(color))
    }

    private fun envelope(c: Canvas, x: Float, y: Float) {
        val r = RectF(x, y, x + 90, y + 62)
        c.drawRoundRect(r, 6f, 6f, fill(Color.parseColor("#FCEFE6")))
        c.drawRoundRect(r, 6f, 6f, stroke(Color.parseColor("#E9A8A0"), 4f))
        val v = Path().apply { moveTo(x, y + 4); lineTo(x + 45, y + 36); lineTo(x + 90, y + 4) }
        c.drawPath(v, stroke(Color.parseColor("#E9A8A0"), 4f))
        heart(c, x + 66, y + 10, 8f, Color.parseColor("#E8556F"))
    }

    private fun notepadSticker(c: Canvas, x: Float, y: Float) {
        c.save(); c.rotate(-8f, x + 45, y + 55)
        val r = RectF(x, y, x + 95, y + 110)
        c.drawRoundRect(r, 6f, 6f, fill(Color.WHITE)); c.drawRoundRect(r, 6f, 6f, stroke(Color.parseColor("#B9C4D8"), 3f))
        val l = stroke(Color.parseColor("#7C8DB0"), 3f)
        for (i in 0 until 5) c.drawLine(x + 12, y + 26 + i * 16, x + 82, y + 26 + i * 16, l)
        c.restore()
    }

    private fun phoneSticker(c: Canvas, x: Float, y: Float) {
        c.save(); c.rotate(-20f, x + 45, y + 35)
        c.drawRoundRect(RectF(x, y, x + 90, y + 66), 8f, 8f, fill(Color.parseColor("#7C8190")))
        c.drawRoundRect(RectF(x + 8, y + 8, x + 82, y + 58), 4f, 4f, fill(Color.parseColor("#C6CBD8")))
        c.restore()
    }

    private fun polaroid(c: Canvas, x: Float, y: Float, rot: Float, tint: Int) {
        c.save(); c.rotate(rot, x + 40, y + 45)
        val r = RectF(x, y, x + 84, y + 96)
        c.drawRect(r, fill(Color.WHITE)); c.drawRect(r, stroke(Color.parseColor("#C9D3DC"), 3f))
        c.drawRect(RectF(x + 8, y + 8, x + 76, y + 70), fill(tint))
        c.restore()
    }

    private fun bookSticker(c: Canvas, x: Float, y: Float) {
        c.save(); c.rotate(-12f, x + 50, y + 40)
        val r = RectF(x, y, x + 100, y + 80)
        c.drawRoundRect(r, 8f, 8f, fill(Color.parseColor("#BFE3D2"))); c.drawRoundRect(r, 8f, 8f, stroke(Color.parseColor("#7FB59B"), 4f))
        c.drawRoundRect(RectF(x + 18, y + 14, x + 82, y + 66), 6f, 6f, fill(Color.parseColor("#EAF6EE")))
        c.restore()
    }

    private fun coffeeCup(c: Canvas, x: Float, y: Float) {
        // steam
        val steam = stroke(Color.WHITE, 7f).apply { strokeCap = Paint.Cap.ROUND }
        val sp = Path().apply { moveTo(x + 62, y); cubicTo(x + 40, y + 20, x + 84, y + 34, x + 62, y + 56) }
        c.drawPath(sp, steam)
        // saucer + cup
        c.drawOval(RectF(x - 6, y + 112, x + 150, y + 146), fill(Color.parseColor("#B7C7E4")))
        c.drawOval(RectF(x + 14, y + 66, x + 122, y + 100), fill(Color.parseColor("#6B4A3A")))
        val cup = Path().apply {
            moveTo(x + 14, y + 83); lineTo(x + 26, y + 124); quadTo(x + 68, y + 140, x + 110, y + 124); lineTo(x + 122, y + 83); close()
        }
        c.drawPath(cup, fill(Color.parseColor("#DCE6F6"))); c.drawPath(cup, stroke(Color.parseColor("#8FA4CC"), 4f))
        c.drawArc(RectF(x + 112, y + 86, x + 150, y + 118), -90f, 180f, false, stroke(Color.parseColor("#8FA4CC"), 6f))
        // beans
        val bean = fill(Color.parseColor("#9B4B2F"))
        c.drawOval(RectF(x + 150, y + 96, x + 178, y + 120), bean)
        c.drawOval(RectF(x + 160, y + 120, x + 188, y + 144), bean)
    }

    private fun trees(c: Canvas, left: Float, right: Float, base: Float) {
        val n = 7
        val step = (right - left) / n
        val p = fill(Color.argb(95, 190, 205, 150))
        for (i in 0 until n) {
            val x = left + step * (i + 0.5f)
            val h = 90f + (i % 3) * 14f
            c.drawOval(RectF(x - 28, base - h, x + 28, base - h + 96), p)
            c.drawRect(RectF(x - 4, base - 26, x + 4, base), p)
        }
    }

    private fun flower(c: Canvas, x: Float, y: Float) {
        c.drawLine(x, y + 12, x, y + 70, stroke(Color.parseColor("#7FB06F"), 5f))
        c.drawOval(RectF(x - 18, y - 12, x + 18, y + 24), fill(Color.parseColor("#E8556F")))
    }

    // ------------------------------------------------------------------- page
    fun draw(c: Canvas, d: PageData) {
        // sheet
        c.drawRect(0f, 0f, W, H, fill(BLUE))
        c.drawRoundRect(RectF(10f, 10f, W - 10f, H - 10f), 40f, 40f, stroke(Color.argb(70, 255, 255, 255), 6f))

        // --- header
        flower(c, 600f, 20f); flower(c, 680f, 36f)
        val title = RectF(330f, 55f, 650f, 160f)
        c.drawRoundRect(title, 46f, 46f, fill(GREEN_LABEL)); c.drawRoundRect(title, 46f, 46f, stroke(Color.WHITE, 6f))
        stickerOutlineText(c, "مشخصات", 490f, 105f, 40f, Paint.Align.CENTER, GREEN_LABEL)
        stickerOutlineText(c, "سفارش دهنده", 490f, 148f, 40f, Paint.Align.CENTER, GREEN_LABEL)

        // date bubble (top right)
        val dateB = RectF(680f, 40f, 965f, 160f)
        bubble(c, dateB, tailLeft = true)
        heart(c, 905f, 30f, 22f, Color.parseColor("#E8556F"))
        stickerOutlineText(c, "تاریخ:", 945f, 112f, 40f)
        fitText(c, d.date, RectF(700f, 66f, 835f, 140f), 36f, 16f, Layout.Alignment.ALIGN_CENTER, centerV = true)

        // invoice bubble (top left)
        val invB = RectF(150f, 55f, 360f, 165f)
        bubble(c, invB, tailLeft = false)
        envelope(c, 35f, 40f)
        c.drawCircle(60f, 130f, 36f, fill(Color.WHITE)); stickerOutlineText(c, "Hello", 60f, 138f, 22f, Paint.Align.CENTER)
        stickerOutlineText(c, "شماره فاکتور:", 345f, 100f, 31f)
        fitText(c, d.invoice, RectF(165f, 108f, 345f, 150f), 34f, 16f, Layout.Alignment.ALIGN_CENTER, centerV = true)
        notepadSticker(c, 60f, 190f)

        // --- yellow info area
        softBlob(c, RectF(30f, 185f, 970f, 660f), 90f, YELLOW)

        stickerOutlineText(c, "اسم", 940f, 255f, 40f, outline = YELLOW)
        stickerOutlineText(c, "سفارش دهنده:", 940f, 300f, 40f, outline = YELLOW)
        fitText(c, d.name, RectF(430f, 236f, 700f, 312f), 40f, 16f, centerV = true)
        dottedLine(c, 430f, 700f, 316f)

        stickerOutlineText(c, "شماره تماس:", 940f, 420f, 40f, outline = YELLOW)
        fitText(c, d.phone, RectF(520f, 372f, 765f, 432f), 38f, 16f, Layout.Alignment.ALIGN_CENTER, centerV = true)
        dottedLine(c, 520f, 765f, 436f)

        stickerOutlineText(c, "وقت تحویل :", 440f, 330f, 36f, outline = YELLOW)
        fitText(c, d.delivery, RectF(70f, 276f, 325f, 346f), 34f, 14f, centerV = true)
        dottedLine(c, 70f, 325f, 350f)

        stickerOutlineText(c, "آدرس:", 505f, 438f, 40f, outline = YELLOW)
        fitText(c, d.address, RectF(70f, 380f, 495f, 640f), 34f, 14f)
        phoneSticker(c, 770f, 560f)

        // --- notes (green)
        polaroid(c, 28f, 690f, -6f, Color.parseColor("#A9D4EA"))
        polaroid(c, 118f, 668f, 8f, Color.parseColor("#F2B3C3"))
        softBlob(c, RectF(55f, 740f, 575f, 1010f), 36f, GREEN)
        stickerOutlineText(c, "نکات", 600f, 725f, 40f, outline = BLUE)
        stickerOutlineText(c, "سفارش:", 600f, 770f, 40f, outline = BLUE)
        fitText(c, d.notes, RectF(85f, 765f, 545f, 985f), 32f, 14f)
        bookSticker(c, 20f, 905f)
        c.drawCircle(437f, 690f, 24f, stroke(Color.WHITE, 5f)); c.drawCircle(487f, 690f, 24f, stroke(Color.WHITE, 5f))

        // --- payments (pink)
        val panel = RectF(595f, 700f, 990f, 1020f)
        c.drawRoundRect(RectF(panel.left - 8, panel.top - 8, panel.right + 8, panel.bottom + 8), 70f, 70f,
            fill(Color.argb(120, 244, 200, 215)).apply { maskFilter = android.graphics.BlurMaskFilter(14f, android.graphics.BlurMaskFilter.Blur.NORMAL) })
        c.drawRoundRect(panel, 66f, 66f, fill(PINK))
        val payB = RectF(790f, 655f, 975f, 735f)
        bubble(c, payB, tailLeft = true)
        stickerOutlineText(c, "پرداخت‌ها", 955f, 710f, 38f)

        val labels = listOf("هزینه کل:", "بیعانه:", "باقی‌مانده:")
        val vals = listOf(d.total, d.deposit, d.remaining)
        for (i in 0 until 3) {
            val top = 760f + i * 75f
            val pill = RectF(622f, top, 800f, top + 58f)
            c.drawRoundRect(pill, 29f, 29f, fill(Color.WHITE))
            val text = if (vals[i] > 0 || i == 2 && d.total > 0) Fmt.money(vals[i]) else ""
            fitText(c, text, RectF(pill.left + 12, pill.top + 4, pill.right - 12, pill.bottom - 4), 32f, 14f, Layout.Alignment.ALIGN_CENTER, centerV = true)
            stickerOutlineText(c, labels[i], 975f, top + 42f, 33f, outline = PINK)
        }
        val btnLabels = listOf("تحویل شد" to d.delivered, "تسویه شد" to d.settled, "آماده شد" to d.ready)
        for ((i, b) in btnLabels.withIndex()) {
            val x = 610f + i * 124f
            val r = RectF(x, 972f, x + 116f, 1022f)
            c.drawRoundRect(r, 25f, 25f, fill(if (b.second) Color.parseColor("#8CD19B") else Color.parseColor("#F8CFDC")))
            c.drawRoundRect(r, 25f, 25f, stroke(Color.WHITE, 3.5f))
            stickerOutlineText(c, (if (b.second) "✓ " else "") + b.first, r.centerX(), r.centerY() + 11f, 27f, Paint.Align.CENTER,
                outline = if (b.second) Color.parseColor("#8CD19B") else Color.parseColor("#F8CFDC"))
        }

        // --- order description
        softBlob(c, RectF(30f, 1055f, 970f, 1385f), 80f, YELLOW)
        val descB = RectF(730f, 1020f, 960f, 1105f)
        bubble(c, descB, tailLeft = true)
        stickerOutlineText(c, "شرح سفارش", 940f, 1075f, 38f)
        c.save(); c.rotate(8f, 670f, 1050f)
        c.drawRect(RectF(630f, 1020f, 700f, 1090f), fill(Color.WHITE))
        c.restore()
        trees(c, 230f, 920f, 1365f)
        fitText(c, d.description, RectF(190f, 1125f, 935f, 1345f), 34f, 14f)
        coffeeCup(c, 50f, 1235f)
    }
}
