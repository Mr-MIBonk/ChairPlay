package io.github.numberonebot.chairplay.ui

import io.github.numberonebot.chairplay.drive.Well
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UiController: the virtual car's wells in the state, for the AltScreen page's drawing of a custom area. */
class UiControllerWellsTest : UiControllerFixture() {
    private fun settings(enabled: Boolean) = "wifi.ssid=Home\nwifi.passphrase=secret123\nwifi.security=WPA_WPA2\n" +
        "altscreen.enabled=$enabled\naltscreen.width=1440\naltscreen.height=540\ngps.gauges=large\n"

    @Suppress("UNCHECKED_CAST")
    private fun wells(ui: UiController) = ui.state()["altscreenWells"] as List<Map<String, Any?>>

    @Test
    fun `the state carries two wells, smaller after the gauges are set small`() {
        val ui = controller(settings = settings(enabled = true))
        val large = wells(ui)

        ui.saveSettings(mapOf("gps.gauges" to "small"))
        val small = wells(ui)

        assertEquals(2, large.size)
        assertEquals(2, small.size)
        assertEquals(244.8f, (large[0]["radius"] as Number).toFloat(), 0.5f)
        assertTrue((small[0]["radius"] as Number).toFloat() < (large[0]["radius"] as Number).toFloat())
    }

    @Test
    fun `no wells while the AltScreen is off`() {
        assertEquals(emptyList<Well>(), wells(controller(settings = settings(enabled = false))))
    }
}
