package io.github.numberonebot.chairplay.drive

import io.github.numberonebot.chairplay.config.DrivePlaceStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingTest {
    private val kyiv = GeoPoint(50.4501, 30.5234)
    private val lviv = GeoPoint(49.8397, 24.0297)
    private val dir = Files.createTempDirectory("chairplay-driving")
    private var now = 0L

    private fun driving(settings: GpsSettings) = Driving(dir, settings, gamepad = { null }, nanoTime = { now })

    @Test
    fun `the car starts at its start, or where it stopped when it resumes`() {
        assertEquals(kyiv, driving(GpsSettings(true, start = kyiv, headingDegrees = 90.0)).drive.snapshot().position)
        DrivePlaceStore(dir).save(lviv, 180.0)
        val resumed = driving(GpsSettings(true, start = kyiv, resume = true)).drive.snapshot()
        assertEquals(lviv, resumed.position)
        assertEquals(180.0, resumed.car.headingDegrees, 0.0)
        assertEquals(kyiv, driving(GpsSettings(true, start = kyiv, resume = false)).drive.snapshot().position)
    }

    // Without a start or a place to resume, the car is nowhere: the iPhone gets no position, only the wheel speed.
    @Test
    fun `a car with no start sends no position`() {
        val driving = driving(GpsSettings(true))
        assertFalse(driving.placed)
        val location = driving.location(GpsSettings(true))
        location.onRequested(setOf(1, 2))
        location.start()
        assertNull(location.latestNmea())

        driving.saved(GpsSettings(true, start = kyiv), setOf("gps.start"))
        assertTrue(driving.placed)
        assertTrue(location.latestNmea()!!.startsWith("\$GPGGA"))
    }

    // Saving a start or heading puts the car there at once; saving the car changes it where it is.
    @Test
    fun `saved settings place the car or change it at once`() {
        val driving = driving(GpsSettings(true, start = kyiv, resume = false))
        driving.saved(GpsSettings(true, start = lviv, headingDegrees = 45.0), setOf("gps.start", "gps.heading"))
        assertEquals(lviv, driving.drive.snapshot().position)
        assertEquals(45.0, driving.drive.snapshot().car.headingDegrees, 0.0)

        val strong = GpsSettings(true, start = lviv, headingDegrees = 45.0, car = CarSpec(powerHp = 600.0))
        driving.saved(strong, setOf("gps.car.power.hp"))
        assertEquals(lviv, driving.drive.snapshot().position)
        assertEquals(600.0, driving.settings.car.powerHp, 0.0)
        assertEquals(600.0, driving.drive.spec.powerHp, 0.0)

        driving.saved(strong.copy(car = strong.car.copy(handling = Handling.SHARP)), setOf("gps.car.handling"))
        assertEquals(Handling.SHARP, driving.drive.spec.handling)
    }

    // The page's heading field turns the car where it stands, rather than sending it back to the start point.
    @Test
    fun `a heading saved alone turns the car where it is`() {
        val driving = driving(GpsSettings(true, start = kyiv, resume = false))
        driving.drive.input = CarInput(throttle = 1.0)
        repeat(120) { now += 16_666_667L; driving.drive.advance() }
        val there = driving.drive.snapshot().position
        driving.saved(GpsSettings(true, start = kyiv, headingDegrees = 200.0), setOf("gps.heading"))
        assertEquals(there, driving.drive.snapshot().position)
        assertEquals(200.0, driving.drive.snapshot().car.headingDegrees, 0.0)
    }

    // Where the car stopped is kept for the next start, and again as the app closes.
    @Test
    fun `the car's place is remembered as it stops and as the app closes`() {
        val driving = driving(GpsSettings(true, start = kyiv, resume = false))
        driving.drive.input = CarInput(throttle = 1.0)
        repeat(120) { now += 16_666_667L; driving.drive.advance() }
        driving.close()
        val (place, _) = DrivePlaceStore(dir).load()!!
        assertTrue(place.latitude > kyiv.latitude)
    }

    // With the GPS off the car stays put: the gamepad and drive mode do nothing, and a drive mode on ends.
    @Test
    fun `the car is driven only while the GPS is on`() {
        val off = Driving(dir, GpsSettings(false, start = kyiv), gamepad = { floatArrayOf(0f, 0f, 0f, 0f, -1f, 1f) }, nanoTime = { now })
        assertNull(off.pass())
        assertFalse(off.toggleKeyboard())
        assertEquals(0.0, off.drive.snapshot().car.speed, 0.0)

        val on = Driving(dir, GpsSettings(true, start = kyiv), gamepad = { null }, nanoTime = { now })
        assertTrue(on.toggleKeyboard())
        on.drive.input = CarInput(throttle = 1.0)
        repeat(120) { now += 16_666_667L; on.drive.advance() }
        assertTrue(on.drive.moving)
        // Turned off mid-drive: the car stops where it is, rather than standing still at speed for the iPhone, and
        // one more pass draws the gauges away.
        on.saved(GpsSettings(false, start = kyiv), setOf("gps.enabled"))
        assertEquals(DriveControl.FRAME_SECONDS, on.pass())
        assertFalse(on.control.keyboard)
        assertFalse(on.drive.moving)
        assertNull(on.gauges())
        assertNull(on.pass())
    }

    // The GPS page: where the car is, how it goes, and what the iPhone asked for.
    @Test
    fun `the state tells the page about the car and the iPhone`() {
        val driving = driving(GpsSettings(true, start = kyiv))
        val idle = driving.state()
        assertEquals(false, idle["listening"])
        assertEquals(false, idle["keyboard"])
        assertEquals(false, idle["gamepad"])
        assertEquals(listOf(50.4501, 30.5234), idle["position"])
        assertEquals(0, idle["speed"])
        assertEquals("P", idle["gear"])

        val location = driving.location(GpsSettings(true))
        location.onRequested(setOf(1, 2, 4))
        location.start()
        location.latestNmea()
        val state = driving.state()
        assertEquals(true, state["listening"])
        assertEquals(listOf("GGA", "RMC", "PASCD"), state["sentences"])
        assertEquals(1L, state["sent"])
    }
}
