package android.util

import java.util.logging.Level
import java.util.logging.Logger

/** The subset of Android's Log that the portable core calls, routed to java.util.logging. */
object Log {
    @JvmStatic fun d(tag: String, msg: String): Int = log(Level.FINE, tag, msg, null)
    @JvmStatic fun d(tag: String, msg: String, tr: Throwable?): Int = log(Level.FINE, tag, msg, tr)
    @JvmStatic fun i(tag: String, msg: String): Int = log(Level.INFO, tag, msg, null)
    @JvmStatic fun i(tag: String, msg: String, tr: Throwable?): Int = log(Level.INFO, tag, msg, tr)
    @JvmStatic fun w(tag: String, msg: String): Int = log(Level.WARNING, tag, msg, null)
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable?): Int = log(Level.WARNING, tag, msg, tr)
    @JvmStatic fun e(tag: String, msg: String): Int = log(Level.SEVERE, tag, msg, null)
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable?): Int = log(Level.SEVERE, tag, msg, tr)

    private fun log(level: Level, tag: String, msg: String, tr: Throwable?): Int {
        Logger.getLogger(tag).log(level, msg, tr)
        return 0
    }
}
