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
 * picture's pixels.
 */
internal fun safeAreaRect(picture: Size, fw: Int, fh: Int, top: Int, bottom: Int, left: Int, right: Int): FloatArray {
    val scale = minOf(fw.toFloat() / picture.width, fh.toFloat() / picture.height)
    val x0 = (fw - picture.width * scale) / 2
    val y0 = (fh - picture.height * scale) / 2
    return floatArrayOf(
        x0 + left * scale,
        y0 + bottom * scale,
        x0 + (picture.width - right) * scale,
        y0 + (picture.height - top) * scale,
    )
}
