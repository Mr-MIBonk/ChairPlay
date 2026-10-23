package io.github.numberonebot.chairplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudMotionTest {
    private fun ms(millis: Long) = millis * 1_000_000

    @Test
    fun `a part rises from the dark over its fade-in`() {
        val presence = Presence(inMillis = 600, outMillis = 400)
        assertEquals(0f, presence.update(true, ms(0)), 0f)
        assertTrue(presence.moving)
        val half = presence.update(true, ms(300))
        assertTrue("half way is between, was $half", half > 0.2f && half < 0.8f)
        assertEquals(1f, presence.update(true, ms(600)), 0f)
        assertFalse(presence.moving)
    }

    @Test
    fun `a part fades out over its own time`() {
        val presence = Presence(inMillis = 600, outMillis = 400)
        presence.update(true, ms(0))
        presence.update(true, ms(600))
        assertEquals(1f, presence.update(false, ms(10_000)), 0f)
        assertTrue(presence.moving)
        assertEquals(0f, presence.update(false, ms(10_400)), 0f)
        assertFalse(presence.moving)
    }

    @Test
    fun `a change after a long still spell starts from that moment, not from the last frame`() {
        val presence = Presence(inMillis = 600, outMillis = 600)
        presence.update(false, ms(0))
        // The window drew nothing for a minute; the part appears now and must still rise, not jump.
        val first = presence.update(true, ms(60_000))
        assertEquals(0f, first, 0f)
        val later = presence.update(true, ms(60_300))
        assertTrue("rising, was $later", later > 0f && later < 1f)
    }

    @Test
    fun `turning back half way goes back from where it is`() {
        val presence = Presence(inMillis = 600, outMillis = 600)
        presence.update(true, ms(0))
        val reached = presence.update(true, ms(300))
        val turned = presence.update(false, ms(300))
        assertEquals(reached, turned, 1e-6f)
        val back = presence.update(false, ms(450))
        assertTrue("falling, was $back", back < turned)
        assertEquals(0f, presence.update(false, ms(600)), 0f)
    }

    @Test
    fun `at start the waiting line rises from the dark`() {
        val motion = HudMotion()
        val first = motion.update(connected = false, media = false, route = false, call = false, nowNanos = ms(0))
        assertEquals(0f, first.waiting, 0f)
        assertTrue(first.moving)
        val settled = motion.update(connected = false, media = false, route = false, call = false, nowNanos = ms(5_000))
        assertEquals(1f, settled.waiting, 0f)
        assertFalse(settled.moving)
    }

    @Test
    fun `on connecting the waiting line fades and the song rises`() {
        val motion = HudMotion()
        motion.update(connected = false, media = true, route = false, call = false, nowNanos = ms(0))
        val waiting = motion.update(connected = false, media = true, route = false, call = false, nowNanos = ms(5_000))
        assertEquals(0f, waiting.media, 0f)
        motion.update(connected = true, media = true, route = false, call = false, nowNanos = ms(6_000))
        val connected = motion.update(connected = true, media = true, route = false, call = false, nowNanos = ms(11_000))
        assertEquals(0f, connected.waiting, 0f)
        assertEquals(1f, connected.media, 0f)
    }

    @Test
    fun `guidance rises beside the song and goes again`() {
        val motion = HudMotion()
        motion.update(connected = true, media = true, route = false, call = false, nowNanos = ms(0))
        val song = motion.update(connected = true, media = true, route = false, call = false, nowNanos = ms(5_000))
        assertEquals(0f, song.route, 0f)
        motion.update(connected = true, media = true, route = true, call = false, nowNanos = ms(6_000))
        val mid = motion.update(connected = true, media = true, route = true, call = false, nowNanos = ms(6_000 + HudMotion.ROUTE_MILLIS * 3 / 4))
        assertTrue("guidance half risen, was ${mid.route}", mid.route > 0f && mid.route < 1f)
        assertTrue(mid.moving)
        val both = motion.update(connected = true, media = true, route = true, call = false, nowNanos = ms(11_000))
        assertEquals(1f, both.route, 0f)
        motion.update(connected = true, media = true, route = false, call = false, nowNanos = ms(12_000))
        val gone = motion.update(connected = true, media = true, route = false, call = false, nowNanos = ms(17_000))
        assertEquals(0f, gone.route, 0f)
        assertEquals(1f, gone.media, 0f)
    }

    @Test
    fun `coming guidance first moves the song to its column and only then rises`() {
        val motion = HudMotion()
        motion.update(connected = true, media = true, route = false, call = false, nowNanos = ms(0))
        motion.update(connected = true, media = true, route = false, call = false, nowNanos = ms(5_000))
        motion.update(connected = true, media = true, route = true, call = false, nowNanos = ms(6_000))
        val early = motion.update(connected = true, media = true, route = true, call = false, nowNanos = ms(6_000 + HudMotion.ROUTE_MILLIS / 4))
        assertTrue("the song is on its way, was ${early.mediaPlace}", early.mediaPlace > 0.3f)
        assertTrue("the route waits for it, was ${early.route}", early.route < 0.05f)
        val late = motion.update(connected = true, media = true, route = true, call = false, nowNanos = ms(6_000 + HudMotion.ROUTE_MILLIS * 3 / 4))
        assertTrue("the song is nearly there, was ${late.mediaPlace}", late.mediaPlace > 0.95f)
        assertTrue("the route rises, was ${late.route}", late.route > 0.2f && late.route < 1f)
    }

    @Test
    fun `going guidance first dissolves and only then the song comes back to the middle`() {
        val motion = HudMotion()
        motion.update(connected = true, media = true, route = true, call = false, nowNanos = ms(0))
        motion.update(connected = true, media = true, route = true, call = false, nowNanos = ms(5_000))
        motion.update(connected = true, media = true, route = false, call = false, nowNanos = ms(6_000))
        val early = motion.update(connected = true, media = true, route = false, call = false, nowNanos = ms(6_000 + HudMotion.ROUTE_MILLIS / 4))
        assertTrue("the route dissolves, was ${early.route}", early.route < 0.8f)
        assertTrue("the song stays in its column, was ${early.mediaPlace}", early.mediaPlace > 0.95f)
        val late = motion.update(connected = true, media = true, route = false, call = false, nowNanos = ms(6_000 + HudMotion.ROUTE_MILLIS * 3 / 4))
        assertEquals(0f, late.route, 0f)
        assertTrue("the song comes back, was ${late.mediaPlace}", late.mediaPlace < 0.7f)
    }

    @Test
    fun `guidance without a song rises at once`() {
        val motion = HudMotion()
        motion.update(connected = true, media = false, route = false, call = false, nowNanos = ms(0))
        motion.update(connected = true, media = false, route = true, call = false, nowNanos = ms(5_000))
        val early = motion.update(connected = true, media = false, route = true, call = false, nowNanos = ms(5_000 + HudMotion.ROUTE_MILLIS / 4))
        assertTrue("rising, was ${early.route}", early.route > 0.05f)
    }

    @Test
    fun `a call covers the song and the route, which come back after it`() {
        val motion = HudMotion()
        motion.update(connected = true, media = true, route = true, call = false, nowNanos = ms(0))
        motion.update(connected = true, media = true, route = true, call = true, nowNanos = ms(5_000))
        val call = motion.update(connected = true, media = true, route = true, call = true, nowNanos = ms(10_000))
        assertEquals(1f, call.call, 0f)
        assertEquals(0f, call.media, 0f)
        assertEquals(0f, call.route, 0f)
        motion.update(connected = true, media = true, route = true, call = false, nowNanos = ms(11_000))
        val after = motion.update(connected = true, media = true, route = true, call = false, nowNanos = ms(16_000))
        assertEquals(0f, after.call, 0f)
        assertEquals(1f, after.media, 0f)
        assertEquals(1f, after.route, 0f)
    }

    @Test
    // The HUD keeps 6 % of its width at each side, and as much between the route and the song.
    fun `the song sits in the middle alone and moves to the right column, smaller, beside the route`() {
        val w = 1440f
        val h = 542f
        val pad = 0.06f * w
        val alone = mediaBox(w, h, route = 0f)
        assertEquals(pad, alone.left, 1e-3f)
        assertEquals(w - pad, alone.right, 1e-3f)
        // 260 px on the 540 px AltScreen; the text keeps its own size, smaller beside the route.
        assertEquals(0.48f * h, alone.cover, 1e-3f)
        assertEquals(1f, alone.text, 1e-3f)
        val beside = mediaBox(w, h, route = 1f)
        assertEquals(w / 2 + pad / 2, beside.left, 1e-3f)
        assertEquals(w - pad, beside.right, 1e-3f)
        assertEquals(0.38f * h, beside.cover, 1e-3f)
        assertEquals(0.38f / 0.55f, beside.text, 1e-3f)
        val between = mediaBox(w, h, route = 0.5f)
        assertEquals((alone.left + beside.left) / 2, between.left, 1e-3f)
        assertEquals((alone.cover + beside.cover) / 2, between.cover, 1e-3f)
        assertEquals((alone.text + beside.text) / 2, between.text, 1e-3f)
    }

    @Test
    fun `the route takes the whole width alone and the left half beside the song`() {
        val w = 1440f
        val h = 542f
        val pad = 0.06f * w
        val alone = routeBox(w, h, media = 0f)
        assertEquals(pad, alone.left, 1e-3f)
        assertEquals(w - pad, alone.right, 1e-3f)
        val beside = routeBox(w, h, media = 1f)
        assertEquals(pad, beside.left, 1e-3f)
        assertEquals(w / 2 - pad / 2, beside.right, 1e-3f)
    }
}
