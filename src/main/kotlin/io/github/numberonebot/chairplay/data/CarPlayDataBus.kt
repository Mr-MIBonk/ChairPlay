package io.github.numberonebot.chairplay.data

import com.shilapi.xcertplay.hud.BydHudRouteState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.media.CarPlayPlaybackStatus
import com.shilapi.xcertplay.transport.Iap2ArtworkTransfer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the iPhone tells the car besides the picture, as typed events and a snapshot: route guidance, lanes,
 * now playing and its artwork, calls, the phone's network and its battery. Fed with every iAP2 data frame
 * and artwork transfer of the session, from any thread; listeners hear each change on the thread that made
 * it, after the snapshot holds it.
 *
 * Upstream's parsers decide what they already know: BydHudRouteState which maneuver is next (its rules for
 * the iPhone's empty lists and stale routes come from cars), CarPlayPlaybackStatus the song. The rest of
 * each message comes from [CarPlayDataMessages].
 */
class CarPlayDataBus(nanoTime: () -> Long = System::nanoTime) {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(CarPlayDataEvent) -> Unit>()

    private val nextManeuverRules = BydHudRouteState(nanoTime)
    private var routeValues: RouteGuidance? = null
    private val maneuvers = HashMap<Int, Maneuver>()
    private var activeIndex: Int? = null
    private var route: RouteGuidance? = null

    private val playback = CarPlayPlaybackStatus()
    private var nowPlayingValues = NowPlaying()
    private var nowPlaying: NowPlaying? = null

    /** The lane events the iPhone has sent ahead, by their index, oldest first. */
    private val laneEvents = LinkedHashMap<Int, LaneGuidance>()
    private var lanes: LaneGuidance? = null
    private var artwork: Artwork? = null
    private val calls = LinkedHashMap<String, Call>()
    private var communications: Communications? = null
    private var power: Power? = null

