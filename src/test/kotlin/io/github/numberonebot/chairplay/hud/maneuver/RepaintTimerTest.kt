package io.github.numberonebot.chairplay.hud.maneuver

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RepaintTimerTest {
    @Test
    fun `the earliest request wins and one repaint answers them all`() {
        val repaints = AtomicInteger()
        val first = CountDownLatch(1)
        RepaintTimer({ repaints.incrementAndGet(); first.countDown() }).use { timer ->
            val start = System.nanoTime()
            timer.after(500)
            timer.after(20)
            timer.after(300)
            first.await(2, TimeUnit.SECONDS)
            val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertEquals(1, repaints.get())
            assert(waited < 250) { "repainted after $waited ms" }
            Thread.sleep(600)
            assertEquals(1, repaints.get())
        }
    }

    @Test
    fun `a request after the repaint schedules another`() {
        val repaints = AtomicInteger()
        RepaintTimer({ repaints.incrementAndGet() }).use { timer ->
            timer.after(0)
            Thread.sleep(100)
            timer.after(0)
            Thread.sleep(100)
            assertEquals(2, repaints.get())
        }
    }
}
