package io.github.numberonebot.chairplay.log

import io.github.numberonebot.chairplay.media.FfmpegLog
import java.io.PrintWriter
import java.io.StringWriter

/** Sends everything the JVM prints to [log]: java.util.logging and uncaught errors, System.out and System.err. */
fun routeAllOutput(log: AppLog) {
    log.install()
    captureConsole(log)
}

/**
 * Sends FFmpeg's native log to [log], following the log's level, through [install]. It loads FFmpeg's natives, which can
 * fail (a missing or broken DLL): the failure is logged and the app goes on, the decoder failing again when a session
 * needs it. Call it once the log file is open, so the failure reaches it.
 */
fun routeFfmpegLog(log: AppLog, install: (AppLog) -> Unit = ::installFfmpegLog) {
    runCatching { install(log) }.onFailure { error ->
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()
        log.error("FFmpeg's log not routed: $stack")
    }
}

private fun installFfmpegLog(log: AppLog) {
    FfmpegLog.install(log)
    FfmpegLog.level(log.level)
    log.onLevel = FfmpegLog::level
}
