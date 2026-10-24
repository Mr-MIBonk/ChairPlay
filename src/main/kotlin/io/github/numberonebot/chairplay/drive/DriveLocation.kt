package io.github.numberonebot.chairplay.drive

import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationMessages
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import java.util.Locale
import kotlin.math.abs

/**
 * The spoofed car as the iPhone's GPS, for iAP2 LocationInformation: GGA and RMC from its place, its speed and course
 * every second; $GPGSV, a sky of strong satellites, when asked and [satellites] is on; and $PASCD, its wheel speed ten
 * times a second, when asked, for the iPhone to reckon between the fixes. A car not [placed] anywhere yet sends no
 * position, only its wheel speed.
 */
class DriveLocation(
    private val drive: Drive,
    private val satellites: Boolean,
    log: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val placed: () -> Boolean = { true },
) : Iap2LocationProvider {
    /** What the GPS page shows: whether the iPhone listens, the sentence types it asked for, and the updates sent. */
    data class Status(val listening: Boolean, val requested: Set<Int>, val sent: Long)

    @Volatile private var listening = false
    @Volatile private var requested: Set<Int> = emptySet()
    @Volatile private var sent = 0L

    private val position = object : Iap2LocationProvider {
        override fun start() = true

        override fun stop() = Unit

        override fun latestNmea(): String? {
            if (!placed()) return null
            val car = drive.snapshot()
            val fix = CarPlayLocationFix(
                latitudeDegrees = car.position.latitude,
                longitudeDegrees = car.position.longitude,
                // The course over the ground: none standing still, opposite the heading backing up.
                bearingDegrees = when {
                    car.car.speed > 0 -> car.car.headingDegrees
                    car.car.speed < 0 -> (car.car.headingDegrees + 180) % 360
                    else -> null
                },
                speedMetersPerSecond = abs(car.car.speed),
                accuracyMeters = ACCURACY_METERS,
                timestampMillis = wallMillis(),
            )
            val sky = if (satellites && SATELLITES_IN_VIEW in requested) SatellitesInView.SENTENCES else ""
            return NmeaLocationEncoder.encode(fix) + sky
        }
    }

    private val speed = object : VehicleSpeedSource {
        override fun start() = Unit

        override fun stop() = Unit

        // Parked with the loop idle, nothing was sampled: one sample at the standstill keeps the wheel speed going.
        override fun drain(): VehicleSpeedReading {
            val car = drive.snapshot()
            val samples = drive.drainSpeed().ifEmpty { listOf(VehicleSpeedSample(nanoTime() / 1_000_000, abs(car.car.speed))) }
            val gear = when {
                car.car.gear < 0 -> VehicleGear.REVERSE
                car.car.speed == 0.0 && car.input == CarInput() -> VehicleGear.PARK
                else -> VehicleGear.DRIVE
            }
            return VehicleSpeedReading(gear, samples)
        }
    }

    private val sentences = VehicleSpeedLocationProvider(position, speed, log)

    override fun onRequested(components: Set<Int>) {
        requested = components
        sentences.onRequested(components)
    }

    override fun start(): Boolean {
        listening = true
        return sentences.start()
    }

    override fun stop() {
        listening = false
        sentences.stop()
    }

    override fun latestNmea(): String? = sentences.latestNmea()?.also { sent++ }

    fun status() = Status(listening, requested, sent)

    private companion object {
        /** The HDOP the encoder derives from it is its best, 0.5. */
        const val ACCURACY_METERS = 2.5
        /** 0xFFFA's selector for $GPGSV, identification field 19; [Iap2LocationMessages.VEHICLE_SPEED_DATA] is 4 for 20. */
        const val SATELLITES_IN_VIEW = Iap2LocationMessages.VEHICLE_SPEED_DATA - 1
    }
}

/** $GPGSV for a clear sky: ten satellites spread around, all with a strong signal, in sentences of four. */
internal object SatellitesInView {
    // PRN, elevation, azimuth, signal to noise (dB-Hz).
    private val sky = listOf(
        listOf(2, 67, 45, 47), listOf(5, 41, 112, 45), listOf(7, 23, 170, 42), listOf(9, 55, 220, 46),
        listOf(13, 30, 285, 43), listOf(15, 72, 330, 48), listOf(18, 18, 20, 40), listOf(20, 48, 95, 45),
        listOf(24, 35, 250, 44), listOf(29, 61, 140, 47),
    )

    val SENTENCES: String = sky.chunked(4).let { groups ->
        groups.mapIndexed { index, group ->
            val body = "GPGSV,${groups.size},${index + 1},${sky.size}," +
                group.joinToString(",") { (prn, elevation, azimuth, snr) ->
                    String.format(Locale.US, "%02d,%02d,%03d,%02d", prn, elevation, azimuth, snr)
                }
            val checksum = body.fold(0) { sum, character -> sum xor character.code }
            "$$body*${String.format(Locale.US, "%02X", checksum)}\r\n"
        }.joinToString("")
    }
}
