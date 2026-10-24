package io.github.numberonebot.chairplay.media

import io.github.numberonebot.chairplay.config.WindowSize
import io.github.numberonebot.chairplay.input.Size

/**
 * The picture a safe-area frame is laid out on: the one shown, or with none (under the HUD, before the first) the
 * size the last connection brought, and before any the size the window [opened] for.
 */
internal fun frameShape(picture: Size?, stream: WindowSize?, opened: Size): Size =
    picture ?: stream?.let { Size(it.width, it.height) } ?: opened

/**
 * Where a safe area of [picture] lies in a [fw] x [fh] framebuffer, the picture fitted in at its own shape and centred
 * as the window draws it: (left, bottom, right, top) in framebuffer pixels, y up as GL counts it. The insets are in the
 * pixels of a frame [of] that size, the picture's own unless the settings now give the next connection another one.
 */
internal fun safeAreaRect(picture: Size, fw: Int, fh: Int, top: Int, bottom: Int, left: Int, right: Int, of: Size = picture): FloatArray {
    val scale = minOf(fw.toFloat() / picture.width, fh.toFloat() / picture.height)
    val across = scale * picture.width / of.width
    val down = scale * picture.height / of.height
    val x0 = (fw - picture.width * scale) / 2
    val y0 = (fh - picture.height * scale) / 2
    return floatArrayOf(
        x0 + left * across,
        y0 + bottom * down,
        x0 + picture.width * scale - right * across,
        y0 + picture.height * scale - top * down,
    )
}
