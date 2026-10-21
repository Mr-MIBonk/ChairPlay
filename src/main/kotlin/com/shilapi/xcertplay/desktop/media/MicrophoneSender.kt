package com.shilapi.xcertplay.desktop.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import javax.sound.sampled.AudioFormat as JavaAudioFormat

/**
 * Seals each captured frame the way MicrophoneUplink does on Android (big-endian PCM body, or the Opus
 * packets [encode] makes of it, one [MicrophoneCounters] per stream) and hands the packet to [send]. [capture] blocks until a whole
 * frame of `config.frameBytes` little-endian PCM is ready and returns null when capture has ended;
 * both are injected so tests never touch a sound device or a socket. [release] frees the line and
 * socket; it must be idempotent and unblock a pending [capture], and runs when the loop ends or on close.
 */
class MicrophoneSender(
    private val config: MicrophoneConfig,
    private val capture: () -> ByteArray?,
    private val send: (ByteArray) -> Unit,
    private val release: () -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
    /** Turns a captured frame into Opus packets; used only when the iPhone chose Opus for this stream. */
    private val encode: (ByteArray) -> List<ByteArray> = { emptyList() },
) : Closeable {
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
        }
    }

    override fun close() {
        running = false
        release()
        val worker = synchronized(this) { thread }
        if (worker != null && worker !== Thread.currentThread()) worker.join(JOIN_MILLIS)
    }

    private companion object {
        const val JOIN_MILLIS = 1_000L
    }
}

/**
 * Opens the default microphone at the rate the iPhone asked for and a socket to its data port, or
 * returns null after logging why not. No resampling: a device that cannot record at [MicrophoneConfig.sampleRate]
 * fails this stream and leaves the session running.
 */
internal fun openMicrophoneSender(config: MicrophoneConfig, log: (String) -> Unit): MicrophoneSender? {
    val format = JavaAudioFormat(config.sampleRate.toFloat(), 16, config.channels, true, false)
    val info = DataLine.Info(TargetDataLine::class.java, format)
    if (!AudioSystem.isLineSupported(info)) {
        log("microphone: no input device records ${config.sampleRate} Hz x${config.channels}; stream ${config.audioType} has no uplink")
        return null
    }
    val line = try {
        (AudioSystem.getLine(info) as TargetDataLine).also {
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
    val frame = ByteArray(config.frameBytes)
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
    )
    line.start()
    return sender
}

private const val LINE_BUFFER_FRAMES = 4
