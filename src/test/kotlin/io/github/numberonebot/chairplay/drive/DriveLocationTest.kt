package io.github.numberonebot.chairplay.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveLocationTest {
    private val kyiv = GeoPoint(50.4501, 30.5234)
    private var now = 1_000_000_000L
    private val frame = 16_666_667L
    private val wallClock = 1_761_300_000_000L // 2025-10-24 10:00:00 UTC

    private fun drive(heading: Double = 0.0) = Drive(CarSpec(), kyiv, heading) { now }

    private fun location(drive: Drive, satellites: Boolean = false) =
        DriveLocation(drive, satellites, nanoTime = { now }, wallMillis = { wallClock })

    private fun Drive.tick(seconds: Double) {
        repeat((seconds * 60).toInt()) { now += frame; advance() }
    }

    private fun sentence(nmea: String, type: String) = nmea.split("\r\n").firstOrNull { it.startsWith("$$type,") }

    // The parked car: where it stands, no speed, and no course (0 would say north).
    @Test
    fun `a parked car is sent where it stands, with no course`() {
        val location = location(drive())
        location.onRequested(setOf(1, 2))
        assertTrue(location.start())
        val nmea = location.latestNmea()!!

        assertEquals("\$GPGGA,100000.00,5027.0060,N,03031.4040,E,1,08,0.5,0.0,M,0.0,M,,*", sentence(nmea, "GPGGA")!!.dropLast(2))
        val rmc = sentence(nmea, "GPRMC")!!.split(',')
        assertEquals("0.00", rmc[7])
        assertEquals("", rmc[8])
        assertNull(sentence(nmea, "PASCD"))
        assertNull(sentence(nmea, "GPGSV"))
    }

    // On the move: the speed in knots and the course over the ground, opposite the heading when backing up.
    @Test
    fun `a moving car is sent with its speed and course`() {
        val drive = drive(heading = 90.0)
        drive.input = CarInput(throttle = 1.0)
        drive.tick(5.0)
        val location = location(drive)
        location.start()
        val rmc = sentence(location.latestNmea()!!, "GPRMC")!!.split(',')
        assertEquals(drive.snapshot().car.speed * 1.94384449, rmc[7].toDouble(), 0.01)
        assertEquals(90.0, rmc[8].toDouble(), 0.5)

        val back = drive(heading = 90.0)
        back.input = CarInput(brake = 1.0)
        back.tick(3.0)
        val reversing = location(back)
        reversing.start()
        assertEquals(270.0, sentence(reversing.latestNmea()!!, "GPRMC")!!.split(',')[8].toDouble(), 0.5)
    }

    // $PASCD when asked (selector 4): the samples since the last sentence, with the gear.
    @Test
    fun `wheel speed goes with the position when asked`() {
        val drive = drive()
        drive.input = CarInput(throttle = 1.0)
        val location = location(drive)
        location.onRequested(setOf(1, 2, 4))
        location.start()
        drive.tick(1.0)
        val pascd = sentence(location.latestNmea()!!, "PASCD")!!.split(',')
        assertEquals("D", pascd[3])
        assertTrue(pascd[5].toInt() in 9..11)

        // Parked with nothing ticking: still one sample, at zero, in park.
        val parked = location(drive())
        parked.onRequested(setOf(1, 2, 4))
        parked.start()
        val still = sentence(parked.latestNmea()!!, "PASCD")!!.split(',')
        assertEquals("P", still[3])
        assertEquals("1", still[5])
        assertEquals("0.000", still[7].substringBefore('*'))
    }

    @Test
    fun `backing up says reverse in the wheel speed`() {
        val drive = drive()
        drive.input = CarInput(brake = 1.0)
        val location = location(drive)
        location.onRequested(setOf(1, 2, 4))
        location.start()
        drive.tick(3.0)
        assertEquals("R", sentence(location.latestNmea()!!, "PASCD")!!.split(',')[3])
    }

    // $GPGSV when asked (selector 3) and turned on: a sky of strong satellites, in sentences of four.
    @Test
    fun `satellites in view are sent when asked and turned on`() {
        val location = location(drive(), satellites = true)
        location.onRequested(setOf(1, 2, 3))
        location.start()
        val nmea = location.latestNmea()!!
        val gsv = nmea.split("\r\n").filter { it.startsWith("\$GPGSV,") }
        assertEquals(3, gsv.size)
        assertEquals(listOf("3", "1", "10"), gsv[0].split(',').subList(1, 4))
        for (line in gsv) {
            val body = line.substring(1, line.indexOf('*'))
            val checksum = body.fold(0) { sum, c -> sum xor c.code }
            assertEquals(String.format("%02X", checksum), line.substringAfter('*'))
        }
        assertTrue(nmea.endsWith("\r\n"))

        val off = location(drive(), satellites = false)
        off.onRequested(setOf(1, 2, 3))
        off.start()
        assertNull(sentence(off.latestNmea()!!, "GPGSV"))
    }

    // For the GPS page: what the iPhone asked for, whether it listens, and how many updates went.
    @Test
    fun `the link reports what the iPhone asked for and what was sent`() {
        val location = location(drive())
        assertEquals(false, location.status().listening)
        location.onRequested(setOf(1, 2, 4))
        location.start()
        location.latestNmea()
        location.latestNmea()
        assertEquals(DriveLocation.Status(listening = true, requested = setOf(1, 2, 4), sent = 2), location.status())
        location.stop()
        assertEquals(false, location.status().listening)
    }
}
