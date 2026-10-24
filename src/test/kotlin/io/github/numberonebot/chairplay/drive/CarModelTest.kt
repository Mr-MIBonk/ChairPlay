package io.github.numberonebot.chairplay.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarModelTest {
    private val dt = 1.0 / 60

    private fun CarModel.run(seconds: Double, input: CarInput, each: (CarState) -> Unit = {}) {
        repeat((seconds / dt).toInt()) { each(step(input, dt)) }
    }

    // A 150 hp, 1300 kg car: about 6 to 9 s to 100 km/h, a top speed held by its drag near 220 km/h, the engine always
    // between idle and the red line, the gears going up one by one.
    @Test
    fun `full throttle takes an ordinary car to 100 and on to its top speed`() {
        val car = CarModel(CarSpec(massKg = 1300.0, powerHp = 150.0))
        var to100 = -1.0
        var time = 0.0
        val gears = mutableListOf<Int>()
        car.run(120.0, CarInput(throttle = 1.0)) { state ->
            time += dt
            if (to100 < 0 && state.speedKmh >= 100) to100 = time
            assertTrue("rpm ${state.rpm}", state.rpm in car.spec.idleRpm..car.spec.redlineRpm)
            if (gears.lastOrNull() != state.gear) gears += state.gear
        }

        assertTrue("0-100 in $to100 s", to100 in 5.0..11.0)
        assertTrue("top ${car.state.speedKmh} km/h", car.state.speedKmh in 190.0..240.0)
        assertEquals(listOf(1, 2, 3, 4, 5), gears)
    }

    // Twice the power, a quicker car; twice the mass, a slower one.
    @Test
    fun `power quickens the car and mass slows it`() {
        fun speedAfter(spec: CarSpec): Double = CarModel(spec).also { it.run(10.0, CarInput(throttle = 1.0)) }.state.speedKmh
        val base = speedAfter(CarSpec())
        assertTrue(speedAfter(CarSpec(powerHp = 300.0)) > base + 10)
        assertTrue(speedAfter(CarSpec(massKg = 2600.0)) < base - 10)
    }

    @Test
    fun `the brake stops the car within a few seconds, and coasting slows it`() {
        val car = CarModel(CarSpec())
        car.run(30.0, CarInput(throttle = 1.0))
        val cruising = car.state.speedKmh
        car.run(3.0, CarInput())
        assertTrue(car.state.speedKmh in 1.0..cruising - 2)

        var time = 0.0
        var stoppedAt = -1.0
        car.run(8.0, CarInput(brake = 1.0)) { state ->
            time += dt
            if (stoppedAt < 0 && state.speed <= 0.0) stoppedAt = time
        }
        assertTrue("stopped at $stoppedAt s", stoppedAt in 2.0..6.0)
    }

    // Arcade: held at a standstill, the brake backs the car up, slowly; the throttle then stops it and drives on.
    @Test
    fun `the brake held at a standstill reverses, and the throttle drives forward again`() {
        val car = CarModel(CarSpec())
        car.run(4.0, CarInput(brake = 1.0))
        assertTrue("speed ${car.state.speed}", car.state.speed < -1.0)
        assertEquals(-1, car.state.gear)
        assertTrue(car.state.speedKmh <= 25.0)

        car.run(6.0, CarInput(throttle = 1.0))
        assertTrue(car.state.speed > 1.0)
        assertTrue(car.state.gear >= 1)
    }

    // Arcade steering, without slip: the stick turns a moving car, more gently the faster it goes, and a parked one not.
    @Test
    fun `the stick turns a moving car and not a parked one`() {
        val parked = CarModel(CarSpec())
        parked.run(1.0, CarInput(steer = 1.0))
        assertEquals(0.0, parked.state.headingDegrees, 0.0)

        val slow = CarModel(CarSpec(), CarState(speed = 50 / 3.6, gear = 3))
        slow.run(1.0, CarInput(steer = 1.0, throttle = 0.3))
        val fast = CarModel(CarSpec(), CarState(speed = 150 / 3.6, gear = 5))
        fast.run(1.0, CarInput(steer = 1.0, throttle = 0.3))
        assertTrue("slow ${slow.state.headingDegrees}", slow.state.headingDegrees in 30.0..120.0)
        assertTrue("fast ${fast.state.headingDegrees}", fast.state.headingDegrees in 5.0..slow.state.headingDegrees - 5)
        val left = CarModel(CarSpec(), CarState(speed = 50 / 3.6, gear = 3))
        left.run(1.0, CarInput(steer = -1.0, throttle = 0.3))
        assertTrue(left.state.headingDegrees > 180.0)
    }

    // The tachometer: the engine idles at rest, revs with the throttle as the clutch slips at a launch.
    @Test
    fun `the engine idles at rest and revs at a launch`() {
        val car = CarModel(CarSpec())
        car.run(1.0, CarInput())
        assertEquals(car.spec.idleRpm, car.state.rpm, 50.0)
        car.run(0.3, CarInput(throttle = 1.0))
        assertTrue("rpm ${car.state.rpm}", car.state.rpm > 2500)
    }

    // Each step says how far the car went, for its place on the earth.
    @Test
    fun `a step reports the distance covered`() {
        val car = CarModel(CarSpec(), CarState(speed = 20.0, gear = 4))
        var meters = 0.0
        repeat(60) { car.step(CarInput(throttle = 0.4), dt); meters += car.lastMeters }
        assertEquals(20.0, meters, 2.0)
    }
}
