package io.github.numberonebot.chairplay.media

import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.EchoReference
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.Mixer

/**
 * Shows the main CarPlay screen in a [VideoWindow], and the altscreen (the car's instrument cluster) in
 * [altscreen] when there is one. Each screen stream's thread decodes and hands each picture to its window,
 * which draws it on the GL thread.
 *
 * Audio goes to [audio], which decodes and plays each stream on its own thread. A microphone stream
 * gets one [MicrophoneSender] capturing [microphone] until the iPhone stops it. With [echoCancellation], a call's
 * audio stream records what it plays and the call's microphone stream cancels it, as AndroidMediaSink does.
 */
class DesktopMediaSink(
    private val window: VideoWindow,
    private val audio: AudioRenderer,
    private val log: (String) -> Unit = {},
    /** The microphone (audio.input), or the Windows default recording device when it is null. */
    private val microphone: Mixer.Info? = null,
    /** audio.echo.cancellation: run SpeexDSP's echo canceller on calls. */
    private val echoCancellation: Boolean = false,
    /** altscreen.enabled: the window for the cluster stream; its frames are ignored without one. */
    private val altscreen: VideoWindow? = null,
) : MediaSink, Closeable {
    private val diagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    // The handler sends a command, and a failed send closes the session, which stops this stream and
    // closes the decoder. Run inside decode() that would free the decoder under its own feet, so the
    // request is posted away from the decoder's call path (as AndroidMediaSink does).
    private val recoveryExecutor = Executors.newSingleThreadExecutor { Thread(it, "video-recovery").apply { isDaemon = true } }
    private val microphones = ConcurrentHashMap<AudioStreamId, MicrophoneSender>()
    private val callEchoReferences = ConcurrentHashMap<AudioStreamId, EchoReference>()
    private val screens: Map<Int, Screen> = buildMap {
        put(MAIN_SCREEN, Screen(MAIN_SCREEN, window))
        if (altscreen != null) put(ALT_SCREEN, Screen(ALT_SCREEN, altscreen))
    }

    /** One screen stream's decoder and window. */
    private inner class Screen(private val type: Int, private val window: VideoWindow) {
        private val lock = Any()
        private var decoder: VideoDecoder? = null
        var h264 = true
        private var lastRecoveryNanos = 0L
        private val firstFramePending = AtomicBoolean()

        init {
            window.onFrameShown = {
                if (firstFramePending.compareAndSet(true, false)) diagnosticHandlers[type]?.invoke(FIRST_FRAME)
            }
        }

        /** A configuration starts a stream, so it starts a fresh decoder and awaits that stream's first picture. */
        fun configure(codecData: ByteArray) = synchronized(lock) {
            decoder?.close()
            decoder = VideoDecoder(window::show, ::recover).also { it.configure(codecData) }
            firstFramePending.set(true)
        }

        fun decode(nalus: ByteArray) = synchronized(lock) { decoder?.decode(nalus) }

        fun stop() = synchronized(lock) {
            decoder?.close()
            decoder = null
        }

        /** The decoder rejected a packet: ask the iPhone for a keyframe of this screen, at most once a second. */
        private fun recover(reason: String) {
            log("video decoder ($type): $reason")
            if (recoveryExecutor.isShutdown) return
            val now = System.nanoTime()
            if (now - lastRecoveryNanos < RECOVERY_INTERVAL_NANOS && lastRecoveryNanos != 0L) return
            lastRecoveryNanos = now
            recoveryExecutor.execute { recoveryHandlers[type]?.invoke() }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        val screen = screens[type] ?: return
        screen.h264 = codec == VideoCodec.H264
        if (!screen.h264) log("video codec $codec of screen $type is not supported; frames are ignored")
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val screen = screens[type]?.takeIf { it.h264 } ?: return
        screen.configure(codecData)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        val screen = screens[type]?.takeIf { it.h264 } ?: return
        screen.decode(naluBytes)
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        diagnosticHandlers[type] = handler
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        recoveryHandlers[type] = handler
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (active) return
        val screen = screens[type] ?: return
        diagnosticHandlers.remove(type)
        recoveryHandlers.remove(type)
        screen.stop()
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        // A restarted stream gets a fresh reference, as AndroidMediaSink gives each renderer its own ring.
        val echoReference = if (echoCancellation && format.audioType == TELEPHONY_AUDIO_TYPE && format.sampleRate > 0) {
            EchoReference(format.sampleRate).also { callEchoReferences[id] = it }
        } else {
            callEchoReferences.remove(id)
            null
        }
        audio.start(id, format, echoReference)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) =
        audio.push(id, rtp, sample)

    override fun onAudioStopped(id: AudioStreamId) {
        audio.stop(id)
        callEchoReferences.remove(id)
    }

    // The engine calls this on the downlink thread: a missing device only costs this stream its uplink.
    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        microphones.remove(id)?.close()
        val echoReference = if (config.audioType == TELEPHONY_AUDIO_TYPE) callEchoReferences[id] else null
        val sender = openMicrophoneSender(config, log, microphone, echoReference) ?: return
        microphones[id] = sender
        sender.start()
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        microphones.remove(id)?.close()
    }

    /**
     * Fades the sound out over [durationMillis], for a reconnect or a close: the graceful end of the session waits
     * seconds for the iPhone. The next session's streams play at full volume.
     */
    fun fadeOut(durationMillis: Long) = audio.fadeOut(durationMillis)

    override fun close() {
        screens.values.forEach { it.stop() }
        recoveryExecutor.shutdownNow()
        microphones.keys.toList().forEach(::onMicrophoneStopped)
        callEchoReferences.clear()
        audio.close()
        window.close()
        altscreen?.close()
    }

    private companion object {
        // The screen stream types CarPlayMediaEngine uses for the main display and the altscreen (cluster).
        const val MAIN_SCREEN = 110
        const val ALT_SCREEN = 111
        const val FIRST_FRAME = "first frame rendered"
        const val RECOVERY_INTERVAL_NANOS = 1_000_000_000L
        const val TELEPHONY_AUDIO_TYPE = "telephony"
    }
}
