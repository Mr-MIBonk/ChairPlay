package io.github.numberonebot.chairplay.media

/** No fade-out in progress. */
internal const val NO_FADE = Long.MIN_VALUE

/** The gain of a fade-out begun at [startNs] and lasting [durationNs]: 1 until it starts, 0 once over, 1 with none. */
internal fun fadeGain(startNs: Long, nowNs: Long, durationNs: Long): Float {
    if (startNs == NO_FADE) return 1f
    val elapsed = nowNs - startNs
    return when {
        elapsed <= 0 -> 1f
        elapsed >= durationNs -> 0f
        else -> 1f - elapsed.toFloat() / durationNs
    }
}

/**
 * How a music stream starts: [silenceMillis] of silence, then a straight rise to full over [fadeMillis]. The iPhone's
 * first second or two of music after a connection come in bursts, and the line runs dry in them; this hides that.
 */
data class StartFade(val silenceMillis: Long, val fadeMillis: Long) {
    /** The gain [elapsedNs] after the stream started playing. */
    fun gain(elapsedNs: Long): Float {
        val intoFade = elapsedNs - silenceMillis * 1_000_000
        if (intoFade < 0) return 0f
        if (fadeMillis <= 0) return 1f
        return (intoFade.toFloat() / (fadeMillis * 1_000_000)).coerceAtMost(1f)
    }

    companion object {
        val NONE = StartFade(0, 0)
    }
}

/** Multiplies the first [length] bytes of little-endian 16-bit PCM by [gain], in place. */
internal fun scaleSamples(pcm: ByteArray, length: Int, gain: Float) {
    var at = 0
    while (at + 1 < length) {
        scaleSample(pcm, at, gain)
        at += 2
    }
}

/** Multiplies the little-endian 16-bit sample at [at] by [gain], in place. */
internal fun scaleSample(pcm: ByteArray, at: Int, gain: Float) {
    val sample = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xFF)).toShort()
    val scaled = (sample * gain).toInt()
    pcm[at] = scaled.toByte()
    pcm[at + 1] = (scaled shr 8).toByte()
}
