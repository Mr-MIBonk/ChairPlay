package io.github.numberonebot.chairplay.media

import kotlin.math.pow

/**
 * Hears the iPhone's duckAudio (true) and unduckAudio (false), which bracket each navigation prompt, with the command's
 * params. DiPlay 0.2.22's MediaSink.onAudioDuck carries only a Boolean, losing the iPhone's volume and ramp duration.
 * Keep this adapter until upstream carries those parameters too; then remove it and AudioDuckCommands together.
 */
interface AudioDuckSink {
    fun onAudioDuck(ducked: Boolean, params: Map<String, Any?> = emptyMap())
}

/**
 * audio.duck.db and audio.duck.iphone: the music's gain under a navigation prompt, and whether a level the iPhone sends
 * with duckAudio wins over it.
 */
data class DuckSetting(val level: Float = MediaDucking.DEFAULT_LEVEL, val iPhoneFirst: Boolean = true)

/** What duckAudio asks for: the music's gain under the prompt, and how long the way there takes. */
internal data class DuckRequest(val level: Float, val rampMillis: Long)

/**
 * The params of duckAudio or unduckAudio: the ramp's length (durationMs) and, for a duck, the level to lower the music
 * to (volume, in decibels: an iPhone sends -13.98, a fifth of the volume, and 0 with its unduck). The level is
 * [setting]'s unless it lets the iPhone's win and one came. A missing or nonsensical value takes the default, and a ramp
 * is kept between [MediaDucking.MIN_RAMP_MILLIS] and [MediaDucking.MAX_RAMP_MILLIS].
 */
internal fun duckRequest(params: Map<String, Any?>, setting: DuckSetting): DuckRequest {
    val sent = (params[VOLUME_PARAM] as? Number)?.toDouble()?.takeIf { it.isFinite() && it <= 0 }?.let(::decibelGain)
    val level = sent?.takeIf { setting.iPhoneFirst } ?: setting.level
    val ramp = (params[DURATION_PARAM] as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
        ?.toLong()?.coerceIn(MediaDucking.MIN_RAMP_MILLIS, MediaDucking.MAX_RAMP_MILLIS)
        ?: MediaDucking.DEFAULT_RAMP_MILLIS
    return DuckRequest(level, ramp)
}

/** The linear gain of a level in [decibels]: -6 is about a half, -20 a tenth. */
fun decibelGain(decibels: Double): Float = 10.0.pow(decibels / 20).toFloat()

private const val VOLUME_PARAM = "volume"
private const val DURATION_PARAM = "durationMs"

/**
 * [scaleSamples] with a gain going straight from [startGain] at the first frame towards [endGain] at the next block's
 * first, so a ramp has no steps at block edges.
 */
internal fun rampSamples(pcm: ByteArray, length: Int, channels: Int, startGain: Float, endGain: Float) {
    if (startGain == endGain) return scaleSamples(pcm, length, startGain)
    val frameBytes = 2 * channels
    val frames = length / frameBytes
    for (frame in 0 until frames) {
        val gain = startGain + (endGain - startGain) * frame / frames
        for (at in frame * frameBytes until (frame + 1) * frameBytes step 2) scaleSample(pcm, at, gain)
    }
}

/**
 * The CarPlay media streams' gain under duckAudio and unduckAudio: each command starts a straight ramp from wherever the
 * gain is to the prompt's level or back to 1. A duck that no unduck ends ramps back after [TIMEOUT_NS] from the last
 * duck, as DiPlay's navigation focus gives up on a missing unduckAudio. Commands arrive on the RTSP thread and [gain]
 * is read by each media stream's writer, so every member holds the lock.
 */
internal class MediaDucking(private val log: (String) -> Unit = {}) {
    private var from = 1f
    private var to = 1f
    private var rampStartNs = 0L
    private var rampNs = 0L
    // When a duck with no unduck gives up; NO_DEADLINE while not ducked.
    private var deadlineNs = NO_DEADLINE

    /** A duck is in force: the gain is on its way down or held there. */
    val ducked: Boolean
        @Synchronized get() = to < 1f

    @Synchronized
    fun duck(level: Float, rampNs: Long, nowNs: Long) {
        rampTo(level, rampNs, nowNs)
        deadlineNs = nowNs + TIMEOUT_NS
    }

    @Synchronized
    fun unduck(rampNs: Long, nowNs: Long) {
        rampTo(1f, rampNs, nowNs)
        deadlineNs = NO_DEADLINE
    }

    /** Full volume at once, cutting any ramp short; true when a duck was in force. */
    @Synchronized
    fun reset(): Boolean {
        val was = ducked
        from = 1f
        to = 1f
        deadlineNs = NO_DEADLINE
        return was
    }

    /** The gain at [nowNs]; a writer reads it for both ends of a block, so [nowNs] can lie a block ahead. */
    @Synchronized
    fun gain(nowNs: Long): Float {
        if (deadlineNs != NO_DEADLINE && nowNs >= deadlineNs) {
            val deadline = deadlineNs
            log("audio media: no unduckAudio ${TIMEOUT_NS / 1_000_000_000} s after duckAudio; back to full volume")
            unduck(DEFAULT_RAMP_MILLIS * 1_000_000, deadline)
        }
        return valueAt(nowNs)
    }

    private fun rampTo(target: Float, durationNs: Long, nowNs: Long) {
        from = valueAt(nowNs)
        to = target
        rampStartNs = nowNs
        rampNs = durationNs
    }

    private fun valueAt(nowNs: Long): Float {
        val elapsed = nowNs - rampStartNs
        return when {
            elapsed <= 0 -> from
            elapsed >= rampNs -> to
            else -> from + (to - from) * elapsed / rampNs
        }
    }

    companion object {
        /** Apple's own -14 dB, as an iPhone sends it: the music stays audible under the prompt without covering it. */
        const val DEFAULT_DECIBELS = -14
        val DEFAULT_LEVEL = decibelGain(DEFAULT_DECIBELS.toDouble())
        const val DEFAULT_RAMP_MILLIS = 250L
        // A step within one block clicks; a ramp of seconds is no longer heard as making way for the prompt.
        const val MIN_RAMP_MILLIS = 20L
        const val MAX_RAMP_MILLIS = 2_000L
        // DiPlay's NavigationPromptFocus holds its focus as long; prompts were seen to last up to about 15 s.
        const val TIMEOUT_NS = 30_000_000_000L
        private const val NO_DEADLINE = Long.MIN_VALUE
    }
}
