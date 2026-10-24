package io.github.numberonebot.chairplay.drive

import com.shilapi.xcertplay.transport.Iap2LocationProvider
import io.github.numberonebot.chairplay.config.DrivePlaceStore
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * The spoofed GPS's car for the app: placed where it last stopped ([GpsSettings.resume]) or at gps.start, driven by
 * [control] from the window loop, sent to the iPhone by the [location] each connection makes, and shown on the GPS
 * page by [state]. Its place is kept in window.properties as it stops and as the app closes.
 */
class Driving(
    dir: Path,
    settings: GpsSettings,
    private val log: (String) -> Unit = {},
    gamepad: () -> FloatArray? = GlfwGamepad::axes,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val store = DrivePlaceStore(dir)

    @Volatile var settings: GpsSettings = settings
        private set

    /** Whether the car is anywhere yet: without a start or a place to resume it sends no position. */
    @Volatile var placed: Boolean
        private set

    val drive: Drive

    init {
        val resumed = if (settings.resume) runCatching { store.load() }.getOrNull() else null
        val place = resumed ?: settings.start?.let { it to settings.headingDegrees }
        placed = place != null
        drive = Drive(settings.car, place?.first ?: GeoPoint(0.0, 0.0), place?.second ?: settings.headingDegrees, nanoTime)
    }

    val control = DriveControl(drive, gamepad, onStopped = ::remember)

    @Volatile private var link: DriveLocation? = null

    /**
     * [DriveControl.pass] while the GPS is on. Off, the car stays put: one driven or moving as the GPS went off stops
     * where it is, drive mode ends, and one more pass draws the gauges away.
     */
    fun pass(): Double? {
        if (settings.enabled) return control.pass()
        if (!control.keyboard && !drive.moving) return null
        if (control.keyboard) control.toggleKeyboard()
        drive.stop()
        return DriveControl.FRAME_SECONDS
    }

    /** Turns drive mode on or off and says which; it stays off while the GPS is off. */
    fun toggleKeyboard(): Boolean = if (settings.enabled || control.keyboard) control.toggleKeyboard() else false

    /**
     * Drive mode [on] or off as the GPS page's switch asks, from its thread; the next pass of the window loop puts it in
     * force. False while the GPS is off, which keeps drive mode off.
     */
    fun driveMode(on: Boolean): Boolean {
        if (!settings.enabled) return false
        control.requestKeyboard(on)
        return true
    }

    /** The iPhone's GPS for a connection that identified with it. */
    fun location(gps: GpsSettings): Iap2LocationProvider =
        DriveLocation(drive, gps.satellites, log, nanoTime = nanoTime, placed = { placed }, settings = { settings }).also { link = it }

    /**
     * The gps.* settings saved as [now], [keys] among them: a place puts the car there facing the heading, a heading
     * alone turns it where it is, and a power or mass remakes it.
     */
    fun saved(now: GpsSettings, keys: Set<String>) {
        settings = now
        if ("gps.start" in keys) {
            now.start?.let { start ->
                drive.place(start, now.headingDegrees)
                placed = true
                log("GPS car placed at ${start.latitude}, ${start.longitude}, heading ${now.headingDegrees}°")
            }
        } else if ("gps.heading" in keys) {
            drive.turn(now.headingDegrees)
            log("GPS car turned to ${now.headingDegrees}°")
        }
        if ("gps.car.power.hp" in keys || "gps.car.mass.kg" in keys) {
            drive.respec(now.car)
            log("GPS car ${now.car.powerHp.roundToInt()} hp, ${now.car.massKg.roundToInt()} kg")
        }
    }

    /** What the GPS page shows: the car's place and motion, the controls driving it, and what the iPhone asked for. */
    fun state(): Map<String, Any?> {
        val snapshot = drive.snapshot()
        val car = snapshot.car
        val status = link?.status()
        return linkedMapOf(
            "position" to if (placed) listOf(snapshot.position.latitude, snapshot.position.longitude) else null,
            "heading" to car.headingDegrees.roundToInt() % 360,
            "speed" to car.speedKmh.roundToInt(),
            "rpm" to car.rpm.roundToInt(),
            "gear" to if (car.gear < 0) "R" else "D${car.gear}",
            "throttle" to snapshot.input.throttle,
            "brake" to snapshot.input.brake,
            "keyboard" to control.keyboard,
            "gamepad" to control.gamepad,
            "listening" to (status?.listening ?: false),
            "sentences" to status?.requested?.map { SENTENCES[it] ?: "#$it" },
            "sent" to (status?.sent ?: 0L),
        )
    }

    /** What the gauges show while drive mode is on; null when they are gone. */
    fun gauges(): GaugeView? {
        if (!settings.enabled || !control.keyboard) return null
        val snapshot = drive.snapshot()
        val car = snapshot.car
        return GaugeView(
            speedKmh = car.speedKmh,
            rpm = car.rpm,
            gear = if (car.gear < 0) "R" else "D${car.gear}",
            throttle = snapshot.input.throttle,
            brake = snapshot.input.brake,
            steer = snapshot.input.steer,
            speedometer = GaugeScale.speedometer(),
            tachometer = GaugeScale.tachometer(drive.spec),
        )
    }

    /** Keeps where the car is for the next start; the app calls it as it closes. */
    fun close() = remember(drive.snapshot())

    private fun remember(snapshot: DriveSnapshot) {
        if (!placed) return
        runCatching { store.save(snapshot.position, snapshot.car.headingDegrees) }.onFailure { log("GPS car place not saved: $it") }
    }

    private companion object {
        /** 0xFFFA's selectors, identification fields 17 to 20 less 16. */
        val SENTENCES = mapOf(1 to "GGA", 2 to "RMC", 3 to "GSV", 4 to "PASCD")
    }
}
