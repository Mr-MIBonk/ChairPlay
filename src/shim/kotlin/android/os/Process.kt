package android.os

/** Thread priorities are Android scheduler hints; the desktop JVM leaves them to Windows. */
object Process {
    const val THREAD_PRIORITY_AUDIO: Int = -16

    @JvmStatic fun setThreadPriority(priority: Int) {}
}
