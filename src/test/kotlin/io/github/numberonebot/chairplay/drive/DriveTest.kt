package io.github.numberonebot.chairplay.drive

import io.github.numberonebot.chairplay.config.DrivePlaceStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveTest {
    private val kyiv = GeoPoint(50.4501, 30.5234)
    private var now = 0L
    private val frame = 16_666_667L

    private fun Drive.tick(seconds: Double) {
        repeat((seconds * 60).toInt()) { now += frame; advance() }
    }

    // The window loop ticks the drive; the car moves on the earth by its heading, as far as its speed took it.
    @Test
    fun `the car moves along its heading as far as its speed takes it`() {
        val drive = Drive(CarSpec(), kyiv, headingDegrees = 0.0) { now }
        drive.input = CarInput(throttle = 1.0)
        drive.tick(10.0)

        val snapshot = drive.snapshot()
        assertTrue(snapshot.car.speed > 20.0)
        val meters = (snapshot.position.latitude - kyiv.latitude) * GeoPoint.METERS_PER_DEGREE
        assertTrue("meters $meters", meters in 150.0..350.0)
        assertEquals(kyiv.longitude, snapshot.position.longitude, 1e-9)
        assertEquals(CarInput(throttle = 1.0), snapshot.input)

        val east = Drive(CarSpec(), kyiv, headingDegrees = 90.0) { now }
        east.input = CarInput(throttle = 1.0)
        east.tick(10.0)
        assertTrue(east.snapshot().position.longitude > kyiv.longitude)
        assertEquals(kyiv.latitude, east.snapshot().position.latitude, 1e-9)
    }

    // A pause in the loop (the window dragged, the app stalled) must not throw the car down the road.
    @Test
    fun `a long pause in the loop counts as a short step`() {
        val drive = Drive(CarSpec(), kyiv, headingDegrees = 0.0) { now }
        drive.input = CarInput(throttle = 1.0)
        drive.tick(5.0)
        val before = drive.snapshot()
        now += 5_000_000_000L
        drive.advance()
        val after = drive.snapshot()
        val meters = (after.position.latitude - before.position.latitude) * GeoPoint.METERS_PER_DEGREE
        assertTrue("meters $meters", meters < before.car.speed * 0.11)
    }

    // The wheel speed for $PASCD: a sample every 100 ms, each handed out once.
    @Test
    fun `wheel speed is sampled ten times a second and drained once`() {
        val drive = Drive(CarSpec(), kyiv, headingDegrees = 0.0) { now }
        drive.input = CarInput(throttle = 1.0)
        drive.tick(1.0)

        val samples = drive.drainSpeed()
        assertTrue("samples ${samples.size}", samples.size in 9..11)
        assertTrue(samples.zipWithNext().all { (a, b) -> b.elapsedMillis - a.elapsedMillis in 95..120 })
        assertTrue(samples.zipWithNext().all { (a, b) -> b.metersPerSecond >= a.metersPerSecond })
        assertTrue(drive.drainSpeed().isEmpty())
    }

    // Nobody draining (the iPhone did not ask for speed): the samples stay a short history, not a growing one.
    @Test
    fun `undrained samples keep only the latest`() {
        val drive = Drive(CarSpec(), kyiv, headingDegrees = 0.0) { now }
        drive.tick(60.0)
        assertTrue(drive.drainSpeed().size <= Drive.MAX_SAMPLES)
    }

    // A new start in the settings puts the car there, stopped, facing its heading.
    @Test
    fun `placing the car moves it to a new start and stops it`() {
        val drive = Drive(CarSpec(), kyiv, headingDegrees = 0.0) { now }
        drive.input = CarInput(throttle = 1.0)
        drive.tick(3.0)
        val lviv = GeoPoint(49.8397, 24.0297)
        drive.place(lviv, 270.0)

        val snapshot = drive.snapshot()
        assertEquals(lviv, snapshot.position)
        assertEquals(0.0, snapshot.car.speed, 0.0)
        assertEquals(270.0, snapshot.car.headingDegrees, 0.0)
        assertTrue(!drive.moving)
    }

    // A new power or mass from the settings: the same car goes on where it is, as fast as it went.
    @Test
    fun `a new spec keeps the car where it is`() {
        val drive = Drive(CarSpec(), kyiv, headingDegrees = 0.0) { now }
        drive.input = CarInput(throttle = 1.0)
        drive.tick(3.0)
        val before = drive.snapshot()
        drive.respec(CarSpec(powerHp = 400.0))
        assertEquals(before.position, drive.snapshot().position)
        assertEquals(before.car.speed, drive.snapshot().car.speed, 0.0)
        assertEquals(400.0, drive.spec.powerHp, 0.0)
    }

    @Test
    fun `the car's last place is remembered between starts`() {
        val dir = Files.createTempDirectory("chairplay-drive")
        val store = DrivePlaceStore(dir)
        assertEquals(null, store.load())
        store.save(kyiv, 123.5)
        assertEquals(kyiv to 123.5, store.load())
        Files.writeString(dir.resolve("window.properties"), "drive.latitude=north\n")
        assertEquals(null, store.load())
    }
}
