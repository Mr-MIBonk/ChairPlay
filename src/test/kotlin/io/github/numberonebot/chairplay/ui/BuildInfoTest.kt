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
}
