package com.shilapi.xcertplay.desktop.media

import com.shilapi.xcertplay.media.MediaCodecSupport
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * One decoded picture as tightly packed YUV420P planes (no row padding, so [strides] equal the plane
 * widths). The planes are copies: the decoder reuses its own frame, and the picture is handed to
 * another thread.
 */
class DecodedFrame(
    val width: Int,
    val height: Int,
    val y: ByteBuffer,
    val u: ByteBuffer,
    val v: ByteBuffer,
    val strides: IntArray,
)

/**
 * Software H.264 decoder over FFmpeg. Not thread-safe by design: it is driven from the one thread
 * that reads the screen stream, and [onFrame] runs on that thread.
 *
 * [onError] reports a packet the decoder rejected (or a picture format it cannot show). The caller
 * answers by asking the iPhone for a new keyframe.
 */
class VideoDecoder(
    private val onFrame: (DecodedFrame) -> Unit,
    private val onError: (String) -> Unit = {},
) : Closeable {
    private val context: AVCodecContext
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private var parameterSets: ByteArray? = null
    private var closed = false

    init {
        val codec = avcodec_find_decoder(AV_CODEC_ID_H264) ?: error("FFmpeg has no H.264 decoder")
        context = avcodec_alloc_context3(codec)
        // Low delay and slice threading: frame threading would hold pictures back for several frames.
        context.flags(context.flags() or AV_CODEC_FLAG_LOW_DELAY)
        context.thread_type(FF_THREAD_SLICE)
        context.thread_count(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        check(avcodec_open2(context, codec, null as AVDictionary?) >= 0) { "cannot open the H.264 decoder" }
    }

    /**
     * Takes SPS and PPS from the avcC record. Frames arrive in Annex B without them, so they are put in
     * front of the next frame; a new configuration also drops the pictures of the previous stream.
     */
    @Synchronized
    fun configure(avcC: ByteArray) {
        if (closed) return
        val (sps, pps) = MediaCodecSupport.avcParameterSets(avcC)
        parameterSets = if (sps.isEmpty() || pps.isEmpty()) null else START_CODE + sps + START_CODE + pps
        avcodec_flush_buffers(context)
    }

    @Synchronized
    fun decode(annexB: ByteArray) {
        if (closed) return
        val data = parameterSets?.let { it + annexB } ?: annexB
        parameterSets = null
        if (av_new_packet(packet, data.size) < 0) return onError("cannot allocate a packet of ${data.size} bytes")
        try {
            packet.data().put(data, 0, data.size)
            val sent = avcodec_send_packet(context, packet)
            if (sent < 0) onError("send_packet failed: ${errorText(sent)}")
        } finally {
            av_packet_unref(packet)
        }
        drain()
    }

    private fun drain() {
        while (true) {
            val received = avcodec_receive_frame(context, frame)
            if (received == AVERROR_EAGAIN() || received == AVERROR_EOF) return
            if (received < 0) return onError("receive_frame failed: ${errorText(received)}")
            try {
                if (frame.format() != AV_PIX_FMT_YUV420P && frame.format() != AV_PIX_FMT_YUVJ420P) {
                    onError("unsupported pixel format ${frame.format()}")
                } else {
                    onFrame(copyOut())
                }
            } finally {
                av_frame_unref(frame)
            }
        }
    }

    private fun copyOut(): DecodedFrame {
        val width = frame.width()
        val height = frame.height()
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        return DecodedFrame(
            width, height,
            plane(0, width, height), plane(1, chromaWidth, chromaHeight), plane(2, chromaWidth, chromaHeight),
            intArrayOf(width, chromaWidth, chromaWidth),
        )
    }

    private fun plane(index: Int, width: Int, height: Int): ByteBuffer {
        val stride = frame.linesize(index)
        val source = frame.data(index).capacity(stride.toLong() * (height - 1) + width).asByteBuffer()
        val packed = ByteBuffer.allocateDirect(width * height)
        for (row in 0 until height) {
            source.limit(row * stride + width).position(row * stride)
            packed.put(source)
        }
        return packed.flip()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        // The free functions take pointer-to-pointer and null the handle they are given.
        avcodec_free_context(context)
        av_packet_free(packet)
        av_frame_free(frame)
    }

    private fun errorText(code: Int): String {
        val buffer = ByteArray(64)
        av_strerror(code, buffer, buffer.size.toLong())
        return String(buffer).trimEnd('\u0000') + " ($code)"
    }

    private companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)

        // libavcodec's FF_THREAD_SLICE macro, which the presets do not export.
        const val FF_THREAD_SLICE = 2
    }
}
