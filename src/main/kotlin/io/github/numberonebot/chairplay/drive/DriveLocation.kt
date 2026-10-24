package io.github.numberonebot.chairplay.drive

import com.shilapi.xcertplay.transport.Iap2LocationMessages
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.PascdEncoder
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import java.util.Locale
import kotlin.math.abs

/** A location source that sends at its own pace on the link it is [attach]ed to, rather than DiPlay's once a second. */
interface PacedLocation {
    /** [send] puts the given NMEA text on the link as one LocationInformation; it throws once the link is gone. */
    fun attach(send: (String) -> Unit)
}

/**
 * The spoofed car as the iPhone's GPS, for iAP2 LocationInformation: its place, speed and course in the sentences
 * gps.nmea picks; $GPGSV, a sky of strong satellites, once a second when asked and [satellites] is on; and $PASCD, its
 * wheel speed ten times a second, when asked, for the iPhone to reckon between the fixes. A car not [placed] anywhere
 * yet sends no position, only its wheel speed.
 *
 * [attach]ed to the tunnel, it sends on its own at gps.rate fixes a second (the iPhone asks for one every 250 ms), each
 * with its own time, and DiPlay's once-a-second update sends nothing; unattached, it answers that update.
 */
class DriveLocation(
    private val drive: Drive,
    private val satellites: Boolean,
    private val log: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val placed: () -> Boolean = { true },
    private val settings: () -> GpsSettings = { GpsSettings(enabled = true, rate = 1) },
    /** Runs the action every period, read again each time, until closed. */
    private val schedule: (period: () -> Long, action: () -> Unit) -> AutoCloseable = ::repeatEvery,
) : Iap2LocationProvider, PacedLocation {
    /** What the GPS page shows: whether the iPhone listens, the sentence types it asked for, and the updates sent. */
    data class Status(val listening: Boolean, val requested: Set<Int>, val sent: Long)

    @Volatile private var listening = false
    @Volatile private var requested: Set<Int> = emptySet()
    @Volatile private var sent = 0L
    @Volatile private var send: ((String) -> Unit)? = null
    private val lock = Any()
    private var sender: AutoCloseable? = null
    private var lastSkyNanos: Long? = null
    // A minute of what went, for the log.
    private var windowStart = -1L
    private var windowSent = 0
    private var windowPositions = 0
    private var windowSamples = 0

    override fun attach(send: (String) -> Unit) {
        this.send = send
    }

    override fun onRequested(components: Set<Int>) {
        requested = components
    }

    override fun start(): Boolean {
        listening = true
        val now = settings()
        log("GPS: the iPhone asks for ${components(requested)}; sending ${now.nmea.key} sentences " +
            if (send != null) "at ${now.rate} a second" else "once a second")
        val link = send ?: return true
        synchronized(lock) {
            sender?.close()
            sender = schedule({ 1000L / settings().rate.coerceAtLeast(1) }) { tick(link) }
        }
        return true
    }

    override fun stop() {
        listening = false
        synchronized(lock) {
            sender?.close()
            sender = null
        }
    }

    /** DiPlay's once-a-second update: nothing while this sends on its own. */
    override fun latestNmea(): String? = if (sending()) null else compose()?.also { sent++ }

    fun status() = Status(listening, requested, sent)

    private fun sending() = synchronized(lock) { sender != null }

    private fun tick(link: (String) -> Unit) {
        val nmea = compose() ?: return
        try {
            link(nmea)
            sent++
        } catch (error: Exception) {
            log("GPS: the tunnel took no more updates (${error.message}); sending stops")
            stop()
        }
    }

    /** One update: the position, the sky once a second, and the wheel speed since the last. */
    private fun compose(): String? {
        val now = settings()
        val car = drive.snapshot()
        val position = if (placed()) {
            val course = when {
                car.car.speed > 0 -> car.car.headingDegrees
                car.car.speed < 0 -> (car.car.headingDegrees + 180) % 360
                else -> null
            }
            NmeaSentences.position(NmeaFix(car.position, abs(car.car.speed), course), wallMillis(), now.nmea)
        } else null
        val sky = if (satellites && SATELLITES_IN_VIEW in requested && skyDue()) SatellitesInView.SENTENCES else null
        val reading = if (Iap2LocationMessages.VEHICLE_SPEED_DATA in requested) wheelSpeed(car) else null
        record(position != null, reading?.samples?.size ?: 0, now)
        val text = listOfNotNull(position, sky, reading?.let(PascdEncoder::encode)).joinToString("")
        return text.ifEmpty { null }
    }

    private fun skyDue(): Boolean {
        val now = nanoTime()
        val last = lastSkyNanos
        if (last != null && now - last < SKY_NANOS) return false
        lastSkyNanos = now
        return true
    }

    // Parked with the loop idle, nothing was sampled: one sample at the standstill keeps the wheel speed going.
    private fun wheelSpeed(car: DriveSnapshot): VehicleSpeedReading {
        val samples = drive.drainSpeed().ifEmpty { listOf(VehicleSpeedSample(nanoTime() / 1_000_000, abs(car.car.speed))) }
        val gear = when {
            car.car.gear < 0 -> VehicleGear.REVERSE
            car.car.speed == 0.0 && car.input == CarInput() -> VehicleGear.PARK
            else -> VehicleGear.DRIVE
        }
        return VehicleSpeedReading(gear, samples)
    }

    private fun record(withPosition: Boolean, samples: Int, now: GpsSettings) {
        val millis = nanoTime() / 1_000_000
        if (windowStart < 0) windowStart = millis
        windowSent++
        if (withPosition) windowPositions++
        windowSamples += samples
        if (millis - windowStart < SUMMARY_MILLIS) return
        log("GPS: last ${(millis - windowStart) / 1000} s updates=$windowSent withPosition=$windowPositions " +
            "wheelSpeedSamples=$windowSamples rate=${if (send != null) now.rate else 1} nmea=${now.nmea.key} " +
            "speed=${String.format(Locale.US, "%.1f", drive.snapshot().car.speedKmh)} km/h")
        windowStart = millis
        windowSent = 0
        windowPositions = 0
        windowSamples = 0
    }

    private companion object {
        /** 0xFFFA's selector for $GPGSV, identification field 19; [Iap2LocationMessages.VEHICLE_SPEED_DATA] is 4 for 20. */
        const val SATELLITES_IN_VIEW = Iap2LocationMessages.VEHICLE_SPEED_DATA - 1
        const val SKY_NANOS = 1_000_000_000L
        const val SUMMARY_MILLIS = 60_000L
        val NAMES = mapOf(1 to "GGA", 2 to "RMC", 3 to "GSV", 4 to "PASCD")

        /** The sentence types among 0xFFFA's parameters; the others (0, and 0x8001 on with a number each) as they are. */
        fun components(requested: Set<Int>) = requested.sorted().joinToString(", ") { NAMES[it] ?: "#$it" }
    }
}

