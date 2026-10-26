package io.github.numberonebot.chairplay.drive

import java.util.Locale
import kotlin.math.*

internal data class SatelliteAngles(val azimuth: Double, val elevation: Double)
internal data class VisibleSatellite(val prn: Int, val elevation: Int, val azimuth: Int)

/** Open-sky prediction at the transmitted position, not measured GNSS reception. */
internal object SatelliteGeometry {
    /** WGS84 observer -> local east, north, up. Height zero matches the current GGA altitude model. */
    fun angles(satellite: Ecef, point: GeoPoint, height: Double = 0.0): SatelliteAngles {
        val lat = Math.toRadians(point.latitude)
        val lon = Math.toRadians(point.longitude)
        val eccentricitySquared = 6.69437999014e-3
        val n = 6378137.0 / sqrt(1 - eccentricitySquared * sin(lat).pow(2))
        val x = satellite.x - (n + height) * cos(lat) * cos(lon)
        val y = satellite.y - (n + height) * cos(lat) * sin(lon)
        val z = satellite.z - (n * (1 - eccentricitySquared) + height) * sin(lat)
        val east = -sin(lon) * x + cos(lon) * y
        val north = -sin(lat) * cos(lon) * x - sin(lat) * sin(lon) * y + cos(lat) * z
        val up = cos(lat) * cos(lon) * x + cos(lat) * sin(lon) * y + sin(lat) * z
        return SatelliteAngles((Math.toDegrees(atan2(east, north)) + 360) % 360,
            Math.toDegrees(atan2(up, hypot(east, north))))
    }

    fun visible(almanac: GpsAlmanac, point: GeoPoint, millis: Long): List<VisibleSatellite> {
        require(point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0) { "invalid observer position" }
        require(almanac.validAt(millis)) { "stale GPS almanac" }
        return almanac.satellites.filter { it.health == 0 }.mapNotNull { satellite ->
            val angles = angles(satellite.ecef(millis), point)
            if (angles.elevation < 5) null else VisibleSatellite(satellite.prn,
                angles.elevation.roundToInt().coerceIn(0, 90), angles.azimuth.roundToInt() % 360)
        }.sortedBy { it.prn }
    }

    /** NMEA GSV: up to four satellites per sentence, complete numbered cycle and checksum.
     * https://receiverhelp.trimble.com/oem-gnss/nmea0183-messages-gsv.html
     * C/N0 is modeled at 45 dB-Hz, as in the controlled experiments; an almanac cannot measure it.
     */
    fun gsv(satellites: List<VisibleSatellite>): String {
        if (satellites.isEmpty()) return NmeaSentences.sentence("GPGSV,1,1,00")
        val groups = satellites.chunked(4)
        return groups.mapIndexed { i, group ->
            NmeaSentences.sentence(String.format(Locale.US, "GPGSV,%d,%d,%02d,", groups.size, i + 1, satellites.size) +
                group.joinToString(",") { String.format(Locale.US, "%02d,%02d,%03d,45", it.prn, it.elevation, it.azimuth) })
        }.joinToString("")
    }
}
