package io.github.numberonebot.chairplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.nanovg.NanoVG.nvgBeginFrame
import org.lwjgl.nanovg.NanoVG.nvgEndFrame
import org.lwjgl.nanovg.NanoVG.nvgRestore
import org.lwjgl.nanovg.NanoVG.nvgSave
import org.lwjgl.nanovg.NanoVG.nvgScale
import org.lwjgl.opengl.GL

class HudTextMeasureTest {
    // The HUD's blocks scale as they make room for the GPS car's wells: a line measured meanwhile must keep its width,
    // or a title fitted by it jumps in size and cut from frame to frame.
    @Test
    fun `text measures alike at every scale`() {
        assumeTrue("no GLFW", glfwInit())
        try {
            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            val window = glfwCreateWindow(64, 64, "text test", 0, 0)
            assumeTrue("no window", window != 0L)
            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            val painter = HudPainter({ error("not drawn") })
            painter.open()
            nvgBeginFrame(painter.vg, 640f, 480f, 1f)
            val title = "Unknown Delight and more"
            val plain = painter.measure(title, 48f, BOLD)
            val scaled = (40..100).map { percent ->
                nvgSave(painter.vg)
                nvgScale(painter.vg, percent / 100f, percent / 100f)
                painter.measure(title, 48f, BOLD).also { nvgRestore(painter.vg) }
            }
            nvgEndFrame(painter.vg)
            painter.close()
            glfwDestroyWindow(window)
            scaled.forEachIndexed { i, width -> assertEquals("at ${40 + i} %", plain, width, 0f) }
        } finally {
            glfwTerminate()
        }
    }
}
