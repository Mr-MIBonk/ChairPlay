package io.github.numberonebot.chairplay.drive

import io.github.numberonebot.chairplay.drive.GaugeStyle.CAPTION
import io.github.numberonebot.chairplay.drive.GaugeStyle.EDGE
import io.github.numberonebot.chairplay.drive.GaugeStyle.HUB_FILL
import io.github.numberonebot.chairplay.drive.GaugeStyle.REGULAR
import org.lwjgl.nanovg.NanoVG.*
import java.util.Locale
import kotlin.math.roundToInt

/** Position and heading inset in the tachometer, with the same arrow as the GPS page's compass. */
internal fun gaugePosition(pen: NanoPen, centerX: Float, y: Float, height: Float, position: GeoPoint, headingDegrees: Double, compass: Boolean) {
    val vg = pen.vg
    val size = height * 0.24f
    val degrees = (headingDegrees.roundToInt() % 360 + 360) % 360
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val point = points[((degrees + 22.5) / 45).toInt() % points.size]
    val lines = listOf(position.latitude, position.longitude).map { String.format(Locale.ROOT, "%.5f", it) }
    val arrowWidth = size * 0.6f
    val reserved = arrowWidth + size * 0.4f
    // Reserve the widest possible coordinate and heading, rather than measuring the current readings.
    val digit = ('0'..'9').maxBy { pen.textWidth(it.toString(), size, REGULAR) }.toString()
    val coordinateWidth = pen.textWidth("-${digit.repeat(3)}.${digit.repeat(5)}", size, REGULAR)
    val pointSize = size * 0.65f
    val degreesWidth = pen.textWidth("${digit.repeat(3)}°", size, REGULAR)
    val pointWidth = points.maxOf { pen.textWidth(it, pointSize, REGULAR) }
    val gap = size * 0.25f
    val headingWidth = degreesWidth + gap + pointWidth
    val width = maxOf(coordinateWidth, headingWidth + reserved) + height * 0.26f
    val x = centerX - width / 2
    val corner = height * 0.12f
    nvgBeginPath(vg)
    nvgRoundedRect(vg, x, y, width, height, corner)
    pen.fill(HUB_FILL)
    nvgBoxGradient(vg, x, y + height * 0.1f, width, height, corner, height * 0.3f, pen.rgba(0, pen.inner), pen.rgba(0xCC000000.toInt(), pen.outer), pen.paint)
    nvgFillPaint(vg, pen.paint)
    nvgFill(vg)
    nvgStrokeWidth(vg, 1.5f)
    pen.stroke(EDGE)
    for ((i, line) in lines.withIndex()) {
        pen.text(line, centerX, y + height * (0.24f + 0.29f * i), size, CAPTION, REGULAR)
    }
    val angle = "$degrees°"
    val angleWidth = pen.textWidth(angle, size, REGULAR)
    val labelWidth = pen.textWidth(point, pointSize, REGULAR)
    val arrowSpace = if (compass) reserved else 0f
    val rowX = centerX - (arrowSpace + angleWidth + gap + labelWidth) / 2
    val headingX = rowX + arrowSpace
    pen.text(angle, headingX, y + height * 0.82f, size, CAPTION, REGULAR, NVG_ALIGN_LEFT)
    pen.text(point, headingX + angleWidth + gap, y + height * 0.82f, pointSize, CAPTION, REGULAR, NVG_ALIGN_LEFT)
    if (!compass) return
    nvgSave(vg)
    nvgTranslate(vg, rowX + arrowWidth / 2, y + height * 0.79f)
    nvgRotate(vg, Math.toRadians(headingDegrees).toFloat())
    nvgScale(vg, size / 20f, size / 20f)
    nvgBeginPath(vg)
    nvgMoveTo(vg, 0f, -8.5f)
    nvgLineTo(vg, 4.5f, 6.3f)
    nvgLineTo(vg, 0f, 3.6f)
    nvgLineTo(vg, -4.5f, 6.3f)
    nvgClosePath(vg)
    nvgLineJoin(vg, NVG_ROUND)
    nvgStrokeWidth(vg, 1.3f)
    pen.stroke(CAPTION)
    nvgRestore(vg)
}
