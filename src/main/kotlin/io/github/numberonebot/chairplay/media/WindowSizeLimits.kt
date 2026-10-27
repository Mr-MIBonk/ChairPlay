package io.github.numberonebot.chairplay.media

import io.github.numberonebot.chairplay.config.*

/** Large CarPlay windows use the same physical-pixel minimum as the AltScreen. */
internal fun windowSizeLimit(minimum: WindowSize, aspect: WindowSize?, stream: WindowSize?, scale: Float, minScales: Boolean): WindowSize {
    if (minScales && stream != null && stream.width > ALTSCREEN_MIN_WIDTH) {
        return WindowSize(ALTSCREEN_MIN_WIDTH, 1).fittedTo(stream)
    }
    val min = aspect?.let(minimum::grownTo) ?: minimum
    return min.limitFor(if (minScales) scale else 1f, stream)
}
