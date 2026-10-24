package io.github.numberonebot.chairplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.media.MediaCodecSupport
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.concentus.OpusDecoder
import java.io.Closeable

private const val RTP_HEADER_LEN = 12

/**
 * The RTP payload of [rtp]: past the fixed header and any CSRC entries. [AudioStream][com.shilapi.xcertplay.airplay.AudioStream]
 * hands over the 12-byte header followed by the already decrypted payload (the AEAD tag and nonce are
 * gone), so for CarPlay streams, which carry no CSRC, this is simply byte 12 onwards.
 */
fun rtpPayload(rtp: ByteArray): ByteArray {
    val start = RTP_HEADER_LEN + 4 * (rtp[0].toInt() and 0x0f)
    return if (rtp.size <= start) ByteArray(0) else rtp.copyOfRange(start, rtp.size)
}

/** CarPlay's LPCM is big-endian 16-bit; Java Sound's PCM here is little-endian. A stray odd byte is dropped. */
fun lpcmBigEndianToLittle(payload: ByteArray): ByteArray {
    val out = ByteArray(payload.size and 1.inv())
    for (i in out.indices step 2) {
        out[i] = payload[i + 1]
        out[i + 1] = payload[i]
    }
    return out
}

/** Turns one RTP payload into 16-bit little-endian interleaved PCM; owned by a single writer thread. */
internal interface PcmDecoder : Closeable {
    fun decode(payload: ByteArray): ByteArray
}

internal class LpcmDecoder : PcmDecoder {
    override fun decode(payload: ByteArray) = lpcmBigEndianToLittle(payload)
    override fun close() {}
}

internal class OpusPcmDecoder(sampleRate: Int, private val channels: Int) : PcmDecoder {
    private val decoder = OpusDecoder(sampleRate, channels)
    private val pcm = ShortArray(MAX_FRAME_SAMPLES * channels)

    override fun decode(payload: ByteArray): ByteArray {
        val samples = decoder.decode(payload, 0, payload.size, pcm, 0, MAX_FRAME_SAMPLES, false)
        val out = ByteArray(samples * channels * 2)
        for (i in 0 until samples * channels) {
            out[2 * i] = pcm[i].toByte()
            out[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        return out
    }

    override fun close() {}

    private companion object {
        // 120 ms at 48 kHz, the longest Opus packet.
        const val MAX_FRAME_SAMPLES = 5760
    }
}

/**
 * AAC-LC over FFmpeg's `aac` decoder. The access unit is wrapped in an ADTS header exactly as the
 * Android sink does, so the decoder needs no extradata; its planar float output is interleaved to
 * 16-bit here.
 */
internal class AacPcmDecoder(private val sampleRate: Int, private val channels: Int) : PcmDecoder {
    private val context: AVCodecContext
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private var closed = false

    init {
        val codec = avcodec_find_decoder(AV_CODEC_ID_AAC) ?: error("FFmpeg has no AAC decoder")
        context = avcodec_alloc_context3(codec)
        check(avcodec_open2(context, codec, null as AVDictionary?) >= 0) { "cannot open the AAC decoder" }
    }

    override fun decode(payload: ByteArray): ByteArray {
        if (closed || payload.isEmpty()) return ByteArray(0)
        val adts = MediaCodecSupport.adtsFrame(payload, sampleRate, channels)
        check(av_new_packet(packet, adts.size) >= 0) { "cannot allocate a packet of ${adts.size} bytes" }
        try {
            packet.data().put(adts, 0, adts.size)
            val sent = avcodec_send_packet(context, packet)
            check(sent >= 0) { "send_packet failed ($sent)" }
        } finally {
            av_packet_unref(packet)
        }
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val received = avcodec_receive_frame(context, frame)
            if (received == AVERROR_EAGAIN() || received == AVERROR_EOF) break
            check(received >= 0) { "receive_frame failed ($received)" }
            try {
                interleave(out)
            } finally {
                av_frame_unref(frame)
            }
        }
        return out.toByteArray()
    }

    private fun interleave(out: java.io.ByteArrayOutputStream) {
        val samples = frame.nb_samples()
        val planar = av_sample_fmt_is_planar(frame.format()) != 0
        val decodedChannels = frame.ch_layout().nb_channels()
        val bytesPerSample = av_get_bytes_per_sample(frame.format())
        check(frame.format() == AV_SAMPLE_FMT_FLTP || frame.format() == AV_SAMPLE_FMT_FLT) {
            "unsupported sample format ${frame.format()}"
        }
        val planes = if (planar) decodedChannels else 1
        val floats = Array(planes) {
            frame.data(it).capacity(samples.toLong() * bytesPerSample * (if (planar) 1 else decodedChannels))
                .asByteBuffer().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        }
        val pcm = ByteArray(samples * decodedChannels * 2)
        for (i in 0 until samples) {
            for (c in 0 until decodedChannels) {
                val value = if (planar) floats[c].get(i) else floats[0].get(i * decodedChannels + c)
                val s = (value * 32768f).toInt().coerceIn(-32768, 32767)
                val at = 2 * (i * decodedChannels + c)
                pcm[at] = s.toByte()
                pcm[at + 1] = (s shr 8).toByte()
            }
        }
        out.write(pcm)
    }

    override fun close() {
        if (closed) return
        closed = true
        avcodec_free_context(context)
        av_packet_free(packet)
        av_frame_free(frame)
    }
}

/** The decoder for [format]'s codec. */
internal fun pcmDecoder(format: AudioFormat): PcmDecoder = when (format.codec) {
    AudioCodecKind.OPUS -> OpusPcmDecoder(format.sampleRate, format.channels)
    AudioCodecKind.AAC_LC -> AacPcmDecoder(format.sampleRate, format.channels)
    AudioCodecKind.LPCM -> LpcmDecoder()
}

private const val MIN_OPUS_PACKET_BYTES = 4

/** What of [rtp] goes to the [codec]'s decoder. */
internal fun audioPayload(rtp: ByteArray, codec: AudioCodecKind) = rtpPayload(rtp).let {
    // Opus packets shorter than 4 bytes are DTX filler; the Android sink skips them as well.
    if (codec == AudioCodecKind.OPUS && it.size < MIN_OPUS_PACKET_BYTES) ByteArray(0) else it
}
