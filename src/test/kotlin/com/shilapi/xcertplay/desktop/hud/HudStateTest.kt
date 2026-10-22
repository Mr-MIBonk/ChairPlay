package com.shilapi.xcertplay.desktop.hud

import com.shilapi.xcertplay.desktop.data.Call
import com.shilapi.xcertplay.desktop.data.CallDirection
import com.shilapi.xcertplay.desktop.data.CallStatus
import com.shilapi.xcertplay.desktop.data.CarPlayDataSnapshot
import com.shilapi.xcertplay.desktop.data.DistanceUnits
import com.shilapi.xcertplay.desktop.data.Maneuver
import com.shilapi.xcertplay.desktop.data.NowPlaying
import com.shilapi.xcertplay.desktop.data.PlaybackStatus
import com.shilapi.xcertplay.desktop.data.RouteGuidance
import com.shilapi.xcertplay.desktop.data.RouteGuidanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class HudStateTest {
    private val zone = ZoneOffset.ofHours(3)

    // As the data log of a Google Maps route showed it.
    private val route = RouteGuidance(
        state = RouteGuidanceState.ROUTE_SET,
        currentRoad = "вул. Хрещатик",
        destination = "Олімпійський",
        arrivalEpochSeconds = 1_792_622_969, // 2026-10-21 22:49:29 UTC, 01:49 at UTC+3
        remainingSeconds = 1_320,
        remainingText = "15",
        remainingUnits = DistanceUnits.KILOMETERS,
        distanceToManeuverText = "150",
        distanceToManeuverUnits = DistanceUnits.METERS,
        nextManeuver = Maneuver(index = 1, typeCode = 2, description = "вул. Липська", afterRoad = "вул. Липська"),
    )

    @Test
    fun `a route shows the next maneuver, the arrival and what is left`() {
        val hud = hudState(CarPlayDataSnapshot(route = route), zone)

        val maneuver = hud.maneuver!!
        assertEquals("150 m", maneuver.distance)
        assertEquals("вул. Липська", maneuver.road)
        assertEquals(2, maneuver.typeCode)
        assertEquals("01:49", hud.arrival!!.time)
        assertEquals("22 min · 15 km", hud.arrival!!.remaining)
        assertEquals("Олімпійський", hud.arrival!!.destination)
    }

    @Test
    fun `no route shows no maneuver`() {
        val hud = hudState(CarPlayDataSnapshot(route = route.copy(state = RouteGuidanceState.NO_ROUTE_SET)), zone)

        assertNull(hud.maneuver)
        assertNull(hud.arrival)
    }

    @Test
    fun `long trips count hours`() {
        val hud = hudState(CarPlayDataSnapshot(route = route.copy(remainingSeconds = 3 * 3600 + 5 * 60)), zone)

        assertEquals("3 h 05 min · 15 km", hud.arrival!!.remaining)
    }

    @Test
    fun `the song shows title, artist and album, its app and how far it has played`() {
        val song = NowPlaying(
            title = "Gasoline", artist = "Twin Fin", album = "Gasoline", durationMillis = 166_721, elapsedMillis = 41_680,
            status = PlaybackStatus.PLAYING, playing = true, appName = "YouTube Music",
        )
        val media = hudState(CarPlayDataSnapshot(nowPlaying = song), zone).media!!

        assertEquals("Gasoline", media.title)
        assertEquals("Twin Fin — Gasoline", media.subtitle)
        assertEquals("YouTube Music", media.source)
        assertEquals(0.25f, media.progress!!, 0.001f)
        assertEquals(true, media.playing)
    }

    @Test
    fun `a ringing or active call goes on top`() {
        val ringing = Call(id = "1", displayName = "Mom", status = CallStatus.RINGING, direction = CallDirection.INCOMING)
        assertEquals("Incoming call · Mom", hudState(CarPlayDataSnapshot(calls = listOf(ringing)), zone).call)
        assertEquals(
            "On a call · Mom",
            hudState(CarPlayDataSnapshot(calls = listOf(ringing.copy(status = CallStatus.ACTIVE))), zone).call,
        )
        assertNull(hudState(CarPlayDataSnapshot(calls = listOf(ringing.copy(status = CallStatus.DISCONNECTED))), zone).call)
    }

    // Text from the iPhone is cut before it reaches the drawing, which measures it.
    @Test
    fun `very long text is cut`() {
        val media = hudState(CarPlayDataSnapshot(nowPlaying = NowPlaying(title = "x".repeat(10_000))), zone).media!!
        assertEquals(256, media.title!!.length)
    }

    @Test
    fun `an empty snapshot shows nothing`() {
        assertEquals(HudState(), hudState(CarPlayDataSnapshot(), zone))
    }
}
