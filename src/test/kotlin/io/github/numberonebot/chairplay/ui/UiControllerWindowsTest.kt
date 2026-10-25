package io.github.numberonebot.chairplay.ui

import io.github.numberonebot.chairplay.config.WindowSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UiController: the app's windows as the pages show, size and frame them. */
class UiControllerWindowsTest : UiControllerFixture() {
    // Show frame previews the safe area saved on the AltScreen page, before a restart puts it in force.
    @Test
    fun `Show frame draws the saved AltScreen safe area on its window`() {
        val shown = mutableListOf<List<Int>>()
        val frame = UiSafeAreaFrame { top, bottom, left, right, width, height -> shown += listOf(top, bottom, left, right, width, height) }
        val ui = controller(settings = "altscreen.safe.top=40\naltscreen.safe.left=100\naltscreen.safe.right=\n", altscreenFrame = frame)

        assertTrue(ui.showAltscreenFrame())

        // In the pixels of the frame the settings give, the default 1440x540 here.
        assertEquals(listOf(listOf(40, 0, 100, 0, 1440, 540)), shown)
        assertFalse(controller().showAltscreenFrame())
    }

    // A view's Show frame draws its own margins inside its part, as insets from the whole frame; a view without either
    // draws the AltScreen's, as it uses them.
    @Test
    fun `Show frame for a view draws its part's margins`() {
        val shown = mutableListOf<List<Int>>()
        val frame = UiSafeAreaFrame { top, bottom, left, right, width, height -> shown += listOf(top, bottom, left, right, width, height) }
        val ui = controller(settings = "altscreen.width=1920\naltscreen.height=720\naltscreen.safe.top=40\n" +
            "altscreen.card.area=center3\naltscreen.card.safe.top=10\n", altscreenFrame = frame)

        assertTrue(ui.showAltscreenFrame("card"))
        assertTrue(ui.showAltscreenFrame("map"))
        assertFalse(ui.showAltscreenFrame("hud"))
        // A part without margins of its own has the AltScreen's clipped to it, as DiPlay declares it.
        val clipped = controller(settings = "altscreen.width=1920\naltscreen.height=720\naltscreen.safe.top=40\naltscreen.map.area=left5\n",
            altscreenFrame = frame)
        assertTrue(clipped.showAltscreenFrame("map"))

        assertEquals(listOf(listOf(10, 0, 600, 600, 1920, 720), listOf(40, 0, 0, 0, 1920, 720), listOf(40, 0, 0, 720, 1920, 720)), shown)
    }

    // A group set to custom shows its saved rectangle as insets from the whole frame, its own margins inside it.
    @Test
    fun `Show frame for a view set to custom draws its rectangle and margins`() {
        val shown = mutableListOf<List<Int>>()
        val frame = UiSafeAreaFrame { top, bottom, left, right, width, height -> shown += listOf(top, bottom, left, right, width, height) }
        val rect = "altscreen.card.area=custom\naltscreen.card.area.custom=412, 36, 616, 468\n"

        assertTrue(controller(settings = rect + "altscreen.card.safe.top=10\naltscreen.card.safe.left=20\n", altscreenFrame = frame)
            .showAltscreenFrame("card"))
        assertTrue(controller(settings = rect, altscreenFrame = frame).showAltscreenFrame("card"))

        assertEquals(listOf(listOf(46, 36, 432, 412, 1440, 540), listOf(36, 36, 412, 412, 1440, 540)), shown)
    }

    // Actual size cannot go below the window's smallest size, which Windows' display scale makes larger.
    @Test
    fun `the state carries the main window's smallest size`() {
        val window = FakeMainWindow(1024, 576).apply { minWidth = 1000; minHeight = 600 }

        val main = controller(mainWindow = window).state()["mainWindow"] as Map<*, *>

        assertEquals(1000, main["minWidth"])
        assertEquals(600, main["minHeight"])
    }

