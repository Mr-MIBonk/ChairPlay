package io.github.numberonebot.chairplay.drive

import org.junit.Assert.*
import org.junit.Test

class DriveSkyTest {
    private val home = GeoPoint(50.3855, 30.368)
    private val far = GeoPoint(50.3467, 6.9688)

    @Test fun `GSV follows the exact outgoing point and clock at most once a second`() {
        var now = 0L
        var wall = almanacTime
        val drive = Drive(CarSpec(), home, 270.0) { now }
        val requested = mutableListOf<Pair<GeoPoint, Long>>()
        val almanac = GpsAlmanac.parse(almanacFixture())
        val location = DriveLocation(drive, true, nanoTime = { now }, wallMillis = { wall },
            satelliteSentences = { point, millis ->
                requested += point to millis
                SatelliteGeometry.gsv(SatelliteGeometry.visible(almanac, point, millis))
            })
        location.onRequested(setOf(1, 2, 3))
        location.start()
        val first = location.latestNmea()!!
        assertTrue(first.contains("\$GPGSV,"))
        now += 250_000_000
        wall += 250
        assertFalse(location.latestNmea()!!.contains("\$GPGSV,"))
        drive.place(far, 270.0)
        now += 750_000_000
        wall += 750
        val second = location.latestNmea()!!
        assertNotEquals(first.substringAfter("\$GPGSV,"), second.substringAfter("\$GPGSV,"))
        assertEquals(listOf(home to almanacTime, far to wall), requested)
    }

    @Test fun `no sky work when disabled not requested or position unavailable`() {
        for ((enabled, asked, placed) in listOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false))) {
            var called = false
            val location = DriveLocation(Drive(CarSpec(), home, 0.0), enabled, placed = { placed },
                satelliteSentences = { _, _ -> called = true; null })
            location.onRequested(if (asked) setOf(1, 2, 3, 4) else setOf(1, 2, 4))
            location.start()
            assertFalse(location.latestNmea().orEmpty().contains("\$GPGSV,"))
            assertFalse(called)
        }
    }

    @Test fun `missing almanac does not interrupt location or wheel speed`() {
        val location = DriveLocation(Drive(CarSpec(), home, 0.0), true, satelliteSentences = { _, _ -> null })
        location.onRequested(setOf(1, 2, 3, 4))
        location.start()
        val text = location.latestNmea()!!
        for (name in listOf("GPGGA", "GPRMC", "PASCD")) assertTrue(text.contains("$$name,"))
        assertFalse(text.contains("\$GPGSV,"))
    }

    @Test fun `turning the switch off stops sky work before reconnect`() {
        var now = 0L
        var enabled = true
        var calls = 0
        val location = DriveLocation(Drive(CarSpec(), home, 0.0), true, nanoTime = { now },
            settings = { GpsSettings(true, satellites = enabled) },
            satelliteSentences = { _, _ -> calls++; SatelliteGeometry.gsv(emptyList()) })
        location.onRequested(setOf(1, 2, 3))
        location.start()
        assertTrue(location.latestNmea()!!.contains("\$GPGSV,"))
        enabled = false
        now += 1_000_000_000
        assertFalse(location.latestNmea()!!.contains("\$GPGSV,"))
        assertEquals(1, calls)
    }
}
