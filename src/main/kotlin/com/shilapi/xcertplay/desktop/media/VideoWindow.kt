package com.shilapi.xcertplay.desktop.media

import com.shilapi.xcertplay.desktop.input.PointerEvent
import com.shilapi.xcertplay.desktop.input.Size
import com.shilapi.xcertplay.desktop.input.toFramebuffer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The CarPlay screen in a GLFW window. GLFW wants its window and event loop on one thread (the main
 * one), so [run] owns that thread until the window closes. Other threads only call [show] and [close]:
 * [show] leaves the newest frame in an [AtomicReference] and wakes the loop, so a frame that arrives
 * while the previous one is still being drawn simply replaces it — nothing queues, latency stays one frame.
 */
class VideoWindow(private val width: Int, private val height: Int) : Closeable {
    private val latest = AtomicReference<DecodedFrame?>()
    private val closing = AtomicBoolean()
    // GLFW rejects glfwPostEmptyEvent outside init..terminate, which frames and close() can fall outside of.
    private val running = AtomicBoolean()

    /** Runs on the GL thread after a frame has been swapped to the screen. */
    @Volatile
    var onFrameShown: () -> Unit = {}

    /**
     * Receives left-button mouse samples on the GL thread, in framebuffer pixels (the space the picture is
     * letterboxed in). With [framebufferSize] and [pictureSize] that is all the mapping to the screen needs.
     */
    @Volatile
    private var inputHandler: (PointerEvent) -> Unit = {}

    @Volatile
    var framebufferSize = Size(0, 0)
        private set

    /** Size of the newest frame on screen; zero until the first one. */
    @Volatile
    var pictureSize = Size(0, 0)
        private set

    fun setInputHandler(handler: (PointerEvent) -> Unit) {
        inputHandler = handler
    }

    fun show(frame: DecodedFrame) {
        latest.set(frame)
        wake()
    }

    /** Safe from any thread; [run] then returns and releases the window. */
    override fun close() {
        closing.set(true)
        wake()
    }

    private fun wake() {
        if (running.get()) glfwPostEmptyEvent()
    }

    /** Creates the window and draws until it is closed by the user or by [close]. Call on the main thread. */
    fun run() {
        GLFWErrorCallback.createPrint(System.err).set()
        check(glfwInit()) { "cannot initialise GLFW" }
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        val window = glfwCreateWindow(width, height, "DiPlay", 0, 0)
        check(window != 0L) { "cannot create the window" }
        glfwMakeContextCurrent(window)
        // Draw as soon as a frame is ready instead of waiting for vertical blank, which would add up to a frame.
        glfwSwapInterval(0)
        GL.createCapabilities()
        installInput(window)
        running.set(true)

        val program = buildProgram()
        val vao = glGenVertexArrays()
        val textures = IntArray(3) { glGenTextures() }
        val planeSizes = IntArray(6)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glUseProgram(program)
        for (plane in 0 until 3) {
            glActiveTexture(GL_TEXTURE0 + plane)
            glBindTexture(GL_TEXTURE_2D, textures[plane])
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glUniform1i(glGetUniformLocation(program, arrayOf("planeY", "planeU", "planeV")[plane]), plane)
        }
        glBindVertexArray(vao)

        var picture: DecodedFrame? = null
        val w = IntArray(1)
        val h = IntArray(1)
        try {
            while (!glfwWindowShouldClose(window) && !closing.get()) {
                glfwWaitEvents()
                val fresh = latest.getAndSet(null)
                if (fresh != null) {
                    upload(fresh, textures, planeSizes)
                    picture = fresh
                    pictureSize = Size(fresh.width, fresh.height)
                }
                val shown = picture ?: continue
                // A resize repaints the last picture even when no new one has arrived.
                glfwGetFramebufferSize(window, w, h)
                framebufferSize = Size(w[0], h[0])
                draw(shown, w[0], h[0])
                glfwSwapBuffers(window)
                if (fresh != null) onFrameShown()
            }
        } finally {
            running.set(false)
            glDeleteTextures(textures)
            glDeleteVertexArrays(vao)
            glDeleteProgram(program)
            glfwDestroyWindow(window)
            glfwTerminate()
            glfwSetErrorCallback(null)?.free()
        }
    }

