package com.shilapi.xcertplay.desktop.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import com.shilapi.xcertplay.media.CallEchoCanceller
import com.shilapi.xcertplay.media.EchoReference
import com.shilapi.xcertplay.media.SpeexEchoCanceller
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import javax.sound.sampled.AudioFormat as JavaAudioFormat

/**
 * Seals each captured frame the way MicrophoneUplink does on Android (big-endian PCM body, or the Opus
 * packets [encode] makes of it, one [MicrophoneCounters] per stream) and hands the packet to [send]. [capture] blocks until a whole
 * frame of `config.frameBytes` little-endian PCM is ready and returns null when capture has ended;
 * both are injected so tests never touch a sound device or a socket. [release] frees the line and
 * socket; it must be idempotent and unblock a pending [capture], and runs when the loop ends or on close.
 * With a [canceller] and an [echoReference], each frame first has the call's far end taken out of it, as
 * MicrophoneUplink does; a canceller that fails is closed and the call goes on without it.
 */
class MicrophoneSender internal constructor(
    private val config: MicrophoneConfig,
    private val capture: () -> ByteArray?,
    private val send: (ByteArray) -> Unit,
    private val release: () -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
    /** Turns a captured frame into Opus packets; used only when the iPhone chose Opus for this stream. */
    private val encode: (ByteArray) -> List<ByteArray> = { emptyList() },
    canceller: CallEchoCanceller? = null,
    private val echoReference: EchoReference? = null,
    /** When the end of the frame [capture] just returned was heard, on System.nanoTime's clock. */
    private val frameEndNs: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) : Closeable {
    // Owned by the pump thread once it runs; close() frees it only when the thread never started.
    @Volatile private var canceller = canceller
    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (thread != null) return
        running = true
        thread = Thread(::pump, "microphone-uplink").apply { isDaemon = true; start() }
    }

    private fun pump() {
        val counters = MicrophoneCounters()
        try {
            while (running) {
                val frame = capture() ?: break
                cancelEcho(frame)
                // As MicrophoneUplink.sendFrame: an Opus frame the encoder dropped sends nothing.
                val bodies = if (config.codec == AudioCodecKind.OPUS) encode(frame) else listOf(MicrophonePacketizer.toWirePcm(frame))
                for (body in bodies) {
                    send(
                        MicrophonePacketizer.sealPacket(
                            key = config.key,
                            payloadType = config.payloadType,
                            counters = counters,
                            body = body,
                            samples = config.rtpSamplesPerPacket,
                        ),
                    )
                }
            }
        } catch (error: Throwable) {
            // A failure after close() is just the line or socket being torn down under the loop.
            if (running) onFailure(error)
        } finally {
            running = false
            release()
            canceller?.close()
            canceller = null
        }
    }

    private fun cancelEcho(frame: ByteArray) {
        val active = canceller ?: return
        val reference = echoReference ?: return
        val far = farEnd ?: ShortArray(active.frameSamples).also { farEnd = it }
        // Ask slightly ahead of the frame so speaker latency the clock misses stays inside the tail.
        reference.read(far, frameEndNs() + ECHO_REFERENCE_LEAD_NS)
        val processed = try {
            active.process(frame, far)
        } catch (error: RuntimeException) {
            log("microphone: echo processing failed: $error")
            false
        } catch (error: LinkageError) {
            log("microphone: echo processing unavailable: $error")
            false
        }
        if (!processed) {
            active.close()
            canceller = null
            log("microphone: echo canceller stopped; frames go out as captured")
        }
    }

    override fun close() {
        running = false
        release()
        val worker = synchronized(this) { thread }
        if (worker != null && worker !== Thread.currentThread()) worker.join(JOIN_MILLIS)
        if (worker == null) {
            canceller?.close()
            canceller = null
        }
    }

    private var farEnd: ShortArray? = null

    internal companion object {
        const val JOIN_MILLIS = 1_000L
        // MicrophoneUplink's values.
        const val ECHO_TAIL_MILLIS = 250
        const val ECHO_REFERENCE_LEAD_NS = 30_000_000L
    }
}

/**
 * Opens [device] (the Windows default recording device when null) at the rate the iPhone asked for and a socket to its data port, or
 * returns null after logging why not. No resampling: a device that cannot record at [MicrophoneConfig.sampleRate]
 * fails this stream and leaves the session running. With [echoReference] (a call's far end), a mono stream at its rate
 * runs SpeexDSP's echo canceller.
 */
internal fun openMicrophoneSender(
    config: MicrophoneConfig,
    log: (String) -> Unit,
    device: Mixer.Info? = null,
    echoReference: EchoReference? = null,
): MicrophoneSender? {
    val format = JavaAudioFormat(config.sampleRate.toFloat(), 16, config.channels, true, false)
    val info = DataLine.Info(TargetDataLine::class.java, format)
    val supported = if (device == null) AudioSystem.isLineSupported(info) else AudioSystem.getMixer(device).isLineSupported(info)
    if (!supported) {
        log("microphone: no input device records ${config.sampleRate} Hz x${config.channels}; stream ${config.audioType} has no uplink")
        return null
    }
    val line = try {
        ((if (device == null) AudioSystem.getLine(info) else AudioSystem.getMixer(device).getLine(info)) as TargetDataLine).also {
            it.open(format, config.frameBytes * LINE_BUFFER_FRAMES)
        }
    } catch (error: Exception) {
        log("microphone: cannot open input: $error")
        return null
    }
    // Bound as DiPlay does on Android: the wildcard IPv6 address serves IPv4 and link-local peers.
    val socket = try {
        DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        }
    } catch (error: Exception) {
        line.close()
        log("microphone: cannot create socket: $error")
        return null
    }
    val encoder = if (config.codec == AudioCodecKind.OPUS) {
        // Upstream CarPlayMediaEngine chooses the bitrate from the negotiated Opus rate.
        OpusFrameEncoder(config.bitrate ?: 48_000) { message, error -> log("microphone: $message: $error") }
    } else null
    val canceller = echoReference?.let { reference ->
        if (config.channels != 1 || config.sampleRate != reference.sampleRate) {
            log("microphone echo canceller skipped rate=${config.sampleRate} channels=${config.channels}")
            null
        } else {
            SpeexEchoCanceller.create(config.frameBytes / 2, config.sampleRate, MicrophoneSender.ECHO_TAIL_MILLIS)
        }
    }
    if (echoReference != null) {
        log("microphone echo canceller enabled=${canceller != null} tail=${MicrophoneSender.ECHO_TAIL_MILLIS}ms")
    }
    val frame = ByteArray(config.frameBytes)
    val bytesPerSecond = config.sampleRate.toLong() * config.channels * 2
    val sender = MicrophoneSender(
        config,
        capture = {
            var filled = 0
            while (filled < frame.size) {
                val count = line.read(frame, filled, frame.size - filled)
                if (count <= 0) return@MicrophoneSender null
                filled += count
            }
            frame.copyOf()
        },
        send = { packet -> socket.send(DatagramPacket(packet, packet.size, config.host, config.port)) },
        release = {
            line.stop()
            line.close()
            socket.close()
            encoder?.close()
        },
        onFailure = { error -> log("microphone: uplink ended: $error") },
        encode = { pcm -> encoder?.encode(pcm).orEmpty() },
        canceller = canceller,
        echoReference = echoReference,
        // Java Sound has no capture timestamp: the frame ended as long ago as what the line has buffered since.
        frameEndNs = { System.nanoTime() - line.available() * 1_000_000_000L / bytesPerSecond },
        log = log,
    )
    line.start()
    return sender
}

private const val LINE_BUFFER_FRAMES = 4
