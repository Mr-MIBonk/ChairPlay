package io.github.numberonebot.chairplay.hud.maneuver

import java.nio.ByteBuffer

/**
 * chairplay_maneuver.dll (native/maneuver): the MHI2Q project's maneuver renderer, run frame by frame inside the
 * HUD window's OpenGL 3.3 core context and drawing into a texture of its own. Every call except [glslToCore] needs
 * that context current on the calling thread. The library is looked for on java.library.path, like speex_echo.dll.
 */
internal object ManeuverNative {
    /** Null once the library has loaded, else why it could not. */
    val loadError: String? by lazy {
        try {
            System.loadLibrary("chairplay_maneuver")
            null
        } catch (e: UnsatisfiedLinkError) {
            e.message ?: "chairplay_maneuver.dll not found"
        }
    }

    /** [frame]'s result bits. */
    const val RENDERED = 1
    /** A transition runs: the next frame should follow soon. */
    const val ANIMATING = 2
    /** Only the destination flag waves: a slower frame rate does. */
    const val LOOPING = 4
    /** A maneuver is on screen, not cleared. */
    const val SHOWING = 8

    /** The renderer's whole frame in its own pixels (protocol.h); the texture keeps this shape. */
    const val FRAME_WIDTH = 328
    const val FRAME_HEIGHT = 181
    /** Where a maneuver is centred and framed in that frame: the cluster's popup crop. */
    const val CROP_X = 59
    const val CROP_Y = 27
    const val CROP_WIDTH = 210
    const val CROP_HEIGHT = 153

    /** Creates the renderer drawing into a [width] x [height] texture and returns that texture, or 0 ([lastError]). */
    @JvmStatic external fun init(width: Int, height: Int): Int

    @JvmStatic external fun lastError(): String

    @JvmStatic external fun resize(width: Int, height: Int)

    /** Queues one 48-byte command ([ManeuverPackets]) for the next [frame]. */
    @JvmStatic external fun command(packet: ByteArray): Boolean

    /** Applies the queued commands and draws when anything changed; [now] is a monotonic time in seconds. */
    @JvmStatic external fun frame(now: Double): Int

    /** Copies the texture, bottom row first, premultiplied RGBA, into a direct buffer; for tests. */
    @JvmStatic external fun read(width: Int, height: Int, pixels: ByteBuffer): Boolean

    @JvmStatic external fun shutdown()

    /** The GLSL translation the GL layer applies to the renderer's shaders; needs no GL context. */
    @JvmStatic external fun glslToCore(source: String, fragment: Boolean): String?
}
