package com.shilapi.xcertplay.desktop.media

import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.Mixer

/**
 * Shows the main CarPlay screen in a [VideoWindow]. The screen stream's thread decodes and hands each
 * picture to the window, which draws it on the GL thread.
 *
 * Audio goes to [audio], which decodes and plays each stream on its own thread. A microphone stream
 * gets one [MicrophoneSender] capturing [microphone] until the iPhone stops it.
 */
class DesktopMediaSink(
    private val window: VideoWindow,
    private val audio: AudioRenderer,
    private val log: (String) -> Unit = {},
    /** The microphone (audio.input), or the Windows default recording device when it is null. */
    private val microphone: Mixer.Info? = null,
) : MediaSink, Closeable {
    private val lock = Any()
    private val diagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private var decoder: VideoDecoder? = null
    private var h264 = true
    private var lastRecoveryNanos = 0L
    // The handler sends a command, and a failed send closes the session, which stops this stream and
    // closes the decoder. Run inside decode() that would free the decoder under its own feet, so the
    // request is posted away from the decoder's call path (as AndroidMediaSink does).
    private val recoveryExecutor = Executors.newSingleThreadExecutor { Thread(it, "video-recovery").apply { isDaemon = true } }
    private val microphones = ConcurrentHashMap<AudioStreamId, MicrophoneSender>()
    private val firstFramePending = AtomicBoolean()

    init {
        window.onFrameShown = {
            if (firstFramePending.compareAndSet(true, false)) diagnosticHandlers[MAIN_SCREEN]?.invoke(FIRST_FRAME)
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        if (type != MAIN_SCREEN) return
        h264 = codec == VideoCodec.H264
        if (!h264) log("video codec $codec is not supported; frames are ignored")
    }

    /** A configuration starts a stream, so it starts a fresh decoder and awaits that stream's first picture. */
    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        if (type != MAIN_SCREEN || !h264) return
        synchronized(lock) {
            decoder?.close()
            decoder = VideoDecoder(window::show, ::recover).also { it.configure(codecData) }
            firstFramePending.set(true)
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        if (type != MAIN_SCREEN || !h264) return
        synchronized(lock) { decoder?.decode(naluBytes) }
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        diagnosticHandlers[type] = handler
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        recoveryHandlers[type] = handler
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (active || type != MAIN_SCREEN) return
        diagnosticHandlers.remove(type)
        recoveryHandlers.remove(type)
        synchronized(lock) {
            decoder?.close()
            decoder = null
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) = audio.start(id, format)

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) =
        audio.push(id, rtp, sample)

    override fun onAudioStopped(id: AudioStreamId) = audio.stop(id)

    // The engine calls this on the downlink thread: a missing device only costs this stream its uplink.
    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        microphones.remove(id)?.close()
        val sender = openMicrophoneSender(config, log, microphone) ?: return
        microphones[id] = sender
        sender.start()
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        microphones.remove(id)?.close()
    }

    /** The decoder rejected a packet: ask the iPhone for a keyframe, at most once a second. */
    private fun recover(reason: String) {
        log("video decoder: $reason")
        if (recoveryExecutor.isShutdown) return
        val now = System.nanoTime()
        if (now - lastRecoveryNanos < RECOVERY_INTERVAL_NANOS && lastRecoveryNanos != 0L) return
        lastRecoveryNanos = now
        recoveryExecutor.execute { recoveryHandlers[MAIN_SCREEN]?.invoke() }
    }

    override fun close() {
        synchronized(lock) {
            decoder?.close()
            decoder = null
        }
        recoveryExecutor.shutdownNow()
        microphones.keys.toList().forEach(::onMicrophoneStopped)
        audio.close()
        window.close()
    }

    private companion object {
        // The screen stream type CarPlayMediaEngine uses for the main display (the cluster is 111).
        const val MAIN_SCREEN = 110
        const val FIRST_FRAME = "first frame rendered"
        const val RECOVERY_INTERVAL_NANOS = 1_000_000_000L
    }
}
