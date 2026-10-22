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
                publishRoute()
            }
            ROUTE_GUIDANCE_MANEUVER_UPDATE -> {
                val index = fields.int("index", null) ?: return emptyList()
                maneuvers[index] = (maneuvers[index] ?: Maneuver(index)).merged(fields)
                publishRoute()
            }
            LANE_GUIDANCE_INFO_UPDATE -> laneGuidance(fields).takeIf { it != lanes }?.let {
                lanes = it
                CarPlayDataEvent.LanesChanged(it)
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
        const val CALL_STATE_UPDATE = 0x4155
        const val COMMUNICATIONS_UPDATE = 0x4158
        const val POWER_UPDATE = 0xae01
    }
}

// Updates carry only what changed: an absent parameter keeps the previous value, a present empty string clears it.

private fun DecodedFields.string(name: String, previous: String?): String? =
    if (has(name)) (values[name] as String).trim().ifEmpty { null } else previous

private fun DecodedFields.long(name: String, previous: Long?): Long? =
    if (has(name)) (values[name] as Number).toLong() else previous

private fun DecodedFields.int(name: String, previous: Int?): Int? =
    if (has(name)) (values[name] as Number).toInt() else previous

private fun DecodedFields.bool(name: String, previous: Boolean?): Boolean? =
    if (has(name)) values[name] as Boolean else previous

@Suppress("UNCHECKED_CAST")
private fun DecodedFields.ints(name: String, previous: List<Int>): List<Int> =
    if (has(name)) values[name] as List<Int> else previous

private inline fun <reified E> DecodedFields.coded(name: String, previous: E?): E? where E : Enum<E>, E : Iap2Coded =
    if (has(name)) codeOf<E>((values[name] as CodedValue).code) else previous

private fun DecodedFields.group(name: String): DecodedFields? = values[name] as? DecodedFields

private fun RouteGuidance.merged(fields: DecodedFields) = copy(
    state = fields.coded("routeGuidanceState", state),
    maneuverState = fields.coded("maneuverState", maneuverState),
    currentRoad = fields.string("currentRoadName", currentRoad),
    destination = fields.string("destinationName", destination),
    arrivalEpochSeconds = fields.long("estimatedTimeOfArrival", arrivalEpochSeconds),
    remainingSeconds = fields.long("timeRemainingToDestination", remainingSeconds),
    remainingMeters = fields.long("distanceRemaining", remainingMeters),
    remainingText = fields.string("distanceRemainingDisplayStr", remainingText),
    remainingUnits = fields.coded("distanceRemainingDisplayUnits", remainingUnits),
    distanceToManeuverMeters = fields.long("distanceToNextManeuver", distanceToManeuverMeters),
    distanceToManeuverText = fields.string("distanceToNextManeuverDisplayStr", distanceToManeuverText),
    distanceToManeuverUnits = fields.coded("distanceToNextManeuverDisplayUnits", distanceToManeuverUnits),
    currentManeuverIndexes = fields.ints("currentManeuverList", currentManeuverIndexes),
    maneuverCount = fields.int("maneuverCount", maneuverCount),
    visibleInApp = fields.bool("visibleInApp", visibleInApp),
    laneGuidanceIndex = fields.int("laneGuidanceIndex", laneGuidanceIndex),
    laneGuidanceTotal = fields.int("laneGuidanceTotalCount", laneGuidanceTotal),
    laneGuidanceShowing = fields.bool("laneGuidanceShowing", laneGuidanceShowing),
    sourceName = fields.string("sourceName", sourceName),
)

