package io.github.numberonebot.chairplay.drive

import io.github.numberonebot.chairplay.drive.GaugeStyle.BLACK
import io.github.numberonebot.chairplay.drive.GaugeStyle.BOLD
import io.github.numberonebot.chairplay.drive.GaugeStyle.REGULAR
import io.github.numberonebot.chairplay.drive.GaugeStyle.GREEN
import io.github.numberonebot.chairplay.drive.GaugeStyle.EDGE
import io.github.numberonebot.chairplay.drive.GaugeStyle.FACE
import io.github.numberonebot.chairplay.drive.GaugeStyle.FACE_LIGHT
import io.github.numberonebot.chairplay.drive.GaugeStyle.ITALIC
import io.github.numberonebot.chairplay.drive.GaugeStyle.OPAQUE
import io.github.numberonebot.chairplay.drive.GaugeStyle.PANEL
import io.github.numberonebot.chairplay.drive.GaugeStyle.RED
import io.github.numberonebot.chairplay.drive.GaugeStyle.WIDE
import io.github.numberonebot.chairplay.media.WindowOverlay
import org.lwjgl.nanovg.NanoVG.*
import org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS
import org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES
import org.lwjgl.nanovg.NanoVGGL3.nvgCreate
import org.lwjgl.nanovg.NanoVGGL3.nvgDelete
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * The GPS car's tachometer and speedometer over the AltScreen, as Audi's Virtual Cockpit lays them out: the engine on
 * the left, the speed on the right, each a round [GaugeDial]. Each sits in a well finished as the controls window's
 * knob: a smooth rim round a knurled ring round a dark face lit from above. The speedometer's open bottom shows a small
 * wheel following steering input or heading beside both pedals as bars, the tachometer's the car's coordinates. Nothing is drawn
 * while [view] is null.
 */
