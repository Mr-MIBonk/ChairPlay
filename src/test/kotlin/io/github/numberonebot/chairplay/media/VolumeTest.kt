package io.github.numberonebot.chairplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeTest {
    // As a car's volume knob: steps of 5 between silence and full.
    @Test
    fun `the volume steps by five between 0 and 100`() {
        val volume = Volume(level = 50)
        volume.change(1)
        assertEquals(55, volume.level)
        volume.change(-3)
        assertEquals(40, volume.level)
        volume.change(-20)
        assertEquals(0, volume.level)
        volume.change(30)
        assertEquals(100, volume.level)
    }

    // The level squared sounds about even from step to step; full volume leaves the sound as the iPhone sent it.
    @Test
    fun `the gain is the level squared`() {
        assertEquals(1f, Volume(level = 100).gain, 0f)
        assertEquals(0.25f, Volume(level = 50).gain, 0.0001f)
        assertEquals(0f, Volume(level = 0).gain, 0f)
    }

    @Test
    fun `mute silences without losing the level, and a step unmutes`() {
        val volume = Volume(level = 60)
        volume.toggleMute()
        assertTrue(volume.muted)
        assertEquals(0f, volume.gain, 0f)
        assertEquals(60, volume.level)
        volume.toggleMute()
        assertFalse(volume.muted)
        assertEquals(0.36f, volume.gain, 0.0001f)

        volume.toggleMute()
        volume.change(1)
        assertFalse(volume.muted)
        assertEquals(65, volume.level)
    }

    @Test
    fun `every change is reported`() {
        val volume = Volume(level = 50)
        val seen = mutableListOf<Pair<Int, Boolean>>()
        volume.onChange = { seen += volume.level to volume.muted }
        volume.change(1)
        volume.toggleMute()
        assertEquals(listOf(55 to false, 55 to true), seen)
    }

    @Test
    fun `a level from outside is kept within 0 and 100`() {
        assertEquals(100, Volume(level = 140).level)
        assertEquals(0, Volume(level = -5).level)
    }

    // The Audio page's slider sets the level itself, to the percent, unmuting as a turn of the knob does.
    @Test
    fun `the slider sets the level and unmutes`() {
        val volume = Volume(level = 50, muted = true)
        var changes = 0
        volume.onChange = { changes++ }

        volume.set(37)

        assertEquals(37 to false, volume.level to volume.muted)
        assertEquals(1, changes)
        volume.set(140)
        assertEquals(100, volume.level)
    }
}
