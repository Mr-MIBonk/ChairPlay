package io.github.numberonebot.chairplay.media

import io.github.numberonebot.chairplay.config.WindowSize
import io.github.numberonebot.chairplay.input.Size
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SafeAreaFrameTest {
    // GL counts y from the bottom; the frame's rectangle is (left, bottom, right, top) in framebuffer pixels.
    @Test
    fun `at one window pixel per picture pixel the frame sits on the insets`() {
        val rect = safeAreaRect(Size(1440, 540), 1440, 540, top = 40, bottom = 0, left = 100, right = 20)

        assertArrayEquals(floatArrayOf(100f, 0f, 1420f, 500f), rect, 0.01f)
    }

    // The picture is letterboxed into the window at its own shape, as the window draws it; the frame follows it.
    @Test
    fun `in a window of another size the frame follows the scaled, centred picture`() {
        val rect = safeAreaRect(Size(1440, 540), 720, 400, top = 40, bottom = 0, left = 100, right = 20)

        // Half the size: 720x270, 65 px above and below.
        assertArrayEquals(floatArrayOf(50f, 65f, 710f, 315f), rect, 0.01f)
    }

    // Insets saved for another frame size than the picture's (a new size waiting for the next connection) scale to the
    // picture: the middle half of a 1440 px frame is the middle half of a 1280 px picture.
    @Test
    fun `insets of another frame size scale to the picture`() {
        val rect = safeAreaRect(Size(1280, 480), 1280, 480, top = 0, bottom = 0, left = 360, right = 360, of = Size(1440, 540))

        assertArrayEquals(floatArrayOf(320f, 0f, 960f, 480f), rect, 0.01f)
    }

    // Under the HUD there is no picture: the frame takes the size the last connection brought, not the one at start.
    @Test
    fun `without a picture the frame takes the stream's size, not the size the window opened for`() {
        assertEquals(Size(1600, 600), frameShape(picture = null, stream = WindowSize(1600, 600), opened = Size(1440, 540)))
        assertEquals(Size(1440, 540), frameShape(picture = null, stream = null, opened = Size(1440, 540)))
        assertEquals(Size(1280, 480), frameShape(picture = Size(1280, 480), stream = WindowSize(1600, 600), opened = Size(1440, 540)))
    }
}
