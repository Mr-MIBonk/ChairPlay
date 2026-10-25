package io.github.numberonebot.chairplay.log

import java.io.BufferedWriter
import java.io.Closeable
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** log.level: how much the app's log tells, each level with those above it. */
enum class LogLevel(val key: String, val shown: String, internal val java: Level) {
    ERROR("error", "ERROR", Level.SEVERE),
    WARNING("warning", "WARN ", Level.WARNING),
    INFO("info", "INFO ", Level.INFO),
    DEBUG("debug", "DEBUG", Level.FINE),
    TRACE("trace", "TRACE", Level.ALL);

    companion object {
        fun of(key: String): LogLevel? = entries.firstOrNull { it.key == key.trim().lowercase() }

        internal fun of(level: Level): LogLevel = entries.first { level.intValue() >= it.java.intValue() }
    }
}

/**
 * The app's log: each line at [level] or above goes to the console and to logs\chairplay-<date-time>.log, with the ten
 * newest such files kept. ChairPlay's own lines, what DiPlay tells the session listener ([fromDiPlay]), and DiPlay's
 * android.util.Log calls, which the shim hands to java.util.logging ([javaLoggingHandler]), all come here. Lines before
 * [open] wait in memory and are written as the file opens.
 */
class AppLog(
    private val clock: () -> LocalDateTime = LocalDateTime::now,
    private val console: PrintStream? = null,
) : Closeable {
    /** Called with the new level each time [level] is set. */
    @Volatile
    var onLevel: (LogLevel) -> Unit = {}

    @Volatile
    var level = LogLevel.INFO
        set(value) {
            field = value
            if (installed) Logger.getLogger("").level = value.java
            onLevel(value)
        }

    @Volatile
    private var installed = false

    private var file: BufferedWriter? = null
    private var early: MutableList<String>? = mutableListOf()

    // What the java.util.logging handler last wrote on this thread: DiPlay's debugLog logs a line there, then tells the
    // listener the same, and that copy is dropped.
    private val lastJavaLine = ThreadLocal<String?>()

    fun log(level: LogLevel, message: String) {
        if (level <= this.level) write("${clock().format(TIME)} ${level.shown} $message")
    }

    fun error(message: String) = log(LogLevel.ERROR, message)

    fun info(message: String) = log(LogLevel.INFO, message)

    /** AirPlaySessionListener.onDebugLog: DiPlay's trace() lines start with TRACE, its debugLog() ones are info. */
    fun fromDiPlay(message: String) {
        if (message.startsWith(TRACE_MARK)) return log(LogLevel.TRACE, message.removePrefix(TRACE_MARK))
        if (lastJavaLine.get() == message) {
            lastJavaLine.remove()
            return
        }
        info(message)
    }

    /** Writes java.util.logging's records here, each at its level and with its error's stack. */
    fun javaLoggingHandler(): Handler = object : Handler() {
        override fun publish(record: LogRecord) {
            val message = record.message ?: return
            lastJavaLine.set(message)
            val stack = record.thrown?.let { thrown -> "\n" + StringWriter().also { thrown.printStackTrace(PrintWriter(it)) }.toString().trimEnd() }
            val tag = if (JMDNS_LOGGERS.any { record.loggerName?.startsWith(it) == true }) "[jmdns] " else ""
            log(LogLevel.of(record.level), tag + message + stack.orEmpty())
        }

        override fun flush() {}

        override fun close() {}
    }

    /**
     * Takes java.util.logging's records instead of its console handler, which printed each twice over two lines, and
     * logs an uncaught error's stack before the thread ends.
     */
    fun install() {
        installed = true
        LogManager.getLogManager().reset()
        Logger.getLogger("").apply {
            addHandler(javaLoggingHandler().apply { level = Level.ALL })
            level = this@AppLog.level.java
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()
            error("uncaught in ${thread.name}: $stack")
        }
    }

    /**
     * Starts this run's file `<prefix>-<date-time>.log` in [dir], removing the oldest of that prefix beyond the ten
     * newest; a file that cannot open is left out.
     */
    @Synchronized
    fun open(dir: Path, prefix: String = "chairplay") {
        val lines = early ?: return
        early = null
        runCatching {
            Files.createDirectories(dir)
            val path = dir.resolve("$prefix-${clock().format(FILE_TIME)}.log")
            file = Files.newBufferedWriter(path)
            dir.listDirectoryEntries("$prefix-*.log").sortedByDescending { it.name }.drop(KEPT).forEach { Files.deleteIfExists(it) }
        }.onFailure { console?.println("log file not opened in $dir: $it") }
        lines.forEach { toFile(it) }
    }

    @Synchronized
    private fun write(line: String) {
        console?.println(line)
        early?.let { if (it.size < EARLY_MAX) it += line } ?: toFile(line)
    }

    private fun toFile(line: String) {
        val writer = file ?: return
        runCatching {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }.onFailure {
            file = null
            console?.println("log file stopped: $it")
        }
    }

    @Synchronized
    override fun close() {
        runCatching { file?.close() }
        file = null
    }

    private companion object {
        const val TRACE_MARK = "TRACE "
        val JMDNS_LOGGERS = listOf("javax.jmdns", "org.jmdns")
        const val KEPT = 10
        const val EARLY_MAX = 1000
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
    }
}
