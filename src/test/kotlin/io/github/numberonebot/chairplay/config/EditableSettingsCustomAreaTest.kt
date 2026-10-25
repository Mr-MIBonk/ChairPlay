package io.github.numberonebot.chairplay.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** EditableSettings: an area group's custom rectangle, altscreen.<group>.area.custom, and the margins inside it. */
class EditableSettingsCustomAreaTest {
    private val card = customKey(AreaGroup.CARD)
    private val current = mapOf("altscreen.width" to "1440", "altscreen.height" to "540")

    private fun errors(vararg changes: Pair<String, String>, base: Map<String, String> = current) =
        EditableSettings.validate(changes.toMap(), base)

    @Test
    fun `a rectangle is four whole numbers, at least 64 px each way, inside the frame`() {
        assertEquals(emptyMap<String, String>(), errors(card to "412, 36, 616, 468"))
        assertEquals(emptyMap<String, String>(), errors(card to ""))
        assertEquals(emptyMap<String, String>(), errors(card to "0, 0, 1440, 540"))
        for (wrong in listOf("1,2,3", "a, b, c, d", "0, 0, 32, 540", "0, 0, 400, 60", "1200, 0, 400, 300", "-2, 0, 400, 300",
            "0, 300, 400, 300")) {
            assertEquals(wrong, setOf(card), errors(card to wrong).keys)
        }
        // Against the frame as it will be.
        assertEquals(setOf(card), errors(card to "1000, 0, 400, 300", "altscreen.width" to "1280").keys)
    }

    // Off the grid is no error: the save snaps it, as the app reads it.
    @Test
    fun `a rectangle off the grid is accepted and saved snapped`() {
        assertEquals(emptyMap<String, String>(), errors(card to "413, 37, 619, 469"))
        assertEquals("412, 36, 616, 468", EditableSettings.normalized(card, " 413,37, 619 ,469 "))
        // What is wrong stays as typed, for its error.
        assertEquals("0, 0, 32, 540", EditableSettings.normalized(card, "0, 0, 32, 540"))
        assertEquals("1,2,3", EditableSettings.normalized(card, "1,2,3"))
        assertEquals("custom", EditableSettings.normalized(areaKey(AreaGroup.CARD), " Custom "))
    }

    // A smaller frame clamps a saved rectangle as the app reads it, so it blocks no save.
    @Test
    fun `a smaller frame is no error on a rectangle saved before`() {
        val saved = current + (areaKey(AreaGroup.CARD) to "custom") + (card to "1200, 0, 200, 300")
        assertEquals(emptyMap<String, String>(), errors("altscreen.width" to "1280", base = saved))
    }

    @Test
    fun `margins wider than the rectangle are blamed on the rectangle or the margin that changed`() {
        val saved = current + (areaKey(AreaGroup.CARD) to "custom") + (card to "412, 36, 616, 468") +
            (safeKey(AreaGroup.CARD, "left") to "300") + (safeKey(AreaGroup.CARD, "right") to "300")
        assertEquals(emptyMap<String, String>(), errors(safeKey(AreaGroup.CARD, "top") to "10", base = saved))
        assertEquals(setOf(card), errors(card to "412, 36, 600, 468", base = saved).keys)
        assertEquals(setOf(safeKey(AreaGroup.CARD, "right")), errors(safeKey(AreaGroup.CARD, "right") to "320", base = saved).keys)
        // A blank rectangle under Custom holds the group's default, Center 3/8 (536 px here).
        val blank = saved + (card to "")
        assertEquals(emptyMap<String, String>(), errors(safeKey(AreaGroup.CARD, "right") to "200", base = blank))
        assertEquals(setOf(safeKey(AreaGroup.CARD, "right")), errors(safeKey(AreaGroup.CARD, "right") to "240", base = blank).keys)
        // The rectangle under a preset: its margins shrink with it, as in a narrower preset.
        assertEquals(emptyMap<String, String>(), errors(card to "0, 0, 400, 300", base = saved + (areaKey(AreaGroup.CARD) to "full")))
    }

    @Test
    fun `custom is a choice of the area, at once, and the rectangle applies on reconnect`() {
        assertEquals(emptyMap<String, String>(), errors(areaKey(AreaGroup.MAP) to "custom", areaKey(AreaGroup.CARD) to "custom"))
        assertTrue(areaKey(AreaGroup.MAP) in errors(areaKey(AreaGroup.MAP) to "half"))
        assertEquals(Applies.NOW, EditableSettings.applies(areaKey(AreaGroup.CARD)))
        for (group in AreaGroup.entries) assertEquals(Applies.RECONNECT, EditableSettings.applies(customKey(group)))
    }
}
