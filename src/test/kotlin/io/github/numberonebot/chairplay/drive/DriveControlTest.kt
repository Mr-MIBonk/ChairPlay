package io.github.numberonebot.chairplay.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_KEY_UP
import org.lwjgl.glfw.GLFW.GLFW_KEY_A
import org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
import org.lwjgl.glfw.GLFW.GLFW_REPEAT

class DriveControlTest {
    private val kyiv = GeoPoint(50.4501, 30.5234)
    private var now = 0L

    // GLFW's gamepad axes: left stick X and Y, right stick X and Y, left and right trigger (-1 released, 1 pressed).
    private fun pad(steer: Float = 0f, brake: Float = -1f, throttle: Float = -1f) = floatArrayOf(steer, 0f, 0f, 0f, brake, throttle)

    @Test
    fun `the arrows are the pedals and the wheel`() {
        val keys = DriveKeys()
        assertTrue(keys.key(GLFW_KEY_UP, GLFW_PRESS))
        assertTrue(keys.key(GLFW_KEY_LEFT, GLFW_PRESS))
        assertEquals(CarInput(throttle = 1.0, steer = -1.0), keys.input())
        assertTrue(keys.key(GLFW_KEY_LEFT, GLFW_REPEAT))
        keys.key(GLFW_KEY_UP, GLFW_RELEASE)
        keys.key(GLFW_KEY_LEFT, GLFW_RELEASE)
        keys.key(GLFW_KEY_DOWN, GLFW_PRESS)
        keys.key(GLFW_KEY_RIGHT, GLFW_PRESS)
        assertEquals(CarInput(brake = 1.0, steer = 1.0), keys.input())
        assertFalse(keys.key(GLFW_KEY_A, GLFW_PRESS))
        keys.release()
        assertEquals(CarInput(), keys.input())
        // An arrow held as the knob before drive mode began: its release goes on to the knob.
        assertFalse(keys.key(GLFW_KEY_LEFT, GLFW_RELEASE))
        // Ctrl+Up and Ctrl+Down stay the volume keys.
        assertFalse(keys.key(GLFW_KEY_UP, GLFW_PRESS, GLFW_MOD_CONTROL))
        assertEquals(CarInput(), keys.input())
    }

    // The triggers press the pedals as far as they go; a stick near the middle steers nothing.
    @Test
    fun `the gamepad's triggers are the pedals and its stick the wheel`() {
        assertEquals(CarInput(), gamepadInput(pad()))
        assertEquals(CarInput(throttle = 1.0, brake = 0.5, steer = 1.0), gamepadInput(pad(steer = 1f, brake = 0f, throttle = 1f)))
        assertEquals(0.0, gamepadInput(pad(steer = 0.1f)).steer, 0.0)
        val half = gamepadInput(pad(steer = -0.56f)).steer
        assertTrue("steer $half", half in -0.6..-0.4)
    }

    @Test
    fun `keys and the gamepad together press as far as either`() {
        assertEquals(
            CarInput(throttle = 1.0, brake = 0.3, steer = 1.0),
            combined(CarInput(throttle = 1.0, steer = 0.6), CarInput(throttle = 0.4, brake = 0.3, steer = 0.7)),
        )
    }

    // The arrows drive only in drive mode; outside it they stay the knob's.
    @Test
    fun `the arrows drive only in drive mode`() {
        val control = DriveControl(Drive(CarSpec(), kyiv, 0.0) { now }, gamepad = { null })
        assertFalse(control.key(GLFW_KEY_UP, GLFW_PRESS))
        assertTrue(control.toggleKeyboard())
        assertTrue(control.key(GLFW_KEY_UP, GLFW_PRESS))
        assertFalse(control.toggleKeyboard())
        assertFalse(control.key(GLFW_KEY_UP, GLFW_RELEASE))
    }

    // Idle with no gamepad, the loop sleeps; a gamepad is polled now and then; driving, the loop runs at frame pace.
    @Test
    fun `a pass moves the car and says how soon the next is due`() {
        var axes: FloatArray? = null
        val drive = Drive(CarSpec(), kyiv, 0.0) { now }
        val stops = mutableListOf<DriveSnapshot>()
        val control = DriveControl(drive, gamepad = { axes }, onStopped = { stops += it })
        assertNull(control.pass())

        axes = pad()
        assertEquals(DriveControl.GAMEPAD_POLL_SECONDS, control.pass())
        assertTrue(control.gamepad)

        axes = pad(throttle = 1f)
        repeat(180) { now += 16_666_667L; assertEquals(DriveControl.FRAME_SECONDS, control.pass()) }
        assertTrue(drive.snapshot().car.speed > 5.0)

        axes = pad(brake = 1f)
        repeat(240) { now += 16_666_667L; control.pass() }
        assertEquals(1, stops.size)
        assertFalse(control.gamepad && axes == null)
    }

    @Test
    fun `drive mode keeps the loop at frame pace and lets go of the keys as it ends`() {
        val drive = Drive(CarSpec(), kyiv, 0.0) { now }
        val control = DriveControl(drive, gamepad = { null })
        control.toggleKeyboard()
        assertEquals(DriveControl.FRAME_SECONDS, control.pass())
        control.key(GLFW_KEY_UP, GLFW_PRESS)
        now += 16_666_667L
        control.pass()
        assertEquals(1.0, drive.input.throttle, 0.0)
        control.toggleKeyboard()
        now += 16_666_667L
        control.pass()
        assertEquals(CarInput(), drive.input)
    }
}
