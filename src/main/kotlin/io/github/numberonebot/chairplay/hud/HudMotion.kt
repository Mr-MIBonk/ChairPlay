package io.github.numberonebot.chairplay.hud

import kotlin.math.pow

/**
 * How present one part of the HUD is, from 0 (gone into the dark) to 1 (fully there), moving toward whether it
 * should show at a steady pace: [inMillis] to rise, [outMillis] to fade. It is linear: [HudMotion] eases it and
 * shapes it into its curves. Time counts only from the frame that set a new aim: the window draws nothing while
 * nothing moves, and the gap since its last frame must not count as time already spent rising.
 */
class Presence(private val inMillis: Long, private val outMillis: Long) {
    private var linear = 0f
    private var shown = false
    private var last = 0L

    /** Whether it is still on its way to its aim. */
    val moving: Boolean
        get() = linear != if (shown) 1f else 0f

    /** Aims at [shown] and steps to [nowNanos]; returns how far along it is, linear. */
    fun update(shown: Boolean, nowNanos: Long): Float {
        if (shown != this.shown) {
            this.shown = shown
            last = nowNanos
        }
        val millis = (nowNanos - last).coerceAtLeast(0) / 1_000_000f
        last = nowNanos
        linear = if (shown) {
            (linear + millis / inMillis).coerceAtMost(1f)
        } else {
            (linear - millis / outMillis).coerceAtLeast(0f)
        }
        return linear
    }
}

/**
 * The HUD for one frame: how far out of the dark each part is (0 gone, 1 fully there), where the song sits
 * ([mediaPlace]: 0 the middle, 1 its column) and how wide the route is ([routePlace]: 0 the whole width, 1 the left
 * half), and whether anything still moves, so the next frame is needed.
 */
data class HudMotionFrame(
    val waiting: Float,
    val media: Float,
    val route: Float,
    val call: Float,
    val mediaPlace: Float,
    val routePlace: Float,
    val moving: Boolean,
)

/**
 * The HUD's animations: the waiting line, the song, the route and a call each rise from the dark and fade back into
 * it. Before a session only the waiting line shows; a call covers the song and the route, which come back after it.
 *
 * Guidance and the song share the HUD in turns, so neither is drawn over the other: coming guidance first moves the
 * song from the middle into its column, smaller ([mediaBox]), and rises once the place is free; going guidance first
 * dissolves, and the song then comes back. Both run on the route's one presence, so going is coming played back. A
 * song that comes beside the route likewise first narrows it to its half ([routeBox]).
 */
class HudMotion {
    private val waiting = Presence(inMillis = 600, outMillis = 300)
    private val media = Presence(inMillis = MEDIA_MILLIS, outMillis = MEDIA_MILLIS)
    private val route = Presence(inMillis = ROUTE_MILLIS, outMillis = ROUTE_MILLIS)
    private val call = Presence(inMillis = 300, outMillis = 300)

    fun update(connected: Boolean, media: Boolean, route: Boolean, call: Boolean, nowNanos: Long): HudMotionFrame {
        val waiting = this.waiting.update(!connected, nowNanos)
        val m = this.media.update(connected && media && !call, nowNanos)
        val r = this.route.update(connected && route && !call, nowNanos)
        val c = this.call.update(connected && call, nowNanos)
        return HudMotionFrame(
            waiting = ease(waiting),
            // A part waits for the other to make room only as much as that other one is there.
            media = lerp(ease(m), afterMove(m), ease(r)),
            route = lerp(ease(r), afterMove(r), ease(m)),
            call = ease(c),
            mediaPlace = move(r),
            routePlace = move(m),
            moving = this.waiting.moving || this.media.moving || this.route.moving || this.call.moving,
        )
    }

    companion object {
        const val MEDIA_MILLIS = 800L
        const val ROUTE_MILLIS = 1_000L

        /** The share of a part's time the other part takes to move aside, and the share it then takes to rise. */
        private const val MOVE = 0.5f
        private const val RISE_FROM = 0.4f

        private fun move(t: Float) = ease((t / MOVE).coerceAtMost(1f))
        private fun afterMove(t: Float) = ease(((t - RISE_FROM) / (1 - RISE_FROM)).coerceIn(0f, 1f))
    }
}

/** Starts and stops softly: cubic in and out. */
internal fun ease(t: Float): Float = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2

/** A horizontal span of the HUD and, for the song, its cover's size. */
data class HudBox(val left: Float, val right: Float, val cover: Float = 0f)

/**
 * The song's span and cover in a [width] x [height] HUD, as it lays out, alone in the middle; beside the route it is
 * this layout scaled down into its column ([songPlacement]).
 */
fun mediaBox(width: Float, height: Float): HudBox {
    val pad = HUD_SIDE * width
    return HudBox(left = pad, right = width - pad, cover = MEDIA_COVER_ALONE * height)
}

/**
 * The route's span in a [width] x [height] HUD: the whole width alone, the left half beside the song. The route lays
 * out in the whole width; beside the song it is that layout scaled down into the half ([routePlacement]).
 */
fun routeBox(width: Float, height: Float, media: Float): HudBox {
    val pad = HUD_SIDE * width
    return HudBox(left = pad, right = lerp(width - pad, width / 2 - pad / 2, media))
}

internal fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

/** The margin above and below the HUD, in its height. */
internal const val HUD_PAD = 0.06f

/** The margin at each side of the HUD, and between the route and the song, in its width. */
internal const val HUD_SIDE = 0.06f
// 260 px on the 540 px AltScreen.
private const val MEDIA_COVER_ALONE = 0.48f

/** The song's scale beside the route: its cover 0.38 of the HUD's height. */
internal const val MEDIA_BESIDE = 0.38f / MEDIA_COVER_ALONE
