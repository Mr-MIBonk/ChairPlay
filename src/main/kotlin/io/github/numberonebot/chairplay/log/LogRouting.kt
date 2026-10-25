package io.github.numberonebot.chairplay.log

import io.github.numberonebot.chairplay.media.FfmpegLog

/**
 * Sends everything the process prints to [log]: java.util.logging and uncaught errors, System.out and System.err, and
 * FFmpeg's native log, which follows the log's level.
 */
fun routeAllOutput(log: AppLog) {
    log.install()
    captureConsole(log)
    FfmpegLog.install(log)
    log.onLevel = FfmpegLog::level
    FfmpegLog.level(log.level)
}
