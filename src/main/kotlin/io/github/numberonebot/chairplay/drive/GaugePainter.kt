package io.github.numberonebot.chairplay.drive

import io.github.numberonebot.chairplay.media.WindowOverlay
import org.lwjgl.nanovg.NVGColor
import org.lwjgl.nanovg.NVGPaint
import org.lwjgl.nanovg.NanoVG.*
import org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS
import org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES
import org.lwjgl.nanovg.NanoVGGL3.nvgCreate
import org.lwjgl.nanovg.NanoVGGL3.nvgDelete
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The GPS car's speedometer and tachometer over the AltScreen, as Audi's Virtual Cockpit lays them out: the speed on
 * the left, the engine on the right, each a round dial of thin ticks and round numbers with a red zone, a needle in
 * the accent colour, and the speed or the gear in digits in its middle. Each sits in a well finished as the controls
 * window's knob: a knurled bezel lit from the top left round a dark face lit from above. The pedals show as bars in the
 * dials' open bottoms: the brake under the speed, the throttle under the engine. Nothing is drawn while [view] is null.
 */
class GaugePainter(
    private val view: () -> GaugeView?,
    private val log: (String) -> Unit = {},
    /** ui.accent as 0xRRGGBB: the needles and their sweep. */
    accent: Int = 0x0A84FF,
) : WindowOverlay {
    private val accent = accent and 0xFFFFFF
    private var vg = 0L
    private var fonts = false
    // NanoVG reads the font files from these buffers for as long as the context lives.
    private val fontData = mutableListOf<ByteBuffer>()
    private val color = NVGColor.create()
    private val inner = NVGColor.create()
    private val outer = NVGColor.create()
    private val paint = NVGPaint.create()

    override fun open() {
        vg = nvgCreate(NVG_ANTIALIAS or NVG_STENCIL_STROKES)
        check(vg != 0L) { "cannot create the gauges' NanoVG context" }
        fun font(name: String, file: String): Boolean {
            val bytes = GaugePainter::class.java.getResourceAsStream("/fonts/$file")?.use { it.readBytes() } ?: return false
            val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip()
            fontData += buffer
            return nvgCreateFontMem(vg, name, buffer, false) >= 0
        }
        fonts = font(REGULAR, "Inter-Regular.ttf") && font(WIDE, "RobotoFlexWide-SemiBold.ttf")
        if (!fonts) log("gauges: the fonts are missing from the app; the gauges draw no numbers")
    }

    override fun close() {
        if (vg != 0L) nvgDelete(vg)
        vg = 0L
        fontData.clear()
    }

    override fun paint(width: Int, height: Int) {
        val now = view() ?: return
        if (vg == 0L || width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(h * 0.46f, w * 0.17f)
        val margin = h * 0.04f
        nvgBeginFrame(vg, w, h, 1f)
        dial(margin + r, h / 2, r, now.speedometer, now.speedKmh, now.speedKmh.roundToInt().toString(), "km/h", now.brake, RED)
        dial(w - margin - r, h / 2, r, now.tachometer, now.rpm / 1000, now.gear, "×1000 rpm", now.throttle, OPAQUE or accent)
        nvgEndFrame(vg)
    }

    private fun dial(
        cx: Float, cy: Float, r: Float, scale: GaugeScale, value: Double, digits: String, unit: String, pedal: Double, pedalRgb: Int,
    ) {
        val face = r * FACE
        well(cx, cy, r, face)
        val track = face * 0.95f
        // The sweep the needle has made, and the red zone, along the face's rim.
        nvgLineCap(vg, NVG_BUTT)
        if (value > 0) arc(cx, cy, track, scale.angle(0.0), scale.angle(value), face * 0.035f, 0x59000000 or accent)
        scale.redFrom?.let { arc(cx, cy, track, scale.angle(it), scale.angle(scale.max), face * 0.035f, 0xD9000000.toInt() or (RED and 0xFFFFFF)) }
        for (tick in scale.ticks()) {
            val angle = scale.angle(tick.value)
            val red = scale.redFrom?.let { tick.value >= it } == true
            nvgBeginPath(vg)
            ray(cx, cy, angle, face * (if (tick.major) 0.72f else 0.79f), face * 0.88f)
            nvgStrokeWidth(vg, face * (if (tick.major) 0.016f else 0.008f))
            stroke(if (red) RED else if (tick.major) WHITE else TICK)
            if (tick.major) {
                val x = cx + cos(angle).toFloat() * face * 0.58f
                val y = cy + sin(angle).toFloat() * face * 0.58f
                text(tick.value.roundToInt().toString(), x, y, face * 0.13f, if (red) RED else LABEL, WIDE)
            }
        }
        needle(cx, cy, face, scale.angle(value))
        // The middle: a dark disc with the digits, as Audi's.
        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, face * HUB)
        fill(HUB_FILL)
        nvgStrokeWidth(vg, 1.5f)
        stroke(EDGE)
        text(digits, cx, cy - face * 0.04f, face * 0.28f, WHITE, WIDE)
        text(unit, cx, cy + face * 0.2f, face * 0.08f, GREY, REGULAR)
        pedalBar(cx, cy + face * 0.66f, face * 0.62f, face * 0.045f, pedal, pedalRgb)
    }

    /** The well: the shadow it casts, a knurled bezel lit from the top left, and a dark face lit from above. */
    private fun well(cx: Float, cy: Float, r: Float, face: Float) {
        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy + r * 0.03f, r * 1.06f)
        nvgRadialGradient(vg, cx, cy + r * 0.03f, r * 0.94f, r * 1.06f, rgba(0xB3000000.toInt(), inner), rgba(0, outer), paint)
        nvgFillPaint(vg, paint)
        nvgFill(vg)

        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, r)
        fill(PANEL)
        // Fine ridges round the bezel, as the knob's.
        nvgBeginPath(vg)
        for (i in 0 until RIDGES) ray(cx, cy, i * 2 * PI / RIDGES, face, r * 0.995f)
        nvgStrokeWidth(vg, (2 * PI * r / RIDGES).toFloat() * 0.35f)
        stroke(EDGE)
        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, r)
        nvgCircle(vg, cx, cy, face)
        nvgPathWinding(vg, NVG_HOLE)
        nvgLinearGradient(vg, cx - r * 0.6f, cy - r * 0.8f, cx + r * 0.6f, cy + r * 0.8f, rgba(0x1FFFFFFF, inner), rgba(0x66000000, outer), paint)
        nvgFillPaint(vg, paint)
        nvgFill(vg)
        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, r)
        nvgStrokeWidth(vg, 1.5f)
        stroke(BLACK)

        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, face)
        nvgRadialGradient(vg, cx, cy - face * 0.44f, 0f, face * 1.25f, rgba(FACE_LIGHT, inner), rgba(PANEL, outer), paint)
        nvgFillPaint(vg, paint)
        nvgFill(vg)
        nvgStrokeWidth(vg, 1.5f)
        stroke(BLACK)
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
        stroke(0x40000000 or accent)
        val wide = face * 0.018f
        val narrow = face * 0.004f
        nvgBeginPath(vg)
        nvgMoveTo(vg, cx + dx * base - dy * wide, cy + dy * base + dx * wide)
        nvgLineTo(vg, cx + dx * tip - dy * narrow, cy + dy * tip + dx * narrow)
        nvgLineTo(vg, cx + dx * tip + dy * narrow, cy + dy * tip - dx * narrow)
        nvgLineTo(vg, cx + dx * base + dy * wide, cy + dy * base - dx * wide)
        nvgClosePath(vg)
        fill(OPAQUE or accent)
    }

    /** A pedal's travel: a dark track, filled as far as the pedal is pressed. */
    private fun pedalBar(cx: Float, cy: Float, width: Float, height: Float, pressed: Double, rgb: Int) {
        nvgBeginPath(vg)
        nvgRoundedRect(vg, cx - width / 2, cy - height / 2, width, height, height / 2)
        fill(TRACK)
        if (pressed <= 0) return
        nvgBeginPath(vg)
        nvgRoundedRect(vg, cx - width / 2, cy - height / 2, maxOf(height, width * pressed.toFloat()), height, height / 2)
        fill(rgb)
    }

    private fun arc(cx: Float, cy: Float, radius: Float, from: Double, to: Double, width: Float, argb: Int) {
        nvgBeginPath(vg)
        nvgArc(vg, cx, cy, radius, from.toFloat(), to.toFloat(), NVG_CW)
        nvgStrokeWidth(vg, width)
        stroke(argb)
    }

    /** A line along [angle] from [from] to [to] off the centre, added to the path. */
    private fun ray(cx: Float, cy: Float, angle: Double, from: Float, to: Float) {
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        nvgMoveTo(vg, cx + dx * from, cy + dy * from)
        nvgLineTo(vg, cx + dx * to, cy + dy * to)
    }

    private fun text(text: String, x: Float, y: Float, size: Float, argb: Int, font: String) {
        if (!fonts) return
        nvgFontFace(vg, font)
        nvgFontSize(vg, size)
        nvgTextAlign(vg, NVG_ALIGN_CENTER or NVG_ALIGN_MIDDLE)
        nvgFillColor(vg, rgba(argb, color))
        nvgText(vg, x, y, text)
    }

    private fun fill(argb: Int) {
        nvgFillColor(vg, rgba(argb, color))
        nvgFill(vg)
    }

    private fun stroke(argb: Int) {
        nvgStrokeColor(vg, rgba(argb, color))
        nvgStroke(vg)
    }

    private fun rgba(argb: Int, into: NVGColor): NVGColor =
        nvgRGBA((argb shr 16 and 0xFF).toByte(), (argb shr 8 and 0xFF).toByte(), (argb and 0xFF).toByte(), (argb ushr 24).toByte(), into)

    private companion object {
        const val REGULAR = "regular"
        const val WIDE = "wide"
        /** The face inside the bezel, and the hub in the face, in their radii. */
        const val FACE = 0.9f
        const val HUB = 0.38f
        const val RIDGES = 180
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
}
