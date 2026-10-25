package io.github.numberonebot.chairplay.drive

import io.github.numberonebot.chairplay.hud.smallWellScale

/** One well of the virtual car's gauges: its center and radius in the frame's pixels. */
data class Well(val x: Float, val y: Float, val radius: Float)

/**
 * The two wells (the tachometer's on the left, the speedometer's on the right) in a [width] x [height] frame, laid out
 * as [GaugePainter] does: large, or [small] scaled down about the lower corner, a margin above the bottom.
 */
fun gaugeWells(width: Int, height: Int, small: Boolean): List<Well> {
    val w = width.toFloat()
    val h = height.toFloat()
    val radius = GaugeLayout.radius(w, h)
    val x = GaugeLayout.margin(h) + radius
    val y = h / 2
    if (!small) return listOf(Well(x, y, radius), Well(w - x, y, radius))
    val scale = smallWellScale(w, h)
    val (cornerX, cornerY) = GaugeLayout.corner(w, h, scale)
    val left = Well(cornerX + scale * (x - cornerX), cornerY + scale * (y - cornerY), radius * scale)
    return listOf(left, left.copy(x = w - left.x))
}
