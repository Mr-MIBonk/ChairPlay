package io.github.numberonebot.chairplay.drive

import io.github.numberonebot.chairplay.drive.GaugeStyle.EDGE
import io.github.numberonebot.chairplay.drive.GaugeStyle.GREY
import io.github.numberonebot.chairplay.drive.GaugeStyle.HUB
import io.github.numberonebot.chairplay.drive.GaugeStyle.HUB_FILL
import io.github.numberonebot.chairplay.drive.GaugeStyle.ITALIC
import io.github.numberonebot.chairplay.drive.GaugeStyle.LABEL
import io.github.numberonebot.chairplay.drive.GaugeStyle.OPAQUE
import io.github.numberonebot.chairplay.drive.GaugeStyle.RED
import io.github.numberonebot.chairplay.drive.GaugeStyle.TICK
import io.github.numberonebot.chairplay.drive.GaugeStyle.WHITE
import io.github.numberonebot.chairplay.drive.GaugeStyle.WIDE
import org.lwjgl.nanovg.NanoVG.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One dial on its face, as Audi's: the needle's sweep and the red zone (dashed where it begins) thin along the rim,
 * short ticks with italic numbers inside them, the needle in the accent colour, and a dark hub with the reading upright
 * in its middle.
 */
internal class GaugeDial(private val pen: NanoPen, private val accent: Int) {
    private val vg = pen.vg

    /** [face] is the face's radius, and [labelSize] the numbers' size in it. */
    fun draw(cx: Float, cy: Float, face: Float, scale: GaugeScale, labelSize: Float, value: Double, digits: String, unit: String) {
        val track = face * 0.95f
        val band = face * BAND
        nvgLineCap(vg, NVG_BUTT)
        if (value > 0) pen.arc(cx, cy, track, scale.angle(0.0), scale.angle(value), band, 0x59000000 or accent)
        scale.redFrom?.let { red ->
            pen.arc(cx, cy, track, scale.angle(red), scale.angle(scale.max), band, RED_ZONE)
            scale.warnFrom?.let { dashes(cx, cy, track, scale.angle(it), scale.angle(red), band, face) }
        }
        for (tick in scale.ticks()) {
            val angle = scale.angle(tick.value)
            val red = scale.redFrom?.let { tick.value >= it } == true
            val (from, width) = when (tick.size) {
                GaugeScale.TickSize.MAJOR -> MAJOR_FROM to 0.016f
                GaugeScale.TickSize.MIDDLE -> MIDDLE_FROM to 0.008f
                GaugeScale.TickSize.FINE -> FINE_FROM to 0.005f
            }
            nvgBeginPath(vg)
            pen.ray(cx, cy, angle, face * from, face * TICKS_TO)
            nvgStrokeWidth(vg, face * width)
            pen.stroke(if (red) RED else if (tick.major) WHITE else TICK)
            if (tick.major) label(tick.value.roundToInt().toString(), cx, cy, angle, face * MAJOR_FROM, face * labelSize, if (red) RED else LABEL)
        }
        needle(cx, cy, face, scale.angle(value))
        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, face * HUB)
        pen.fill(HUB_FILL)
        nvgStrokeWidth(vg, 1.5f)
        pen.stroke(EDGE)
        pen.text(digits, cx, cy - face * 0.05f, face * 0.28f, WHITE, WIDE)
        pen.text(unit, cx, cy + face * 0.21f, face * 0.08f, GREY, WIDE)
    }

    /** The red zone's start, dashed from [from] to [to]: dashes and gaps of even length, a dash at each end. */
    private fun dashes(cx: Float, cy: Float, radius: Float, from: Double, to: Double, width: Float, face: Float) {
        val count = maxOf(2, ((to - from) * radius / (face * DASH * 2)).roundToInt())
        val part = (to - from) / (2 * count - 1)
        for (i in 0 until count) pen.arc(cx, cy, radius, from + 2 * i * part, from + (2 * i + 1) * part, width, RED_ZONE)
    }

    /**
     * A scale's number on the ray at [angle], a gap inside the ticks' inner end [edge], set back by its width and height
     * along that ray: the wide numbers at the sides stand as far from their ticks as the low ones at the top.
     */
    private fun label(text: String, cx: Float, cy: Float, angle: Double, edge: Float, size: Float, argb: Int) {
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        val halfWidth = pen.textWidth(text, size, ITALIC) / 2
        val at = edge - size * LABEL_GAP - (halfWidth * abs(dx) + size * HALF_CAP * abs(dy))
        pen.text(text, cx + dx * at, cy + dy * at, size, argb, ITALIC)
    }

    /** A needle from the hub's rim to the scale, tapering, in the accent colour with a faint glow. */
    private fun needle(cx: Float, cy: Float, face: Float, angle: Double) {
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        val base = face * (HUB + 0.02f)
        val tip = face * 0.9f
        nvgLineCap(vg, NVG_ROUND)
        nvgBeginPath(vg)
        nvgMoveTo(vg, cx + dx * base, cy + dy * base)
        nvgLineTo(vg, cx + dx * tip, cy + dy * tip)
        nvgStrokeWidth(vg, face * 0.05f)
        pen.stroke(0x40000000 or accent)
        val wide = face * 0.018f
        val narrow = face * 0.004f
        nvgBeginPath(vg)
        nvgMoveTo(vg, cx + dx * base - dy * wide, cy + dy * base + dx * wide)
        nvgLineTo(vg, cx + dx * tip - dy * narrow, cy + dy * tip + dx * narrow)
        nvgLineTo(vg, cx + dx * tip + dy * narrow, cy + dy * tip - dx * narrow)
        nvgLineTo(vg, cx + dx * base + dy * wide, cy + dy * base - dx * wide)
        nvgClosePath(vg)
        pen.fill(OPAQUE or accent)
        nvgLineCap(vg, NVG_BUTT)
    }

    private companion object {
        /** The sweep's and the red zone's width, and a dash of the red zone's start, in the face's radius. */
        const val BAND = 0.0175f
        const val DASH = 0.03f
        val RED_ZONE = 0xD9000000.toInt() or (RED and 0xFFFFFF)
        /** Where the ticks run in the face's radius: short, to leave the numbers room. */
        const val TICKS_TO = 0.88f
        const val MAJOR_FROM = TICKS_TO - 0.16f / 3
        const val MIDDLE_FROM = TICKS_TO - 0.09f / 3
        const val FINE_FROM = TICKS_TO - 0.018f
        /** The numbers' gap from the ticks, and half a digit's height, in the numbers' size. */
        const val LABEL_GAP = 0.35f
        const val HALF_CAP = 0.36f
    }
}

/** What the gauges share: their fonts, their face and hub, and their colours, the controls window's knob's among them. */
internal object GaugeStyle {
    const val WIDE = "wide"
    const val ITALIC = "wide-italic"
    /** The face inside the bezel, and the hub in the face, in their radii. */
    const val FACE = 0.9f
    const val HUB = 0.456f
    const val OPAQUE = 0xFF000000.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val GREY = 0xFF9A9AA0.toInt()
    const val LABEL = 0xFFD1D1D6.toInt()
    const val TICK = 0xFF8E8E93.toInt()
    const val RED = 0xFFFF453A.toInt()
    // The controls window's knob: --panel, --edge and the pad's light.
    const val PANEL = 0xFF111113.toInt()
    const val EDGE = 0xFF3A3A3F.toInt()
    const val FACE_LIGHT = 0xFF29292D.toInt()
    const val HUB_FILL = 0xFF0B0B0D.toInt()
    const val TRACK = 0xFF2A2A2E.toInt()
}
