package io.github.numberonebot.chairplay.drive

import org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_1
import org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_LAST
import org.lwjgl.glfw.GLFW.glfwGetGamepadState
import org.lwjgl.glfw.GLFW.glfwJoystickIsGamepad
import org.lwjgl.glfw.GLFWGamepadState

/**
 * The first gamepad GLFW knows (an Xbox controller, or any in its mapping database), read on the main thread while
 * GLFW runs: its six axes, or null without one.
 */
object GlfwGamepad {
    private val state by lazy { GLFWGamepadState.create() }
    private var last = GLFW_JOYSTICK_1

    fun axes(): FloatArray? {
        // The one found last time first, so a pass asks one joystick, not sixteen.
        for (jid in listOf(last) + (GLFW_JOYSTICK_1..GLFW_JOYSTICK_LAST)) {
            if (glfwJoystickIsGamepad(jid) && glfwGetGamepadState(jid, state)) {
                last = jid
                return FloatArray(AXES) { state.axes(it) }
            }
        }
        return null
    }

    private const val AXES = 6
}
