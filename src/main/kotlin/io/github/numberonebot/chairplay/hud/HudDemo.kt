package io.github.numberonebot.chairplay.hud

import io.github.numberonebot.chairplay.data.CarPlayDataSnapshot
import io.github.numberonebot.chairplay.data.DrivingSide
import io.github.numberonebot.chairplay.data.JunctionType
import io.github.numberonebot.chairplay.data.Maneuver
import io.github.numberonebot.chairplay.data.ManeuverType
import io.github.numberonebot.chairplay.data.NowPlaying
import io.github.numberonebot.chairplay.data.RouteGuidance
import io.github.numberonebot.chairplay.data.RouteGuidanceState

/**
 * A made-up route for watching the HUD's maneuvers without an iPhone: CHAIRPLAY_HUD_DEMO=1 in the environment shows
 * it in the AltScreen's HUD instead of the bus's data, CHAIRPLAY_HUD_DEMO=media with a song beside it. Each maneuver
 * is approached from [START_METERS] in [STEP_MILLIS], so the arrow fills and blinks before the next one slides in;
 * the route starts over after the last. CHAIRPLAY_HUD_DEMO=cycle plays the HUD's own animations instead, over and
 * over in [CYCLE_MILLIS]: the song alone, guidance coming beside it, the song alone again and nothing. With
 * CHAIRPLAY_HUD_DEMO_SHOTS=1 the first lap saves screenshots too.
 */
class HudDemo(val withMedia: Boolean, val takeShots: Boolean = false, val cycle: Boolean = false) {
    /** How many maneuvers a lap has. */
    val steps: Int get() = ROUTE.size

    /** The bus as it would be [elapsedMillis] into the demo. */
    fun snapshot(elapsedMillis: Long): CarPlayDataSnapshot {
        val full = route(elapsedMillis)
        if (!cycle) return full
        val into = elapsedMillis.coerceAtLeast(0) % CYCLE_MILLIS
        return CarPlayDataSnapshot(
            route = full.route.takeIf { into in CYCLE_ROUTE },
            nowPlaying = full.nowPlaying.takeIf { into < CYCLE_SONG_END },
        )
    }

    private fun route(elapsedMillis: Long): CarPlayDataSnapshot {
        val step = (elapsedMillis.coerceAtLeast(0) / STEP_MILLIS).toInt()
        val into = elapsedMillis.coerceAtLeast(0) % STEP_MILLIS
        val (road, maneuver) = ROUTE[step % ROUTE.size]
        val meters = START_METERS * (STEP_MILLIS - into) / STEP_MILLIS
        val route = RouteGuidance(
            state = RouteGuidanceState.ROUTE_SET,
            currentRoad = "Demo Street",
            destination = "Demo destination",
            remainingSeconds = (ROUTE.size - step % ROUTE.size) * 60L,
            remainingMeters = (ROUTE.size - step % ROUTE.size) * START_METERS,
            distanceToManeuverMeters = meters,
            nextManeuver = maneuver.copy(index = step, afterRoad = road, description = road),
            sourceName = "ChairPlay demo",
        )
        val song = NowPlaying(title = "Demo song", artist = "ChairPlay", album = "Maneuvers", playing = true, appName = "Demo")
        return CarPlayDataSnapshot(route = route, nowPlaying = song.takeIf { withMedia })
    }

    /** When, within each step of the first lap, a screenshot shows the transition, the filling arrow and the blink. */
    val shots: List<Pair<String, Long>> = listOf("moving" to 500L, "filling" to 4_500L, "blinking" to 6_600L)

    /** When, in the first cycle, a screenshot catches each of the HUD's animations half way and the still between. */
    val cycleShots: List<Pair<String, Long>> = listOf(
        "cycle-1-alone" to 2_000L, "cycle-2-song-moving-aside" to 4_250L, "cycle-3-guidance-rising" to 4_750L,
        "cycle-4-beside" to 6_000L, "cycle-5-guidance-dissolving" to 10_250L, "cycle-6-song-coming-back" to 10_750L,
        "cycle-7-alone" to 12_000L, "cycle-8-fading" to 14_350L,
    )

    /**
     * Starts the demo's clock on a daemon thread, which calls [repaint] every [FRAME_MILLIS] and, with [takeShots],
     * [screenshot] with a name at each of the [shots] of the first lap. Returns the bus snapshot of the moment.
     */
    fun start(repaint: () -> Unit, screenshot: (String) -> Unit): () -> CarPlayDataSnapshot {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val due = when {
            !takeShots -> emptyList()
            cycle -> cycleShots
            else -> (0 until steps).flatMap { step -> shots.map { (name, at) -> "${step + 1}-$name" to step * STEP_MILLIS + at } }
        }.toMutableList()
        Thread({
            while (true) {
                Thread.sleep(FRAME_MILLIS)
                repaint()
                val now = elapsed()
                while (due.isNotEmpty() && due.first().second <= now) screenshot(due.removeAt(0).first)
            }
        }, "hud-demo").apply { isDaemon = true }.start()
        return { snapshot(elapsed()) }
    }

    companion object {
        const val STEP_MILLIS = 7_000L
        const val START_METERS = 400L
        /** How often the demo's distance is drawn again. */
        const val FRAME_MILLIS = 100L
        /** The cycle's length: the song from its start to [CYCLE_SONG_END], guidance over [CYCLE_ROUTE]. */
        const val CYCLE_MILLIS = 16_000L
        private const val CYCLE_SONG_END = 14_000L
        private val CYCLE_ROUTE = 4_000L until 10_000L

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): HudDemo? {
            val shots = environment["CHAIRPLAY_HUD_DEMO_SHOTS"]?.trim() == "1"
            return when (environment["CHAIRPLAY_HUD_DEMO"]?.trim()?.lowercase()) {
                null, "", "0", "false" -> null
                "media" -> HudDemo(withMedia = true, takeShots = shots)
                "cycle" -> HudDemo(withMedia = true, takeShots = shots, cycle = true)
                else -> HudDemo(withMedia = false, takeShots = shots)
            }
        }

        private fun turn(type: ManeuverType, exit: Int? = null, roads: List<Int> = emptyList(), junction: JunctionType = JunctionType.INTERSECTION) =
            Maneuver(
                index = 0, typeCode = type.code, exitAngle = exit, junctionAngles = roads, junctionType = junction,
                drivingSide = DrivingSide.RIGHT, distanceBetweenMeters = 300,
            )

        private val ROUTE = listOf(
            "Left Street" to turn(ManeuverType.LEFT_TURN, -90, listOf(-90, 0, 90)),
            "Ring Road" to turn(ManeuverType.ROUNDABOUT_EXIT_2, 0, listOf(-90, 0, 90, 180), JunctionType.ROUNDABOUT),
            "Bend Avenue" to turn(ManeuverType.SLIGHT_RIGHT_TURN, 35, listOf(35, 0)),
            "Circle Lane" to turn(ManeuverType.ROUNDABOUT_EXIT_3, 90, listOf(-90, 0, 90, 180), JunctionType.ROUNDABOUT),
            "Sharp Corner" to turn(ManeuverType.SHARP_LEFT_TURN, -135, listOf(-135, 0, 90)),
            "Back Road" to turn(ManeuverType.U_TURN),
            "Exit 12" to turn(ManeuverType.OFF_RAMP, 25),
            "Motorway" to turn(ManeuverType.CHANGE_HIGHWAY_LEFT),
            "Right Street" to turn(ManeuverType.RIGHT_TURN, 90, listOf(90, 0)),
            "Demo destination" to turn(ManeuverType.ARRIVE_AT_DESTINATION),
        )
    }
}