class GaugePainter(
    private val view: () -> GaugeView?,
    private val log: (String) -> Unit = {},
    /** ui.accent as 0xRRGGBB: the needles and their sweep. */
    accent: Int = 0x52C4FF,
    private val motion: GaugeMotion = GaugeMotion(),
    /** How small the small wells are in a window of the given width and height, in the large ones' size. */
    private val smallScale: (Float, Float) -> Float = { _, _ -> 0.65f },
) : WindowOverlay {
    private val accent = accent and 0xFFFFFF
    private var pen: NanoPen? = null
    private var dial: GaugeDial? = null
    private var failed = false
    // NanoVG reads the font files from these buffers for as long as the context lives.
    private val fontData = mutableListOf<ByteBuffer>()

    // Nothing before the gauges first show: an app that never drives the car makes no context and loads no fonts.
    override fun open() = Unit

    /** Makes the NanoVG context on the first gauges; one that cannot be made leaves them undrawn, said once. */
    private fun create(): NanoPen? {
        pen?.let { return it }
        if (failed) return null
        val vg = nvgCreate(NVG_ANTIALIAS or NVG_STENCIL_STROKES)
        if (vg == 0L) {
            failed = true
            log("gauges: cannot create their NanoVG context; they are not drawn")
            return null
        }
        fun font(name: String, file: String): Boolean {
            val bytes = GaugePainter::class.java.getResourceAsStream("/fonts/$file")?.use { it.readBytes() } ?: return false
            val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip()
            fontData += buffer
            return nvgCreateFontMem(vg, name, buffer, false) >= 0
        }
        val made = NanoPen(vg)
        made.fonts = font(REGULAR, "RobotoFlexWide-Regular.ttf") && font(WIDE, "RobotoFlexWide-SemiBold.ttf") &&
            font(BOLD, "RobotoFlexWide-ExtraBold.ttf") && font(ITALIC, "RobotoFlexWide-ExtraBoldItalic.ttf")
        if (!made.fonts) log("gauges: the fonts are missing from the app; the gauges draw no numbers")
        pen = made
        dial = GaugeDial(made, accent)
        return made
    }

    override fun close() {
        pen?.let { nvgDelete(it.vg) }
        pen = null
        dial = null
        fontData.clear()
    }

    override val animating: Boolean get() = motion.animating

    override fun paint(width: Int, height: Int) {
        val frame = motion.frame(view()) ?: return
        val now = frame.view
        if (width == 0 || height == 0) return
        val pen = create() ?: return
        val dial = dial ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        // Laid out large; small, each well is scaled down about its lower corner.
        val r = GaugeLayout.radius(w, h)
        val face = r * FACE
        val cy = h / 2
        val left = GaugeLayout.margin(h) + r
        val right = w - left
        val small = smallScale(w, h)
        val scale = GaugeLayout.scale(GaugeMotion.sized(motion.smallness), small)
        val (cornerX, cornerY) = GaugeLayout.corner(w, h, small)
        nvgBeginFrame(pen.vg, w, h, 1f)
        // Both wells come in and go out together, about the screen's middle.
        moving(pen.vg, frame, w / 2, h / 2) {
            scaled(pen.vg, cornerX, cornerY, scale) {
                well(pen, left, cy, r, face)
                // The tachometer's single digits half as large again as the speedometer's three.
                dial.draw(left, cy, face, now.tachometer, LABEL_SIZE * 1.5f, now.rpm / 1000, now.gear, "×1000 rpm")
                gaugePosition(pen, left, cy + face * 0.625f, face * 0.3f, now.position, now.headingDegrees, now.wheelMode == WheelMode.STEERING)
            }
            scaled(pen.vg, w - cornerX, cornerY, scale) {
                well(pen, right, cy, r, face)
                dial.draw(right, cy, face, now.speedometer, LABEL_SIZE, now.speed, now.speed.roundToInt().toString(), now.units.symbol)
                // Under the speed the wheel, turned by the heading or the steering, and beside it both pedals, the throttle over the brake.
                wheel(pen, right - face * 0.2f, cy + face * 0.76f, face * 0.112f, wheelTurn(now.wheelMode, now.headingDegrees, now.steer))
                pedalBar(pen, right + face * 0.18f, cy + face * 0.72f, face * 0.38f, face * 0.025f, now.throttle, GREEN)
                pedalBar(pen, right + face * 0.18f, cy + face * 0.80f, face * 0.38f, face * 0.025f, now.brake, RED)
            }
        }
        nvgEndFrame(pen.vg)
    }

    /** Draws [draw] scaled by [scale] about [x], [y]. */
    private inline fun scaled(vg: Long, x: Float, y: Float, scale: Float, draw: () -> Unit) {
        nvgSave(vg)
        nvgTranslate(vg, x, y)
        nvgScale(vg, scale, scale)
        nvgTranslate(vg, -x, -y)
        draw()
        nvgRestore(vg)
    }

    /**
     * Draws [draw] as [frame] has it, scaled about [cx], [cy]: coming, it shrinks from large onto its place as it shows;
     * leaving, the same backwards, growing as it fades.
     */
    private inline fun moving(vg: Long, frame: GaugeMotion.Frame, cx: Float, cy: Float, draw: () -> Unit) {
        val there = (if (frame.coming) frame.progress else 1 - frame.progress).toFloat()
        val eased = GaugeMotion.eased(there)
        val scale = COME_SCALE - (COME_SCALE - 1) * eased
        nvgSave(vg)
        nvgGlobalAlpha(vg, eased)
        nvgTranslate(vg, cx, cy)
        nvgScale(vg, scale, scale)
        nvgTranslate(vg, -cx, -cy)
        draw()
        nvgRestore(vg)
    }

    /**
     * The well: the shadow it casts; a smooth rounded rim as Audi's, lit from above; inside it a knurled ring lit from
     * the top left; and a dark face lit from above.
     */
    private fun well(pen: NanoPen, cx: Float, cy: Float, r: Float, face: Float) {
        val vg = pen.vg
        val knurl = (r + face) / 2
        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy + r * 0.03f, r * 1.06f)
        nvgRadialGradient(vg, cx, cy + r * 0.03f, r * 0.94f, r * 1.06f, pen.rgba(0xB3000000.toInt(), pen.inner), pen.rgba(0, pen.outer), pen.paint)
        nvgFillPaint(vg, pen.paint)
        nvgFill(vg)

        // The rim, rounded as a tube: dark at both edges, lightest along its crest.
        val crest = (r + knurl) / 2
        ring(pen, cx, cy, knurl, crest) { nvgRadialGradient(vg, cx, cy, knurl, crest, pen.rgba(RIM_EDGE, pen.inner), pen.rgba(RIM_CREST, pen.outer), pen.paint) }
        ring(pen, cx, cy, crest, r) { nvgRadialGradient(vg, cx, cy, crest, r, pen.rgba(RIM_CREST, pen.inner), pen.rgba(RIM_EDGE, pen.outer), pen.paint) }
        // Lit from above: brighter over the top, in shadow under the bottom.
        ring(pen, cx, cy, knurl, r) {
            nvgLinearGradient(vg, cx, cy - r, cx, cy + r, pen.rgba(0x40FFFFFF, pen.inner), pen.rgba(0x99000000.toInt(), pen.outer), pen.paint)
        }

        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, knurl)
        pen.fill(PANEL)
        // Fine ridges round the inner ring, as the knob's.
        nvgBeginPath(vg)
        for (i in 0 until RIDGES) pen.ray(cx, cy, i * 2 * PI / RIDGES, face, knurl)
        nvgStrokeWidth(vg, (2 * PI * knurl / RIDGES).toFloat() * 0.35f)
        pen.stroke(EDGE)
        ring(pen, cx, cy, face, knurl) {
            nvgLinearGradient(
                vg, cx - r * 0.6f, cy - r * 0.8f, cx + r * 0.6f, cy + r * 0.8f, pen.rgba(0x1FFFFFFF, pen.inner), pen.rgba(0x66000000, pen.outer), pen.paint,
            )
        }
        nvgStrokeWidth(vg, 1.5f)
        for (edge in listOf(r, knurl)) {
            nvgBeginPath(vg)
            nvgCircle(vg, cx, cy, edge)
            pen.stroke(BLACK)
        }

        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, face)
        nvgRadialGradient(vg, cx, cy - face * 0.44f, 0f, face * 1.25f, pen.rgba(FACE_LIGHT, pen.inner), pen.rgba(PANEL, pen.outer), pen.paint)
        nvgFillPaint(vg, pen.paint)
        nvgFill(vg)
        nvgStrokeWidth(vg, 1.5f)
        pen.stroke(BLACK)
    }

    /** The ring between [inner] and [outer], filled with the paint [gradient] sets in the pen. */
    private inline fun ring(pen: NanoPen, cx: Float, cy: Float, inner: Float, outer: Float, gradient: () -> Unit) {
        val vg = pen.vg
        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, outer)
        nvgCircle(vg, cx, cy, inner)
        nvgPathWinding(vg, NVG_HOLE)
        gradient()
        nvgFillPaint(vg, pen.paint)
        nvgFill(vg)
    }

    /** A pedal's travel: a track filled and edged as the wheel is, filled in colour as far as the pedal is pressed. */
    private fun pedalBar(pen: NanoPen, cx: Float, cy: Float, width: Float, height: Float, pressed: Double, rgb: Int) {
        val vg = pen.vg
        nvgBeginPath(vg)
        nvgRoundedRect(vg, cx - width / 2, cy - height / 2, width, height, height / 2)
        pen.fill(WHEEL)
        if (pressed > 0) {
            nvgBeginPath(vg)
            nvgRoundedRect(vg, cx - width / 2, cy - height / 2, maxOf(height, width * pressed.toFloat()), height, height / 2)
            pen.fill(rgb)
        }
        nvgBeginPath(vg)
        nvgRoundedRect(vg, cx - width / 2, cy - height / 2, width, height, height / 2)
        nvgStrokeWidth(vg, 1.25f)
        pen.stroke(WHEEL_EDGE)
    }

    /**
     * A schematic three-spoke steering wheel, dark and thin, turned by [turn] radians with its red centre mark on it: a
     * round rim, spokes to the left, the right and down, and a broad pad, each edged a shade lighter to stand off the
     * face.
     */
    private fun wheel(pen: NanoPen, cx: Float, cy: Float, radius: Float, turn: Double) {
        val vg = pen.vg
        val rim = radius * 0.86f
        val thick = radius * 0.13f
        fun shape(widen: Float, argb: Int) {
            nvgLineCap(vg, NVG_ROUND)
            nvgBeginPath(vg)
            nvgCircle(vg, cx, cy, rim)
            for (spoke in listOf(0.0, PI / 2, PI)) pen.ray(cx, cy, spoke + turn, radius * 0.3f, rim)
            nvgStrokeWidth(vg, thick + widen)
            pen.stroke(argb)
            nvgBeginPath(vg)
            nvgCircle(vg, cx, cy, radius * 0.36f + widen / 2)
            pen.fill(argb)
        }
        // The lighter shape a little wider under the dark one leaves an edge round it.
        shape(2.5f, WHEEL_EDGE)
        shape(0f, WHEEL)
        nvgLineCap(vg, NVG_BUTT)
        pen.arc(cx, cy, rim, -PI / 2 + turn - 0.085, -PI / 2 + turn + 0.085, thick + 2.5f, RED)
    }

    private companion object {
        /** The speedometer's numbers, in the face's radius. */
        const val LABEL_SIZE = 0.13f
        const val RIDGES = 180
        /** How large the wells are as they start coming and as they are gone. */
        const val COME_SCALE = 1.6f
        /** The rim's edges and its crest: dark steel lit to a soft grey. */
        const val RIM_EDGE = 0xFF141416.toInt()
        const val RIM_CREST = 0xFF6A6A72.toInt()
        /** The heading wheel and its edge. */
        const val WHEEL = 0xFF1C1C1F.toInt()
        const val WHEEL_EDGE = 0xFF55555C.toInt()
    }
}
