package com.shilapi.xcertplay.desktop.data

/** An enumeration of Apple's iAP2 spec: [code] is its value on the wire. */
interface Iap2Coded {
    val code: Int
}

internal inline fun <reified E> codeOf(code: Int?): E? where E : Enum<E>, E : Iap2Coded =
    code?.let { value -> enumValues<E>().firstOrNull { it.code == value } }

internal inline fun <reified E> codeNames(): Map<Int, String> where E : Enum<E>, E : Iap2Coded =
    enumValues<E>().associate { it.code to it.name }

/** RouteGuidanceState (0x5201 parameter 1). */
enum class RouteGuidanceState(override val code: Int) : Iap2Coded {
    NO_ROUTE_SET(0), ROUTE_SET(1), ARRIVED(2), LOADING(3), LOCATING(4), REROUTING(5), PROCEED_TO_ROUTE(6),
}

/** ManeuverState (0x5201 parameter 2): how close the next maneuver is. */
enum class ManeuverState(override val code: Int) : Iap2Coded { CONTINUE(0), INITIAL(1), PREPARE(2), EXECUTE(3) }

/** The units of the iPhone's own distance text. */
enum class DistanceUnits(override val code: Int) : Iap2Coded { KILOMETERS(0), MILES(1), METERS(2), YARDS(3), FEET(4) }

enum class DrivingSide(override val code: Int) : Iap2Coded { RIGHT(0), LEFT(1) }

enum class JunctionType(override val code: Int) : Iap2Coded { INTERSECTION(0), ROUNDABOUT(1) }

/**
 * RouteGuidanceManeuverType (0x5202 parameter 3), the same codes upstream's BydManeuverCodes and
 * FceManeuverCodes map to cluster glyphs. 28 to 46 are the exits of a roundabout, first to nineteenth.
 */
enum class ManeuverType(override val code: Int) : Iap2Coded {
    NO_TURN(0), LEFT_TURN(1), RIGHT_TURN(2), STRAIGHT_AHEAD(3), U_TURN(4), FOLLOW_ROAD(5), ENTER_ROUNDABOUT(6),
    EXIT_ROUNDABOUT(7), OFF_RAMP(8), ON_RAMP(9), ARRIVE_END_OF_NAVIGATION(10), START_ROUTE(11), ARRIVE_AT_DESTINATION(12),
    KEEP_LEFT(13), KEEP_RIGHT(14), ENTER_FERRY(15), EXIT_FERRY(16), CHANGE_FERRY(17), START_ROUTE_WITH_U_TURN(18),
    U_TURN_AT_ROUNDABOUT(19), LEFT_TURN_AT_END(20), RIGHT_TURN_AT_END(21), HIGHWAY_OFF_RAMP_LEFT(22),
    HIGHWAY_OFF_RAMP_RIGHT(23), ARRIVE_AT_DESTINATION_LEFT(24), ARRIVE_AT_DESTINATION_RIGHT(25), U_TURN_WHEN_POSSIBLE(26),
    ARRIVE_END_OF_DIRECTIONS(27),
    ROUNDABOUT_EXIT_1(28), ROUNDABOUT_EXIT_2(29), ROUNDABOUT_EXIT_3(30), ROUNDABOUT_EXIT_4(31), ROUNDABOUT_EXIT_5(32),
    ROUNDABOUT_EXIT_6(33), ROUNDABOUT_EXIT_7(34), ROUNDABOUT_EXIT_8(35), ROUNDABOUT_EXIT_9(36), ROUNDABOUT_EXIT_10(37),
    ROUNDABOUT_EXIT_11(38), ROUNDABOUT_EXIT_12(39), ROUNDABOUT_EXIT_13(40), ROUNDABOUT_EXIT_14(41), ROUNDABOUT_EXIT_15(42),
    ROUNDABOUT_EXIT_16(43), ROUNDABOUT_EXIT_17(44), ROUNDABOUT_EXIT_18(45), ROUNDABOUT_EXIT_19(46),
    SHARP_LEFT_TURN(47), SHARP_RIGHT_TURN(48), SLIGHT_LEFT_TURN(49), SLIGHT_RIGHT_TURN(50), CHANGE_HIGHWAY(51),
    CHANGE_HIGHWAY_LEFT(52), CHANGE_HIGHWAY_RIGHT(53),
    ;

    /** The exit a roundabout maneuver takes, 1 to 19; null for other maneuvers. */
    val roundaboutExit: Int? get() = if (code in 28..46) code - 27 else null
}

/** A lane's state in LaneGuidanceInfoUpdate (0x5204). */
enum class LaneStatus(override val code: Int) : Iap2Coded { NOT_GOOD(0), GOOD(1), PREFERRED(2) }

