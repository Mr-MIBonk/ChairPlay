package com.shilapi.xcertplay.desktop.media

import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Stands in for rendering until the video and audio tasks: counts what arrives, once a second. */
class LoggingMediaSink(log: (String) -> Unit) : MediaSink, Closeable {
    private val videoFrames = AtomicLong()
    private val audioRtp = AtomicLong()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "media-counters").apply { isDaemon = true } }

    init {
        timer.scheduleAtFixedRate({ log("video frames ${videoFrames.get()}, audio rtp ${audioRtp.get()}") },
            1, 1, TimeUnit.SECONDS)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoFrames.incrementAndGet()
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRtp.incrementAndGet()
    }

    override fun close() = timer.shutdown()
}
