package com.shilapi.xcertplay.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class InstanceLockTest {
    @Test
    fun `the first run takes the lock without waiting`() {
        val dir = Files.createTempDirectory("diplay-lock")
        var waited = false

        InstanceLock.acquire(dir, timeoutMillis = 1_000, onWait = { waited = true }).use {
            assertFalse(waited)
        }
    }

    // A run started while the previous one still ends its session waits for it, then goes on.
    @Test(timeout = 10_000)
    fun `a second run waits until the first one lets go`() {
        val dir = Files.createTempDirectory("diplay-lock")
        val first = InstanceLock.acquire(dir, timeoutMillis = 1_000)
        val waiting = CountDownLatch(1)
        val acquired = CountDownLatch(1)

        thread {
            InstanceLock.acquire(dir, timeoutMillis = 5_000, onWait = { waiting.countDown() }).use { acquired.countDown() }
        }

        assertTrue(waiting.await(2, TimeUnit.SECONDS))
        assertFalse(acquired.await(300, TimeUnit.MILLISECONDS))
        first.close()
        assertTrue(acquired.await(2, TimeUnit.SECONDS))
    }

    // A previous run that hangs must not keep the app from starting.
    @Test(timeout = 10_000)
    fun `a second run goes on after the timeout`() {
        val dir = Files.createTempDirectory("diplay-lock")
        InstanceLock.acquire(dir, timeoutMillis = 1_000).use {
            val started = System.nanoTime()
            InstanceLock.acquire(dir, timeoutMillis = 300).close()
            assertTrue(System.nanoTime() - started >= 300_000_000L)
        }
    }
}
