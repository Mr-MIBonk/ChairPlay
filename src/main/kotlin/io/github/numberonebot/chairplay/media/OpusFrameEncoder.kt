package io.github.numberonebot.chairplay.media

import org.concentus.OpusApplication
import org.concentus.OpusEncoder
import org.concentus.OpusSignal
import java.io.Closeable

/**
 * Encodes 20 ms of 48 kHz mono 16-bit little-endian PCM into one Opus packet, copied from upstream's
 * SoftwareOpusEncoder (shared/src/main/java/com/shilapi/xcertplay/media/SoftwareOpusEncoder.kt). That file
 * implements an interface declared next to Android's MediaCodec encoder, so it cannot be compiled here.
 * Wireless CarPlay sends Siri and calls as Opus.
 */
class OpusFrameEncoder(
    bitrate: Int,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : Closeable {
    private var encoder: OpusEncoder? = OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(bitrate)
        setComplexity(COMPLEXITY)
        setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
    }
    private val output = ByteArray(MAX_PACKET_BYTES)
    private var failures = 0

    /** One packet for a whole frame; nothing for a frame of another size or one that failed. */
    fun encode(pcm: ByteArray): List<ByteArray> {
        val encoder = encoder ?: return emptyList()
        if (pcm.size != FRAME_BYTES) return emptyList()
        return try {
            val length = encoder.encode(pcm, 0, FRAME_SAMPLES, output, 0, output.size)
            if (length > 0) listOf(output.copyOf(length)) else emptyList()
        } catch (error: Exception) {
            failed(encoder, error)
        } catch (error: AssertionError) {
            // Concentus checks the reference library's debug assertions in every build and throws an Error
            // (upstream #865). A failed check costs this frame only.
            failed(encoder, error)
        }
    }

    private fun failed(encoder: OpusEncoder, error: Throwable): List<ByteArray> {
        if (failures++ < MAX_REPORTED_FAILURES) onError("Opus encode failed", error)
        // The frame stopped part way through, so the next one starts from a clean encoder state.
        runCatching { encoder.resetState() }
        return emptyList()
    }

    override fun close() {
        encoder = null
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val FRAME_SAMPLES = 960
        const val FRAME_BYTES = FRAME_SAMPLES * CHANNELS * 2

        /** Upstream's choice: speech matched complexity 10 at about three quarters of its CPU time. */
        private const val COMPLEXITY = 5
        private const val MAX_PACKET_BYTES = 1_275
        private const val MAX_REPORTED_FAILURES = 3
    }
}
