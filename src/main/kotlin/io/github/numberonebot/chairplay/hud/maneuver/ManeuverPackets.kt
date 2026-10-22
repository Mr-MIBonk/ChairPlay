package io.github.numberonebot.chairplay.hud.maneuver

/**
 * The maneuver renderer's 48-byte commands (native/maneuver/protocol.h), laid out as the MHI2Q project's
 * RendererServer writes them: the command, its flags, then a 46-byte payload, integers big-endian.
 */
object ManeuverPackets {
    const val SIZE = 48

    private const val CMD_MANEUVER = 0x01
    private const val CMD_PROGRESS = 0x06
    private const val CMD_CLEAR = 0x07
    private const val CMD_LANES_BEGIN = 0x0C
    private const val CMD_LANES_LANE = 0x0D
    private const val CMD_LANES_COMMIT = 0x0E

    private const val FLAG_SET_PERSPECTIVE = 0x01
    private const val FLAG_PROGRESS = 0x02
    private const val FLAG_HALF_DEGREES = 0x04
    private const val FLAG_REFRESH = 0x08
    private const val FLAG_SNAP_TO_ROAD = 0x10
    /** The explicit progress state (payload 42 of a maneuver, 2 of a progress command). */
    private const val FLAG_PROGRESS_STATE = 0x20

    /** The payload starts after the command and its flags. */
    private const val P = 2

    /**
     * A new maneuver with its progress, in 3D like the MHI2Q bridge. [refresh] replaces the shown maneuver's
     * roads without a transition.
     */
    fun maneuver(maneuver: RendererManeuver, progress: ArrowProgress, refresh: Boolean): ByteArray {
        val packet = ByteArray(SIZE)
        packet[0] = CMD_MANEUVER.toByte()
        var flags = FLAG_SET_PERSPECTIVE or FLAG_PROGRESS or FLAG_HALF_DEGREES or FLAG_PROGRESS_STATE
        if (refresh) flags = flags or FLAG_REFRESH
        if (maneuver.snapToRoad) flags = flags or FLAG_SNAP_TO_ROAD
        packet[1] = flags.toByte()
        packet[P] = maneuver.icon.toByte()
        packet[P + 1] = maneuver.direction.toByte()
        short(packet, P + 2, maneuver.exitAngle)
        packet[P + 4] = maneuver.drivingSide.toByte()
        val roads = maneuver.junctionAngles.take(RendererManeuver.MAX_ROADS)
        packet[P + 5] = roads.size.toByte()
        roads.forEachIndexed { i, angle -> short(packet, P + 6 + i * 2, angle) }
        packet[P + 42] = progress.state.toByte()
        packet[P + 43] = 1 // perspective: 3D
        packet[P + 44] = progress.level.toByte()
        packet[P + 45] = mode(progress).toByte()
        return packet
    }

    /** The shown maneuver's progress. */
    fun progress(progress: ArrowProgress): ByteArray {
        val packet = ByteArray(SIZE)
        packet[0] = CMD_PROGRESS.toByte()
        packet[1] = FLAG_PROGRESS_STATE.toByte()
        packet[P] = progress.level.toByte()
        packet[P + 1] = mode(progress).toByte()
        packet[P + 2] = progress.state.toByte()
        return packet
    }

    /** Fades the maneuver out: guidance has ended. It clears the lanes too. */
    fun clear(): ByteArray = ByteArray(SIZE).also { it[0] = CMD_CLEAR.toByte() }

    /**
     * The lane event [lanes] as one batch (RendererServer.lanePacket): a begin, one packet per lane, a commit, all
     * with [token], which tells this batch from any other. The renderer takes the lanes only at the commit, and only
     * whole: a batch with a lane missing shows none.
     */
    fun lanes(lanes: RendererLanes, token: Int): List<ByteArray> {
        val begin = ByteArray(SIZE)
        begin[0] = CMD_LANES_BEGIN.toByte()
        int(begin, P, token)
        begin[P + 4] = lanes.lanes.size.toByte()
        begin[P + 5] = (if (lanes.complete) 1 else 0).toByte()
        begin[P + 6] = (if (lanes.showing) 1 else 0).toByte()
        int(begin, P + 8, lanes.eventIndex)
        val records = lanes.lanes.mapIndexed { i, lane ->
            val packet = ByteArray(SIZE)
            packet[0] = CMD_LANES_LANE.toByte()
            int(packet, P, token)
            packet[P + 4] = i.toByte()
            short(packet, P + 5, lane.position)
            packet[P + 7] = lane.status.toByte()
            packet[P + 8] = lane.angles.size.toByte()
            short(packet, P + 9, lane.primary)
            lane.angles.forEachIndexed { j, angle -> short(packet, P + 11 + j * 2, angle) }
            packet
        }
        val commit = ByteArray(SIZE)
        commit[0] = CMD_LANES_COMMIT.toByte()
        int(commit, P, token)
        return listOf(begin) + records + listOf(commit)
    }

    /** The legacy mode byte the renderer checks against the state: 1 while progress shows. */
    private fun mode(progress: ArrowProgress) = if (progress.state == ArrowProgress.QUIET) 0 else 1

    private fun short(packet: ByteArray, at: Int, value: Int) {
        packet[at] = (value shr 8).toByte()
        packet[at + 1] = value.toByte()
    }

    private fun int(packet: ByteArray, at: Int, value: Int) {
        short(packet, at, value shr 16)
        short(packet, at + 2, value)
    }
}
