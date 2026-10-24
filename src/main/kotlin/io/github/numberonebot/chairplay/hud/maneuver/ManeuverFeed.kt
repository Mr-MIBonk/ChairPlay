package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.hud.HudManeuver

/**
 * Turns the HUD's maneuver into the renderer's commands, sending only what changed, by the rules of the MHI2Q
 * bridge (BAPBridge.updateRendererIfChanged): a new maneuver is pushed with a transition, new roads for the same one
 * only refresh it, a repeated arrival with the same picture is left alone so its flag keeps waving, and between
 * maneuvers only the progress goes out. The lanes go before the maneuver, as an event of their own, whenever they
 * change (BAPBridge.update): they never restart the arrow. Nothing waits on a clock: a maneuver ends with its arrow
 * all blue, and the next goes out at once, its arrow white from its start. Not thread-safe: the HUD calls it on its
 * GL thread.
 */
class ManeuverFeed {
    private var shownIndex: Int? = null
    private var shown: RendererManeuver? = null
    private var progress: ArrowProgress? = null
    /** The renderer starts without lanes, and a clear removes them. */
    private var shownLanes = RendererLanes.HIDDEN
    private var laneToken = 0

    /** Whether the renderer shows a maneuver; if not, the HUD draws its own arrow. */
    val showing: Boolean get() = shown != null

    /** Whether the renderer draws a row of lanes under the maneuver. */
    val showingLanes: Boolean get() = shownLanes.drawable

    /** The commands that bring the renderer to [maneuver] (null without guidance). */
    fun update(maneuver: HudManeuver?): List<ByteArray> {
        val next = maneuver?.next
        val mapped = next?.let(::rendererManeuver)?.takeIf { it.icon != RendererIcon.NONE }
        if (next == null || mapped == null) {
            if (shown == null) return emptyList()
            shown = null
            shownIndex = null
            progress = null
            shownLanes = RendererLanes.HIDDEN
            return listOf(ManeuverPackets.clear())
        }
        val now = arrowProgress(maneuver.distanceMeters, next.distanceBetweenMeters, next.typeCode)
        val last = shown
        val sameIndex = next.index == shownIndex
        val sameGeometry = mapped == last
        val same = sameGeometry && (sameIndex || mapped.icon == RendererIcon.ARRIVED)
        val roadsOnly = !same && last != null && sameIndex && mapped.copy(junctionAngles = last.junctionAngles) == last
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
}