    private fun installInput(window: Long) {
        var held = false
        // Hovering sends nothing, so only a held button turns cursor motion into events.
        glfwSetCursorPosCallback(window) { _, x, y -> if (held) emitPointer(window, x, y, true) }
        glfwSetMouseButtonCallback(window) { _, button, action, _ ->
            if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
            held = action == GLFW_PRESS
            val x = DoubleArray(1)
            val y = DoubleArray(1)
            glfwGetCursorPos(window, x, y)
            emitPointer(window, x[0], y[0], held)
        }
    }

    /** Converts a cursor position in window units to framebuffer pixels, the space the letterbox is computed in. */
    private fun emitPointer(window: Long, x: Double, y: Double, down: Boolean) {
        val ww = IntArray(1)
        val wh = IntArray(1)
        val fw = IntArray(1)
        val fh = IntArray(1)
        glfwGetWindowSize(window, ww, wh)
        glfwGetFramebufferSize(window, fw, fh)
        // Cache the size the conversion used so the handler maps in exactly that space.
        framebufferSize = Size(fw[0], fh[0])
        inputHandler(PointerEvent(x, y, down).toFramebuffer(Size(ww[0], wh[0]), framebufferSize))
    }

    private fun upload(frame: DecodedFrame, textures: IntArray, sizes: IntArray) {
        val planes = arrayOf(frame.y, frame.u, frame.v)
        val widths = intArrayOf(frame.width, (frame.width + 1) / 2, (frame.width + 1) / 2)
        val heights = intArrayOf(frame.height, (frame.height + 1) / 2, (frame.height + 1) / 2)
        for (plane in 0 until 3) {
            glActiveTexture(GL_TEXTURE0 + plane)
            glBindTexture(GL_TEXTURE_2D, textures[plane])
            // Planes are tightly packed (DecodedFrame.strides == plane widths), so no row length is needed.
            if (sizes[plane * 2] != widths[plane] || sizes[plane * 2 + 1] != heights[plane]) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, widths[plane], heights[plane], 0, GL_RED, GL_UNSIGNED_BYTE, planes[plane])
                sizes[plane * 2] = widths[plane]
                sizes[plane * 2 + 1] = heights[plane]
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, widths[plane], heights[plane], GL_RED, GL_UNSIGNED_BYTE, planes[plane])
            }
        }
    }

    /** Fits the picture inside the window at its own aspect ratio; the rest stays black. */
    private fun draw(frame: DecodedFrame, fw: Int, fh: Int) {
        if (fw == 0 || fh == 0) return
        glViewport(0, 0, fw, fh)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        val scale = minOf(fw.toDouble() / frame.width, fh.toDouble() / frame.height)
        val w = (frame.width * scale).toInt()
        val h = (frame.height * scale).toInt()
        glViewport((fw - w) / 2, (fh - h) / 2, w, h)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(): Int {
        val program = glCreateProgram()
        val vertex = compile(GL_VERTEX_SHADER, VERTEX)
        val fragment = compile(GL_FRAGMENT_SHADER, FRAGMENT)
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        check(glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE) { "shader link failed: ${glGetProgramInfoLog(program)}" }
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        check(glGetShaderi(shader, GL_COMPILE_STATUS) == GL_TRUE) { "shader compile failed: ${glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        // A strip of four vertices generated from gl_VertexID: no vertex buffer to manage.
        const val VERTEX = """#version 330 core
out vec2 uv;
void main() {
    vec2 corner = vec2(gl_VertexID & 1, gl_VertexID >> 1);
    uv = vec2(corner.x, 1.0 - corner.y);
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}"""

        // BT.709, limited (studio) range: Y 16..235, Cb/Cr 16..240.
        const val FRAGMENT = """#version 330 core
in vec2 uv;
out vec4 color;
uniform sampler2D planeY;
uniform sampler2D planeU;
uniform sampler2D planeV;
void main() {
    float y = (texture(planeY, uv).r - 16.0 / 255.0) * (255.0 / 219.0);
    float u = (texture(planeU, uv).r - 128.0 / 255.0) * (255.0 / 224.0);
    float v = (texture(planeV, uv).r - 128.0 / 255.0) * (255.0 / 224.0);
    color = vec4(y + 1.5748 * v, y - 0.1873 * u - 0.4681 * v, y + 1.8556 * u, 1.0);
}"""
    }
}