    // The CarPlay page offers Actual size while the window's client area differs from the picture the app asked for.
    @Test
    fun `the state carries the main window's size and the picture's`() {
        val ui = controller(mainWindow = FakeMainWindow(1024, 576))

        assertEquals(
            mapOf("width" to 1024, "height" to 576, "pictureWidth" to 1280, "pictureHeight" to 720, "minWidth" to 0, "minHeight" to 0),
            ui.state()["mainWindow"],
        )
        assertNull(controller().state()["mainWindow"])
    }

    @Test
    fun `Actual size resizes the main window to one screen pixel per picture pixel`() {
        val window = FakeMainWindow(1024, 576)
        val ui = controller(mainWindow = window)

        assertTrue(ui.actualSize("main"))

        assertEquals(listOf("1280x720"), window.asked)
        assertFalse(controller().actualSize("main"))
    }

    // The AltScreen's window can be dragged to another size as well; its picture is the size the AltScreen page set.
    @Test
    fun `the AltScreen window has an Actual size of its own`() {
        val alt = FakeMainWindow(1200, 450).apply { picture = WindowSize(1440, 540) }
        val ui = controller(altscreenWindow = alt)

        assertEquals(
            mapOf("width" to 1200, "height" to 450, "pictureWidth" to 1440, "pictureHeight" to 540, "minWidth" to 0, "minHeight" to 0),
            ui.state()["altscreenWindow"],
        )
        assertTrue(ui.actualSize("altscreen"))
        assertEquals(listOf("1440x540"), alt.asked)
        assertFalse(ui.actualSize("controls"))
        assertNull(controller().state()["altscreenWindow"])
    }

    // The AltScreen's window waits, hidden, while altscreen.enabled is off: the page sees no AltScreen until a
    // connection turns it on.
    @Test
    fun `an AltScreen that is off is not there for the page`() {
        val trigger = FakeWindow(visible = false, available = false)
        val alt = FakeMainWindow(1440, 540).apply { picture = WindowSize(1440, 540); available = false }
        val ui = controller(windows = mapOf("altscreen" to trigger), altscreenWindow = alt)

        assertEquals(mapOf("available" to false, "visible" to false), (ui.state()["windows"] as Map<*, *>)["altscreen"])
        assertNull(ui.state()["altscreenWindow"])
        assertFalse(ui.actualSize("altscreen"))

        trigger.available = true
        alt.available = true
        assertEquals(mapOf("available" to true, "visible" to false), (ui.state()["windows"] as Map<*, *>)["altscreen"])
        assertEquals(1440, (ui.state()["altscreenWindow"] as Map<*, *>)["pictureWidth"])
    }

    @Test
    fun `a main window resized moves the version on`() {
        val ui = controller(mainWindow = FakeMainWindow(1024, 576))
        val before = ui.version

        ui.windowsChanged()

        assertTrue(ui.version > before)
    }

    // The settings screen's triggers: the AltScreen's and the controls' windows, shown or hidden, or not there at all.
    @Test
    fun `the state says which windows there are and which show`() {
        val ui = controller(windows = mapOf("controls" to FakeWindow(visible = false)))

        assertEquals(
            mapOf(
                "altscreen" to mapOf("available" to false, "visible" to false),
                "controls" to mapOf("available" to true, "visible" to false),
            ),
            ui.state()["windows"],
        )
    }

    @Test
    fun `a trigger shows or hides its window, if there is one`() {
        val controls = FakeWindow(visible = true)
        val ui = controller(windows = mapOf("controls" to controls))

        assertTrue(ui.window("controls", visible = false))
        assertFalse(controls.visible)
        assertFalse(ui.window("altscreen", visible = true))
        assertFalse(ui.window("elsewhere", visible = true))
    }

    // A window hides itself with its close button: the page hears of it.
    @Test
    fun `a window shown or hidden moves the version on`() {
        val ui = controller(windows = mapOf("controls" to FakeWindow(visible = true)))
        val before = ui.version

        ui.windowsChanged()

        assertTrue(ui.version > before)
    }
}
