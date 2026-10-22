package io.github.numberonebot.chairplay.hud.maneuver

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Asks for the HUD's next frame at a time: the window draws only on demand, so an animation asks for each of its
 * frames. Of several requests the earliest wins; one repaint answers them all, and the frame it draws asks again if
 * it still needs to. Nothing runs while nothing asks, so an idle HUD costs no CPU.
 */
class RepaintTimer(
    private val repaint: () -> Unit,
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "hud-frames").apply { isDaemon = true } },
    private val clock: () -> Long = System::nanoTime,
) : Closeable {
    private var pending: ScheduledFuture<*>? = null
    private var due = 0L

    /** Repaints in [millis] milliseconds, unless a repaint is already due by then. */
    @Synchronized
    fun after(millis: Long) {
        val delay = millis.coerceAtLeast(0)
        val at = clock() + TimeUnit.MILLISECONDS.toNanos(delay)
        val current = pending
        if (current != null && !current.isDone && due - at <= 0) return
        current?.cancel(false)
        due = at
        pending = executor.schedule({ repaint() }, delay, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
