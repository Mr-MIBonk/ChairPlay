package io.github.numberonebot.chairplay.drive

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.util.concurrent.atomic.AtomicBoolean

/** Lazy, bounded background loading: the GPS sender never waits for the network or disk.
 * Downloads one public constellation for all locations; no car coordinates leave the application.
 */
internal class AlmanacCache(
    private val file: Path,
    private val log: (String) -> Unit = {},
    private val download: () -> String = ::downloadYuma,
    private val execute: (() -> Unit) -> Unit = { Thread(it, "gps-almanac").apply { isDaemon = true }.start() },
) : AutoCloseable {
    @Volatile private var almanac: GpsAlmanac? = null
    @Volatile private var nextAttempt = Long.MIN_VALUE
    @Volatile private var closed = false
    private val busy = AtomicBoolean()
    private val lifecycle = Any()

    fun current(millis: Long): GpsAlmanac? {
        if (closed) return null
        if (millis >= nextAttempt && busy.compareAndSet(false, true)) {
            try { execute { load(millis) } }
            catch (error: Exception) {
                busy.set(false)
                nextAttempt = millis + RETRY_MILLIS
                log("GPS almanac: unable to start refresh (${error.message})")
            }
        }
        return almanac?.takeIf { it.validAt(millis) }
    }

    fun sentences(point: GeoPoint, millis: Long): String? = current(millis)?.let {
        SatelliteGeometry.gsv(SatelliteGeometry.visible(it, point, millis))
    }

    private fun load(millis: Long) {
        var success = false
        try {
            if (closed) return
            if (almanac == null && Files.isRegularFile(file)) {
                runCatching {
                    require(Files.size(file) <= MAX_BYTES) { "cached almanac is too large" }
                    val cached = GpsAlmanac.parse(Files.readString(file))
                    if (cached.validAt(millis)) {
                        almanac = cached
                        log("GPS almanac: using cached constellation (${cached.satellites.size} satellites)")
                        val age = millis - Files.getLastModifiedTime(file).toMillis()
                        success = age in 0 until REFRESH_MILLIS
                    }
                }.onFailure { log("GPS almanac: cache unavailable (${it.message})") }
                if (success) return
            }
            val text = download()
            val fresh = GpsAlmanac.parse(text)
            require(fresh.validAt(millis)) { "downloaded almanac epoch is too far from current time" }
            synchronized(lifecycle) {
                if (closed) return
                almanac = fresh
                success = true
                runCatching { save(text) }.onFailure { log("GPS almanac: cache could not be saved (${it.message})") }
            }
            log("GPS almanac: refreshed constellation (${fresh.satellites.size} satellites)")
        } catch (error: Exception) {
            log("GPS almanac: refresh failed (${error.message}); keeping only unexpired data")
        } finally {
            nextAttempt = millis + if (success) REFRESH_MILLIS else RETRY_MILLIS
            busy.set(false)
        }
    }

    private fun save(text: String) {
        val target = file.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "gps-almanac-", ".tmp")
        try {
            Files.writeString(temporary, text)
            try { Files.move(temporary, target, REPLACE_EXISTING, ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    override fun close() { synchronized(lifecycle) { closed = true } }

    companion object {
        private const val REFRESH_MILLIS = 6 * 3600_000L
        private const val RETRY_MILLIS = 15 * 60_000L
        private const val MAX_BYTES = 65536
    }
}

private fun downloadYuma(): String {
    val connection = URI("https://celestrak.org/GPS/almanac/Yuma/almanac.yuma.txt").toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 5000
    connection.readTimeout = 5000
    connection.setRequestProperty("User-Agent", "ChairPlay GPS almanac")
    try {
        if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
        val bytes = connection.inputStream.use { it.readNBytes(65537) }
        require(bytes.size <= 65536) { "almanac download is too large" }
        return bytes.toString(Charsets.US_ASCII)
    } finally { connection.disconnect() }
}
