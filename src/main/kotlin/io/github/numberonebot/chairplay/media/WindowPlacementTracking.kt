package io.github.numberonebot.chairplay.media

import io.github.numberonebot.chairplay.config.ScreenArea
import io.github.numberonebot.chairplay.config.WindowPlacement
import org.lwjgl.glfw.GLFW.*

// Where a GLFW window is, for keeping its place: the monitors' work areas a remembered place must lie on, and the
// window's normal place and size as it moves.

/** The monitors' work areas, where a remembered place must lie to be used. */
internal fun workAreas(): List<ScreenArea> {
    val monitors = glfwGetMonitors() ?: return emptyList()
    val x = IntArray(1)
    val y = IntArray(1)
    val w = IntArray(1)
    val h = IntArray(1)
    return (0 until monitors.limit()).map { i ->
        glfwGetMonitorWorkarea(monitors[i], x, y, w, h)
        ScreenArea(x[0], y[0], w[0], h[0])
    }
}

/**
 * Follows the window's normal (not maximized, not minimized) place and size, since GLFW reports the maximized
 * or minimized geometry once the window is in that state. A maximized window minimized and closed from the
 * taskbar stays maximized: GLFW keeps the flag across the minimize.
 */
internal class PlacementTracker(private val window: Long, @Volatile private var maximized: Boolean) {
    private var x = 0
    private var y = 0
    private var width = 0
    private var height = 0

    init {
        val a = IntArray(1)
        val b = IntArray(1)
        glfwGetWindowPos(window, a, b)
        x = a[0]
        y = b[0]
        glfwGetWindowSize(window, a, b)
        width = a[0]
        height = b[0]
        glfwSetWindowMaximizeCallback(window) { _, max -> maximized = max }
        glfwSetWindowPosCallback(window) { _, newX, newY -> if (normal()) { x = newX; y = newY } }
        glfwSetWindowSizeCallback(window) { _, newW, newH -> if (normal() && newW > 0 && newH > 0) { width = newW; height = newH } }
    }

    private fun normal() =
        glfwGetWindowAttrib(window, GLFW_MAXIMIZED) == GLFW_FALSE && glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_FALSE

    fun placement() = WindowPlacement(x, y, width, height, maximized)
}
