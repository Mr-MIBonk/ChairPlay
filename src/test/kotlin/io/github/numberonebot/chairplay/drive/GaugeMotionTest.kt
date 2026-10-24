package io.github.numberonebot.chairplay.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeMotionTest {
    private var nanos = 0L
    private val motion = GaugeMotion { nanos }
    private val view = GaugeView(0.0, 800.0, "D1", 0.0, 0.0, 0.0, GaugeScale.speedometer(), GaugeScale.tachometer(CarSpec()))

    private fun at(seconds: Double) {
        nanos = (seconds * 1e9).toLong()
    }

    // The gauges come in over a moment from nothing, then stay.
    @Test
    fun `the gauges come in, then stay`() {
        assertNull(motion.frame(null))
        assertFalse(motion.animating)
        val first = motion.frame(view)!!
        assertTrue(first.coming)
        assertEquals(0.0, first.progress, 0.0)
        assertTrue(motion.animating)
        at(GaugeMotion.IN_SECONDS / 2)
        assertEquals(0.5, motion.frame(view)!!.progress, 1e-6)
        at(GaugeMotion.IN_SECONDS + 1)
        assertEquals(1.0, motion.frame(view)!!.progress, 0.0)
        // One frame more, for the HUD, which reads the motion a frame late, to draw its end.
        assertTrue(motion.animating)
        motion.frame(view)
        assertFalse(motion.animating)
    }

    // Gone, they leave over a moment, showing the last view, then nothing is drawn.
    @Test
    fun `the gauges leave with their last view, then are gone`() {
        motion.frame(view)
        at(5.0)
        motion.frame(view)
        val leaving = motion.frame(null)!!
        assertFalse(leaving.coming)
        assertSame(view, leaving.view)
        assertEquals(0.0, leaving.progress, 0.0)
        assertTrue(motion.animating)
        at(5.0 + GaugeMotion.OUT_SECONDS / 2)
        assertEquals(0.5, motion.frame(null)!!.progress, 1e-6)
        at(5.0 + GaugeMotion.OUT_SECONDS)
        assertNull(motion.frame(null))
        // One frame more, for the HUD to draw the room the wells leave in full.
        assertTrue(motion.animating)
        assertNull(motion.frame(null))
        assertFalse(motion.animating)
    }

    // How far in the gauges are, for the HUD to make room in step: 0 gone, 1 in place, the way back as the way in.
    @Test
    fun `the gauges say how far in they are`() {
        assertEquals(0f, motion.there, 0f)
        motion.frame(view)
        at(GaugeMotion.IN_SECONDS / 2)
        motion.frame(view)
        assertEquals(0.5f, motion.there, 1e-4f)
        at(5.0)
        motion.frame(view)
        assertEquals(1f, motion.there, 0f)
        motion.frame(null)
        at(5.0 + GaugeMotion.OUT_SECONDS / 4)
        motion.frame(null)
        assertEquals(0.75f, motion.there, 1e-4f)
        at(10.0)
        motion.frame(null)
        assertEquals(0f, motion.there, 0f)
    }

    // Switched between large and small, the gauges scale over a moment; shown at first, they start at their size.
    @Test
    fun `the gauges scale between large and small over a moment`() {
        val small = view.copy(size = GaugeSize.SMALL)
        motion.frame(small)
        assertEquals(1f, motion.smallness, 0f)
        at(5.0)
        motion.frame(view)
        assertEquals(1f, motion.smallness, 0f)
        assertTrue(motion.animating)
        at(5.0 + GaugeMotion.SIZE_SECONDS / 2)
        motion.frame(view)
        assertEquals(0.5f, motion.smallness, 1e-4f)
        at(6.0 + GaugeMotion.SIZE_SECONDS)
        motion.frame(view)
        assertEquals(0f, motion.smallness, 0f)
        assertTrue(motion.animating)
        motion.frame(view)
        assertFalse(motion.animating)
    }

    // Turned back on as they leave, they come back from as far as they had gone, not from nothing.
    @Test
    fun `turned back on as they leave, they come back from where they are`() {
        motion.frame(view)
        at(5.0)
        motion.frame(view)
        motion.frame(null)
        at(5.0 + GaugeMotion.OUT_SECONDS / 4)
        motion.frame(null)
        val back = motion.frame(view)!!
        assertTrue(back.coming)
        assertEquals(0.75, back.progress, 1e-6)
    }
}
