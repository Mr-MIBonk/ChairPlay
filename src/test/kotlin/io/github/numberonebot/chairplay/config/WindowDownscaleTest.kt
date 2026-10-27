package io.github.numberonebot.chairplay.config

import io.github.numberonebot.chairplay.media.windowSizeLimit
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowDownscaleTest {
    @Test fun `large streams can shrink to 1280 wide despite Windows scaling`() {
        for ((picture, expected) in listOf(
            WindowSize(2560, 1440) to WindowSize(1280, 720),
            WindowSize(2560, 1080) to WindowSize(1280, 540),
            WindowSize(1920, 720) to WindowSize(1280, 480),
        )) {
            for (scale in listOf(1f, 1.5f, 2f)) {
                assertEquals(expected, windowSizeLimit(MAIN_WINDOW_MIN_SIZE, picture, picture, scale, true))
            }
        }
    }

    @Test fun `small streams keep their existing minimum`() {
        val picture = WindowSize(1080, 1080)
        assertEquals(WindowSize(1080, 1080), windowSizeLimit(MAIN_WINDOW_MIN_SIZE, picture, picture, 2f, true))
        assertEquals(WindowSize(1600, 960), windowSizeLimit(MAIN_WINDOW_MIN_SIZE, null, null, 2f, true))
    }

    @Test fun `AltScreen keeps the same minimum`() {
        val picture = WindowSize(2560, 1080)
        val minimum = WindowSize(ALTSCREEN_MIN_WIDTH, 1).fittedTo(picture)
        assertEquals(minimum, windowSizeLimit(minimum, picture, picture, 2f, false))
    }
}
