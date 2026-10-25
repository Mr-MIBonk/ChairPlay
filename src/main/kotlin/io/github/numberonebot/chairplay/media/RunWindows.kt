package io.github.numberonebot.chairplay.media

import io.github.numberonebot.chairplay.appLog
import io.github.numberonebot.chairplay.log.AppLog
import io.github.numberonebot.chairplay.log.LogLevel
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback

/** GLFW's errors as warning lines in [log], each as `[glfw] 0x<code>: <description>`. */
fun glfwErrorCallback(log: AppLog): GLFWErrorCallback = GLFWErrorCallback.create { code, description ->
    log.log(LogLevel.WARNING, "[glfw] 0x${code.toString(16)}: ${GLFWErrorCallback.getDescription(description)}")
}

/** The wait between passes while a fade runs: a little over a frame at 120 Hz. */
private const val FADE_FRAME_SECONDS = 0.008

/**
 * Runs the GLFW event loop for [main] and [others] on the calling (main) thread until [main] closes. GLFW wants
 * every window and its events on one thread, so the windows take turns after each wake-up. Closing one of
 * [others] closes only that window. [pass] runs on each pass, before the windows draw, and says how soon the next
 * is due (the GPS car while it drives); null leaves the loop to sleep until something happens.
 */
fun runWindows(main: VideoWindow, vararg others: VideoWindow, pass: () -> Double? = { null }) {
    glfwErrorCallback(appLog).set()
    check(glfwInit()) { "cannot initialise GLFW" }
    val open = mutableListOf(main)
    try {
        main.open()
        for (window in others) {
            window.open()
            open += window
        }
        var due: Double? = null
        while (true) {
            // A fade under way draws at about the screen's pace; otherwise the loop sleeps until something happens.
            val wait = listOfNotNull(due, FADE_FRAME_SECONDS.takeIf { open.any { it.animating } }).minOrNull()
            if (wait != null) glfwWaitEventsTimeout(wait) else glfwWaitEvents()
            due = pass()
            if (!main.step()) break
            for (window in open.drop(1)) {
                if (!window.step()) {
                    window.dispose()
                    open -= window
                }
            }
        }
    } finally {
        open.forEach { it.dispose() }
        glfwTerminate()
        glfwSetErrorCallback(null)?.free()
    }
}
