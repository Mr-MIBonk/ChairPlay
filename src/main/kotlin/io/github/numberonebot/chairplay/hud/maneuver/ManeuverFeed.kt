package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.hud.HudManeuver

/**
 * Turns the HUD's maneuver into the renderer's commands, sending only what changed, by the rules of the MHI2Q
 * bridge (BAPBridge.updateRendererIfChanged): a new maneuver is pushed with a transition, new roads for the same one
 * only refresh it, a repeated arrival with the same picture is left alone so its flag keeps waving, and between
 * maneuvers only the progress goes out. Not thread-safe: the HUD calls it on its GL thread.
 */
class ManeuverFeed {
    private var shownIndex: Int? = null
    private var shown: RendererManeuver? = null
    private var progress: ArrowProgress? = null

    /** Whether the renderer shows a maneuver; if not, the HUD draws its own arrow. */
    val showing: Boolean get() = shown != null

    /** The commands that bring the renderer to [maneuver] (null without guidance) at [nowMillis]. */
    fun update(maneuver: HudManeuver?, nowMillis: Long): List<ByteArray> {
        val next = maneuver?.next
        val mapped = next?.let(::rendererManeuver)?.takeIf { it.icon != RendererIcon.NONE }
        if (next == null || mapped == null) {
            if (shown == null) return emptyList()
            shown = null
            shownIndex = null
            progress = null
            return listOf(ManeuverPackets.clear())
        }
        val now = arrowProgress(maneuver.distanceMeters, next.distanceBetweenMeters, next.typeCode, nowMillis)
        val last = shown
        val sameIndex = next.index == shownIndex
        val sameGeometry = mapped == last
        val packets = when {
            sameGeometry && (sameIndex || mapped.icon == RendererIcon.ARRIVED) ->
                if (now != progress) listOf(ManeuverPackets.progress(now)) else emptyList()
            else -> {
                val roadsOnly = last != null && sameIndex && mapped.copy(junctionAngles = last.junctionAngles) == last
                listOf(ManeuverPackets.maneuver(mapped, now, refresh = roadsOnly))
            }
        }
        shown = mapped
        shownIndex = next.index
        progress = now
        return packets
    }

    /** When the progress of [maneuver] changes next without new data (a blinking arrow), or null. */
    fun nextChange(maneuver: HudManeuver?, nowMillis: Long): Long? {
        val next = maneuver?.next ?: return null
        return nextProgressChange(maneuver.distanceMeters, next.distanceBetweenMeters, next.typeCode, nowMillis)
    }
}