    /** [listener] hears every change from now on, until the returned handle is closed. */
    fun addListener(listener: (CarPlayDataEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** The latest state; it also ends a next maneuver whose time ran out, which listeners then hear. */
    fun snapshot(): CarPlayDataSnapshot {
        val (event, current) = synchronized(lock) { publishRoute() to snapshotLocked() }
        dispatch(listOfNotNull(event))
        return current
    }

    fun accept(frame: Iap2Frame) {
        val events = synchronized(lock) { acceptLocked(frame) }
        dispatch(events)
    }

    fun acceptArtwork(transfer: Iap2ArtworkTransfer) {
        val event = synchronized(lock) {
            val next = Artwork(transfer.id, transfer.bytes)
            if (next == artwork) return
            artwork = next
            CarPlayDataEvent.ArtworkChanged(next)
        }
        dispatch(listOf(event))
    }

    /** The CarPlay session ended: forget everything. */
    fun clear() {
        val wasEmpty = synchronized(lock) {
            val empty = snapshotLocked() == CarPlayDataSnapshot()
            nextManeuverRules.clear()
            routeValues = null
            maneuvers.clear()
            activeIndex = null
            route = null
            playback.clearAll()
            nowPlayingValues = NowPlaying()
            nowPlaying = null
            laneEvents.clear()
            lanes = null
            artwork = null
            calls.clear()
            communications = null
            power = null
            empty
        }
        if (!wasEmpty) dispatch(listOf(CarPlayDataEvent.Cleared))
    }

    private fun acceptLocked(frame: Iap2Frame): List<CarPlayDataEvent> {
        val id = frame.messageId
        if (id == ROUTE_GUIDANCE_UPDATE || id == ROUTE_GUIDANCE_MANEUVER_UPDATE) nextManeuverRules.accept(id, frame.payload)
        if (id == CarPlayPlaybackStatus.NOW_PLAYING_UPDATE) playback.acceptUpdate(frame)
        val fields = CarPlayDataMessages.decode(frame) ?: return emptyList()
        val event = when (id) {
            ROUTE_GUIDANCE_UPDATE -> {
                val state = fields.coded<RouteGuidanceState>("routeGuidanceState", null)
                if (state == RouteGuidanceState.NO_ROUTE_SET || state == RouteGuidanceState.ARRIVED) {
                    maneuvers.clear()
                    activeIndex = null
                    routeValues = RouteGuidance().merged(fields)
                } else {
                    val values = (routeValues ?: RouteGuidance()).merged(fields)
                    values.currentManeuverIndexes.firstOrNull()?.let { activeIndex = it }
                    routeValues = values
                }
                // The MHI2Q project's lane cache (hook/routeguidance/rgd_hook.c): a route reset forgets the lane events,
                // and the iPhone never needs one below the current index again.
                if (state == RouteGuidanceState.NO_ROUTE_SET) laneEvents.clear()
                routeValues?.laneGuidanceIndex?.takeIf { fields.has("laneGuidanceIndex") }?.let { current ->
                    laneEvents.keys.removeAll { it < current }
                }
                return listOfNotNull(publishRoute(), publishLanes())
            }
            ROUTE_GUIDANCE_MANEUVER_UPDATE -> {
                val index = fields.int("index", null) ?: return emptyList()
                maneuvers[index] = (maneuvers[index] ?: Maneuver(index)).merged(fields)
                publishRoute()
            }
            LANE_GUIDANCE_INFO_UPDATE -> {
                val event = laneGuidance(fields)
                val index = event.index ?: return emptyList()
                // Full: the oldest event goes, never the current one.
                if (index !in laneEvents && laneEvents.size >= MAX_LANE_EVENTS) {
                    val current = routeValues?.laneGuidanceIndex
                    laneEvents.keys.firstOrNull { it != current }?.let { laneEvents.remove(it) }
                }
                laneEvents[index] = event
                publishLanes()
            }
            CarPlayPlaybackStatus.NOW_PLAYING_UPDATE -> {
                nowPlayingValues = nowPlayingValues.merged(fields)
                val song = playback.nowPlaying
                val next = nowPlayingValues.copy(
                    title = song.title, artist = song.artist, album = song.album, durationMillis = song.durationMillis,
                    elapsedMillis = song.elapsedMillis, playing = song.playing, appName = song.sourceApp,
                    artworkTransferId = song.artworkTransferId,
                )
                next.takeIf { it != nowPlaying }?.let {
                    nowPlaying = it
                    CarPlayDataEvent.NowPlayingChanged(it)
                }
            }
            CALL_STATE_UPDATE -> acceptCall(fields)
            COMMUNICATIONS_UPDATE -> (communications ?: Communications()).merged(fields).takeIf { it != communications }?.let {
                communications = it
                CarPlayDataEvent.CommunicationsChanged(it)
            }
            POWER_UPDATE -> (power ?: Power()).merged(fields).takeIf { it != power }?.let {
                power = it
                CarPlayDataEvent.PowerChanged(it)
            }
            else -> null
        }
        return listOfNotNull(event)
    }

    /** The route with its maneuvers, when it differs from the one last published. */
    private fun publishRoute(): CarPlayDataEvent? {
        val values = routeValues ?: return null
        val showing = nextManeuverRules.currentApple() != null
        val next = values.copy(
            nextManeuver = if (showing) activeIndex?.let { maneuvers[it] } else null,
            currentManeuvers = values.currentManeuverIndexes.mapNotNull { maneuvers[it] },
        )
        if (next == route) return null
        route = next
        return CarPlayDataEvent.RouteChanged(next)
    }

    /** The current lane event, when it differs from the one last published. */
    private fun publishLanes(): CarPlayDataEvent? {
        val next = routeValues?.laneGuidanceIndex?.let { laneEvents[it] }
        if (next == lanes) return null
        lanes = next
        return CarPlayDataEvent.LanesChanged(next)
    }

    /** Several calls can exist at once (call waiting); each is known by its UUID, or its number without one. */
    private fun acceptCall(fields: DecodedFields): CarPlayDataEvent? {
        val id = fields.string("callUuid", null) ?: fields.string("remoteId", null) ?: ""
        val previous = calls[id]
        val call = (previous ?: Call(id)).merged(fields)
        val ended = call.status == CallStatus.DISCONNECTED || call.status == CallStatus.DISCONNECTING
        if (ended) {
            calls.remove(id) ?: return null
        } else {
            if (call == previous) return null
            calls[id] = call
        }
        return CarPlayDataEvent.CallChanged(call, calls.values.toList())
    }

    private fun snapshotLocked() = CarPlayDataSnapshot(
        route = route,
        lanes = lanes,
        nowPlaying = nowPlaying,
        artwork = artwork,
        calls = calls.values.toList(),
        communications = communications,
        power = power,
    )

    /** A listener's failure must not reach the iAP2 control loop that delivered the frame. */
    private fun dispatch(events: List<CarPlayDataEvent>) {
        for (event in events) for (listener in listeners) runCatching { listener(event) }
    }

    private companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
        const val LANE_GUIDANCE_INFO_UPDATE = 0x5204
        /** As many lane events as the MHI2Q project keeps (MANEUVER_CACHE_SIZE); the iPhone sends a few at a time. */
        const val MAX_LANE_EVENTS = 32
        const val CALL_STATE_UPDATE = 0x4155
        const val COMMUNICATIONS_UPDATE = 0x4158
        const val POWER_UPDATE = 0xae01
    }
}