private fun Maneuver.merged(fields: DecodedFields) = copy(
    typeCode = if (fields.has("maneuverType")) (fields.values["maneuverType"] as CodedValue).code else typeCode,
    description = fields.string("maneuverDescription", description),
    afterRoad = fields.string("afterManeuverRoadName", afterRoad),
    distanceBetweenMeters = fields.long("distanceBetweenManeuver", distanceBetweenMeters),
    distanceBetweenText = fields.string("distanceBetweenManeuverDisplayStr", distanceBetweenText),
    distanceBetweenUnits = fields.coded("distanceBetweenManeuverDisplayUnits", distanceBetweenUnits),
    drivingSide = fields.coded("drivingSide", drivingSide),
    junctionType = fields.coded("junctionType", junctionType),
    junctionAngles = fields.ints("junctionElementAngles", junctionAngles),
    exitAngle = fields.int("junctionElementExitAngle", exitAngle),
)

/** A lane update describes all its lanes. */
private fun laneGuidance(fields: DecodedFields) = LaneGuidance(
    index = fields.int("laneGuidanceIndex", null),
    lanes = (fields.values["laneInformation"] as? List<*>).orEmpty().filterIsInstance<DecodedFields>().map {
        Lane(it.int("position", null), it.int("direction", null), it.coded("status", null))
    },
    description = fields.string("laneGuidanceDescription", null),
)

/** What upstream's CarPlayPlaybackStatus does not read; the bus takes the rest from it. */
private fun NowPlaying.merged(fields: DecodedFields): NowPlaying {
    val item = fields.group("mediaItem")
    val playback = fields.group("playback")
    var next = this
    if (item != null) {
        next = next.copy(
            albumArtist = item.string("albumArtist", albumArtist),
            genre = item.string("genre", genre),
            composer = item.string("composer", composer),
            trackNumber = item.int("albumTrackNumber", trackNumber),
            trackCount = item.int("albumTrackCount", trackCount),
            discNumber = item.int("albumDiscNumber", discNumber),
            discCount = item.int("albumDiscCount", discCount),
        )
    }
    if (playback != null) {
        next = next.copy(
            status = playback.coded("playbackStatus", status),
            queueIndex = playback.long("queueIndex", queueIndex),
            queueCount = playback.long("queueCount", queueCount),
            shuffle = playback.coded("shuffleMode", shuffle),
            repeat = playback.coded("repeatMode", repeat),
            appBundleId = playback.string("appBundleId", appBundleId),
        )
    }
    return next
}

private fun Call.merged(fields: DecodedFields) = copy(
    remoteId = fields.string("remoteId", remoteId),
    displayName = fields.string("displayName", displayName),
    status = fields.coded("status", status),
    direction = fields.coded("direction", direction),
    label = fields.string("label", label),
    service = fields.coded("service", service),
    conferenced = fields.bool("isConferenced", conferenced),
    conferenceGroup = fields.int("conferenceGroup", conferenceGroup),
    disconnectReason = fields.coded("disconnectReason", disconnectReason),
)

private fun Communications.merged(fields: DecodedFields) = copy(
    signalStrength = fields.int("signalStrength", signalStrength),
    registration = fields.coded("registrationStatus", registration),
    airplaneMode = fields.bool("airplaneModeStatus", airplaneMode),
    carrier = fields.string("carrierName", null)?.ifEmpty { null } ?: fields.string("carrierId", null) ?: carrier,
    telephonyEnabled = fields.bool("telephonyEnabled", telephonyEnabled),
    faceTimeAudioEnabled = fields.bool("faceTimeAudioEnabled", faceTimeAudioEnabled),
    faceTimeVideoEnabled = fields.bool("faceTimeVideoEnabled", faceTimeVideoEnabled),
    muted = fields.bool("muteStatus", muted),
    callCount = fields.int("currentCallCount", callCount),
    voicemailCount = fields.int("newVoicemailCount", voicemailCount),
)

private fun Power.merged(fields: DecodedFields) = copy(
    chargeLevel = fields.int("batteryChargeLevel", chargeLevel),
    chargingState = fields.coded("batteryChargingState", chargingState),
    externalChargerConnected = fields.bool("isExternalChargerConnected", externalChargerConnected),
)
