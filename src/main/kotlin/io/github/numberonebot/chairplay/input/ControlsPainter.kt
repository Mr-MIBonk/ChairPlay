package io.github.numberonebot.chairplay.input

import io.github.numberonebot.chairplay.media.WindowOverlay
import org.lwjgl.nanovg.NVGColor
import org.lwjgl.nanovg.NanoVG.*
import org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS
import org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES
import org.lwjgl.nanovg.NanoVGGL3.nvgCreate
import org.lwjgl.nanovg.NanoVGGL3.nvgDelete
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.min

/**
 * Draws the on-screen controls window with NanoVG on black: the buttons of [controlsLayout], the one under the
 * pressed mouse button lighter. Labels are in Inter, which ships with the app; the knob's arrows and the media
 * symbols are vectors.
 */
class ControlsPainter(private val log: (String) -> Unit = {}) : WindowOverlay {
    /** The button the mouse holds down, set on the GL thread by the window's input; null when none. */
    @Volatile
    var pressed: ControlAction? = null

    /** The layout last drawn, for the window's clicks; empty before the first paint. */
    @Volatile
    var buttons: List<ControlButton> = emptyList()
        private set

    private var vg = 0L
    private var fonts = false
    private val fontData = mutableListOf<ByteBuffer>()
    private val color = NVGColor.create()

    override fun open() {
        vg = nvgCreate(NVG_ANTIALIAS or NVG_STENCIL_STROKES)
        check(vg != 0L) { "cannot create the controls window's NanoVG context" }
        val bytes = ControlsPainter::class.java.getResourceAsStream("/fonts/Inter-SemiBold.ttf")?.use { it.readBytes() }
        if (bytes != null) {
            val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip()
            fontData += buffer
            fonts = nvgCreateFontMem(vg, FONT, buffer, false) >= 0
        }
        if (!fonts) log("controls window: the font is missing from the app; the buttons draw no labels")
    }

    override fun close() {
        if (vg != 0L) nvgDelete(vg)
        vg = 0L
        fontData.clear()
    }

    override fun paint(width: Int, height: Int) {
        if (vg == 0L || width == 0 || height == 0) return
        val layout = controlsLayout(width.toFloat(), height.toFloat())
        buttons = layout
        nvgBeginFrame(vg, width.toFloat(), height.toFloat(), 1f)
        for (button in layout) drawButton(button, button.action == pressed)
        nvgEndFrame(vg)
    }

    private fun drawButton(button: ControlButton, down: Boolean) {
        val ground = when (button.action) {
            ControlAction.CALL_ANSWER -> if (down) GREEN_DOWN else GREEN
            ControlAction.CALL_END -> if (down) RED_DOWN else RED
            ControlAction.SELECT -> if (down) KNOB_DOWN else KNOB
            else -> if (down) BUTTON_DOWN else BUTTON
        }
        nvgBeginPath(vg)
        if (button.round) {
            nvgCircle(vg, button.centreX, button.centreY, min(button.width, button.height) / 2)
        } else {
            nvgRoundedRect(vg, button.left, button.top, button.width, button.height, min(button.width, button.height) * 0.22f)
        }
        fill(ground)
        val size = min(button.width, button.height)
        val x = button.centreX
        val y = button.centreY
        when (button.action) {
            ControlAction.KNOB_UP -> triangle(x, y, size * 0.32f, -PI.toFloat() / 2)
            ControlAction.KNOB_DOWN -> triangle(x, y, size * 0.32f, PI.toFloat() / 2)
            ControlAction.KNOB_LEFT -> triangle(x, y, size * 0.32f, PI.toFloat())
            ControlAction.KNOB_RIGHT -> triangle(x, y, size * 0.32f, 0f)
            ControlAction.KNOB_PREVIOUS -> turnArrow(x, y, size * 0.26f, clockwise = false)
            ControlAction.KNOB_NEXT -> turnArrow(x, y, size * 0.26f, clockwise = true)
            ControlAction.SELECT -> label("OK", x, y, size * 0.26f, button.width * 0.8f)
            ControlAction.MEDIA_PLAY_PAUSE -> {
                triangle(x - size * 0.12f, y, size * 0.2f, 0f)
                bar(x + size * 0.1f, y, size * 0.05f, size * 0.34f)
                bar(x + size * 0.2f, y, size * 0.05f, size * 0.34f)
            }
            ControlAction.MEDIA_PREVIOUS -> {
                bar(x - size * 0.2f, y, size * 0.05f, size * 0.34f)
                triangle(x, y, size * 0.2f, PI.toFloat())
            }
            ControlAction.MEDIA_NEXT -> {
                triangle(x, y, size * 0.2f, 0f)
                bar(x + size * 0.2f, y, size * 0.05f, size * 0.34f)
            }
            else -> label(LABELS[button.action] ?: "", x, y, size * 0.3f, button.width * 0.8f)
        }
    }

