package io.github.numberonebot.chairplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildInfoTest {
    // The build writes build-info.properties into the resources: this repository's commit and the DiPlay core's.
    @Test
    fun `the build records its own commit and the DiPlay core's`() {
        val info = BuildInfo.load()

        for (commit in listOf(info.app, info.core)) {
            assertTrue("$commit", commit != null && Regex("[0-9a-f]{8}").matches(commit.id))
            assertTrue("$commit", Regex("""\d{4}-\d{2}-\d{2}""").matches(commit!!.date))
        }
    }

    @Test
    fun `a build without a record has none to show`() {
        assertEquals(BuildInfo(Commit("4e6d50df", "2026-10-23"), null), BuildInfo.parse("app.commit=4e6d50df\napp.date=2026-10-23\n"))
        assertNull(BuildInfo.parse("").app)
    }

    @Test
    fun `a release build records its version and a development build has none`() {
        assertEquals("0.1.0", BuildInfo.parse("app.version=0.1.0\napp.commit=4e6d50df\napp.date=2026-10-25\n").version)
        assertNull(BuildInfo.parse("app.commit=4e6d50df\napp.date=2026-10-25\n").version)
        assertNull(BuildInfo.parse("app.version=\n").version)
    }

    // The log's first line says which build wrote it, at a level a release build logs.
    @Test
    fun `the start line names the version or a development build, and the commit`() {
        val release = BuildInfo(Commit("4e6d50df", "2026-10-25"), null, "0.1.0").startLine()
        val development = BuildInfo(Commit("4e6d50df", "2026-10-25"), null).startLine()
        val unrecorded = BuildInfo(null, null).startLine()

        assertTrue(release, "0.1.0" in release && "4e6d50df" in release)
        assertTrue(development, "4e6d50df" in development && "null" !in development)
        assertTrue(unrecorded, "null" !in unrecorded)
        assertTrue(listOf(release, development, unrecorded).none { '\n' in it })
    }

    @Test
    fun `the page carries the version and both commits`() {
        val page = BuildInfo(Commit("4e6d50df", "2026-10-25"), null, "0.1.0").toPage("details text")

        assertEquals("0.1.0", page["version"])
        assertEquals(mapOf("commit" to "4e6d50df", "date" to "2026-10-25"), page["app"])
        assertNull(page["core"])
        assertEquals("details text", page["details"])
        assertNull(BuildInfo(null, null).toPage("")["version"])
    }
}
