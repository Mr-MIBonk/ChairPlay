package io.github.numberonebot.chairplay.drive

/** Display units only: car physics and outgoing GPS measurements keep their existing units. */
enum class SpeedUnits(val key: String, val symbol: String) {
    METRIC("metric", "km/h"),
    IMPERIAL("imperial", "mph");

    fun fromKmh(speed: Double): Double = if (this == IMPERIAL) speed / 1.609344 else speed
}
