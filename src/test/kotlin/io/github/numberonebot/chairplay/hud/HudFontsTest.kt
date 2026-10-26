package io.github.numberonebot.chairplay.hud

import java.awt.Font
import org.junit.Assert.assertTrue
import org.junit.Test

class HudFontsTest {
    private fun font(file: String): Font =
        HudFontsTest::class.java.getResourceAsStream("/fonts/$file")!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }

    // Songs, streets and callers come in any script: each face and its fallback must cover their letters.
    @Test
    fun `every letter of a song, a street or a caller is drawn by the HUD's faces`() {
        val text = "Café Straße Łódź Привіт, Ёлка Ґанок Їжак Ελλάδα Thành phố 1.2 km → 12:45 · 3°"
        val fonts = HUD_FONTS.associate { it.name to font(it.file) }
        for (face in HUD_FONTS) {
            val primary = fonts.getValue(face.name)
            val fallback = face.fallback?.let { fonts.getValue(it) }
            val missing = text.filter {
                !it.isWhitespace() && !primary.canDisplay(it) && fallback?.canDisplay(it) != true
            }
            assertTrue("${face.name} draws no $missing", missing.isEmpty())
        }
    }
}
