package io.github.numberonebot.chairplay.media

/**
 * A native view inside a [VideoWindow]'s client area, over the picture, such as the web UI. Every call comes on
 * the window's (main) thread, the one whose message loop GLFW pumps.
 */
interface WindowChild {
    /** The window exists; [nativeWindow] is its handle (an HWND on Windows). */
    fun attach(nativeWindow: Long)

    /** Each pass of the loop, with the client area's size in pixels: the time to resize, show or hide. */
    fun layout(width: Int, height: Int)

    /** The window is about to be destroyed. */
    fun detach()
}
