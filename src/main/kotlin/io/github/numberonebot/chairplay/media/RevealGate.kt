package io.github.numberonebot.chairplay.media

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/** The average of a tightly packed luma plane [y], [width] x [height], from 0 (black) to 255, read at 32 x 18 points. */
internal fun meanLuma(y: ByteBuffer, width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return 0
    var sum = 0L
    for (row in 0 until 18) {
        for (column in 0 until 32) {
            sum += y.get((row * height / 18) * width + column * width / 32).toInt() and 0xff
        }
    }
    return (sum / (32 * 18)).toInt()
}

internal fun meanLuma(frame: DecodedFrame) = meanLuma(frame.y, frame.width, frame.height)

/**
 * Keeps a window black after it changed to another view of the iPhone's picture, until that view is in: the iPhone
 * draws a view only once asked (showUI), which the window does on black, and its first pictures after the ask can
 * still be the view left or a blank one. Pictures that came before the ask are the old view's. The picture may rise
 * once pictures have come for [settleMillis] since the first after the ask, or after [maxMillis] on black whatever
 * came, so a lost ask never leaves the window black. [log] gets each wait's times and the first pictures' brightness.
 *
 * [hold], [picture] and [open] run on the window's thread, [sent] on the one that asked.
 */
internal class RevealGate(
    private val settleMillis: Long = SETTLE_MILLIS,
    private val maxMillis: Long = MAX_HOLD_MILLIS,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::nanoTime,
) {
    private var generation = 0
    private var heldAt = NONE
    private var firstPictureAt = NONE
    private val lumas = mutableListOf<Int>()
    // The wait the ask went out for, and when.
    private val sent = AtomicReference<Pair<Int, Long>?>()

    /** The window is black on another view, and the ask for it goes out now; the number goes to [sent]. */
    fun hold(): Int {
        generation++
        heldAt = now()
        firstPictureAt = NONE
        lumas.clear()
        return generation
    }

    /** The ask for the view of wait [generation] went out. */
    fun sent(generation: Int = this.generation) = sent.set(generation to now())

    /** A picture came, its average brightness [luma] from 0 (black) to 255 (white). */
    fun picture(luma: Int) {
        if (heldAt == NONE || askedAt() == NONE) return
        if (firstPictureAt == NONE) firstPictureAt = now()
        if (lumas.size < LUMAS_LOGGED) lumas += luma
    }

    /** Whether the picture may rise now; true from the moment a wait ends until the next [hold]. */
    fun open(): Boolean {
        if (heldAt == NONE) return true
        val time = now()
        val settled = firstPictureAt != NONE && time - firstPictureAt >= settleMillis * 1_000_000
        if (!settled && time - heldAt < maxMillis * 1_000_000) return false
        val asked = askedAt()
        log(
            when {
                asked == NONE -> "view: not asked for ${millis(time - heldAt)} ms on black; revealed"
                firstPictureAt == NONE ->
                    "view: asked ${millis(asked - heldAt)} ms after black, no picture since; revealed ${millis(time - heldAt)} ms after black"
                else ->
                    "view: asked ${millis(asked - heldAt)} ms after black, first picture ${millis(firstPictureAt - asked)} ms " +
                        "after the ask (luma ${lumas.joinToString()}), revealed ${millis(time - heldAt)} ms after black"
            },
        )
        heldAt = NONE
        return true
    }

    private fun askedAt(): Long = sent.get()?.takeIf { it.first == generation }?.second ?: NONE

    private fun millis(nanos: Long) = nanos / 1_000_000

    companion object {
        private const val NONE = Long.MIN_VALUE
        private const val LUMAS_LOGGED = 5

        /** How long the asked-for view's pictures play on black before they show, a guess the log's times can tune. */
        const val SETTLE_MILLIS = 300L

        /** The longest a window waits on black for a view. */
        const val MAX_HOLD_MILLIS = 2_000L
    }
}
