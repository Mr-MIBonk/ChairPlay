package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.hud.HudManeuver
import org.lwjgl.nanovg.NVGColor
import org.lwjgl.nanovg.NVGPaint
import org.lwjgl.nanovg.NanoVG.*
import org.lwjgl.nanovg.NanoVGGL3.NVG_IMAGE_NODELETE
import org.lwjgl.nanovg.NanoVGGL3.nvglCreateImageFromHandle
import kotlin.math.abs

/**
 * The animated maneuver in the HUD: the MHI2Q project's renderer ([ManeuverNative]) fed from the HUD's maneuver,
 * drawn into its texture and shown with NanoVG. Every call runs on the HUD window's GL thread, inside its paint.
 *
 * The window draws only when asked, so while a transition, a fill or a blink runs this asks [frames] for the next
 * frame: at [ACTIVE_FRAME_MILLIS] while something moves, at [FLAG_FRAME_MILLIS] while only the arrival flag waves,
 * and at the blink's next phase. When nothing moves it asks for nothing.
 */
class ManeuverView(private val frames: RepaintTimer?, private val log: (String) -> Unit = {}) {
    private val feed = ManeuverFeed()
    private var texture = 0
    private var textureWidth = 0
    private var textureHeight = 0
    private var image = -1
    private var imageContext = 0L
    private var unavailable = false
    private var lanesShown = false
    private var lanesChangedAt = Long.MIN_VALUE / 2
    private val paint = NVGPaint.create()
    private val black = nvgRGBAf(0f, 0f, 0f, 1f, NVGColor.create())
    private val clear = nvgRGBAf(0f, 0f, 0f, 0f, NVGColor.create())

    /**
     * Brings the renderer to [maneuver] (null without guidance). Called on every paint, also while the maneuver is
     * hidden, so that a route that ended and a new one do not animate from a stale maneuver.
     */
    fun update(maneuver: HudManeuver?) {
        if (unavailable || texture == 0) return
        for (packet in feed.update(maneuver, nowMillis())) ManeuverNative.command(packet)
    }

    /**
     * Draws the maneuver with [vg] so that the part of the renderer's frame the HUD shows ([ManeuverPlacement], of
     * [ManeuverPlacement.ASPECT]) fills the box at [x], [y], [width] x [height], rendered for a box [sharpHeight] tall
     * (see [maneuverPlacement]); returns false when there is nothing to draw (no renderer, or a maneuver it does not
     * draw), and the caller draws its own arrow instead.
     */
    fun draw(vg: Long, maneuver: HudManeuver?, x: Float, y: Float, width: Float, height: Float, sharpHeight: Float = height): Boolean {
        val place = maneuverPlacement(x, y, width, height, sharpHeight)
        if (!ready(place)) return false
        update(maneuver)
        if (!feed.showing) return false
        val now = nowMillis()
        val status = ManeuverNative.frame(now / 1000.0)
        when {
            status and ManeuverNative.ANIMATING != 0 -> frames?.after(ACTIVE_FRAME_MILLIS)
            status and ManeuverNative.LOOPING != 0 -> frames?.after(FLAG_FRAME_MILLIS)
        }
        feed.nextChange(maneuver, now)?.let { frames?.after(it - now) }
        if (feed.showingLanes != lanesShown) {
            lanesShown = feed.showingLanes
            lanesChangedAt = now
        }
        if (now - lanesChangedAt < ManeuverPlacement.LANE_FADE_MILLIS) frames?.after(ACTIVE_FRAME_MILLIS)
        if (image < 0 || imageContext != vg) {
            if (image >= 0 && imageContext == vg) nvgDeleteImage(vg, image)
            // The texture stays ours: NanoVG must not delete it with the image.
            image = nvglCreateImageFromHandle(vg, texture, textureWidth, textureHeight, NVG_IMAGE_FLIPY or NVG_IMAGE_PREMULTIPLIED or NVG_IMAGE_NODELETE)
            imageContext = vg
        }
        nvgImagePattern(vg, place.frameX, place.frameY, place.frameWidth, place.frameHeight, 0f, image, 1f, paint)
        nvgBeginPath(vg)
        nvgRect(vg, x, y, width, height)
        nvgFillPaint(vg, paint)
        nvgFill(vg)
        feather(vg, x, y, width, height, bottomFade(lanesShown, now - lanesChangedAt))
        return true
    }

