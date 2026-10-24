package io.github.numberonebot.chairplay.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GaugesTest {
    // The speedometer reaches past the car's top speed as a car's does: 260 for an ordinary car, more for a strong one.
    @Test
    fun `the dials fit the car`() {
        val ordinary = CarSpec()
        val top = CarModel.topSpeed(ordinary) * 3.6
        assertTrue("top $top", top in 200.0..240.0)
        assertEquals(260.0, GaugeScale.speedometer(ordinary).max, 0.0)
        assertTrue(GaugeScale.speedometer(CarSpec(powerHp = 600.0)).max >= 300.0)
        val tach = GaugeScale.tachometer(ordinary)
        assertEquals(7.0, tach.max, 0.0)
        assertEquals(6.5, tach.redFrom!!, 0.0)
    }

    // The gauges show while the car is driven and five seconds after; gps.gauges turns them off.
    @Test
    fun `the gauges show while driving and a while after`() {
        var now = 0L
        val dir = Files.createTempDirectory("chairplay-gauges")
        val driving = Driving(dir, GpsSettings(true, start = GeoPoint(50.0, 30.0)), gamepad = { null }, nanoTime = { now })
        assertNull(driving.gauges())
        driving.control.toggleKeyboard()
        driving.control.pass()
        val view = driving.gauges()!!
        assertEquals(0.0, view.speedKmh, 0.0)
        assertEquals("D1", view.gear)
        driving.control.toggleKeyboard()
        now += 1_000_000_000L
        driving.control.pass()
        assertTrue(driving.gauges() != null)
        now += 5_000_000_000L
        driving.control.pass()
        assertNull(driving.gauges())

        driving.saved(GpsSettings(true, start = GeoPoint(50.0, 30.0), gauges = false), setOf("gps.gauges"))
        driving.control.toggleKeyboard()
        driving.control.pass()
        assertNull(driving.gauges())
    }

    // Lingering, the loop wakes once more as the gauges go, so they do not stay drawn.
    @Test
    fun `the loop wakes as the gauges go`() {
        var now = 0L
        val control = DriveControl(Drive(CarSpec(), GeoPoint(50.0, 30.0), 0.0) { now }, gamepad = { null }, nanoTime = { now })
        control.toggleKeyboard()
        control.pass()
        control.toggleKeyboard()
        now += 1_000_000_000L
        assertEquals(4.0, control.pass()!!, 1e-6)
        now += 4_000_000_000L
        assertNull(control.pass())
    }
}
