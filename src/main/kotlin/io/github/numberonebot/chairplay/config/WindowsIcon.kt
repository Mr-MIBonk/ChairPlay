package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.airplay.AirPlayIcon
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The car-button icon: a classic Windows window on the teal desktop, drawn rather than shipped as a file.
 * Upstream sends one square PNG of at most 192 pixels (AirPlayIconSizing): the iPhone stopped connecting
 * when a large icon was in /info (#567). iOS rounds the corners itself.
 */
object WindowsIcon {
    const val SIZE = 192

    fun render(): AirPlayIcon {
        val image = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            draw(g)
        } finally {
            g.dispose()
        }
        val png = ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "no PNG writer" }
            output.toByteArray()
        }
        return AirPlayIcon(widthPixels = SIZE, heightPixels = SIZE, data = png)
    }

    private fun draw(g: Graphics2D) {
        g.color = DESKTOP
        g.fillRect(0, 0, SIZE, SIZE)

        // Window frame with the raised two-pixel bevel of Windows 95/98.
        val x = 24
        val y = 34
        val w = 144
        val h = 124
        g.color = FACE
        g.fillRect(x, y, w, h)
        raised(g, x, y, w, h)

        // Title bar.
        val barX = x + 6
        val barY = y + 6
        val barW = w - 12
        val barH = 24
        g.paint = GradientPaint(barX.toFloat(), 0f, TITLE_START, (barX + barW).toFloat(), 0f, TITLE_END)
        g.fillRect(barX, barY, barW, barH)

        // Minimize, maximize and close buttons.
        val button = 18
        val buttonY = barY + 3
        val closeX = barX + barW - button - 3
        val maximizeX = closeX - button - 4
        val minimizeX = maximizeX - button
        for (bx in listOf(minimizeX, maximizeX, closeX)) {
            g.color = FACE
            g.fillRect(bx, buttonY, button, button)
            raised(g, bx, buttonY, button, button)
        }
        g.color = Color.BLACK
        g.fillRect(minimizeX + 5, buttonY + 12, 8, 2)
        g.drawRect(maximizeX + 4, buttonY + 4, 9, 9)
        g.fillRect(maximizeX + 4, buttonY + 4, 10, 2)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.stroke = BasicStroke(2.2f)
        g.drawLine(closeX + 5, buttonY + 5, closeX + 13, buttonY + 13)
        g.drawLine(closeX + 13, buttonY + 5, closeX + 5, buttonY + 13)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)

        // Sunken white client area.
        val clientX = barX
        val clientY = barY + barH + 6
        val clientW = barW
        val clientH = y + h - 6 - clientY
        g.color = Color.WHITE
        g.fillRect(clientX, clientY, clientW, clientH)
        sunken(g, clientX, clientY, clientW, clientH)
    }

    private fun raised(g: Graphics2D, x: Int, y: Int, w: Int, h: Int) =
        bevel(g, x, y, w, h, Color.WHITE, Color.BLACK, LIGHT, SHADOW)

    private fun sunken(g: Graphics2D, x: Int, y: Int, w: Int, h: Int) =
        bevel(g, x, y, w, h, SHADOW, Color.WHITE, Color.BLACK, LIGHT)

    /** Two one-pixel rings, the outer one in [outerTopLeft]/[outerBottomRight], the inner one in the other two. */
    private fun bevel(
        g: Graphics2D, x: Int, y: Int, w: Int, h: Int,
        outerTopLeft: Color, outerBottomRight: Color, innerTopLeft: Color, innerBottomRight: Color,
    ) {
        g.stroke = BasicStroke(1f)
        ring(g, x, y, w, h, outerTopLeft, outerBottomRight)
        ring(g, x + 1, y + 1, w - 2, h - 2, innerTopLeft, innerBottomRight)
    }

    private fun ring(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, topLeft: Color, bottomRight: Color) {
        g.color = topLeft
        g.drawLine(x, y, x + w - 1, y)
        g.drawLine(x, y, x, y + h - 1)
        g.color = bottomRight
        g.drawLine(x, y + h - 1, x + w - 1, y + h - 1)
        g.drawLine(x + w - 1, y, x + w - 1, y + h - 1)
    }

    private val DESKTOP = Color(0x00, 0x80, 0x80)
    private val FACE = Color(0xC0, 0xC0, 0xC0)
    private val LIGHT = Color(0xDF, 0xDF, 0xDF)
    private val SHADOW = Color(0x80, 0x80, 0x80)
    private val TITLE_START = Color(0x00, 0x00, 0x80)
    private val TITLE_END = Color(0x10, 0x84, 0xD0)
}
