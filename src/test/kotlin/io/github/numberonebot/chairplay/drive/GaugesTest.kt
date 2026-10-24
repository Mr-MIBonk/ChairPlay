package io.github.numberonebot.chairplay.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GaugesTest {
    // As Audi's dials: 240 degrees, from the lower left, clockwise over the top to the lower right (screen angles,
    // y down); half the scale points straight up.
    @Test
    fun `a dial's needle sweeps 240 degrees over the top`() {
        val scale = GaugeScale(max = 260.0, major = 20.0, minor = 10.0)
        assertEquals(150.0, Math.toDegrees(scale.angle(0.0)), 1e-9)
        assertEquals(270.0, Math.toDegrees(scale.angle(130.0)), 1e-9)
        assertEquals(390.0, Math.toDegrees(scale.angle(260.0)), 1e-9)
        assertEquals(390.0, Math.toDegrees(scale.angle(400.0)), 1e-9)
        assertEquals(150.0, Math.toDegrees(scale.angle(-5.0)), 1e-9)
    }

    @Test
    fun `a dial has a tick at each minor step, the major ones numbered`() {
        val ticks = GaugeScale(max = 260.0, major = 20.0, minor = 10.0).ticks()
        assertEquals(27, ticks.size)
        assertEquals(14, ticks.count { it.major })
        assertEquals(listOf(0.0, 10.0, 20.0), ticks.take(3).map { it.value })
        assertEquals(listOf(true, false, true), ticks.take(3).map { it.major })
    }

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
