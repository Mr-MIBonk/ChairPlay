package io.github.numberonebot.chairplay

import org.junit.Assert.*
import org.junit.Test

/** The feedback expires even without input; another press restarts its lifetime, including at a volume limit. */
class VolumeTitleTest {
    private var now = 0L
    private var controlsVisible = true
    private val titles = mutableListOf<String?>()
    private val mainTitles = mutableListOf<String?>()
    private val feedback = VolumeTitle({ controlsVisible }, titles::add, mainTitles::add) { now }

    @Test
    fun `idle feedback leaves the title alone and active feedback expires`() {
        assertNull(feedback.pass())
        assertTrue(titles.isEmpty())
        feedback.show(65, false)
        assertEquals(1, titles.size)
        val remaining = feedback.pass()!!
        assertTrue(remaining > 0)
        now += (remaining * 1_000_000_000).toLong() + 1
        assertNull(feedback.pass())
        assertEquals(2, titles.size)
        assertNull(feedback.pass())
        assertEquals(2, titles.size)
    }

    @Test
    fun `another press at the same level postpones expiry`() {
        feedback.show(100, false)
        val lifetime = feedback.pass()!!
        now += (lifetime * 500_000_000).toLong()
        feedback.show(100, false)
        now += (lifetime * 500_000_000).toLong() + 1
        assertNotNull(feedback.pass())
        now += (lifetime * 1_000_000_000).toLong()
        assertNull(feedback.pass())
    }

    @Test
    fun `mute feedback replaces pending feedback and restarts expiry`() {
        feedback.show(65, false)
        val lifetime = feedback.pass()!!
        now += (lifetime * 500_000_000).toLong()
        feedback.show(65, true)
        assertEquals(2, titles.size)
        now += (lifetime * 500_000_000).toLong() + 1
        assertNotNull(feedback.pass())
        now += (lifetime * 1_000_000_000).toLong()
        assertNull(feedback.pass())
        assertEquals(3, titles.size)
    }

    @Test
    fun `hidden controls send feedback to the main window and clear it on expiry`() {
        controlsVisible = false
        feedback.show(65, false)
        assertTrue(titles.isEmpty())
        assertNotNull(mainTitles.single())
        now += (feedback.pass()!! * 1_000_000_000).toLong() + 1
        assertNull(feedback.pass())
        assertNull(mainTitles.last())
    }

    @Test
    fun `visibility changes move feedback without extending its lifetime`() {
        feedback.show(65, false)
        val lifetime = feedback.pass()!!
        now += (lifetime * 500_000_000).toLong()
        controlsVisible = false
        assertTrue(feedback.pass()!! < lifetime)
        assertNull(titles.last())
        assertNotNull(mainTitles.last())
        controlsVisible = true
        assertNotNull(feedback.pass())
        assertNull(mainTitles.last())
        assertNotNull(titles.last())
        now += (lifetime * 500_000_000).toLong() + 1
        assertNull(feedback.pass())
        assertNull(titles.last())
    }

    @Test
    fun `new feedback after hiding controls clears the old window`() {
        feedback.show(65, false)
        controlsVisible = false
        feedback.show(65, true)
        assertNull(titles.last())
        assertNotNull(mainTitles.last())
        now += (feedback.pass()!! * 1_000_000_000).toLong() + 1
        assertNull(feedback.pass())
        assertNull(mainTitles.last())
    }
}
