package io.github.numberonebot.chairplay.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WindowPlacementTest {
    private val configured = WindowSize(1280, 720)

    @Test
    fun `remembers where the window was left`() {
        val store = WindowPlacementStore(Files.createTempDirectory("chairplay-window"))
        val placement = WindowPlacement(x = -1500, y = 120, width = 1600, height = 900, maximized = true)

        store.save(placement, configured)

        assertEquals(placement, store.load(configured))
    }

    @Test
    fun `nothing is remembered before the first close`() {
        assertNull(WindowPlacementStore(Files.createTempDirectory("chairplay-window")).load(configured))
    }

    // A file edited into nonsense is forgotten rather than stopping the start.
    @Test
    fun `a broken file is ignored`() {
        val dir = Files.createTempDirectory("chairplay-window")
        Files.writeString(dir.resolve(WindowPlacementStore.FILE), "x=left\ny=10\nwidth=0\nheight=720\n")

        assertNull(WindowPlacementStore(dir).load(configured))
    }

    // window.width/height (or the display size they follow) changed since: the new size wins, the place stays.
    @Test
    fun `a new window size in the settings replaces the remembered one`() {
        val store = WindowPlacementStore(Files.createTempDirectory("chairplay-window"))
        store.save(WindowPlacement(200, 100, 1600, 900, maximized = false), configured)

        assertEquals(WindowPlacement(200, 100, 960, 540, maximized = false), store.load(WindowSize(960, 540)))
    }

    // The altscreen window keeps its place in the same file under its own prefix, next to the main window's.
    @Test
    fun `two windows share the file without overwriting each other`() {
        val dir = Files.createTempDirectory("chairplay-window")
        val main = WindowPlacementStore(dir)
        val altscreen = WindowPlacementStore(dir, prefix = "altscreen.")
        main.save(WindowPlacement(10, 20, 1280, 720, maximized = false), configured)
        altscreen.save(WindowPlacement(30, 900, 1280, 480, maximized = false), WindowSize(1280, 480))
        main.save(WindowPlacement(11, 21, 1300, 740, maximized = true), configured)

        assertEquals(WindowPlacement(11, 21, 1300, 740, maximized = true), main.load(configured))
        assertEquals(WindowPlacement(30, 900, 1280, 480, maximized = false), altscreen.load(WindowSize(1280, 480)))
    }

    // With window.keep.aspect a remembered or configured size takes the picture's shape, keeping its width.
    @Test
    fun `a size is fitted to the picture's aspect by its width`() {
        assertEquals(WindowSize(1280, 720), WindowSize(1280, 828).fittedTo(WindowSize(1280, 720)))
        assertEquals(WindowSize(1000, 375), WindowSize(1000, 900).fittedTo(WindowSize(1280, 480)))
    }

    // The AltScreen opens on the content and card layout it was left on, beside the windows' places in the file.
    @Test
    fun `the AltScreen view is remembered unless the settings changed`() {
        val dir = Files.createTempDirectory("chairplay-window")
        val windows = WindowPlacementStore(dir)
        val view = AltscreenViewStore(dir)
        windows.save(WindowPlacement(10, 20, 1280, 720, maximized = false), configured)
        view.save(AltscreenContent.HUD, AltscreenCards.RIGHT, configured = AltscreenContent.MAP to AltscreenCards.AUTO)

        assertEquals(AltscreenContent.HUD to AltscreenCards.RIGHT, view.load(AltscreenContent.MAP, AltscreenCards.AUTO))
        assertEquals(WindowPlacement(10, 20, 1280, 720, maximized = false), windows.load(configured))
        // altscreen.content or altscreen.cards edited since: the settings win.
        assertEquals(AltscreenContent.CARD to AltscreenCards.AUTO, view.load(AltscreenContent.CARD, AltscreenCards.AUTO))
    }

    @Test
    fun `without a remembered view the settings apply`() {
        assertEquals(
            AltscreenContent.MAP to AltscreenCards.TOP,
            AltscreenViewStore(Files.createTempDirectory("chairplay-window")).load(AltscreenContent.MAP, AltscreenCards.TOP),
        )
    }

    @Test
    fun `a window on a monitor that is still there is visible`() {
        val screens = listOf(ScreenArea(0, 0, 1920, 1040), ScreenArea(-1920, 0, 1920, 1040))

        assertTrue(WindowPlacement(-1500, 120, 1280, 720, false).isVisibleOn(screens))
        // Mostly off the right edge, but enough of its title bar is left to drag it back.
        assertTrue(WindowPlacement(1700, 300, 1280, 720, false).isVisibleOn(screens))
    }

    @Test
    fun `a window left on a monitor that is gone is not visible`() {
        val screens = listOf(ScreenArea(0, 0, 1920, 1040))

        assertFalse(WindowPlacement(-1500, 120, 1280, 720, false).isVisibleOn(screens))
        // Its title bar would sit above the top of the screen, out of reach.
        assertFalse(WindowPlacement(300, -200, 1280, 720, false).isVisibleOn(screens))
        // Only a sliver of the title bar shows at the right edge.
        assertFalse(WindowPlacement(1900, 300, 1280, 720, false).isVisibleOn(screens))
    }
}
