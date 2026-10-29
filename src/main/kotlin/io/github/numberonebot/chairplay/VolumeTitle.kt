package io.github.numberonebot.chairplay

/** Brief volume feedback in the controls title, or the main title while the controls are hidden. */
internal class VolumeTitle(
    private val controlsVisible: () -> Boolean,
    private val controls: (String?) -> Unit,
    private val main: (String?) -> Unit,
    private val clock: () -> Long = System::nanoTime,
) {
    private var until: Long? = null
    private var notice: String? = null
    private var target: ((String?) -> Unit)? = null

    /** Every press renews the feedback, including a step at 0 or 100 percent. Safe from the page's thread too. */
    @Synchronized
    fun show(level: Int, muted: Boolean) {
        until = clock() + 2_000_000_000L
        notice = if (muted) "Muted" else "Volume: $level%"
        display()
    }

    /** Seconds until another window-loop pass is needed; null when there is no feedback left. */
    @Synchronized
    fun pass(): Double? {
        val remaining = (until ?: return null) - clock()
        if (remaining > 0) {
            if (target !== destination()) display()
            return remaining / 1_000_000_000.0
        }
        until = null
        notice = null
        target?.invoke(null)
        target = null
        return null
    }

    private fun destination() = if (controlsVisible()) controls else main

    private fun display() {
        val next = destination()
        if (target !== next) target?.invoke(null)
        target = next
        next(notice)
    }
}
