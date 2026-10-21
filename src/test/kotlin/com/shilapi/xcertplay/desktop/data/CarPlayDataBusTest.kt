package com.shilapi.xcertplay.desktop.data

import com.shilapi.xcertplay.desktop.data.CarPlayDataEvent.ArtworkChanged
import com.shilapi.xcertplay.desktop.data.CarPlayDataEvent.CallChanged
import com.shilapi.xcertplay.desktop.data.CarPlayDataEvent.Cleared
import com.shilapi.xcertplay.desktop.data.CarPlayDataEvent.CommunicationsChanged
import com.shilapi.xcertplay.desktop.data.CarPlayDataEvent.LanesChanged
import com.shilapi.xcertplay.desktop.data.CarPlayDataEvent.NowPlayingChanged
import com.shilapi.xcertplay.desktop.data.CarPlayDataEvent.PowerChanged
import com.shilapi.xcertplay.desktop.data.CarPlayDataEvent.RouteChanged
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.transport.Iap2ArtworkTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayDataBusTest {
    private var now = 0L
    private val bus = CarPlayDataBus(nanoTime = { now })
    private val events = ArrayList<CarPlayDataEvent>()

    init {
        bus.addListener { events += it }
    }

    private fun frame(id: Int, block: Iap2BodyBuilder.() -> Unit) = bus.accept(Iap2Messages.buildRaw(id, block))

    private fun maneuver(index: Int, type: Int, road: String) = frame(0x5202) {
        u16(1, index)
        u8(3, type)
        string(4, road)
        u8(8, 0)
    }

    private inline fun <reified E : CarPlayDataEvent> last(): E = events.filterIsInstance<E>().last()

    @Test
    fun `route and maneuver updates make the next maneuver`() {
        frame(0x5202) {
            u16(1, 3)
            string(2, "Turn right onto Oak Avenue")
            u8(3, 2)
            string(4, "Oak Avenue")
            u32(5, 300)
            u8(8, 1)
            u8(9, 0)
            i16(10, -90)
            i16(10, 90)
            i16(11, 90)
        }
        maneuver(4, 12, "")
        frame(0x5201) {
            u8(1, 1)
            u8(2, 2)
            string(3, "Main Street")
            string(4, "Home")
            u64(5, 1_790_000_000L)
            u64(6, 600)
            u32(7, 5_400)
            u32(10, 150)
            string(11, "150")
            u8(12, 2)
            u16List(13, listOf(3, 4))
            string(19, "Maps")
        }

        val route = last<RouteChanged>().route
        val next = Maneuver(
            index = 3, typeCode = 2, description = "Turn right onto Oak Avenue", afterRoad = "Oak Avenue",
            distanceBetweenMeters = 300, drivingSide = DrivingSide.LEFT, junctionType = JunctionType.INTERSECTION,
            junctionAngles = listOf(-90, 90), exitAngle = 90,
        )
        assertEquals(next, route.nextManeuver)
        assertEquals(ManeuverType.RIGHT_TURN, route.nextManeuver!!.type)
        assertEquals(listOf(next, Maneuver(4, typeCode = 12, drivingSide = DrivingSide.RIGHT)), route.currentManeuvers)
        assertEquals(RouteGuidanceState.ROUTE_SET, route.state)
        assertEquals(ManeuverState.PREPARE, route.maneuverState)
        assertEquals("Main Street", route.currentRoad)
        assertEquals("Home", route.destination)
        assertEquals(1_790_000_000L, route.arrivalEpochSeconds)
        assertEquals(600L, route.remainingSeconds)
        assertEquals(5_400L, route.remainingMeters)
        assertEquals(150L, route.distanceToManeuverMeters)
        assertEquals("150", route.distanceToManeuverText)
        assertEquals(DistanceUnits.METERS, route.distanceToManeuverUnits)
        assertEquals(listOf(3, 4), route.currentManeuverIndexes)
        assertEquals("Maps", route.sourceName)
        assertEquals(route, bus.snapshot().route)
    }

    @Test
    fun `route updates carry only what changed`() {
        maneuver(0, 1, "Elm Street")
        frame(0x5201) {
            u8(1, 1)
            string(3, "Main Street")
            u32(10, 500)
            u16List(13, listOf(0))
        }
        frame(0x5201) { u32(10, 120) }

        val route = last<RouteChanged>().route
        assertEquals(120L, route.distanceToManeuverMeters)
        assertEquals("Main Street", route.currentRoad)
        assertEquals(0, route.nextManeuver!!.index)
    }

    // The iPhone sends an empty current list every few seconds; upstream's BydHudRouteState keeps the turn for 3 s.
    @Test
    fun `an empty current list keeps the next maneuver for a while`() {
        maneuver(0, 1, "Elm Street")
        frame(0x5201) {
            u8(1, 1)
            u16List(13, listOf(0))
        }
        frame(0x5201) { u16List(13, emptyList()) }
        assertEquals(0, bus.snapshot().route!!.nextManeuver!!.index)

        now += 3_000_000_000L

        assertNull(bus.snapshot().route!!.nextManeuver)
        assertNull(last<RouteChanged>().route.nextManeuver)
    }

    @Test
    fun `arrival ends the route`() {
        maneuver(0, 12, "")
        frame(0x5201) {
            u8(1, 1)
            string(3, "Main Street")
            u16List(13, listOf(0))
        }
        frame(0x5201) { u8(1, 2) }

        val route = last<RouteChanged>().route
        assertEquals(RouteGuidanceState.ARRIVED, route.state)
        assertNull(route.nextManeuver)
        assertTrue(route.currentManeuvers.isEmpty())
        assertNull(route.currentRoad)
    }

    @Test
    fun `now playing keeps what each update leaves out`() {
        frame(0x5001) {
            group(0) {
                string(1, "Song")
                u32(4, 215_000)
                string(6, "Album")
                string(12, "Artist")
                string(14, "Band")
                u16(7, 4)
                u8(26, 7)
            }
            group(1) {
                u8(0, 1)
                u32(1, 1_000)
                u8(5, 1)
                string(7, "Music")
            }
        }
        frame(0x5001) { group(1) { u32(1, 1_500) } }

        val playing = last<NowPlayingChanged>().nowPlaying
        assertEquals(
            NowPlaying(
                title = "Song", artist = "Artist", album = "Album", albumArtist = "Band", durationMillis = 215_000,
                elapsedMillis = 1_500, status = PlaybackStatus.PLAYING, playing = true, trackNumber = 4,
                shuffle = ShuffleMode.SONGS, appName = "Music", artworkTransferId = 7,
            ),
            playing,
        )

        frame(0x5001) { group(1) { u8(0, 2) } }
        val paused = last<NowPlayingChanged>().nowPlaying
        assertEquals(PlaybackStatus.PAUSED, paused.status)
        assertEquals(false, paused.playing)
        assertEquals("Song", bus.snapshot().nowPlaying!!.title)
    }

    @Test
    fun `artwork arrives as bytes`() {
        bus.acceptArtwork(Iap2ArtworkTransfer(7, byteArrayOf(1, 2, 3)))

        assertEquals(Artwork(7, byteArrayOf(1, 2, 3)), last<ArtworkChanged>().artwork)
        assertEquals(3, bus.snapshot().artwork!!.size)
    }

    @Test
    fun `a call is kept until it ends`() {
        frame(0x4155) {
            string(0, "+15551234567")
            string(1, "Alice")
            u8(2, 2)
            u8(3, 1)
            string(4, "call-1")
        }
        val ringing = Call("call-1", "+15551234567", "Alice", CallStatus.RINGING, CallDirection.INCOMING)
        assertEquals(CallChanged(ringing, listOf(ringing)), events.last())

        frame(0x4155) {
            u8(2, 4)
            string(4, "call-1")
        }
        val active = ringing.copy(status = CallStatus.ACTIVE)
        assertEquals(listOf(active), bus.snapshot().calls)
        assertEquals("Alice", active.name)

        frame(0x4155) {
            u8(2, 0)
            string(4, "call-1")
            u8(11, 1)
        }
        val ended = active.copy(status = CallStatus.DISCONNECTED, disconnectReason = DisconnectReason.DECLINED)
        assertEquals(CallChanged(ended, emptyList()), events.last())
        assertTrue(bus.snapshot().calls.isEmpty())
    }

    @Test
    fun `communications and power keep their last values`() {
        frame(0x4158) {
            u8(0, 3)
            string(3, "Carrier")
            bool(5, true)
        }
        frame(0x4158) { u8(0, 4) }
        assertEquals(
            Communications(signalStrength = 4, carrier = "Carrier", telephonyEnabled = true),
            last<CommunicationsChanged>().communications,
        )

        frame(0xae01) {
            bool(4, true)
            u8(5, 1)
            u16(6, 37)
        }
        val power = last<PowerChanged>().power
        assertEquals(Power(37, BatteryChargingState.CHARGING, true), power)
        // The iPhone sends percent: 38 in the data log while it showed 37 %.
        assertEquals(37, power.batteryPercent)
    }

    // The iPhone 15 on iOS 18.6 leaves the carrier name empty and names the carrier in parameter 4.
    @Test
    fun `the carrier falls back to the carrier id`() {
        frame(0x4158) {
            string(3, "")
            string(4, "UA-KYIVSTAR")
        }
        assertEquals("UA-KYIVSTAR", last<CommunicationsChanged>().communications.carrier)
    }

    @Test
    fun `lane guidance becomes lanes`() {
        frame(0x5204) {
            u16(1, 0)
            group(2) {
                u16(0, 0)
                i16(1, 0)
                u8(2, 2)
            }
            string(3, "Keep left")
        }

        assertEquals(
            LaneGuidance(0, listOf(Lane(0, 0, LaneStatus.PREFERRED)), "Keep left"),
            last<LanesChanged>().lanes,
        )
    }

    @Test
    fun `an update that changes nothing raises no event`() {
        repeat(2) { frame(0xae01) { u16(6, 100) } }
        frame(0x4171) { string(0, "list") }

        assertEquals(1, events.size)
    }

    @Test
    fun `a listener that leaves hears nothing more and one that fails does not stop the others`() {
        val heard = ArrayList<CarPlayDataEvent>()
        bus.addListener { error("listener failed") }
        val registration = bus.addListener { heard += it }
        frame(0xae01) { u16(6, 100) }
        registration.close()
        frame(0xae01) { u16(6, 200) }

        assertEquals(1, heard.size)
        assertEquals(2, events.size)
    }

    @Test
    fun `the end of a session empties the snapshot`() {
        frame(0xae01) { u16(6, 100) }
        frame(0x4158) { string(3, "Carrier") }

        bus.clear()

        assertEquals(Cleared, events.last())
        assertEquals(CarPlayDataSnapshot(), bus.snapshot())
    }
}
