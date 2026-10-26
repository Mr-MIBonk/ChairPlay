package io.github.numberonebot.chairplay.drive

import java.time.Instant
import kotlin.math.*

/** GPS Yuma orbital elements. Angles are radians; health zero means usable.
 * Format: https://celestrak.org/GPS/almanac/Yuma/definition.php
 */
internal data class GpsOrbit(
    val prn: Int, val health: Int, val week: Int, val toa: Double,
    val sqrtA: Double, val eccentricity: Double, val inclination: Double,
    val node: Double, val nodeRate: Double, val perigee: Double, val meanAnomaly: Double,
) {
    fun fullWeek(millis: Long): Int = if (week >= 1024) week else
        week + ((floor(gpsSeconds(millis) / WEEK) - week) / 1024).roundToInt() * 1024

    fun ageSeconds(millis: Long): Double = gpsSeconds(millis) - fullWeek(millis) * WEEK - toa

    /** Kepler orbit with Earth's rotation; almanacs have no ephemeris harmonic corrections.
     * Algorithm: https://gssc.esa.int/navipedia/index.php/GPS_and_Galileo_Satellite_Coordinates_Computation
     */
    fun ecef(millis: Long): Ecef {
        val elapsed = ageSeconds(millis)
        require(abs(elapsed) <= GpsAlmanac.MAX_AGE_SECONDS) { "almanac epoch is too far from current time" }
        val a = sqrtA * sqrtA
        val mean = (meanAnomaly + sqrt(3.986005e14 / (a * a * a)) * elapsed) % (2 * PI)
        var eccentric = mean
        repeat(15) {
            eccentric -= (eccentric - eccentricity * sin(eccentric) - mean) / (1 - eccentricity * cos(eccentric))
        }
        val trueAnomaly = atan2(sqrt(1 - eccentricity * eccentricity) * sin(eccentric), cos(eccentric) - eccentricity)
        val argument = trueAnomaly + perigee
        val radius = a * (1 - eccentricity * cos(eccentric))
        val ascending = node + (nodeRate - EARTH_ROTATION) * elapsed - EARTH_ROTATION * toa
        val x = radius * cos(argument)
        val y = radius * sin(argument)
        return Ecef(x * cos(ascending) - y * cos(inclination) * sin(ascending),
            x * sin(ascending) + y * cos(inclination) * cos(ascending), y * sin(inclination))
    }

    private companion object {
        const val WEEK = 604800.0
        const val EARTH_ROTATION = 7.2921151467e-5
        val GPS_EPOCH = Instant.parse("1980-01-06T00:00:00Z").toEpochMilli()
        // GPS-UTC since 2017; verified 2026-10-26 against NIST's TAI-UTC=37 (GPS=TAI-19).
        // This is a coarse satellite-visibility model, not a precision timing source.
        fun gpsSeconds(millis: Long): Double = (millis - GPS_EPOCH) / 1000.0 + 18
    }
}

internal data class GpsAlmanac(val satellites: List<GpsOrbit>) {
    fun validAt(millis: Long): Boolean = satellites.all { abs(it.ageSeconds(millis)) <= MAX_AGE_SECONDS }

    companion object {
        // Operational freshness policy for predicted GSV, not a claimed positioning accuracy bound.
        const val MAX_AGE_SECONDS = 14 * 86400.0

        fun parse(text: String): GpsAlmanac {
            require(text.length <= 65536) { "almanac is too large" }
            val blocks = text.split(Regex("\\*{2,}[^\\r\\n]*\\*{2,}")).filter(String::isNotBlank)
            val satellites = blocks.map { block ->
                val fields = linkedMapOf<String, Double>()
                block.lineSequence().filter(String::isNotBlank).forEach { line ->
                    val parts = line.split(':', limit = 2)
                    require(parts.size == 2) { "invalid Yuma field" }
                    val key = parts[0].trim()
                    val value = parts[1].trim().toDoubleOrNull()
                    require(value != null && value.isFinite() && fields.put(key, value) == null) { "invalid Yuma value" }
                }
                fun number(key: String, range: ClosedFloatingPointRange<Double>): Double =
                    fields[key]?.also { require(it in range) { "Yuma $key out of range" } }
                        ?: throw IllegalArgumentException("missing Yuma $key")
                fun integer(key: String, range: IntRange): Int = number(key, range.first.toDouble()..range.last.toDouble()).let {
                    require(it == floor(it)) { "Yuma $key must be an integer" }; it.toInt()
                }
                GpsOrbit(integer("ID", 1..32), integer("Health", 0..255), integer("week", 0..8191),
                    number("Time of Applicability(s)", 0.0..604799.999), number("SQRT(A)  (m 1/2)", 5000.0..5300.0),
                    number("Eccentricity", 0.0..0.1), number("Orbital Inclination(rad)", 0.0..PI),
                    number("Right Ascen at Week(rad)", -2 * PI..2 * PI), number("Rate of Right Ascen(r/s)", -1e-6..1e-6),
                    number("Argument of Perigee(rad)", -2 * PI..2 * PI), number("Mean Anom(rad)", -2 * PI..2 * PI))
            }
            require(satellites.size in 24..32 && satellites.map { it.prn }.distinct().size == satellites.size) {
                "incomplete or duplicate GPS constellation"
            }
            return GpsAlmanac(satellites.sortedBy { it.prn })
        }
    }
}

internal data class Ecef(val x: Double, val y: Double, val z: Double)
