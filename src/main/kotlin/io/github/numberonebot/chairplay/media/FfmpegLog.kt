package io.github.numberonebot.chairplay.media

import io.github.numberonebot.chairplay.log.AppLog
import io.github.numberonebot.chairplay.log.LogLevel
import org.bytedeco.ffmpeg.avutil.LogCallback
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer

/** The app level an FFmpeg message goes to: its errors are only warnings here, its warnings debug, the rest trace. */
fun ffmpegLevel(avLevel: Int): LogLevel = when {
    avLevel <= avutil.AV_LOG_ERROR -> LogLevel.WARNING
    avLevel <= avutil.AV_LOG_WARNING -> LogLevel.DEBUG
    else -> LogLevel.TRACE
}

/** How much FFmpeg should say for the app's log level, so it does not format messages nobody reads. */
fun avLevelFor(level: LogLevel): Int = when (level) {
    LogLevel.TRACE -> avutil.AV_LOG_VERBOSE
    LogLevel.DEBUG -> avutil.AV_LOG_WARNING
    LogLevel.INFO, LogLevel.WARNING, LogLevel.ERROR -> avutil.AV_LOG_ERROR
}

/** FFmpeg's native log, which printed to the console, goes to the app's log as "[ffmpeg] ..." lines. */
object FfmpegLog {
    // The native side keeps only a pointer: this reference stops the garbage collector from freeing the callback.
    private var callback: LogCallback? = null

    fun install(log: AppLog) {
        val installed = object : LogCallback() {
            override fun call(level: Int, message: BytePointer) {
                log.log(ffmpegLevel(level), "[ffmpeg] " + message.string.trimEnd('\n', '\r'))
            }
        }
        callback = installed
        avutil.setLogCallback(installed)
    }

    fun level(level: LogLevel) = avutil.av_log_set_level(avLevelFor(level))
}
