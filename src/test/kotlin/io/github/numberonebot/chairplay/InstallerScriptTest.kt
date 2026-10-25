package io.github.numberonebot.chairplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The installer's directives that decide what it may touch: a per-user install without admin rights into the
 * folder ChairPlay reads its data from, never closing a running ChairPlay, recognizing an earlier install, and
 * clearing or shipping nothing but program files.
 */
class InstallerScriptTest {
    private val sections: Map<String, List<String>> = parse(File("packaging/installer/ChairPlay.iss").readLines())

    private fun setup(key: String): String? = sections["setup"].orEmpty()
        .map { it.split('=', limit = 2) }
        .lastOrNull { it.size == 2 && it[0].trim().equals(key, ignoreCase = true) }
        ?.get(1)?.trim()

    @Test
    fun `installs per user without administrator rights into the user's programs folder`() {
        assertEquals("lowest", setup("PrivilegesRequired"))
        assertEquals("{localappdata}\\Programs\\ChairPlay", setup("DefaultDirName"))
    }

    @Test
    fun `never closes a running ChairPlay through the Restart Manager`() {
        assertEquals("no", setup("CloseApplications"))
    }

    @Test
    fun `keeps a fixed GUID as its AppId so a new installer updates the existing install`() {
        val appId = setup("AppId").orEmpty()
        assertTrue(appId, Regex("""\{\{[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}}""").matches(appId))
    }

    @Test
    fun `clears only the app and runtime folders before an update`() {
        val names = sections["installdelete"].orEmpty().map { param(it, "Name") }
        assertEquals(setOf("{app}\\app", "{app}\\runtime"), names.toSet())
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `deletes nothing more than it installed on uninstall`() {
        assertFalse(sections.containsKey("uninstalldelete"))
    }

    @Test
    fun `ships no user data file`() {
        val sources = sections["files"].orEmpty().flatMap { listOf(param(it, "Source"), param(it, "DestName")) }
        assertTrue(sections["files"].orEmpty().isNotEmpty())
        for (source in sources) for (name in userData) {
            assertFalse("$source names $name", source.contains(name, ignoreCase = true))
        }
    }

    companion object {
        private val userData = listOf(
            "settings.properties", "controls.properties", "window.properties", "identity.properties", "pairings",
            "offline-mfi", "carplay-icon.png", "logs", "screenshots", "ChairPlay.lock",
        )

        /** Lines of each `[Section]`, by its lower-case name, without blanks, `;` comments and `#` directives. */
        private fun parse(lines: List<String>): Map<String, List<String>> {
            val result = mutableMapOf<String, MutableList<String>>()
            var current: MutableList<String>? = null
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
                val header = Regex("""\[(\w+)]""").matchEntire(line)
                if (header != null) {
                    current = result.getOrPut(header.groupValues[1].lowercase()) { mutableListOf() }
                } else {
                    current?.add(line)
                }
            }
            return result
        }

        /** A parameter of a section entry (`Name: "x"; Type: y`), unquoted, or "" when the entry has none. */
        private fun param(entry: String, key: String): String = entry.split(';')
            .map { it.split(':', limit = 2) }
            .firstOrNull { it.size == 2 && it[0].trim().equals(key, ignoreCase = true) }
            ?.get(1)?.trim()?.removeSurrounding("\"").orEmpty()
    }
}
