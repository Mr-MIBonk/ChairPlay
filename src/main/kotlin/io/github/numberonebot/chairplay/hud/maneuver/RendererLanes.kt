package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.LaneGuidance

/** One lane as the renderer takes it (protocol.h, CMD_LANES_LANE): angles in plain signed degrees. */
data class RendererLane(
    /** Where the lane is across the road; [RendererLanes.UNKNOWN_POSITION] when not sent. */
    val position: Int,
    /** Apple's lane status, 2 for the recommended lanes; [RendererLanes.UNKNOWN_STATUS] when not sent. */
    val status: Int,
    /** The way the route takes from the lane; [RendererLanes.UNKNOWN_ANGLE] when there is none. */
    val primary: Int,
    /** Every way the lane leads, at most [RendererLanes.MAX_ANGLES]. */
    val angles: List<Int>,
)

/**
 * One lane event as the renderer takes it (protocol.h, CMD_LANES_BEGIN to CMD_LANES_COMMIT): the MHI2Q project's
 * LaneGuidanceSnapshot. The renderer draws it as a row of lane arrows under the maneuver (lane_panel.cpp), on its own:
 * lanes never change the maneuver, nor the maneuver the lanes.
 */
data class RendererLanes(
    val eventIndex: Int,
    val showing: Boolean,
    /** Every lane came whole and fitted: nothing was left out or made an unknown. */
    val complete: Boolean,
    val lanes: List<RendererLane>,
) {
    /** Whether the renderer draws a row for these lanes: as lane_panel.cpp, when some lane has a way within ±180°. */
    val drawable: Boolean
        get() = showing && lanes.any { lane -> (lane.angles + lane.primary).any { it in -180..180 } }

    companion object {
        const val MAX_LANES = 8
        const val MAX_ANGLES = 16
        const val UNKNOWN_POSITION = 65535
        const val UNKNOWN_STATUS = 255
        const val UNKNOWN_ANGLE = 1000

        /** No lanes shown. */
        val HIDDEN = RendererLanes(-1, showing = false, complete = false, lanes = emptyList())
    }
}

/**
 * What the renderer draws for the lane event [guidance] ([RendererLanes.HIDDEN] for none): a copy, as the MHI2Q project's
 * LaneGuidanceSnapshot.copy makes it (java_patch/com/luka/carplay/rgd/), that picks no lane, sorts nothing and guesses
 * no direction. Each lane's primary angle is the one its parser takes (hook/routeguidance/rgd_tlv.c): the highlighted
 * angle, else the first. Up to [RendererLanes.MAX_LANES] lanes of [RendererLanes.MAX_ANGLES] angles go; what is cut or
 * missing becomes an explicit unknown and leaves the event incomplete.
 */
fun rendererLanes(guidance: LaneGuidance?): RendererLanes {
    guidance ?: return RendererLanes.HIDDEN
    var complete = guidance.lanes.size <= RendererLanes.MAX_LANES
    val lanes = guidance.lanes.take(RendererLanes.MAX_LANES).map { lane ->
        if (lane.position == null || lane.statusCode == null || lane.angles.size > RendererLanes.MAX_ANGLES) complete = false
        RendererLane(
            position = lane.position?.takeIf { it in 0..65535 } ?: RendererLanes.UNKNOWN_POSITION,
            status = lane.statusCode?.takeIf { it in 0..255 } ?: RendererLanes.UNKNOWN_STATUS,
            primary = lane.direction?.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE } ?: RendererLanes.UNKNOWN_ANGLE,
            angles = lane.angles.take(RendererLanes.MAX_ANGLES),
        )
    }
    return RendererLanes(guidance.index ?: -1, showing = true, complete = complete, lanes = lanes)
}