enum class PlaybackStatus(override val code: Int) : Iap2Coded { STOPPED(0), PLAYING(1), PAUSED(2), SEEK_FORWARD(3), SEEK_BACKWARD(4) }

enum class ShuffleMode(override val code: Int) : Iap2Coded { OFF(0), SONGS(1), ALBUMS(2) }

enum class RepeatMode(override val code: Int) : Iap2Coded { OFF(0), ONE(1), ALL(2) }

enum class CallStatus(override val code: Int) : Iap2Coded {
    DISCONNECTED(0), SENDING(1), RINGING(2), CONNECTING(3), ACTIVE(4), HELD(5), DISCONNECTING(6),
}

enum class CallDirection(override val code: Int) : Iap2Coded { UNKNOWN(0), INCOMING(1), OUTGOING(2) }

enum class CallService(override val code: Int) : Iap2Coded { UNKNOWN(0), TELEPHONY(1), FACETIME_AUDIO(2), FACETIME_VIDEO(3) }

enum class DisconnectReason(override val code: Int) : Iap2Coded { ENDED(0), DECLINED(1), FAILED(2) }

enum class RegistrationStatus(override val code: Int) : Iap2Coded {
    UNKNOWN(0), NOT_REGISTERED(1), SEARCHING(2), DENIED(3), REGISTERED_HOME(4), REGISTERED_ROAMING(5), EMERGENCY_CALLS_ONLY(6),
}

enum class BatteryChargingState(override val code: Int) : Iap2Coded { DISABLED(0), CHARGING(1), CHARGED(2) }

/** One maneuver of the route, from RouteGuidanceManeuverUpdate (0x5202); the iPhone sends each once per route. */
data class Maneuver(
    val index: Int,
    /** Apple's maneuver type as sent, also when [type] does not know it yet. */
    val typeCode: Int? = null,
    val description: String? = null,
    /** The road the maneuver turns onto. */
    val afterRoad: String? = null,
    val distanceBetweenMeters: Long? = null,
    val distanceBetweenText: String? = null,
    val distanceBetweenUnits: DistanceUnits? = null,
    val drivingSide: DrivingSide? = null,
    val junctionType: JunctionType? = null,
    /** The angles of the junction's roads, in degrees, as the iPhone draws the junction. */
    val junctionAngles: List<Int> = emptyList(),
    /** The angle of the road the maneuver leaves the junction by. */
    val exitAngle: Int? = null,
) {
    val type: ManeuverType? get() = codeOf<ManeuverType>(typeCode)
}

/** The route from RouteGuidanceUpdate (0x5201), each value as last sent, with its maneuvers. */
data class RouteGuidance(
    val state: RouteGuidanceState? = null,
    val maneuverState: ManeuverState? = null,
    val currentRoad: String? = null,
    val destination: String? = null,
    val arrivalEpochSeconds: Long? = null,
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    /** The iPhone's own text for [remainingMeters], in [remainingUnits]. */
    val remainingText: String? = null,
    val remainingUnits: DistanceUnits? = null,
    val distanceToManeuverMeters: Long? = null,
    val distanceToManeuverText: String? = null,
    val distanceToManeuverUnits: DistanceUnits? = null,
    /** The indexes of the maneuvers the iPhone shows now, the next first; briefly empty while it reroutes. */
    val currentManeuverIndexes: List<Int> = emptyList(),
    val maneuverCount: Int? = null,
    val laneGuidanceIndex: Int? = null,
    val laneGuidanceTotal: Int? = null,
    val laneGuidanceShowing: Boolean? = null,
    /** The navigation app. */
    val sourceName: String? = null,
    /**
     * The maneuver to show, by upstream's BydHudRouteState rules: the first current one, kept through the
     * iPhone's brief empty lists, gone after 3 s of an empty list, 30 s without a route update, or at the
     * end of the route. The rules depend on time: [CarPlayDataBus.snapshot] brings them up to date.
     */
    val nextManeuver: Maneuver? = null,
    /** The current maneuvers whose details have arrived, in the iPhone's order. */
    val currentManeuvers: List<Maneuver> = emptyList(),
)

/** One lane of LaneGuidanceInfoUpdate (0x5204). */
data class Lane(val position: Int? = null, val directionAngle: Int? = null, val status: LaneStatus? = null)

/**
 * Lane guidance from LaneGuidanceInfoUpdate (0x5204). Its layout is not in upstream and has not been seen
 * from an iPhone (the identification does not declare the message), so it is decoded from the layout of
 * its siblings 0x5201 and 0x5202 and the data log keeps its raw bytes too.
 */
data class LaneGuidance(val index: Int? = null, val lanes: List<Lane> = emptyList(), val description: String? = null)

