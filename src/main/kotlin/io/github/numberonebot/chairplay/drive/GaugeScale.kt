package io.github.numberonebot.chairplay.drive

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A round dial's scale, as Audi's: 240 degrees from the lower left, clockwise over the top to the lower right, a tick
 * at each [minor] step, the [major] ones numbered, and a red zone from [redFrom] when there is one.
 */
data class GaugeScale(val max: Double, val major: Double, val minor: Double, val redFrom: Double? = null) {
    data class Tick(val value: Double, val major: Boolean)

    /** Where the needle points for [value], in radians as NanoVG turns (0 to the right, clockwise, y down). */
    fun angle(value: Double): Double = Math.toRadians(START_DEGREES + SWEEP_DEGREES * (value / max).coerceIn(0.0, 1.0))

    fun ticks(): List<Tick> = (0..(max / minor).roundToInt()).map { step ->
        val value = step * minor
        Tick(value, abs(value / major - (value / major).roundToInt()) < 1e-9)
    }

    companion object {
        const val START_DEGREES = 150.0
        const val SWEEP_DEGREES = 240.0

        /** km/h, reaching past the car's top speed as a car's speedometer does: 260 for an ordinary car. */
        fun speedometer(spec: CarSpec): GaugeScale {
            val top = CarModel.topSpeed(spec) * 3.6
            val max = (ceil(top * SPEEDOMETER_MARGIN / 20) * 20).coerceIn(140.0, 400.0)
            return if (max <= 260) GaugeScale(max, 20.0, 10.0) else GaugeScale(max, 40.0, 20.0)
        }

        /** Thousands of rpm, half a thousand past the red line and up to the next whole, the red zone from the red line. */
        fun tachometer(spec: CarSpec): GaugeScale {
            val redline = spec.redlineRpm / 1000
            return GaugeScale(ceil(redline + 0.5), 1.0, 0.5, redFrom = redline)
        }

        private const val SPEEDOMETER_MARGIN = 1.15
    }
}

/** What the gauges show at a moment: the speed, the engine, the gear, the pedals, and the dials' scales. */
data class GaugeView(
    val speedKmh: Double,
    val rpm: Double,
    val gear: String,
    val throttle: Double,
    val brake: Double,
    val speedometer: GaugeScale,
    val tachometer: GaugeScale,
)
