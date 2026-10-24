package io.github.numberonebot.chairplay.media

import io.github.numberonebot.chairplay.input.Size
import org.lwjgl.opengl.GL33C.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The GL side of one VideoWindow, made in its context once that is current, and used only with it current: the
 * picture's three plane textures and the program that turns them from BT.709 YUV to RGB, the black that dims it in
 * the fades, and the safe-area frame's dots. Every program draws a strip of four vertices generated from gl_VertexID.
 */
internal class PictureGl {
    private val program: Int
    private val sourceUniform: Int
    // Black over the window at the fades' depth: one layer dims the picture and the HUD alike.
    private val dimProgram = buildProgram(DIM_FRAGMENT)
    private val dimUniform = glGetUniformLocation(dimProgram, "alpha")
    // The safe-area frame's dots.
    private val frameProgram = buildProgram(FRAME_FRAGMENT)
    private val frameUniform = glGetUniformLocation(frameProgram, "rect")
    private val vao: Int
    private val textures = IntArray(3)
    private val planeSizes = IntArray(6)

    init {
        program = buildProgram(FRAGMENT)
        vao = glGenVertexArrays()
        for (plane in 0 until 3) textures[plane] = glGenTextures()
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
        sourceUniform = glGetUniformLocation(program, "source")
        glUniform4f(sourceUniform, 0f, 0f, 1f, 1f)
    }

