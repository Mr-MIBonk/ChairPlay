package io.github.numberonebot.chairplay.hud

import io.github.numberonebot.chairplay.data.ManeuverType

// The HUD's fonts, colours and sizes.

internal const val REGULAR = "regular"
internal const val BOLD = "bold"
internal const val WIDE = "wide"
internal const val WIDE_FALLBACK = "wide-fallback"
internal const val STATUS = "status"

/** A face of the HUD's text, from the app's fonts/. */
internal class HudFont(val name: String, val file: String, val fallback: String? = null)

/** Inter body, wide Roboto Flex accents, and Reddit Sans Bold for status text, as in the main window. */
internal val HUD_FONTS = listOf(
    HudFont(REGULAR, "Inter-Regular.ttf"),
    HudFont(BOLD, "Inter-SemiBold.ttf"),
    HudFont(WIDE_FALLBACK, "Inter-Bold.ttf"),
    HudFont(WIDE, "RobotoFlexWide-SemiBold.ttf", WIDE_FALLBACK),
    // Static weight 700 from RedditSans-Variable.ttf: NanoVG does not select variable font weights.
    HudFont(STATUS, "RedditSans-Bold.ttf", WIDE_FALLBACK),
)
/** About 60 frames a second while a part rises or fades; the parts move by elapsed time. */
internal const val FRAME_MILLIS = 16L
/** The size a part starts rising from, and fades down to, in its full size. */
internal const val EMERGE_SCALE = 0.94f
/** The maneuver picture's height in the window's. */
internal const val MANEUVER_HEIGHT = 0.72f
internal const val WHITE = 0xFFFFFFFF.toInt()
internal const val GREY = 0xFF9A9AA0.toInt()
internal const val DIM = 0xFF2C2C2E.toInt()
internal const val GREEN = 0xFF30D158.toInt()
internal const val CALL_GROUND = 0xFF1C1C1E.toInt()
internal val ARRIVALS = setOf(
    ManeuverType.ARRIVE_AT_DESTINATION, ManeuverType.ARRIVE_AT_DESTINATION_LEFT, ManeuverType.ARRIVE_AT_DESTINATION_RIGHT,
    ManeuverType.ARRIVE_END_OF_NAVIGATION, ManeuverType.ARRIVE_END_OF_DIRECTIONS,
)
