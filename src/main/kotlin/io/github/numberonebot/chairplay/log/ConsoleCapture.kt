package io.github.numberonebot.chairplay.log

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * An output stream that logs each line written to it as `[tag] line` at [level]. A partial line waits for its newline
 * or for [flush], or is logged once it holds 8 KiB; bytes are decoded as UTF-8 only once a line or piece is whole, so a
 * character split across writes is kept.
 */
class LineLogStream(private val tag: String, private val level: LogLevel, private val log: AppLog) : OutputStream() {
    private val pending = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
        if (b == NEWLINE) return emit()
        // A line that never ends is logged in pieces of [PIECE] bytes, each cut before a character starts.
        if (pending.size() >= PIECE && ((b and CONTINUATION_MASK) != CONTINUATION || pending.size() >= PIECE + MAX_CONTINUATIONS)) emit()
        pending.write(b)
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt())
    }

    @Synchronized
    override fun flush() {
        if (pending.size() > 0) emit()
    }

    private fun emit() {
        val text = pending.toString(Charsets.UTF_8).removeSuffix("\r")
        pending.reset()
        log.log(level, "[$tag] $text")
    }

    private companion object {
        const val NEWLINE = '\n'.code
        const val PIECE = 8192
        // UTF-8 continuation bytes are 10xxxxxx; a character has at most three of them.
        const val CONTINUATION_MASK = 0xC0
        const val CONTINUATION = 0x80
        const val MAX_CONTINUATIONS = 3
    }
}

/**
 * Sends the JVM's own System.out and System.err to [log] as warnings: the app runs without a console, and what
 * libraries print there would otherwise be lost.
 */
fun captureConsole(log: AppLog) {
    // No autoflush: it would flush after every write and split a line printed in pieces; the stream emits on newline.
    System.setOut(PrintStream(LineLogStream("stdout", LogLevel.WARNING, log), false, Charsets.UTF_8))
    System.setErr(PrintStream(LineLogStream("stderr", LogLevel.WARNING, log), false, Charsets.UTF_8))
}
