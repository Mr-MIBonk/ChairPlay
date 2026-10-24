package io.github.numberonebot.chairplay.drive

import kotlin.math.ceil

/**
 * A round dial's scale, as Audi's: 280 degrees from the lower left, clockwise over the top to the lower right, its
 * [numbers] evenly apart with a tick halfway between each two and fine ones at the tenths, and a red zone from [redFrom],
 * dashed from [warnFrom]. Numbers that do not step evenly change the scale between them, as a fast car's speedometer
 * does past 140.
 */
data class GaugeScale(val numbers: List<Double>, val redFrom: Double? = null, val warnFrom: Double? = null) {
    /** A numbered tick, the one halfway between two numbers, or one of the fine tenths between them. */
    enum class TickSize { MAJOR, MIDDLE, FINE }

    data class Tick(val value: Double, val size: TickSize) {
        val major: Boolean get() = size == TickSize.MAJOR
    }

    val max: Double get() = numbers.last()

    /** Where the needle points for [value], in radians as NanoVG turns (0 to the right, clockwise, y down). */
    fun angle(value: Double): Double {
        val v = value.coerceIn(numbers.first(), max)
        val i = numbers.indices.last { it == 0 || numbers[it] <= v }.coerceAtMost(numbers.size - 2)
        val along = i + (v - numbers[i]) / (numbers[i + 1] - numbers[i])
        return Math.toRadians(START_DEGREES + SWEEP_DEGREES * along / (numbers.size - 1))
    }

    fun ticks(): List<Tick> = numbers.zipWithNext { a, b ->
        (0 until 10).map { tenth ->
            val size = when (tenth) {
                0 -> TickSize.MAJOR
                5 -> TickSize.MIDDLE
                else -> TickSize.FINE
            }
            Tick(a + (b - a) * tenth / 10, size)
        }
    }.flatten() + Tick(max, TickSize.MAJOR)

    companion object {
        const val START_DEGREES = 130.0
        const val SWEEP_DEGREES = 280.0

        /** km/h, as a 250 km/h car's: by 20 to 140, then by 40 to 260. */
        fun speedometer(): GaugeScale = GaugeScale(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0, 120.0, 140.0, 180.0, 220.0, 260.0))

        /**
         * Thousands of rpm, half a thousand past the red line and up to the next whole, the red zone from the red line,
         * dashed half a thousand before it.
         */
        fun tachometer(spec: CarSpec): GaugeScale {
            val redline = spec.redlineRpm / 1000
            return GaugeScale((0..ceil(redline + 0.5).toInt()).map { it.toDouble() }, redFrom = redline, warnFrom = redline - 0.5)
        }
    }
}

/** What the gauges show at a moment: the speed, the engine, the gear, the pedals, the heading, the place, and the scales. */
data class GaugeView(
    val speedKmh: Double,
    val rpm: Double,
    val gear: String,
    val throttle: Double,
    val brake: Double,
    /** Where the car faces: 0 north, 90 east. */
    val headingDegrees: Double,
    val speedometer: GaugeScale,
    val tachometer: GaugeScale,
    val position: GeoPoint = GeoPoint(0.0, 0.0),
)