/** NowPlayingUpdate (0x5001), each value as last sent: an update carries only what changed. */
data class NowPlaying(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val composer: String? = null,
    val durationMillis: Long? = null,
    val elapsedMillis: Long? = null,
    val status: PlaybackStatus? = null,
    /** Upstream's reading: true only while [status] is PLAYING. */
    val playing: Boolean = false,
    val trackNumber: Int? = null,
    val trackCount: Int? = null,
    val discNumber: Int? = null,
    val discCount: Int? = null,
    val queueIndex: Long? = null,
    val queueCount: Long? = null,
    val shuffle: ShuffleMode? = null,
    val repeat: RepeatMode? = null,
    val appName: String? = null,
    val appBundleId: String? = null,
    /** The file transfer that brings this item's artwork, matching [Artwork.transferId]. */
    val artworkTransferId: Int? = null,
)

/** Album artwork from the iAP2 file transfer (Iap2ArtworkTransfer); empty [bytes] clear it. */
class Artwork(val transferId: Int, bytes: ByteArray) {
    private val data = bytes.copyOf()

    /** The image as the iPhone sent it, usually JPEG. */
    val bytes: ByteArray get() = data.copyOf()

    val size: Int get() = data.size

    override fun equals(other: Any?) = other is Artwork && transferId == other.transferId && data.contentEquals(other.data)

    override fun hashCode() = 31 * transferId + data.contentHashCode()

    override fun toString() = "Artwork(transferId=$transferId, ${data.size} bytes)"
}

/** One call from CallStateUpdate (0x4155). */
data class Call(
    /** The call's UUID, or its remote id when the iPhone sends none. */
    val id: String,
    /** The number or address of the other side. */
    val remoteId: String? = null,
    val displayName: String? = null,
    val status: CallStatus? = null,
    val direction: CallDirection? = null,
    /** The number's label in the contact, such as "mobile". */
    val label: String? = null,
    val service: CallService? = null,
    val conferenced: Boolean? = null,
    val conferenceGroup: Int? = null,
    val disconnectReason: DisconnectReason? = null,
) {
    /** What a call card shows: the contact's name, else the number. */
    val name: String? get() = displayName ?: remoteId
}

/** CommunicationsUpdate (0x4158), each value as last sent. */
data class Communications(
    /** Signal bars as the iPhone counts them. */
    val signalStrength: Int? = null,
    val registration: RegistrationStatus? = null,
    val airplaneMode: Boolean? = null,
    /** The carrier's name, or its id ("UA-KYIVSTAR") when the iPhone leaves the name empty. */
    val carrier: String? = null,
    val telephonyEnabled: Boolean? = null,
    val faceTimeAudioEnabled: Boolean? = null,
    val faceTimeVideoEnabled: Boolean? = null,
    val muted: Boolean? = null,
    val callCount: Int? = null,
    val voicemailCount: Int? = null,
)

/** The iPhone's battery from PowerUpdate (0xAE01). */
data class Power(
    /** Percent, as sent: an iPhone 15 on iOS 18.6 reported 38 while it showed 37 %. */
    val chargeLevel: Int? = null,
    val chargingState: BatteryChargingState? = null,
    val externalChargerConnected: Boolean? = null,
) {
    val batteryPercent: Int? get() = chargeLevel?.coerceIn(0, 100)
}

/** Everything the bus knows, as of its last change. */
data class CarPlayDataSnapshot(
    val route: RouteGuidance? = null,
    val lanes: LaneGuidance? = null,
    val nowPlaying: NowPlaying? = null,
    val artwork: Artwork? = null,
    /** Calls that have not ended, in the order they started. */
    val calls: List<Call> = emptyList(),
    val communications: Communications? = null,
    val power: Power? = null,
)

/** One change on the bus; the snapshot already holds it when listeners hear of it. */
sealed interface CarPlayDataEvent {
    data class RouteChanged(val route: RouteGuidance) : CarPlayDataEvent
    data class LanesChanged(val lanes: LaneGuidance) : CarPlayDataEvent
    data class NowPlayingChanged(val nowPlaying: NowPlaying) : CarPlayDataEvent
    data class ArtworkChanged(val artwork: Artwork) : CarPlayDataEvent
    /** [call] as updated, also when it ended and has left [calls]. */
    data class CallChanged(val call: Call, val calls: List<Call>) : CarPlayDataEvent
    data class CommunicationsChanged(val communications: Communications) : CarPlayDataEvent
    data class PowerChanged(val power: Power) : CarPlayDataEvent
    /** The CarPlay session ended: the snapshot is empty again. */
    data object Cleared : CarPlayDataEvent
}
