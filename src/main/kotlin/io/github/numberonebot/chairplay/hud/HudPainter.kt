package io.github.numberonebot.chairplay.hud

import io.github.numberonebot.chairplay.data.Artwork
import io.github.numberonebot.chairplay.data.ManeuverType
import io.github.numberonebot.chairplay.hud.maneuver.ManeuverPlacement
import io.github.numberonebot.chairplay.hud.maneuver.ManeuverView
import io.github.numberonebot.chairplay.hud.maneuver.RepaintTimer
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

/** What the HUD needs at a moment: whether a session runs, its data, and the cover the iPhone sent last. */
class HudView(val connected: Boolean, val state: HudState, val artwork: Artwork?)

/**
 * Draws the HUD with NanoVG on black, centred, as desktop-docs/hud-spec.md lays it out:
 * the song, the route or both in the middle, and a call on top; no clock, signal or battery, which the CarPlay
 * screen shows. Sizes
 * follow the window's height. Text is in Inter, numbers that should stand out (distance) in a
 * wide Roboto Flex, after the way Audi pairs Audi Type with Audi Type Extended; both ship with the app, so the HUD
 * looks the same on every system. Symbols the fonts lack (play, pause) are drawn as vectors.
 *
 * The maneuver is the MHI2Q project's animated renderer ([ManeuverView]) where its library runs, else a simple
 * vector arrow. Every part rises from the dark and fades back into it ([HudMotion]); the song moves between the
 * middle and its column as guidance comes and goes. The animations ask for frames through [repaint], which draws the
 * window again from any thread.
 */
