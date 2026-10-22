package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.Lane
import io.github.numberonebot.chairplay.data.LaneGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererLanesTest {
    private fun lane(position: Int? = 0, status: Int? = 1, angles: List<Int> = listOf(0), highlight: Int? = null) =
        Lane(position, status, angles, highlight)

    @Test
    fun `no lanes to show is the hidden event`() {
        assertEquals(RendererLanes.HIDDEN, rendererLanes(null))
        assertFalse(RendererLanes.HIDDEN.showing)
        assertEquals(-1, RendererLanes.HIDDEN.eventIndex)
    }

    @Test
    fun `lanes are copied as sent, the highlighted angle first`() {
        val lanes = rendererLanes(
            LaneGuidance(
                7,
                listOf(lane(0, 2, listOf(-90, 0), highlight = -90), lane(1, 1, listOf(0, 90)), lane(2, 0, emptyList())),
            ),
        )
        assertEquals(7, lanes.eventIndex)
        assertTrue(lanes.showing)
        assertTrue(lanes.complete)
        assertEquals(
            listOf(
                RendererLane(0, 2, -90, listOf(-90, 0)),
                RendererLane(1, 1, 0, listOf(0, 90)),
                // No direction at all: unknown, never a guess.
                RendererLane(2, 0, 1000, emptyList()),
            ),
            lanes.lanes,
        )
    }

    // LaneGuidanceSnapshot.copy: an unknown stays an explicit unknown and the event is no longer complete.
    @Test
    fun `missing values become unknowns and leave the event incomplete`() {
        val noPosition = rendererLanes(LaneGuidance(1, listOf(lane(position = null))))
        assertEquals(65535, noPosition.lanes.single().position)
        assertFalse(noPosition.complete)

        val noStatus = rendererLanes(LaneGuidance(1, listOf(lane(status = null))))
        assertEquals(255, noStatus.lanes.single().status)
        assertFalse(noStatus.complete)

        assertEquals(-1, rendererLanes(LaneGuidance(null, listOf(lane()))).eventIndex)
    }

    // lane_panel.cpp draws a row only when some lane has a direction it can draw.
    @Test
    fun `lanes draw when one of them has a direction`() {
        assertTrue(rendererLanes(LaneGuidance(1, listOf(lane(angles = emptyList()), lane(angles = listOf(-45))))).drawable)
        assertFalse(RendererLanes.HIDDEN.drawable)
        assertFalse(rendererLanes(LaneGuidance(1, emptyList())).drawable)
        assertFalse(rendererLanes(LaneGuidance(1, listOf(lane(angles = emptyList()), lane(angles = listOf(1000))))).drawable)
    }

    @Test
    fun `at most 8 lanes of 16 angles each go to the renderer`() {
        val many = rendererLanes(LaneGuidance(1, List(10) { lane(position = it) }))
        assertEquals(8, many.lanes.size)
        assertFalse(many.complete)

        val angles = rendererLanes(LaneGuidance(1, listOf(lane(angles = List(20) { it }))))
        assertEquals(16, angles.lanes.single().angles.size)
        assertFalse(angles.complete)
    }
}
