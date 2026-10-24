package io.github.numberonebot.chairplay.media

import java.io.Closeable
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioFormat as JavaAudioFormat

/** Where decoded 16-bit little-endian PCM ends up; a seam so tests never touch a sound device. */
internal interface PcmLine : Closeable {
    fun start()
    fun write(data: ByteArray, length: Int)
    /** Frames the device has played so far, as AudioTrack's playback head; null when unknown. */
    fun playedFrames(): Long? = null
}

internal fun interface PcmLineFactory {
    fun open(sampleRate: Int, channels: Int, bufferBytes: Int): PcmLine
}

private class JavaSoundLine(private val line: SourceDataLine) : PcmLine {
    override fun start() = line.start()
    override fun write(data: ByteArray, length: Int) {
        line.write(data, 0, length)
    }

    override fun playedFrames(): Long = line.longFramePosition

    override fun close() {
        line.stop()
        line.flush()
        line.close()
    }
}

/** Lines on [device], or on the Windows default output when it is null. */
internal fun javaSoundLines(device: Mixer.Info?) = PcmLineFactory { sampleRate, channels, bufferBytes ->
    val format = JavaAudioFormat(sampleRate.toFloat(), 16, channels, true, false)
    val line = if (device == null) AudioSystem.getSourceDataLine(format) else AudioSystem.getSourceDataLine(format, device)
    line.open(format, bufferBytes)
    JavaSoundLine(line)
}
