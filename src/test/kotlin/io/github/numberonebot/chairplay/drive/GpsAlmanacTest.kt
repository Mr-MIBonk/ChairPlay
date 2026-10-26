package io.github.numberonebot.chairplay.drive

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.math.*

internal fun almanacFixture() = GpsAlmanacTest::class.java.getResource("/gps/week0394.yuma")!!.readText()
internal val almanacTime = Instant.parse("2026-10-26T16:57:18Z").toEpochMilli()

class GpsAlmanacTest {
    @Test fun `real Yuma constellation parses and freshness follows its orbital epoch`() {
        val almanac = GpsAlmanac.parse(almanacFixture())
        assertTrue(almanac.satellites.size in 24..32)
        assertTrue(almanac.validAt(almanacTime))
        assertFalse(almanac.validAt(almanacTime + 15 * 86_400_000L))
        assertFalse(almanac.validAt(almanacTime - 15 * 86_400_000L))
        assertEquals(2442, almanac.satellites.first().fullWeek(almanacTime))
    }

    @Test fun `malformed duplicate and partial downloads are rejected`() {
        val text = almanacFixture()
        for (bad in listOf("<html>error</html>", text.substringBefore("******** Week 394 almanac for PRN-02"),
            text + text, text.replace("5153.682617", "NaN"), text.replace("147456.0000", "999999.0"),
            text.replace("ID:                         01", "ID:                         99"))) {
            assertThrows(IllegalArgumentException::class.java) { GpsAlmanac.parse(bad) }
        }
    }

    @Test fun `circular orbit agrees with analytic position across ten bit week rollover`() {
        val satellite = GpsOrbit(1, 0, 394, 0.0, sqrt(26_560_000.0), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val epoch = almanacTime - 147456_000L
        val first = satellite.ecef(epoch)
        assertEquals(26_560_000.0, first.x, 1e-4)
        assertEquals(0.0, first.y, 1e-4)
        val quarter = PI / 2 / sqrt(3.986005e14 / 26_560_000.0.pow(3))
        val later = satellite.ecef(epoch + (quarter * 1000).toLong())
        assertEquals(PI / 2 - 7.2921151467e-5 * quarter, atan2(later.y, later.x), 1e-6)
    }

    @Test fun `observer geometry distinguishes overhead horizon and antipodes`() {
        assertEquals(90.0, SatelliteGeometry.angles(Ecef(26_560_000.0, 0.0, 0.0), GeoPoint(0.0, 0.0)).elevation, 1e-8)
        val horizon = SatelliteGeometry.angles(Ecef(6_378_137.0, 20_000_000.0, 0.0), GeoPoint(0.0, 0.0))
        assertEquals(90.0, horizon.azimuth, 1e-8)
        assertEquals(0.0, horizon.elevation, 1e-8)
        assertEquals(-90.0, SatelliteGeometry.angles(Ecef(26_560_000.0, 0.0, 0.0), GeoPoint(0.0, 180.0)).elevation, 1e-8)
    }

    @Test fun `native sky matches the independent Python experiment reference`() {
        val sky = SatelliteGeometry.visible(GpsAlmanac.parse(almanacFixture()), GeoPoint(50.3855, 30.368), almanacTime)
        // Fixed Yuma fixture and UTC; PRN, elevation, azimuth, rounded to GSV's integer degrees.
        val expected = listOf(Triple(6, 31, 50), Triple(11, 49, 91), Triple(12, 64, 100), Triple(13, 68, 108),
            Triple(19, 6, 59), Triple(24, 15, 172), Triple(25, 74, 295), Triple(28, 31, 309),
            Triple(29, 36, 237), Triple(31, 6, 323), Triple(32, 15, 269))
        assertEquals(expected, sky.map { Triple(it.prn, it.elevation, it.azimuth) })
    }

    @Test fun `sky changes with position and time and sends complete checksummed cycles`() {
        val almanac = GpsAlmanac.parse(almanacFixture())
        val home = SatelliteGeometry.visible(almanac, GeoPoint(50.4, 30.21667), almanacTime)
        assertTrue(home.size >= 4)
        assertNotEquals(home, SatelliteGeometry.visible(almanac, GeoPoint(50.3467, 6.9688), almanacTime))
        assertNotEquals(home, SatelliteGeometry.visible(almanac, GeoPoint(50.4, 30.21667), almanacTime + 3600_000))
        val lines = SatelliteGeometry.gsv(home).trim().split("\r\n")
        assertEquals((home.size + 3) / 4, lines.size)
        lines.forEachIndexed { i, line ->
            val fields = line.substringBefore('*').split(',')
            assertEquals(lines.size, fields[1].toInt())
            assertEquals(i + 1, fields[2].toInt())
            assertEquals(home.size, fields[3].toInt())
            assertEquals(line + "\r\n", NmeaSentences.sentence(line.substring(1).substringBefore('*')))
        }
        val unhealthy = almanac.copy(satellites = almanac.satellites.map { it.copy(health = 1) })
        assertTrue(SatelliteGeometry.visible(unhealthy, GeoPoint(50.4, 30.2), almanacTime).isEmpty())
        assertTrue(SatelliteGeometry.gsv(emptyList()).startsWith("\$GPGSV,1,1,00*"))
    }
}