    /** Puts [frame]'s planes into the textures; glTexImage2D and glTexSubImage2D have read them when this returns. */
    fun upload(frame: DecodedFrame) {
        val planes = arrayOf(frame.y, frame.u, frame.v)
        val widths = intArrayOf(frame.width, (frame.width + 1) / 2, (frame.width + 1) / 2)
        val heights = intArrayOf(frame.height, (frame.height + 1) / 2, (frame.height + 1) / 2)
        for (plane in 0 until 3) {
            glActiveTexture(GL_TEXTURE0 + plane)
            glBindTexture(GL_TEXTURE_2D, textures[plane])
            // Planes are tightly packed (DecodedFrame.strides == plane widths), so no row length is needed.
            if (planeSizes[plane * 2] != widths[plane] || planeSizes[plane * 2 + 1] != heights[plane]) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, widths[plane], heights[plane], 0, GL_RED, GL_UNSIGNED_BYTE, planes[plane])
                planeSizes[plane * 2] = widths[plane]
                planeSizes[plane * 2 + 1] = heights[plane]
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, widths[plane], heights[plane], GL_RED, GL_UNSIGNED_BYTE, planes[plane])
            }
        }
    }

    /** The whole [fw] x [fh] framebuffer black. */
    fun clear(fw: Int, fh: Int) {
        glViewport(0, 0, fw, fh)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
    }

    /** Fits the picture, [frame] in size, inside the window at its own aspect ratio; the rest stays black. */
    fun draw(frame: Size, fw: Int, fh: Int) {
        if (fw == 0 || fh == 0) return
        clear(fw, fh)
        val scale = minOf(fw.toDouble() / frame.width, fh.toDouble() / frame.height)
        val w = (frame.width * scale).toInt()
        val h = (frame.height * scale).toInt()
        glViewport((fw - w) / 2, (fh - h) / 2, w, h)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Black over the whole window, [alpha] deep, as the fades dim what it shows; nothing at 0. */
    fun dim(alpha: Float, fw: Int, fh: Int) {
        if (alpha <= 0f || fw == 0 || fh == 0) return
        glViewport(0, 0, fw, fh)
        glUseProgram(dimProgram)
        glUniform1f(dimUniform, alpha)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisable(GL_BLEND)
        glUseProgram(program)
    }

    /** The safe-area frame's dots inside [rect] (left, bottom, right, top in framebuffer pixels). */
    fun frame(rect: FloatArray, fw: Int, fh: Int) {
        glViewport(0, 0, fw, fh)
        glUseProgram(frameProgram)
        glUniform4f(frameUniform, rect[0], rect[1], rect[2], rect[3])
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glUseProgram(program)
    }

    /** The program, vertex array, plane textures and blending the picture is drawn with, after an overlay changed them. */
    fun restore() {
        glUseProgram(program)
        glBindVertexArray(vao)
        for (plane in 0 until 3) {
            glActiveTexture(GL_TEXTURE0 + plane)
            glBindTexture(GL_TEXTURE_2D, textures[plane])
        }
        glActiveTexture(GL_TEXTURE0)
        glDisable(GL_BLEND)
        glDisable(GL_CULL_FACE)
        glDisable(GL_STENCIL_TEST)
        glDisable(GL_SCISSOR_TEST)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glUniform4f(sourceUniform, 0f, 0f, 1f, 1f)
    }

    /** The window's back buffer as drawn, bottom row first. */
    fun readFramebuffer(width: Int, height: Int): ByteBuffer {
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        return pixels
    }

    /** Draws the current picture at its own size into an offscreen texture and reads it back, bottom row first. */
    fun readPicture(picture: Size): ByteBuffer {
        val framebuffer = glGenFramebuffers()
        val target = glGenTextures()
        // Unit 3: units 0..2 hold the Y, U and V planes the shader samples.
        glActiveTexture(GL_TEXTURE3)
        glBindTexture(GL_TEXTURE_2D, target)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, picture.width, picture.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?)
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0)
        glViewport(0, 0, picture.width, picture.height)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        val pixels = ByteBuffer.allocateDirect(picture.width * picture.height * 4).order(ByteOrder.nativeOrder())
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, picture.width, picture.height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDeleteFramebuffers(framebuffer)
        glDeleteTextures(target)
        glActiveTexture(GL_TEXTURE0)
        return pixels
    }

    /** Frees the GL objects, with the window's context current. */
    fun delete() {
        glDeleteTextures(textures)
        glDeleteVertexArrays(vao)
        glDeleteProgram(program)
        glDeleteProgram(dimProgram)
        glDeleteProgram(frameProgram)
    }

    private fun buildProgram(fragmentSource: String): Int {
        val program = glCreateProgram()
        val vertex = compile(GL_VERTEX_SHADER, VERTEX)
        val fragment = compile(GL_FRAGMENT_SHADER, fragmentSource)
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
// The part of the picture drawn, in texture coordinates (left, top, right, bottom); all of it by default.
uniform vec4 source;
void main() {
    vec2 at = vec2(mix(source.x, source.z, uv.x), mix(source.y, source.w, uv.y));
    float y = (texture(planeY, at).r - 16.0 / 255.0) * (255.0 / 219.0);
    float u = (texture(planeU, at).r - 128.0 / 255.0) * (255.0 / 224.0);
    float v = (texture(planeV, at).r - 128.0 / 255.0) * (255.0 / 224.0);
    color = vec4(y + 1.5748 * v, y - 0.1873 * u - 0.4681 * v, y + 1.8556 * u, 1.0);
}"""

        // The safe-area frame: 4 px thick inside the rectangle (left, bottom, right, top), in 4 px dots and gaps.
        const val FRAME_FRAGMENT = """#version 330 core
out vec4 color;
uniform vec4 rect;
void main() {
    vec2 p = gl_FragCoord.xy;
    float t = 4.0;
    bool inside = p.x >= rect.x && p.x <= rect.z && p.y >= rect.y && p.y <= rect.w;
    bool inner = p.x >= rect.x + t && p.x <= rect.z - t && p.y >= rect.y + t && p.y <= rect.w - t;
    if (!inside || inner) discard;
    bool horizontal = p.y < rect.y + t || p.y > rect.w - t;
    float along = horizontal ? p.x - rect.x : p.y - rect.y;
    if (mod(floor(along / t), 2.0) == 1.0) discard;
    color = vec4(0.188, 0.820, 0.345, 1.0);
}"""

        // Black, blended over what is drawn: the fades' depth.
        const val DIM_FRAGMENT = """#version 330 core
out vec4 color;
uniform float alpha;
void main() {
    color = vec4(0.0, 0.0, 0.0, alpha);
}"""
    }
}
