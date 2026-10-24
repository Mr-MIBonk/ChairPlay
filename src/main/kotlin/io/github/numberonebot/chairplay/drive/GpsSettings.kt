package io.github.numberonebot.chairplay.drive

/**
 * gps.*: whether the iPhone navigates by the spoofed car, and what it is told besides its place ([wheelSpeed] for
 * $PASCD, [satellites] for $GPGSV); where the car starts ([start], null until set) and which way it faces, or where it
 * last stopped ([resume]); the car itself; and whether its gauges show while it drives.
 */
data class GpsSettings(
    val enabled: Boolean,
    val wheelSpeed: Boolean = true,
    val satellites: Boolean = false,
    val start: GeoPoint? = null,
    val headingDegrees: Double = 0.0,
    val resume: Boolean = true,
    val car: CarSpec = CarSpec(),
    val gauges: Boolean = true,
) {
    companion object {
        val POWER_HP = 20.0..2000.0
        val MASS_KG = 300.0..10000.0

        /** A heading in degrees, from 0 up to but not 360. */
        fun heading(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it >= 0.0 && it < 360.0 }
    }
}
