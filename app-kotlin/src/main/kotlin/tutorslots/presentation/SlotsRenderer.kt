package tutorslots.presentation

import tutorslots.core.Interval
import tutorslots.core.ReportWriter
import tutorslots.domain.SlotsView
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/** Draws the free slots as a PNG card (FR-13). Works headless, no window needed. */
class SlotsRenderer(private val title: String = "Свободные окна для занятий") {

    private object Palette {
        val background1 = Color(0xEEF1F8)
        val background2 = Color(0xE3E9F5)
        val card = Color(0xFFFFFF)
        val shadow = Color(0, 0, 0, 22)
        val ink = Color(0x1C2233)
        val muted = Color(0x8A91A3)
        val divider = Color(0xECEEF3)
        val dayChipFill = Color(0xF2F4F8)
        val chipFill = Color(0xE3F5EA)
        val chipText = Color(0x13784A)
        val chipBorder = Color(0xBFE6CF)
        val emptyText = Color(0xB3B8C5)
        val accent = Color(0x1E9E63)
    }

    private val family: String = run {
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        listOf("Segoe UI", "Inter", "Fira Sans", "Roboto", "DejaVu Sans").firstOrNull { it in available } ?: Font.SANS_SERIF
    }

    private fun font(style: Int, size: Int) = Font(family, style, size)

    private val dayNames = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")

    private companion object {
        const val WIDTH = 1080
        const val MARGIN = 40           // outer margin around the card
        const val PAD = 44              // padding inside the card
        const val DAY_COL = 210         // width of the day-name column
        const val CHIP_H = 52
        const val CHIP_GAP = 12
        const val ROW_PAD = 18
    }

    fun render(view: SlotsView): ByteArray {
        val chipsWidth = WIDTH - 2 * MARGIN - 2 * PAD - DAY_COL
        // First pass: measure rows so the image height fits the content.
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().also(::setupHints)
        val rows = view.plan.map { layoutChips(probe, it.intervals, chipsWidth) }
        probe.dispose()
        val rowHeights = rows.map { lines -> maxOf(1, lines.size) * (CHIP_H + CHIP_GAP) - CHIP_GAP + 2 * ROW_PAD }
        val headerH = 150
        val footerH = 124
        val cardH = PAD + headerH + rowHeights.sum() + footerH
        val height = cardH + 2 * MARGIN

        val image = BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        setupHints(g)

        g.paint = GradientPaint(0f, 0f, Palette.background1, WIDTH.toFloat(), height.toFloat(), Palette.background2)
        g.fillRect(0, 0, WIDTH, height)

        // Card with a soft shadow
        val cardX = MARGIN.toDouble()
        val cardY = MARGIN.toDouble()
        val cardW = (WIDTH - 2 * MARGIN).toDouble()
        for (i in 1..6) {
            g.color = Color(0, 0, 0, Palette.shadow.alpha / i)
            g.fill(RoundRectangle2D.Double(cardX - i, cardY - i + 6, cardW + 2 * i, cardH + 2.0 * i, 40.0, 40.0))
        }
        g.color = Palette.card
        g.fill(RoundRectangle2D.Double(cardX, cardY, cardW, cardH.toDouble(), 36.0, 36.0))

        val left = MARGIN + PAD
        var y = MARGIN + PAD

        // Header
        g.color = Palette.accent
        g.fillRoundRect(left, y + 4, 8, 50, 8, 8)
        g.color = Palette.ink
        g.font = font(Font.BOLD, 44)
        g.drawString(title, left + 26, y + 46)
        g.font = font(Font.PLAIN, 26)
        g.color = Palette.muted
        g.drawString("Время указано для: ${view.zoneLabel} (${ReportWriter.formatOffset(view.offsetMinutes)})", left + 26, y + 96)
        y += headerH

        // Day rows
        val chipsLeft = left + DAY_COL
        view.plan.forEachIndexed { index, day ->
            val rowH = rowHeights[index]
            g.color = Palette.divider
            g.stroke = BasicStroke(2f)
            g.drawLine(left, y, WIDTH - MARGIN - PAD, y)

            val firstChipCenter = y + ROW_PAD + CHIP_H / 2
            g.font = font(Font.BOLD, 28)
            g.color = if (day.day >= 5) Palette.accent else Palette.ink
            drawCenteredY(g, dayNames[day.day], left, firstChipCenter)

            if (day.intervals.isEmpty()) {
                g.font = font(Font.PLAIN, 26)
                g.color = Palette.emptyText
                drawCenteredY(g, "нет свободных окон", chipsLeft, firstChipCenter)
            } else {
                rows[index].forEachIndexed { line, chips ->
                    var x = chipsLeft
                    val chipY = y + ROW_PAD + line * (CHIP_H + CHIP_GAP)
                    for ((interval, w) in chips) {
                        drawChip(g, interval, x, chipY, w)
                        x += w + CHIP_GAP
                    }
                }
            }
            y += rowH
        }
        g.color = Palette.divider
        g.drawLine(left, y, WIDTH - MARGIN - PAD, y)

        // Footer
        y += 46
        g.font = font(Font.PLAIN, 22)
        g.color = Palette.muted
        val limit = if (view.earliestStart > 0) "Показаны окна с ${ReportWriter.formatTime(view.earliestStart)} по вашему времени  ·  " else ""
        g.drawString("${limit}одно занятие = 1 час", left, y)
        y += 38
        val moscow = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Moscow"))
        val freshness = if (view.fromCache) "Таблица недоступна, данные от" else "Данные актуальны на"
        g.drawString("$freshness ${moscow.format(view.loadedAt)} МСК", left, y)

        g.dispose()
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    private fun chipLabel(iv: Interval) = "${ReportWriter.formatTime(iv.startMinute)} – ${ReportWriter.formatTime(iv.endMinute)}"

    /** Splits a day's chips into lines that fit [maxWidth]. Each chip is paired with its width. */
    private fun layoutChips(g: Graphics2D, intervals: List<Interval>, maxWidth: Int): List<List<Pair<Interval, Int>>> {
        g.font = font(Font.BOLD, 26)
        val lines = mutableListOf<MutableList<Pair<Interval, Int>>>()
        var used = 0
        for (iv in intervals) {
            val w = g.fontMetrics.stringWidth(chipLabel(iv)) + 44
            if (lines.isEmpty() || used + CHIP_GAP + w > maxWidth) {
                lines.add(mutableListOf())
                used = -CHIP_GAP
            }
            lines.last().add(iv to w)
            used += CHIP_GAP + w
        }
        return lines
    }

    private fun drawChip(g: Graphics2D, iv: Interval, x: Int, y: Int, w: Int) {
        val shape = RoundRectangle2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), CHIP_H.toDouble(), 26.0, 26.0)
        g.color = Palette.chipFill
        g.fill(shape)
        g.color = Palette.chipBorder
        g.stroke = BasicStroke(2f)
        g.draw(shape)
        g.font = font(Font.BOLD, 26)
        g.color = Palette.chipText
        drawCenteredY(g, chipLabel(iv), x + 22, y + CHIP_H / 2)
    }

    private fun drawCenteredY(g: Graphics2D, text: String, x: Int, centerY: Int) {
        val fm = g.fontMetrics
        g.drawString(text, x, centerY + (fm.ascent - fm.descent) / 2)
    }

    private fun setupHints(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }
}
