package io.github.numberonebot.chairplay.drive

import kotlin.math.min

/** Where the two wells sit in a window, which the HUD needs to keep its song and route between them. */
object GaugeLayout {
    /** A well's radius: as tall as the window allows, and no wider than a sixth of it. */
    fun radius(width: Float, height: Float): Float = min(height * 0.46f, width * 0.17f)

    /** The space between a well and the window's edge. */
    fun margin(height: Float): Float = height * 0.04f

    /** The free span between the wells: from the left one's right edge to the right one's left edge. */
    fun between(width: Float, height: Float): Pair<Float, Float> {
        val edge = margin(height) + 2 * radius(width, height)
        return edge to width - edge
    }
}