/** Runs [action] on a daemon thread every [period] milliseconds, read again each time, until closed. */
fun repeatEvery(period: () -> Long, action: () -> Unit): AutoCloseable {
    val thread = Thread({
        try {
            while (!Thread.currentThread().isInterrupted) {
                val started = System.nanoTime()
                action()
                val left = period() - (System.nanoTime() - started) / 1_000_000
                if (left > 0) Thread.sleep(left)
            }
        } catch (_: InterruptedException) {
        }
    }, "gps-sender").apply {
        isDaemon = true
        start()
    }
    return AutoCloseable { thread.interrupt() }
}

/** $GPGSV for a clear sky: ten satellites spread around, all with a strong signal, in sentences of four. */
internal object SatellitesInView {
    // PRN, elevation, azimuth, signal to noise (dB-Hz).
    private val sky = listOf(
        listOf(2, 67, 45, 47), listOf(5, 41, 112, 45), listOf(7, 23, 170, 42), listOf(9, 55, 220, 46),
        listOf(13, 30, 285, 43), listOf(15, 72, 330, 48), listOf(18, 18, 20, 40), listOf(20, 48, 95, 45),
        listOf(24, 35, 250, 44), listOf(29, 61, 140, 47),
    )

    /** The satellites, by PRN, as $GNGSA lists those in the fix. */
    val PRNS: List<Int> = sky.map { it[0] }

    val SENTENCES: String = sky.chunked(4).let { groups ->
        groups.mapIndexed { index, group ->
            NmeaSentences.sentence(
                "GPGSV,${groups.size},${index + 1},${sky.size}," +
                    group.joinToString(",") { (prn, elevation, azimuth, snr) ->
                        String.format(Locale.US, "%02d,%02d,%03d,%02d", prn, elevation, azimuth, snr)
                    },
            )
        }.joinToString("")
    }
}
