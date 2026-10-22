package com.shilapi.xcertplay.desktop.media

import java.nio.ByteBuffer
import kotlin.math.abs

/** A rectangle of pixels, [right] and [bottom] exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top

    fun contains(other: PixelRect) =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    fun union(other: PixelRect) =
        PixelRect(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
}

/**
 * Where the turn card is in the AltScreen's card-only stream, from its luma plane [y] (tightly packed, [width] x
 * [height]). Apple Maps fills the panel with one flat colour and draws the card's content on it, so the card is every
 * pixel that differs from the colour of the corners, grown by [margin]; null on an empty screen. Neither Apple nor
 * the MHI2Q CarPlay project gives the card's size, so it is found in each picture rather than assumed.
 */
fun cardBounds(y: ByteBuffer, width: Int, height: Int, margin: Int = 16): PixelRect? {
    val background = backgroundLuma(y, width, height)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (row in 0 until height) {
        val base = row * width
        for (x in 0 until width) {
            if (abs((y.get(base + x).toInt() and 0xFF) - background) > THRESHOLD) {
                if (x < left) left = x
                if (x > right) right = x
                if (row < top) top = row
                bottom = row
            }
        }
    }
    if (right < 0) return null
    return PixelRect(
        maxOf(0, left - margin), maxOf(0, top - margin),
        minOf(width, right + 1 + margin), minOf(height, bottom + 1 + margin),
    )
}

/** The middle value of the four corners' pixels, the colour Maps fills the card-only panel with. */
private fun backgroundLuma(y: ByteBuffer, width: Int, height: Int): Int {
    val patch = minOf(CORNER, width, height)
    val samples = ArrayList<Int>(4 * patch * patch)
    for (corner in listOf(0 to 0, width - patch to 0, 0 to height - patch, width - patch to height - patch)) {
        for (row in corner.second until corner.second + patch) for (x in corner.first until corner.first + patch) {
            samples += y.get(row * width + x).toInt() and 0xFF
        }
    }
    samples.sort()
    return samples[samples.size / 2]
}

private const val THRESHOLD = 16
private const val CORNER = 8

/**
 * Steadies the card's rectangle from picture to picture: it grows at once, so nothing of a longer maneuver text
 * is cut, and shrinks only after [shrinkAfterFrames] pictures in a row that are smaller. A picture with no card,
 * as while the screen is redrawn, keeps the last one.
 */
class CardTracker(private val shrinkAfterFrames: Int = 15) {
    private var current: PixelRect? = null
    private var smaller = 0

    fun update(found: PixelRect?): PixelRect? {
        val last = current
        when {
            found == null -> Unit
            last == null -> current = found
            !last.contains(found) -> {
                current = last.union(found)
                smaller = 0
            }
            found != last -> if (++smaller >= shrinkAfterFrames) {
                current = found
                smaller = 0
            }
            else -> smaller = 0
        }
        return current
    }
}
