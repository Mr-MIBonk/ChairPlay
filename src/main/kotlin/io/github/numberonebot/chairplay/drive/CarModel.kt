package io.github.numberonebot.chairplay.drive

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sign

/**
 * A car as plain numbers: its power and mass decide how it pulls, its gearing how the engine turns. The defaults are an
 * ordinary 150 hp hatchback.
 */
data class CarSpec(
    val massKg: Double = 1300.0,
    val powerHp: Double = 150.0,
    val idleRpm: Double = 800.0,
    val redlineRpm: Double = 6500.0,
    val gearRatios: List<Double> = listOf(3.6, 2.1, 1.4, 1.05, 0.82),
    val finalDrive: Double = 3.9,
    val wheelRadiusMeters: Double = 0.31,
    /** Drag coefficient times frontal area. */
    val dragAreaSquareMeters: Double = 0.65,
    val rollingResistance: Double = 0.012,
)

/** The pedals and the wheel, each from 0 to 1; steer from -1 (left) to 1 (right). */
data class CarInput(val throttle: Double = 0.0, val brake: Double = 0.0, val steer: Double = 0.0)

/**
 * Where the car is in its motion: [speed] in m/s, negative backing up; [headingDegrees] 0 north, 90 east; [gear] -1 for
 * reverse, else 1 up to the top gear.
 */
data class CarState(val speed: Double = 0.0, val headingDegrees: Double = 0.0, val rpm: Double = 0.0, val gear: Int = 1) {
    val speedKmh: Double get() = abs(speed) * 3.6
}

/**
 * Basic car physics with arcade handling. The engine gives its power at every speed, so the pull is power over speed,
 * held at a launch by what the driven wheels grip; drag and rolling resistance take it back, which sets the top speed.
 * An automatic gearbox only turns the tachometer. The wheel turns the car without slip, as fast as a tight circle at
 * low speed and as a lateral pull allows at high speed. Held at a standstill, the brake backs the car up.
 */
class CarModel(val spec: CarSpec, initial: CarState = CarState()) {
    var state: CarState = initial.copy(rpm = initial.rpm.coerceIn(spec.idleRpm, spec.redlineRpm))
        private set

    /** Metres covered by the last step, negative backing up. */
    var lastMeters: Double = 0.0
        private set

    private val wattsAtWheels = spec.powerHp * WATTS_PER_HP * DRIVETRAIN_EFFICIENCY
    private val gripNewtons = spec.massKg * GRAVITY * DRIVEN_GRIP
    // A car strong enough to pass the red line in top gear before its drag holds it gets a longer final drive, as a
    // strong car has, so it reaches its top speed a little under the red line.
    private val finalDrive = spec.finalDrive * min(1.0, redlineSpeed(spec, spec.finalDrive) / (topSpeed(spec) * TOP_GEAR_MARGIN))
    private var standstillBrakeSeconds = 0.0

    fun step(input: CarInput, dt: Double): CarState {
        val throttle = input.throttle.coerceIn(0.0, 1.0)
        val brake = input.brake.coerceIn(0.0, 1.0)
        val steer = input.steer.coerceIn(-1.0, 1.0)
        val before = state.speed
        var gear = state.gear
        val stopped = abs(before) < STOPPED_SPEED

        // In reverse the pedals swap: the brake drives back, the throttle brakes. Stopped, the throttle selects drive,
        // and the brake held a moment selects reverse.
        standstillBrakeSeconds = if (stopped && gear > 0 && brake > 0 && throttle == 0.0) standstillBrakeSeconds + dt else 0.0
        if (stopped && gear < 0 && throttle > 0 && brake == 0.0) gear = 1
        if (standstillBrakeSeconds >= REVERSE_HOLD_SECONDS) gear = -1
        val drive = if (gear > 0) throttle else brake
        val stopping = if (gear > 0) brake else throttle
        val direction = if (gear > 0) 1.0 else -1.0

        val wheelRpm = wheelRpm(abs(before), gear)
        var force = drive * min(gripNewtons, wattsAtWheels / maxOf(abs(before), POWER_FLOOR_SPEED))
        if (gear > 0 && wheelRpm >= spec.redlineRpm) force = 0.0
        if (gear < 0 && abs(before) >= REVERSE_TOP_SPEED) force = 0.0
        var speed = before + direction * force / spec.massKg * dt
        val resistance = spec.rollingResistance * GRAVITY + AIR_DENSITY / 2 * spec.dragAreaSquareMeters * speed * speed / spec.massKg
        val slowing = (resistance + stopping * BRAKE_DECELERATION) * dt
        // Resistance and brakes stop the car; they never push it the other way, nor does a pedal too light to beat them.
        val halts = abs(speed) <= slowing
        speed = if (halts) 0.0 else speed - sign(speed) * slowing

        if (gear > 0) gear = shifted(gear, wheelRpm(abs(speed), gear), throttle)
        val launch = spec.idleRpm + (LAUNCH_RPM - spec.idleRpm) * drive
        val target = maxOf(wheelRpm(abs(speed), gear), launch).coerceIn(spec.idleRpm, spec.redlineRpm)
        val rpm = target + (state.rpm - target) * exp(-dt / RPM_SECONDS)

        val average = (before + speed) / 2
        val yawRate = steer * sign(average) * min(abs(average) / TIGHTEST_RADIUS, LATERAL_ACCELERATION / maxOf(abs(average), 1.0))
        val heading = ((state.headingDegrees + Math.toDegrees(yawRate * dt)) % 360 + 360) % 360

        lastMeters = average * dt
        state = CarState(speed, heading, rpm.coerceIn(spec.idleRpm, spec.redlineRpm), gear)
        return state
    }

