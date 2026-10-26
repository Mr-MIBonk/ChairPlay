package io.github.numberonebot.chairplay.hud

import java.awt.Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudFontsTest {
    private fun font(file: String): Font =
        HudFontsTest::class.java.getResourceAsStream("/fonts/$file")!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }

    // Songs, streets and callers come in any script: the HUD's faces must draw every letter of them.
    @Test
    fun `every letter of a song, a street or a caller is drawn by the HUD's faces`() {
        val text = "Café Straße Łódź Привіт, Ёлка Ґанок Їжак Ελλάδα Thành phố 1.2 km → 12:45 · 3°"
        assertEquals(setOf(REGULAR, BOLD), HUD_FONTS.map { it.name }.toSet())
        for (face in HUD_FONTS) {
            val missing = text.filter { !it.isWhitespace() && !font(face.file).canDisplay(it) }
            assertTrue("${face.name} draws no $missing", missing.isEmpty())
        }
    }
}
