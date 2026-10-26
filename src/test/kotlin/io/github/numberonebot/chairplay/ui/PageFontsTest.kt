package io.github.numberonebot.chairplay.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The built page (the Vite build in the jar's ui/ folder) draws its text with the Reddit Sans the app ships, and loads no other font. */
class PageFontsTest {
    private val page = classpathAssets()

    private fun text(name: String): String = String(requireNotNull(page(name)) { "the built page has no $name" }, Charsets.UTF_8)

    /** Every @font-face in the page's stylesheets, as (family, weight) to the file it loads. */
    private fun faces(): Map<Pair<String, String>, String> {
        val index = text("index.html")
        val sheets = Regex("""href="/?(assets/[^"]+\.css)"""").findAll(index).map { it.groupValues[1] }.toList()
        assertEquals("the page's stylesheets", 1, sheets.size)
        return Regex("""@font-face\s*\{([^}]*)}""").findAll(text(sheets.single())).associate { face ->
            val body = face.groupValues[1]
            val family = Regex("""font-family:\s*["']?([^;"']+)""").find(body)!!.groupValues[1].trim()
            val weight = Regex("""font-weight:\s*(\d+)""").find(body)!!.groupValues[1]
            val url = Regex("""url\(["']?/?([^)"']+)""").find(body)!!.groupValues[1]
            (family to weight) to url
        }
    }

    @Test
    fun `the page loads its text font and no other`() {
        val shipped = mapOf(
            // A variable font: one face for every weight, 200 to 900.
            ("Reddit Sans" to "200") to "RedditSans-Variable.ttf",
        )
        val faces = faces()
        assertEquals(shipped.keys, faces.keys)
        for ((face, file) in shipped) {
            val hudBytes = PageFontsTest::class.java.getResourceAsStream("/fonts/$file")!!.use { it.readBytes() }
            assertArrayEquals("$face is $file", hudBytes, page(faces.getValue(face)))
        }
    }
}
