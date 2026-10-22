package com.shilapi.xcertplay.desktop

import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * One DiPlayDesktop at a time per app folder. The window of a closed run goes at once, but the run ends its
 * CarPlay session gracefully for a few more seconds and holds the Bluetooth channel meanwhile; a run started then
 * found the iPhone busy and connected only on its second try. The lock is a file lock, so the system lets go of
 * it when a run ends, however it ends.
 */
internal class InstanceLock private constructor(private val channel: FileChannel, private val lock: FileLock?) : Closeable {
    override fun close() {
        runCatching { lock?.release() }
        channel.close()
    }

    companion object {
        const val FILE = "DiPlayDesktop.lock"
        private const val POLL_MILLIS = 200L

        /**
         * Takes the lock in [dir], first calling [onWait] and waiting while another run holds it, at most
         * [timeoutMillis]; after that it goes on without the lock rather than not start at all.
         */
        fun acquire(dir: Path, timeoutMillis: Long, onWait: () -> Unit = {}): InstanceLock {
            val channel = FileChannel.open(dir.resolve(FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            var waited = false
            while (true) {
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock != null) return InstanceLock(channel, lock)
                if (System.nanoTime() >= deadline) return InstanceLock(channel, null)
                if (!waited) {
                    waited = true
                    onWait()
                }
                Thread.sleep(POLL_MILLIS)
            }
        }
    }
}
