package io.github.numberonebot.chairplay.hud.maneuver

import kotlin.math.roundToInt

/**
 * Where the renderer's texture goes when the part of its frame the HUD shows should fill a box: the texture's size
 * in pixels, at the box's scale up to [MAX_TEXTURE_WIDTH], and the rectangle its whole frame covers, reaching past the
 * box.
 *
 * The part shown is the crop the renderer frames a maneuver in, widened by [SIDE] frame pixels left and right, where
 * the HUD fades the roads out into its black (the crop's own edge would cut through them), and up to the frame's top,
 * where the arrival flag stands above the crop.
 */
data class ManeuverPlacement(
    val textureWidth: Int,
    val textureHeight: Int,
    val frameX: Float,
    val frameY: Float,
    val frameWidth: Float,
    val frameHeight: Float,
) {
    companion object {
        const val SIDE = 22
        const val SHOWN_X = ManeuverNative.CROP_X - SIDE
        const val SHOWN_Y = 0
        const val SHOWN_WIDTH = ManeuverNative.CROP_WIDTH + 2 * SIDE
        const val SHOWN_HEIGHT = ManeuverNative.CROP_Y + ManeuverNative.CROP_HEIGHT

        /** The frame pixels at the top that fade into black. */
        const val TOP_FADE = 12

        /** The shown part's width to its height, which the box should keep. */
        const val ASPECT = SHOWN_WIDTH.toFloat() / SHOWN_HEIGHT

        /**
         * The widest texture: the renderer's road masks grow with it (3.5 times its size, twice), so a maximised
         * window's maneuver is drawn at this size and scaled up.
         */
        const val MAX_TEXTURE_WIDTH = 800
    }
}

/**
 * The placement for the shown part at [x], [y], [width] x [height]. The texture is sized for a box [sharpHeight]
 * tall (of the same aspect): a box that grows or shrinks during an animation keeps its texture, since a new size
 * makes the renderer build its road masks again, and NanoVG scales the picture in between.
 */
fun maneuverPlacement(x: Float, y: Float, width: Float, height: Float, sharpHeight: Float = height): ManeuverPlacement {
    val scaleX = width / ManeuverPlacement.SHOWN_WIDTH
    val scaleY = height / ManeuverPlacement.SHOWN_HEIGHT
    val frameWidth = ManeuverNative.FRAME_WIDTH * scaleX
    val frameHeight = ManeuverNative.FRAME_HEIGHT * scaleY
    val sharp = sharpHeight / height
    val textureWidth = (frameWidth * sharp).roundToInt().coerceIn(MIN_WIDTH, ManeuverPlacement.MAX_TEXTURE_WIDTH)
    val textureHeight = if (textureWidth == ManeuverPlacement.MAX_TEXTURE_WIDTH) {
        (textureWidth * frameHeight / frameWidth).roundToInt()
    } else {
        (frameHeight * sharp).roundToInt()
    }
    return ManeuverPlacement(
        textureWidth = textureWidth,
        textureHeight = textureHeight.coerceAtLeast(MIN_HEIGHT),
        frameX = x - ManeuverPlacement.SHOWN_X * scaleX,
        frameY = y - ManeuverPlacement.SHOWN_Y * scaleY,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
    )
}

private const val MIN_WIDTH = 64
private const val MIN_HEIGHT = 36