    /**
     * Fades the shown part's edges into the HUD's black: the sides over the margin around the crop, where the roads
     * would otherwise end in a hard cut, and a little of the top and of the bottom ([bottomShare] of the height), where
     * they leave the frame. The bands reach past the box over NanoVG's antialiased fringe of the picture.
     */
    private fun feather(vg: Long, x: Float, y: Float, width: Float, height: Float, bottomShare: Float) {
        val side = width * ManeuverPlacement.SIDE / ManeuverPlacement.SHOWN_WIDTH
        val top = height * ManeuverPlacement.TOP_FADE / ManeuverPlacement.SHOWN_HEIGHT
        val bottom = height * bottomShare
        val e = FRINGE
        fade(vg, x - e, y - e, side + e, height + 2 * e, x, y, x + side, y)
        fade(vg, x + width - side, y - e, side + e, height + 2 * e, x + width, y, x + width - side, y)
        fade(vg, x - e, y - e, width + 2 * e, top + e, x, y, x, y + top)
        if (bottom > 0f) fade(vg, x - e, y + height - bottom, width + 2 * e, bottom + e, x, y + height, x, y + height - bottom)
    }

    /** Fills the rectangle with black at (fromX, fromY) fading to clear at (toX, toY), and beyond them likewise. */
    private fun fade(vg: Long, x: Float, y: Float, width: Float, height: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        nvgLinearGradient(vg, fromX, fromY, toX, toY, black, clear, paint)
        nvgBeginPath(vg)
        nvgRect(vg, x, y, width, height)
        nvgFillPaint(vg, paint)
        nvgFill(vg)
    }

    /** Creates the renderer at the placement's size, or follows a new size; false when it cannot run. */
    private fun ready(place: ManeuverPlacement): Boolean {
        if (unavailable) return false
        if (texture == 0) {
            ManeuverNative.loadError?.let {
                log("HUD: no animated maneuvers, chairplay_maneuver.dll did not load: $it")
                unavailable = true
                return false
            }
            texture = ManeuverNative.init(place.textureWidth, place.textureHeight)
            if (texture == 0) {
                log("HUD: no animated maneuvers, the renderer did not start: ${ManeuverNative.lastError()}")
                unavailable = true
                return false
            }
            textureWidth = place.textureWidth
            textureHeight = place.textureHeight
            if (!ManeuverNative.loadFlagAtlas()) log("HUD: the destination flag's pictures are missing; the renderer draws a placeholder square for it")
        } else if (abs(place.textureWidth - textureWidth) > RESIZE_SLACK || abs(place.textureHeight - textureHeight) > RESIZE_SLACK) {
            ManeuverNative.resize(place.textureWidth, place.textureHeight)
            textureWidth = place.textureWidth
            textureHeight = place.textureHeight
            // NanoVG keeps the image's size: a new one for the new size.
            if (image >= 0 && imageContext != 0L) nvgDeleteImage(imageContext, image)
            image = -1
        }
        return true
    }

    /** Frees the renderer; the GL context must be current, and [vg] still alive. */
    fun close(vg: Long) {
        if (image >= 0 && imageContext == vg) nvgDeleteImage(vg, image)
        image = -1
        if (texture != 0) ManeuverNative.shutdown()
        texture = 0
        frames?.close()
    }

    private fun nowMillis() = System.nanoTime() / 1_000_000

    private companion object {
        /** About 60 frames a second while something moves; the renderer animates by elapsed time. */
        const val ACTIVE_FRAME_MILLIS = 16L
        /** The flag's 14 frames turn at about 10 a second. */
        const val FLAG_FRAME_MILLIS = 80L
        const val RESIZE_SLACK = 2
        /** How far, in pixels, the fades reach past the box. */
        const val FRINGE = 2f
    }
}