    /** A filled triangle centred on ([x], [y]), pointing at [angle] (0 is right), [r] from its centre to its tip. */
    private fun triangle(x: Float, y: Float, r: Float, angle: Float) {
        nvgBeginPath(vg)
        for (i in 0..2) {
            val a = angle + i * 2f * PI.toFloat() / 3f
            val px = x + r * kotlin.math.cos(a)
            val py = y + r * kotlin.math.sin(a)
            if (i == 0) nvgMoveTo(vg, px, py) else nvgLineTo(vg, px, py)
        }
        nvgClosePath(vg)
        fill(WHITE)
    }

    private fun bar(x: Float, y: Float, w: Float, h: Float) {
        nvgBeginPath(vg)
        nvgRect(vg, x - w / 2, y - h / 2, w, h)
        fill(WHITE)
    }

    /** Three quarters of a circle with an arrowhead, turning the way the knob does. */
    private fun turnArrow(x: Float, y: Float, r: Float, clockwise: Boolean) {
        val start = -PI.toFloat() * 0.75f
        val end = PI.toFloat() * 0.75f
        nvgBeginPath(vg)
        nvgArc(vg, x, y, r, start, end, NVG_CW)
        nvgStrokeWidth(vg, r * 0.28f)
        nvgLineCap(vg, NVG_ROUND)
        nvgStrokeColor(vg, rgba(WHITE))
        nvgStroke(vg)
        val tip = if (clockwise) end else start
        val tx = x + r * kotlin.math.cos(tip)
        val ty = y + r * kotlin.math.sin(tip)
        // The head points along the circle, the way the arrow turns.
        val along = tip + if (clockwise) PI.toFloat() / 2 else -PI.toFloat() / 2
        triangle(tx, ty, r * 0.45f, along)
    }

    /** [text] centred on ([x], [y]) at [size], smaller where it would be wider than [maxWidth]. */
    private fun label(text: String, x: Float, y: Float, size: Float, maxWidth: Float) {
        if (!fonts || text.isEmpty()) return
        nvgFontFace(vg, FONT)
        nvgFontSize(vg, size)
        val width = nvgTextBounds(vg, 0f, 0f, text, null as java.nio.FloatBuffer?)
        if (width > maxWidth) nvgFontSize(vg, size * maxWidth / width)
        nvgTextAlign(vg, NVG_ALIGN_CENTER or NVG_ALIGN_MIDDLE)
        nvgFillColor(vg, rgba(WHITE))
        nvgText(vg, x, y, text)
    }

    private fun fill(rgb: Int) {
        nvgFillColor(vg, rgba(rgb))
        nvgFill(vg)
    }

    private fun rgba(argb: Int): NVGColor =
        nvgRGBA((argb shr 16 and 0xFF).toByte(), (argb shr 8 and 0xFF).toByte(), (argb and 0xFF).toByte(), (argb ushr 24).toByte(), color)

    private companion object {
        const val FONT = "semibold"
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BUTTON = 0xFF1C1C1E.toInt()
        const val BUTTON_DOWN = 0xFF48484A.toInt()
        const val KNOB = 0xFF2C2C2E.toInt()
        const val KNOB_DOWN = 0xFF5A5A5E.toInt()
        const val GREEN = 0xFF248A3D.toInt()
        const val GREEN_DOWN = 0xFF30D158.toInt()
        const val RED = 0xFFB0281F.toInt()
        const val RED_DOWN = 0xFFFF453A.toInt()
        val LABELS = mapOf(
            ControlAction.BACK to "Back",
            ControlAction.HOME to "Home",
            ControlAction.SIRI to "Siri",
            ControlAction.CALL_ANSWER to "Answer",
            ControlAction.CALL_END to "End",
            ControlAction.ALTSCREEN_ZOOM_OUT to "−",
            ControlAction.ALTSCREEN_ZOOM_IN to "+",
        )
    }
}
