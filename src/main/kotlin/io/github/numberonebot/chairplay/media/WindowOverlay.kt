package io.github.numberonebot.chairplay.media

/**
 * Something a [VideoWindow] draws instead of its picture, on the window's GL thread with its GL context current:
 * [open] once before the first [paint], [close] when the window goes. Drawing may change any GL state; the window
 * sets its own again before it draws a picture.
 */
interface WindowOverlay {
    fun open()

    /** Draws onto the cleared framebuffer of [width] x [height] pixels. */
    fun paint(width: Int, height: Int)

    fun close()
}
