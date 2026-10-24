package io.github.numberonebot.chairplay.drive

import io.github.numberonebot.chairplay.drive.GaugeStyle.BLACK
import io.github.numberonebot.chairplay.drive.GaugeStyle.EDGE
import io.github.numberonebot.chairplay.drive.GaugeStyle.FACE
import io.github.numberonebot.chairplay.drive.GaugeStyle.FACE_LIGHT
import io.github.numberonebot.chairplay.drive.GaugeStyle.ITALIC
import io.github.numberonebot.chairplay.drive.GaugeStyle.LABEL
import io.github.numberonebot.chairplay.drive.GaugeStyle.OPAQUE
import io.github.numberonebot.chairplay.drive.GaugeStyle.PANEL
import io.github.numberonebot.chairplay.drive.GaugeStyle.RED
import io.github.numberonebot.chairplay.drive.GaugeStyle.TRACK
import io.github.numberonebot.chairplay.drive.GaugeStyle.WIDE
import io.github.numberonebot.chairplay.media.WindowOverlay
import org.lwjgl.nanovg.NanoVG.*
import org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS
import org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES
import org.lwjgl.nanovg.NanoVGGL3.nvgCreate
import org.lwjgl.nanovg.NanoVGGL3.nvgDelete
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The GPS car's tachometer and speedometer over the AltScreen, as Audi's Virtual Cockpit lays them out: the engine on
 * the left, the speed on the right, each a round [GaugeDial]. Each sits in a well finished as the controls window's
 * knob: a knurled bezel lit from the top left round a dark face lit from above. The speedometer's open bottom shows
 * both pedals as bars, the tachometer's a small steering wheel turned as it is steered. Nothing is drawn while [view]
 * is null.
 */
class GaugePainter(
    private val view: () -> GaugeView?,
    private val log: (String) -> Unit = {},
    /** ui.accent as 0xRRGGBB: the needles and their sweep. */
    accent: Int = 0x0A84FF,
    private val motion: GaugeMotion = GaugeMotion(),
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
        made.fonts = font(WIDE, "RobotoFlexWide-SemiBold.ttf") && font(ITALIC, "RobotoFlexWide-ExtraBoldItalic.ttf")
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
        val r = min(h * 0.46f, w * 0.17f)
        val face = r * FACE
        val margin = h * 0.04f
        val cy = h / 2
        val left = margin + r
        val right = w - margin - r
        nvgBeginFrame(pen.vg, w, h, 1f)
        moving(pen.vg, frame, left, cy, w, r, -1f) {
            well(pen, left, cy, r, face)
            // The tachometer's single digits half as large again as the speedometer's three.
            dial.draw(left, cy, face, now.tachometer, LABEL_SIZE * 1.5f, now.rpm / 1000, now.gear, "×1000 rpm")
            wheel(pen, left, cy + face * 0.69f, face * 0.15f, now.steer)
        }
        moving(pen.vg, frame, right, cy, w, r, 1f) {
            well(pen, right, cy, r, face)
            dial.draw(right, cy, face, now.speedometer, LABEL_SIZE, now.speedKmh, now.speedKmh.roundToInt().toString(), "km/h")
            // Both pedals under the speed: the throttle over the brake.
            pedalBar(pen, right, cy + face * 0.62f, face * 0.5f, face * 0.04f, now.throttle, OPAQUE or accent)
            pedalBar(pen, right, cy + face * 0.72f, face * 0.5f, face * 0.04f, now.brake, RED)
        }
        nvgEndFrame(pen.vg)
    }

    /**
     * Draws the well at [cx], [cy] as [frame] has it. Coming, it shrinks from large onto its place as it shows. Leaving,
     * it turns away about a centre far below the screen's middle, [outward] (-1 to the left, 1 to the right) and down
     * along that arc, as it fades.
     */
    private inline fun moving(vg: Long, frame: GaugeMotion.Frame, cx: Float, cy: Float, w: Float, r: Float, outward: Float, draw: () -> Unit) {
        val p = frame.progress.toFloat()
        nvgSave(vg)
        if (frame.coming) {
            val eased = 1 - (1 - p) * (1 - p) * (1 - p)
            val scale = COME_SCALE - (COME_SCALE - 1) * eased
            nvgGlobalAlpha(vg, eased)
            nvgTranslate(vg, cx, cy)
            nvgScale(vg, scale, scale)
            nvgTranslate(vg, -cx, -cy)
        } else {
            val pivotY = cy + w * ARC_RADIUS
            nvgGlobalAlpha(vg, 1 - p)
            nvgTranslate(vg, w / 2, pivotY)
            nvgRotate(vg, outward * p * p * r * LEAVE_TRAVEL / (w * ARC_RADIUS))
            nvgTranslate(vg, -w / 2, -pivotY)
        }
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

    /** A pedal's travel: a dark track, filled as far as the pedal is pressed. */
    private fun pedalBar(pen: NanoPen, cx: Float, cy: Float, width: Float, height: Float, pressed: Double, rgb: Int) {
        val vg = pen.vg
        nvgBeginPath(vg)
        nvgRoundedRect(vg, cx - width / 2, cy - height / 2, width, height, height / 2)
        pen.fill(TRACK)
        if (pressed <= 0) return
        nvgBeginPath(vg)
        nvgRoundedRect(vg, cx - width / 2, cy - height / 2, maxOf(height, width * pressed.toFloat()), height, height / 2)
        pen.fill(rgb)
    }

    /**
     * The wheel, a schematic three-spoke one turned as far as it is steered, a quarter turn at full lock: a rim marked at
     * its top in the accent colour, as a sports car's, spokes to the left, the right and down, and a hub.
     */
    private fun wheel(pen: NanoPen, cx: Float, cy: Float, radius: Float, steer: Double) {
        val vg = pen.vg
        val turn = steer.coerceIn(-1.0, 1.0) * PI / 2
        val rim = radius * 0.85f
        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, rim)
        nvgStrokeWidth(vg, radius * 0.22f)
        pen.stroke(LABEL)
        nvgLineCap(vg, NVG_ROUND)
        nvgBeginPath(vg)
        for (spoke in listOf(0.0, PI / 2, PI)) pen.ray(cx, cy, spoke + turn, radius * 0.2f, rim)
        nvgStrokeWidth(vg, radius * 0.18f)
        pen.stroke(LABEL)
        nvgBeginPath(vg)
        nvgCircle(vg, cx, cy, radius * 0.28f)
        pen.fill(LABEL)
        nvgLineCap(vg, NVG_BUTT)
        pen.arc(cx, cy, rim, -PI / 2 + turn - 0.2, -PI / 2 + turn + 0.2, radius * 0.24f, OPAQUE or accent)
    }

    private companion object {
        /** The speedometer's numbers, in the face's radius. */
        const val LABEL_SIZE = 0.13f
        const val RIDGES = 180
        /** How large the coming wells start; the leaving ones' arc, in the window's width, and how far along it they go, in their radius. */
        const val COME_SCALE = 1.6f
        const val ARC_RADIUS = 0.75f
        const val LEAVE_TRAVEL = 1.4f
        /** The rim's edges and its crest: dark steel lit to a soft grey. */
        const val RIM_EDGE = 0xFF141416.toInt()
        const val RIM_CREST = 0xFF6A6A72.toInt()
    }
}
