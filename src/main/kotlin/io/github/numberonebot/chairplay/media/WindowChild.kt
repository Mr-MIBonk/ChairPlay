package io.github.numberonebot.chairplay.media

/**
 * A native view inside a [VideoWindow]'s client area, over the picture, such as the web UI. Every call comes on
 * the window's (main) thread, the one whose message loop GLFW pumps.
 */
interface WindowChild {
    /** The window exists; [nativeWindow] is its handle (an HWND on Windows). */
    fun attach(nativeWindow: Long)

    /** Whether it wants to cover the window now; the window fades through black before it shows or hides it. */
    val covers: Boolean get() = true

    /**
     * Each pass of the loop, with the client area's size in pixels: the time to resize, show or hide. [shown] is
     * whether it is to show now, which lags [covers] while the window fades.
     */
    fun layout(width: Int, height: Int, shown: Boolean = covers)

    /** The window is about to be destroyed. */
    fun detach()
}
