package io.github.numberonebot.chairplay.drive

import kotlin.math.cos
import kotlin.math.sin

/** A place on the earth in degrees, north and east positive. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    /**
     * Where [meters] along [headingDegrees] (0 north, 90 east) lead, on a sphere and in a straight local step: right for
     * the few metres a car covers between two fixes. Latitude stops at the poles; longitude wraps at the date line.
     */
    fun moved(headingDegrees: Double, meters: Double): GeoPoint {
        val heading = Math.toRadians(headingDegrees)
        val latitude = (latitude + meters * cos(heading) / METERS_PER_DEGREE).coerceIn(-90.0, 90.0)
        val longitude = longitude + meters * sin(heading) / (METERS_PER_DEGREE * cos(Math.toRadians(this.latitude)))
        return GeoPoint(latitude, wrapLongitude(longitude))
    }

    private fun wrapLongitude(value: Double): Double {
        var wrapped = value
        while (wrapped >= 180.0) wrapped -= 360.0
        while (wrapped < -180.0) wrapped += 360.0
        return wrapped
    }

    companion object {
        /** A degree of latitude on the mean earth (radius 6,371,008.8 m). */
        const val METERS_PER_DEGREE = 111_195.0
    }
}

/** gps.start: a place from a pair of degrees, as a map app shows it, or from the link it shares. */
object Coordinates {
    private const val NUMBER = """(-?\d{1,3}(?:\.\d+)?)"""
    private val pair = Regex("""^\s*$NUMBER\s*[,\s]\s*$NUMBER\s*$""")
    // Google Maps' @lat,lon and q=lat,lon; Apple Maps' ll= and coordinate=.
    private val inLink = Regex("""(?:@|[?&](?:q|ll|coordinate)=)$NUMBER,$NUMBER""")

    /** The place [text] names, or null for none, or one off the globe. */
    fun parse(text: String): GeoPoint? {
        val match = inLink.find(text) ?: pair.find(text) ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return GeoPoint(latitude, longitude)
    }
}
