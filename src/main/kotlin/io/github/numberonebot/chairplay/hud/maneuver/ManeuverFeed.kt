package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.hud.HudManeuver

/**
 * Turns the HUD's maneuver into the renderer's commands, sending only what changed, by the rules of the MHI2Q
 * bridge (BAPBridge.updateRendererIfChanged): a new maneuver is pushed with a transition, new roads for the same one
 * only refresh it, a repeated arrival with the same picture is left alone so its flag keeps waving, and between
 * maneuvers only the progress goes out. The lanes go before the maneuver, as an event of their own, whenever they
 * change (BAPBridge.update): they never restart the arrow. Not thread-safe: the HUD calls it on its GL thread.
 *
 * Unlike the bridge, nothing blinks, and the next maneuver waits for the shown arrow to be white, so its blue never
 * cuts off: a filled arrow, part or all of it blue, is turned white, and the transition follows once the renderer has
 * eased to it ([WHITE_SETTLE_MILLIS]). The next maneuver's lanes wait with it. The destination's flag never turns white:
 * reached, its arrow stays all blue, and what follows it comes at once.
 */
class ManeuverFeed {
    private var shownIndex: Int? = null
    private var shown: RendererManeuver? = null
    private var progress: ArrowProgress? = null
    /** While the next maneuver waits for the white: when its transition may start. */
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
            readyAt = null
            if (shown == null) return emptyList()
            shown = null
            shownIndex = null
            progress = null
            shownLanes = RendererLanes.HIDDEN
            return listOf(ManeuverPackets.clear())
        }
        val arrival = mapped.icon == RendererIcon.ARRIVED
        val reached = arrival && (maneuver.distanceMeters ?: 1) <= 0
        val now = if (reached) FULL else arrowProgress(maneuver.distanceMeters, next.distanceBetweenMeters, next.typeCode)
        val last = shown
        val sameIndex = next.index == shownIndex
        val sameGeometry = mapped == last
        val same = sameGeometry && (sameIndex || mapped.icon == RendererIcon.ARRIVED)
        val roadsOnly = !same && last != null && sameIndex && mapped.copy(junctionAngles = last.junctionAngles) == last
        if (!same && !roadsOnly && last != null && last.icon != RendererIcon.ARRIVED) {
            whitened(nowMillis)?.let { return it }
        } else {
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
    private fun whitened(nowMillis: Long): List<ByteArray>? {
        if (readyAt == null) {
            val shownProgress = progress ?: return null
            // White already: nothing to wait for.
            if (shownProgress.state != ArrowProgress.FILL || shownProgress.level >= 16) return null
            progress = ArrowProgress.OFF
            readyAt = nowMillis + WHITE_SETTLE_MILLIS
            return listOf(ManeuverPackets.progress(ArrowProgress.OFF))
        }
        if (nowMillis < readyAt!!) return emptyList()
        readyAt = null
        return null
    }

    /** When the commands change next without new data (a maneuver waiting on the white), or null. */
    fun nextChange(): Long? = readyAt

    companion object {
        /** How long the renderer eases the arrow's tint to a new phase (arrow_progress.h, cr_progress_tint_target). */
        const val WHITE_SETTLE_MILLIS = 150L

        /** The arrow all blue: the destination's, once reached. */
        private val FULL = ArrowProgress(0, ArrowProgress.FILL)
    }
}
