package io.github.numberonebot.chairplay.drive

import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaSentencesTest {
    private val wallClock = 1_761_300_000_000L // 2025-10-24 10:00:00 UTC

    private fun lines(nmea: String) = nmea.split("\r\n").filter { it.isNotEmpty() }

    private fun checksumOk(line: String): Boolean {
        val body = line.substring(1, line.indexOf('*'))
        return String.format("%02X", body.fold(0) { sum, c -> sum xor c.code }) == line.substringAfter('*')
    }

    // The classic sentences are DiPlay's GGA and RMC, character for character, on a whole second.
    @Test
    fun `classic sentences are DiPlay's on a whole second`() {
        for ((point, course, speed) in listOf(
            Triple(GeoPoint(50.4501, 30.5234), 90.0, 13.9),
            Triple(GeoPoint(-33.8688, -70.6693), null, 0.0),
            Triple(GeoPoint(48.8738, 2.2950), 359.5, 30.0),
        )) {
            val ours = NmeaSentences.position(NmeaFix(point, speed, course), wallClock, NmeaDialect.CLASSIC)
            val diplay = NmeaLocationEncoder.encode(
                CarPlayLocationFix(point.latitude, point.longitude, bearingDegrees = course, speedMetersPerSecond = speed,
                    accuracyMeters = 2.5, timestampMillis = wallClock),
            )
            assertEquals(diplay, ours)
        }
    }

    // Several fixes a second need their own times: hundredths, where DiPlay writes .00.
    @Test
    fun `the time carries hundredths of a second`() {
        val nmea = NmeaSentences.position(NmeaFix(GeoPoint(48.8738, 2.2950), 10.0, 90.0), wallClock + 250, NmeaDialect.CLASSIC)
        assertTrue(lines(nmea).all { it.split(',')[1] == "100000.25" })
    }

    // The modern sentences, as a current GNSS receiver writes them: GN talker, RMC's mode, GSA and VTG.
    @Test
    fun `modern sentences add the mode, the fix's precision and the track`() {
        val nmea = NmeaSentences.position(NmeaFix(GeoPoint(48.8738, 2.2950), 10.0, 90.0), wallClock, NmeaDialect.MODERN)
        val sentences = lines(nmea)
        assertEquals(listOf("\$GNGGA", "\$GNRMC", "\$GNGSA", "\$GNVTG"), sentences.map { it.substringBefore(',') })
        assertTrue(sentences.all(::checksumOk))
        val rmc = sentences[1].substringBefore('*').split(',')
        assertEquals("A", rmc[12])
        val gsa = sentences[2].substringBefore('*').split(',')
        assertEquals(listOf("A", "3"), gsa.subList(1, 3))
        assertEquals(10, gsa.subList(3, 15).count { it.isNotEmpty() })
        val vtg = sentences[3].substringBefore('*').split(',')
        assertEquals("90.00", vtg[1])
        assertEquals(36.0, vtg[7].toDouble(), 0.01)

        val parked = NmeaSentences.position(NmeaFix(GeoPoint(48.8738, 2.2950), 0.0, null), wallClock, NmeaDialect.MODERN)
        assertEquals("", lines(parked)[3].split(',')[1])
    }
}