    private fun wheelRpm(speed: Double, gear: Int): Double {
        val ratio = if (gear < 0) REVERSE_RATIO else spec.gearRatios[gear - 1]
        return speed / spec.wheelRadiusMeters * ratio * finalDrive * 60 / (2 * Math.PI)
    }

    // Up late at full throttle and early when cruising; down when the engine bogs, sooner with the foot down. The gaps
    // keep a shift from bouncing back: the rpm after an upshift stays above the next downshift point.
    private fun shifted(gear: Int, rpm: Double, throttle: Double): Int {
        val up = SHIFT_UP_GENTLE + (SHIFT_UP_FULL - SHIFT_UP_GENTLE) * throttle
        val down = SHIFT_DOWN_GENTLE + (SHIFT_DOWN_FULL - SHIFT_DOWN_GENTLE) * throttle
        return when {
            rpm >= up && gear < spec.gearRatios.size -> gear + 1
            rpm < down && gear > 1 -> gear - 1
            else -> gear
        }
    }

    companion object {
        /** In m/s: where the power at the wheels meets drag and rolling resistance. */
        fun topSpeed(spec: CarSpec): Double {
            val watts = spec.powerHp * WATTS_PER_HP * DRIVETRAIN_EFFICIENCY
            fun resisted(speed: Double) =
                (spec.rollingResistance * spec.massKg * GRAVITY + AIR_DENSITY / 2 * spec.dragAreaSquareMeters * speed * speed) * speed
            var low = 0.0
            var high = 200.0
            repeat(60) {
                val middle = (low + high) / 2
                if (resisted(middle) < watts) low = middle else high = middle
            }
            return low
        }

        /** In m/s: the speed at the red line in top gear with [finalDrive]. */
        private fun redlineSpeed(spec: CarSpec, finalDrive: Double) =
            spec.redlineRpm * 2 * Math.PI / 60 * spec.wheelRadiusMeters / (spec.gearRatios.last() * finalDrive)

        private const val TOP_GEAR_MARGIN = 1.05
        private const val WATTS_PER_HP = 745.7
        private const val DRIVETRAIN_EFFICIENCY = 0.85
        private const val GRAVITY = 9.81
        private const val AIR_DENSITY = 1.2
        /** Half the weight on the driven wheels, on a grip of 1. */
        private const val DRIVEN_GRIP = 0.5
        /** Below it the pull is the grip's, not power over a speed near zero. */
        private const val POWER_FLOOR_SPEED = 3.0
        private const val BRAKE_DECELERATION = 10.0
        private const val STOPPED_SPEED = 0.3
        private const val REVERSE_HOLD_SECONDS = 0.4
        private const val REVERSE_TOP_SPEED = 20 / 3.6
        private const val REVERSE_RATIO = 3.4
        /** What the engine revs to as the clutch slips at a launch, at full throttle. */
        private const val LAUNCH_RPM = 3500.0
        private const val RPM_SECONDS = 0.15
        private const val SHIFT_UP_GENTLE = 2500.0
        private const val SHIFT_UP_FULL = 6000.0
        private const val SHIFT_DOWN_GENTLE = 1200.0
        private const val SHIFT_DOWN_FULL = 2700.0
        private const val TIGHTEST_RADIUS = 5.0
        /** Arcade grip in a corner, more than a road car's 9 m/s². */
        private const val LATERAL_ACCELERATION = 12.0
    }
}
