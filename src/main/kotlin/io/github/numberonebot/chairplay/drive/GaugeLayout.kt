package io.github.numberonebot.chairplay.drive

import kotlin.math.min

/**
 * Where the two wells sit in a window, which the HUD needs to keep its song and route between them. Large, they stand
 * in the middle of the height; small, they are the large ones scaled down to [small] of their size about a point near
 * the window's lower corners ([corner], mirrored for the right one), which leaves them as far from the bottom as from
 * the sides. How small they are goes from 0 (large) to 1 (small), so a switch can scale them over a moment.
 */
object GaugeLayout {
    /** A large well's radius: as tall as the window allows, and no wider than a sixth of it. */
    fun radius(width: Float, height: Float): Float = min(height * 0.46f, width * 0.17f)

    /** The space between a large well and the window's edges. */
    fun margin(height: Float): Float = height * 0.04f

    /** The wells' scale at [smallness] (0 large, 1 small), small ones [small] of the large. */
    fun scale(smallness: Float, small: Float): Float = 1f + (small - 1f) * smallness

    /**
     * The point the left well scales about: a margin in from the side, and as low as puts a well scaled to [small] a
     * margin above the bottom.
     */
    fun corner(width: Float, height: Float, small: Float): Pair<Float, Float> {
        val margin = margin(height)
        // The scaled well's bottom, py + small * (height / 2 + radius - py), at height - margin.
        val y = (height - margin - small * (height / 2 + radius(width, height))) / (1 - small)
        return margin to y
    }

    /** The free span between the wells at [smallness]: from the left one's right edge to the right one's left edge. */
    fun between(width: Float, height: Float, smallness: Float, small: Float): Pair<Float, Float> {
        val edge = margin(height) + 2 * radius(width, height) * scale(smallness, small)
        return edge to width - edge
    }
}