class HudPainter(
    private val view: () -> HudView,
    private val log: (String) -> Unit = {},
    repaint: (() -> Unit)? = null,
) : WindowOverlay {
    private var vg = 0L
    private var fonts = false
    // NanoVG reads the font files from these buffers for as long as the context lives.
    private val fontData = mutableListOf<ByteBuffer>()
    private var cover = -1
    private var coverId: Int? = null
    private val color = NVGColor.create()
    private val paint = NVGPaint.create()
    private val frames = repaint?.let { RepaintTimer(it) }
    private val maneuverView = ManeuverView(frames, log)
    private val motion = HudMotion()
    // What each part showed last, kept while it fades into the dark after its data is gone.
    private var shownMedia: HudMedia? = null
    private var shownManeuver: HudManeuver? = null
    private var shownArrival: HudArrival? = null
    private var shownCall: String? = null

    override fun open() {
        vg = nvgCreate(NVG_ANTIALIAS or NVG_STENCIL_STROKES)
        check(vg != 0L) { "cannot create the HUD's NanoVG context" }
        fun font(name: String, file: String): Boolean {
            val bytes = HudPainter::class.java.getResourceAsStream("/fonts/$file")?.use { it.readBytes() } ?: return false
            val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip()
            fontData += buffer
            return nvgCreateFontMem(vg, name, buffer, false) >= 0
        }
        fonts = font(REGULAR, "Inter-Regular.ttf") && font(BOLD, "Inter-SemiBold.ttf") && font(WIDE, "RobotoFlexWide-SemiBold.ttf")
        if (fonts) nvgAddFallbackFont(vg, WIDE, BOLD) else log("HUD: the fonts are missing from the app; the HUD draws no text")
    }

    override fun close() {
        if (vg != 0L) maneuverView.close(vg)
        if (cover >= 0) nvgDeleteImage(vg, cover)
        if (vg != 0L) nvgDelete(vg)
        vg = 0L
        fontData.clear()
    }

    override fun paint(width: Int, height: Int) {
        if (vg == 0L || width == 0 || height == 0) return
        val now = view()
        val state = now.state
        val connected = now.connected
        val shown = motion.update(connected, state.media != null, state.maneuver != null, state.call != null, System.nanoTime())
        if (connected && state.media != null) shownMedia = state.media else if (shown.media == 0f) shownMedia = null
        if (connected && state.maneuver != null) {
            shownManeuver = state.maneuver
            shownArrival = state.arrival
        } else if (shown.route == 0f) {
            shownManeuver = null
            shownArrival = null
        }
        if (connected && state.call != null) shownCall = state.call else if (shown.call == 0f) shownCall = null
        // Also while the maneuver is hidden (a call) or fading out, so it never animates from a stale one.
        maneuverView.update(shownManeuver)
        nvgBeginFrame(vg, width.toFloat(), height.toFloat(), 1f)
        val h = height.toFloat()
        val w = width.toFloat()
        // No clock, signal or battery: the CarPlay screen always shows them.
        emerging(shown.waiting, w / 2, h / 2) { centred("Waiting for iPhone", w / 2, h / 2, 0.07f * h, GREY) }
        shownManeuver?.let { navigation(it, shownArrival, routeBox(w, h, shown.routePlace), routeBox(w, h, 0f), h, shown.route) }
        shownMedia?.let { media(it, now, mediaBox(w, h, shown.mediaPlace), h, shown.media) }
        shownCall?.let { callCard(it, w, h, shown.call) }
        nvgEndFrame(vg)
        if (shown.moving) frames?.after(FRAME_MILLIS)
    }

    /**
     * Draws [content] [presence] of the way out of the dark: see-through, and a little smaller around ([cx], [cy]),
     * until it is fully there; nothing while it is gone.
     */
    private inline fun emerging(presence: Float, cx: Float, cy: Float, content: () -> Unit) {
        if (presence <= 0f) return
        nvgSave(vg)
        nvgGlobalAlpha(vg, presence)
        if (presence < 1f) {
            val scale = lerp(EMERGE_SCALE, 1f, presence)
            nvgTranslate(vg, cx, cy)
            nvgScale(vg, scale, scale)
            nvgTranslate(vg, -cx, -cy)
        }
        content()
        nvgRestore(vg)
    }

    /** A play triangle or two pause bars at [x], centred on [y]; returns the width used. */
    private fun playState(playing: Boolean, x: Float, y: Float, size: Float): Float {
        val h = size * 0.7f
        if (playing) {
            nvgBeginPath(vg)
            nvgMoveTo(vg, x, y - h / 2)
            nvgLineTo(vg, x + h * 0.85f, y)
            nvgLineTo(vg, x, y + h / 2)
            nvgClosePath(vg)
            fill(GREY)
            return h * 0.85f
        }
        for (bar in 0..1) {
            nvgBeginPath(vg)
            nvgRect(vg, x + bar * h * 0.5f, y - h / 2, h * 0.3f, h)
            fill(GREY)
        }
        return h * 0.8f
    }

    /**
     * The cover and, beside it, the title, artist and album, a progress bar and the app; centred in the [box] with its
     * cover's size, [presence] of the way out of the dark.
     */
    private fun media(media: HudMedia, now: HudView, box: HudBox, h: Float, presence: Float) {
        val gap = 0.05f * h
        val coverSize = box.cover
        val textWidth = min(box.right - box.left - coverSize - gap, 0.9f * h * 1.4f)
        val blockWidth = coverSize + gap + textWidth
        val x = box.left + (box.right - box.left - blockWidth) / 2
        val y = h / 2 - coverSize / 2
        emerging(presence, x + blockWidth / 2, h / 2) { mediaBlock(media, now, x, y, coverSize, gap, textWidth, h) }
    }

    private fun mediaBlock(media: HudMedia, now: HudView, x: Float, y: Float, coverSize: Float, gap: Float, textWidth: Float, h: Float) {
        drawCover(now.artwork?.takeIf { it.transferId == media.artworkId && it.size > 0 }, x, y, coverSize)
        val tx = x + coverSize + gap
        // In whole pixels: while the song moves between the middle and its column, every size between would be
        // another set of glyphs for NanoVG to render.
        val titleSize = (0.085f * h * coverSize / (0.55f * h)).roundToInt().toFloat()
        var ty = y + coverSize * 0.18f
        text(fit(media.title.orEmpty(), textWidth, titleSize, BOLD), tx, ty, titleSize, WHITE, BOLD, NVG_ALIGN_LEFT)
        ty += titleSize * 1.15f
        media.subtitle?.let { text(fit(it, textWidth, titleSize * 0.68f, REGULAR), tx, ty, titleSize * 0.68f, GREY, REGULAR, NVG_ALIGN_LEFT) }
        media.progress?.let { progress ->
            val barY = y + coverSize * 0.70f
            val barHeight = 0.012f * h
            nvgBeginPath(vg)
            nvgRoundedRect(vg, tx, barY, textWidth, barHeight, barHeight / 2)
            fill(DIM)
            nvgBeginPath(vg)
            nvgRoundedRect(vg, tx, barY, textWidth * progress, barHeight, barHeight / 2)
            fill(BLUE)
        }
        media.source?.let {
            val size = 0.04f * h
            val sy = y + coverSize * 0.88f
            val icon = playState(media.playing, tx, sy, size) + size * 0.5f
            text(fit(it, textWidth - icon, size, REGULAR), tx + icon, sy, size, GREY, REGULAR, NVG_ALIGN_LEFT)
        }
    }

    private fun drawCover(artwork: Artwork?, x: Float, y: Float, size: Float) {
        val id = artwork?.transferId
        if (id != coverId) {
            if (cover >= 0) nvgDeleteImage(vg, cover)
            cover = -1
            coverId = id
            // Decoded in the JVM with limits (coverPixels), not by NanoVG's native decoder.
            artwork?.let { coverPixels(it.bytes) }?.let { pixels ->
                cover = nvgCreateImageRGBA(vg, pixels.width, pixels.height, 0, pixels.rgba)
            }
        }
        // Barely rounded: softer than the iPhone draws its covers.
        val radius = size * 0.02f
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x, y, size, size, radius)
        if (cover >= 0) {
            nvgImagePattern(vg, x, y, size, size, 0f, cover, 1f, paint)
            nvgFillPaint(vg, paint)
            nvgFill(vg)
        } else {
            fill(DIM)
            centred("♪", x + size / 2, y + size / 2, size * 0.4f, GREY)
        }
    }

    /**
     * Everything about the route in one block, centred in the [box]: the arrow, and beside it the distance to the
     * maneuver, the road, the arrival with the time and distance left, and the destination; [presence] of the way out
     * of the dark. The renderer draws for the route's largest box, [alone], so a box that changes during an animation
     * only scales its picture.
     */
    private fun navigation(maneuver: HudManeuver, arrival: HudArrival?, box: HudBox, alone: HudBox, h: Float, presence: Float) {
        val left = box.left
        val right = box.right
        val top = HUD_PAD * h
        val bottom = h - top
        val arrowSize = 0.30f * h
        val (viewWidth, viewHeight) = maneuverSize(left, right, top, bottom, h)
        val sharpHeight = maneuverSize(alone.left, alone.right, top, bottom, h).second
        // The picture's sides fade out, so the text can come closer.
        val gap = 0.03f * h
        val roadSize = 0.06f * h
        val smallSize = 0.045f * h
        val textWidth = min(right - left - viewWidth - gap, 0.9f * h * 1.4f)
        // The distance stands out; a long one shrinks a little before it is cut.
        val distanceSize = fitSize(maneuver.distance ?: "", textWidth, 0.14f * h, 0.10f * h, WIDE)
        val x = left + (right - left - (viewWidth + gap + textWidth)) / 2
        val road = maneuver.road ?: maneuver.description
        val arrivalLine = arrival
            ?.let { listOfNotNull(it.time?.let { time -> "Arrival $time" }, it.remaining).joinToString("  ·  ") }
            ?.ifEmpty { null }
        val lines = buildList {
            add(Line(maneuver.distance ?: "", distanceSize, WHITE, WIDE, 1.0f))
            road?.let { add(Line(it, roadSize, GREY, REGULAR, 1.25f)) }
            arrivalLine?.let { add(Line(it, smallSize, WHITE, REGULAR, 1.9f)) }
            arrival?.destination?.let { add(Line(it, smallSize, GREY, REGULAR, 1.35f)) }
        }
        val blockHeight = lines.sumOf { (it.size * it.spacing).toDouble() }.toFloat()
        val cy = (top + bottom) / 2
        emerging(presence, x + (viewWidth + gap + textWidth) / 2, cy) {
            if (!maneuverView.draw(vg, maneuver, x, cy - viewHeight / 2, viewWidth, viewHeight, sharpHeight)) {
                arrow(maneuver.typeCode, x + (viewWidth - arrowSize) / 2, cy - arrowSize / 2, arrowSize)
            }
            val tx = x + viewWidth + gap
            var y = cy - blockHeight / 2
            for (line in lines) {
                y += line.size * line.spacing / 2
                text(fit(line.text, textWidth, line.size, line.font), tx, y, line.size, line.rgb, line.font, NVG_ALIGN_LEFT)
                y += line.size * line.spacing / 2
            }
        }
    }

    /** The renderer's picture in a block: as tall as the block allows but no more than half its width. */
    private fun maneuverSize(left: Float, right: Float, top: Float, bottom: Float, h: Float): Pair<Float, Float> {
        var viewHeight = min(MANEUVER_HEIGHT * h, bottom - top)
        var viewWidth = viewHeight * ManeuverPlacement.ASPECT
        if (viewWidth > (right - left) / 2) {
            viewWidth = (right - left) / 2
            viewHeight = viewWidth / ManeuverPlacement.ASPECT
        }
        return viewWidth to viewHeight
    }

    /** One line of a text block: [spacing] is the height it takes, in multiples of its [size]. */
    private class Line(val text: String, val size: Float, val rgb: Int, val font: String, val spacing: Float)

    /**
     * A simple arrow for when the MHI2Q project's renderer cannot run or draws nothing for the maneuver: a stem from the bottom,
     * bending toward the turn's angle; a ring with the exit for roundabouts, a dot for arriving.
     */
    private fun arrow(typeCode: Int?, x: Float, y: Float, size: Float) {
        val type = ManeuverType.entries.firstOrNull { it.code == typeCode }
        val cx = x + size / 2
        val stroke = size * 0.11f
        nvgStrokeWidth(vg, stroke)
        nvgLineCap(vg, NVG_ROUND)
        nvgLineJoin(vg, NVG_ROUND)
        val exit = type?.roundaboutExit
        if (exit != null || type == ManeuverType.ENTER_ROUNDABOUT || type == ManeuverType.EXIT_ROUNDABOUT) {
            nvgBeginPath(vg)
            nvgCircle(vg, cx, y + size * 0.45f, size * 0.26f)
            stroke(BLUE)
            exit?.let { centred(it.toString(), cx, y + size * 0.45f, size * 0.3f, WHITE, BOLD) }
            return
        }
        if (type in ARRIVALS) {
            nvgBeginPath(vg)
            nvgCircle(vg, cx, y + size * 0.42f, size * 0.2f)
            fill(BLUE)
            return
        }
        val degrees = when (type) {
            ManeuverType.LEFT_TURN, ManeuverType.LEFT_TURN_AT_END -> -90f
            ManeuverType.RIGHT_TURN, ManeuverType.RIGHT_TURN_AT_END -> 90f
            ManeuverType.SLIGHT_LEFT_TURN, ManeuverType.KEEP_LEFT, ManeuverType.HIGHWAY_OFF_RAMP_LEFT, ManeuverType.CHANGE_HIGHWAY_LEFT -> -40f
            ManeuverType.SLIGHT_RIGHT_TURN, ManeuverType.KEEP_RIGHT, ManeuverType.HIGHWAY_OFF_RAMP_RIGHT, ManeuverType.CHANGE_HIGHWAY_RIGHT,
            ManeuverType.OFF_RAMP -> 40f
            ManeuverType.SHARP_LEFT_TURN -> -135f
            ManeuverType.SHARP_RIGHT_TURN -> 135f
            ManeuverType.U_TURN, ManeuverType.U_TURN_WHEN_POSSIBLE, ManeuverType.START_ROUTE_WITH_U_TURN -> 180f
            else -> 0f
        }
        val bendX = cx
        val bendY = y + size * 0.48f
        val angle = degrees * PI.toFloat() / 180f
        val length = size * if (degrees == 180f) 0.0f else 0.36f
        nvgBeginPath(vg)
        nvgMoveTo(vg, cx, y + size * 0.95f)
        nvgLineTo(vg, bendX, bendY)
        val (tipX, tipY) = if (degrees == 180f) {
            // A U-turn: up, over and back down on the left.
            nvgArc(vg, cx - size * 0.16f, bendY, size * 0.16f, 0f, PI.toFloat(), NVG_CCW)
            nvgLineTo(vg, cx - size * 0.32f, bendY + size * 0.22f)
            (cx - size * 0.32f) to (bendY + size * 0.22f)
        } else {
            val tx = bendX + sin(angle) * length
            val ty = bendY - cos(angle) * length
            nvgLineTo(vg, tx, ty)
            tx to ty
        }
        stroke(BLUE)
        // The head points along the last segment.
        val headAngle = if (degrees == 180f) PI.toFloat() else angle
        val head = size * 0.2f
        nvgBeginPath(vg)
        nvgMoveTo(vg, tipX + sin(headAngle) * head * 0.6f, tipY - cos(headAngle) * head * 0.6f)
        nvgLineTo(vg, tipX + sin(headAngle - 2.4f) * head, tipY - cos(headAngle - 2.4f) * head)
        nvgLineTo(vg, tipX + sin(headAngle + 2.4f) * head, tipY - cos(headAngle + 2.4f) * head)
        nvgClosePath(vg)
        fill(BLUE)
    }

    private fun callCard(call: String, w: Float, h: Float, presence: Float) {
        val left = HUD_PAD * h
        val right = w - left
        val height = 0.30f * h
        val y = h / 2 - height / 2
        emerging(presence, w / 2, h / 2) {
            nvgBeginPath(vg)
            nvgRoundedRect(vg, left, y, right - left, height, height * 0.2f)
            fill(CALL_GROUND)
            centred(fit(call, right - left - 0.1f * h, 0.09f * h, BOLD), w / 2, y + height / 2, 0.09f * h, GREEN, BOLD)
        }
    }

    // --- text and colour helpers ---

    /** Draws [text]; returns its width. */
    private fun text(text: String, x: Float, y: Float, size: Float, rgb: Int, font: String, align: Int): Float {
        if (!fonts || text.isEmpty()) return 0f
        nvgFontFace(vg, font)
        nvgFontSize(vg, size)
        nvgTextAlign(vg, align or NVG_ALIGN_MIDDLE)
        nvgFillColor(vg, rgba(rgb))
        nvgText(vg, x, y, text)
        return nvgTextBounds(vg, 0f, 0f, text, null as java.nio.FloatBuffer?)
    }

    private fun centred(text: String, x: Float, y: Float, size: Float, rgb: Int, font: String = REGULAR) {
        text(text, x, y, size, rgb, font, NVG_ALIGN_CENTER)
    }

    /** The largest size from [size] down to [smallest] at which [text] fits [width]; [smallest] if none does. */
    private fun fitSize(text: String, width: Float, size: Float, smallest: Float, font: String): Float {
        if (!fonts || text.isEmpty()) return size
        nvgFontFace(vg, font)
        nvgFontSize(vg, size)
        val measured = nvgTextBounds(vg, 0f, 0f, text, null as java.nio.FloatBuffer?)
        return if (measured <= width) size else (size * width / measured).coerceAtLeast(smallest)
    }

    /** [text] cut with an ellipsis to [width] at [size]. */
    private fun fit(text: String, width: Float, size: Float, font: String): String {
        if (!fonts) return text
        nvgFontFace(vg, font)
        nvgFontSize(vg, size)
        fun measure(s: String) = nvgTextBounds(vg, 0f, 0f, s, null as java.nio.FloatBuffer?)
        if (measure(text) <= width) return text
        // The longest start that fits with the ellipsis, found by halving: a few measures, not one per character.
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (measure(text.substring(0, mid) + "…") <= width) low = mid else high = mid - 1
        }
        return text.substring(0, low).trimEnd() + "…"
    }

    private fun fill(rgb: Int) {
        nvgFillColor(vg, rgba(rgb))
        nvgFill(vg)
    }

    private fun stroke(rgb: Int) {
        nvgStrokeColor(vg, rgba(rgb))
        nvgStroke(vg)
    }

    private fun rgba(argb: Int): NVGColor =
        nvgRGBA((argb shr 16 and 0xFF).toByte(), (argb shr 8 and 0xFF).toByte(), (argb and 0xFF).toByte(), (argb ushr 24).toByte(), color)

    private companion object {
        const val REGULAR = "regular"
        const val BOLD = "bold"
        const val WIDE = "wide"
        /** About 60 frames a second while a part rises or fades; the parts move by elapsed time. */
        const val FRAME_MILLIS = 16L
        /** The size a part starts rising from, and fades down to, in its full size. */
        const val EMERGE_SCALE = 0.94f
        /** The maneuver picture's height in the window's. */
        const val MANEUVER_HEIGHT = 0.72f
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFF9A9AA0.toInt()
        const val DIM = 0xFF2C2C2E.toInt()
        const val BLUE = 0xFF0A84FF.toInt()
        const val GREEN = 0xFF30D158.toInt()
        const val CALL_GROUND = 0xFF1C1C1E.toInt()
        val ARRIVALS = setOf(
            ManeuverType.ARRIVE_AT_DESTINATION, ManeuverType.ARRIVE_AT_DESTINATION_LEFT, ManeuverType.ARRIVE_AT_DESTINATION_RIGHT,
            ManeuverType.ARRIVE_END_OF_NAVIGATION, ManeuverType.ARRIVE_END_OF_DIRECTIONS,
        )
    }
}
