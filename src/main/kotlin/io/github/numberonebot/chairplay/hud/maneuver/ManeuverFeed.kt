package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.hud.HudManeuver

/**
 * Turns the HUD's maneuver into the renderer's commands, sending only what changed, by the rules of the MHI2Q
 * bridge (BAPBridge.updateRendererIfChanged): a new maneuver is pushed with a transition, new roads for the same one
 * only refresh it, a repeated arrival with the same picture is left alone so its flag keeps waving, and between
 * maneuvers only the progress goes out. The lanes go before the maneuver, as an event of their own, whenever they
 * change (BAPBridge.update): they never restart the arrow. Not thread-safe: the HUD calls it on its GL thread.
 *
 * Unlike the bridge, the next maneuver waits for the shown arrow to be white, so its blue never cuts off: a blink
 * in its lit phase goes white on its own clock, a partly filled arrow is turned white, and the transition follows
 * once the renderer has eased to it ([WHITE_SETTLE_MILLIS]). The next maneuver's lanes wait with it.
 */
class ManeuverFeed {
    private var shownIndex: Int? = null
    private var shown: RendererManeuver? = null
    private var progress: ArrowProgress? = null
    /** While the next maneuver waits: when the blink goes white, and when the transition may start. */
    private var whiteAt: Long? = null
    private var readyAt: Long? = null
    /** The renderer starts without lanes, and a clear removes them. */
    private var shownLanes = RendererLanes.HIDDEN
    private var laneToken = 0

    /** Whether the renderer shows a maneuver; if not, the HUD draws its own arrow. */
    val showing: Boolean get() = shown != null

    /** Whether the renderer draws a row of lanes under the maneuver. */
    val showingLanes: Boolean get() = shownLanes.drawable

    /** The commands that bring the renderer to [maneuver] (null without guidance) at [nowMillis]. */
    fun update(maneuver: HudManeuver?, nowMillis: Long): List<ByteArray> {
        val next = maneuver?.next
        val mapped = next?.let(::rendererManeuver)?.takeIf { it.icon != RendererIcon.NONE }
        if (next == null || mapped == null) {
            whiteAt = null
            readyAt = null
            if (shown == null) return emptyList()
            shown = null
            shownIndex = null
            progress = null
            shownLanes = RendererLanes.HIDDEN
            return listOf(ManeuverPackets.clear())
        }
        val now = arrowProgress(maneuver.distanceMeters, next.distanceBetweenMeters, next.typeCode, nowMillis)
        val last = shown
        val sameIndex = next.index == shownIndex
        val sameGeometry = mapped == last
        val same = sameGeometry && (sameIndex || mapped.icon == RendererIcon.ARRIVED)
        val roadsOnly = !same && last != null && sameIndex && mapped.copy(junctionAngles = last.junctionAngles) == last
        if (!same && !roadsOnly && last != null) {
            handover(nowMillis)?.let { return it }
        } else {
            whiteAt = null
            readyAt = null
        }
        val packets = ArrayList<ByteArray>()
        val lanes = rendererLanes(maneuver.lanes)
        if (lanes != shownLanes) {
            // RendererServer.sendLaneGuidance: a fresh token for each batch, never 0.
            if (++laneToken == 0) ++laneToken
            packets += ManeuverPackets.lanes(lanes, laneToken)
            shownLanes = lanes
        }
        if (same) {
            if (now != progress) packets += ManeuverPackets.progress(now)
        } else {
            packets += ManeuverPackets.maneuver(mapped, now, refresh = roadsOnly)
        }
        shown = mapped
        shownIndex = next.index
        progress = now
        return packets
    }

    /**
     * Brings the shown arrow to white before the next maneuver: the commands meanwhile while the next one must wait,
     * or null once it may go.
     */
    private fun handover(nowMillis: Long): List<ByteArray>? {
        val packets = ArrayList<ByteArray>()
        if (whiteAt == null && readyAt == null) {
            val shownProgress = progress ?: return null
            when {
                // Lit: the blink's next phase, on its own clock, is the white one.
                shownProgress.state == ArrowProgress.BLINK_HIGH ->
                    whiteAt = (Math.floorDiv(nowMillis, ArrowProgress.BLINK_MILLIS) + 1) * ArrowProgress.BLINK_MILLIS
                // Partly blue and not blinking: no white phase is coming, so the arrow is turned white.
                shownProgress.state == ArrowProgress.FILL && shownProgress.level < 16 -> {
                    packets += ManeuverPackets.progress(ArrowProgress.OFF)
                    progress = ArrowProgress.OFF
                    readyAt = nowMillis + WHITE_SETTLE_MILLIS
                }
                else -> return null
            }
        }
        whiteAt?.let { at ->
            if (nowMillis < at) return packets
            packets += ManeuverPackets.progress(WHITE_BLINK)
            progress = WHITE_BLINK
            whiteAt = null
            readyAt = at + WHITE_SETTLE_MILLIS
        }
        readyAt?.let { if (nowMillis < it) return packets }
        readyAt = null
        return null
    }

    /** When the commands change next without new data (a blinking arrow, a waiting maneuver), or null. */
    fun nextChange(maneuver: HudManeuver?, nowMillis: Long): Long? {
        (whiteAt ?: readyAt)?.let { return it }
        val next = maneuver?.next ?: return null
        return nextProgressChange(maneuver.distanceMeters, next.distanceBetweenMeters, next.typeCode, nowMillis)
    }

    companion object {
        /** How long the renderer eases the arrow's tint to a new phase (arrow_progress.h, cr_progress_tint_target). */
        const val WHITE_SETTLE_MILLIS = 150L
        private val WHITE_BLINK = ArrowProgress(0, ArrowProgress.BLINK_LOW)
    }
}
