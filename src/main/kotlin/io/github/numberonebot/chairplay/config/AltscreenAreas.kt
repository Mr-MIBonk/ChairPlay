package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.AirPlayViewArea

/**
 * A part of the AltScreen's frame a view can show in, laid out for an 8:3 cluster, all of the full height: the whole
 * frame; five eighths at the left or the right, beside a dial; three eighths at the left, the middle or the right,
 * between dials or as a side panel. A width keeps to a multiple of 8, as DiPlay's streams do, and the middle one to an
 * even origin; [key] is how altscreen.<view>.area names it.
 */
enum class AreaPreset(val key: String, private val eighths: Int) {
    FULL("full", 8),
    LEFT5("left5", 5),
    RIGHT5("right5", 5),
    LEFT3("left3", 3),
    CENTER3("center3", 3),
    RIGHT3("right3", 3);

    /** The part of a [width]x[height] frame. */
    fun area(width: Int, height: Int): AirPlayViewArea {
        if (this == FULL) return AirPlayViewArea(width, height)
        val w = width * eighths / 8 / 8 * 8
        val x = when (this) {
            LEFT5, LEFT3 -> 0
            CENTER3 -> (width - w) / 4 * 2
            else -> width - w
        }
        return AirPlayViewArea(w, height, originX = x)
    }

    companion object {
        fun of(key: String): AreaPreset? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** The AltScreen's views the iPhone draws, each of which may have a part of the frame of its own; the HUD is ChairPlay's. */
val AREA_VIEWS = listOf(AltscreenContent.MAP, AltscreenContent.MAP_CARD, AltscreenContent.CARD)

/** The edges of a view's margins, as altscreen.<view>.safe.<edge> names them. */
val SAFE_EDGES = listOf("top", "bottom", "left", "right")

/** altscreen.<view>.area: the view's part of the frame, an [AreaPreset]'s key. */
fun areaKey(view: AltscreenContent) = "altscreen.${view.key}.area"

/** altscreen.<view>.safe.<edge>: a margin inside the view's part, in pixels. */
fun safeKey(view: AltscreenContent, edge: String) = "altscreen.${view.key}.safe.$edge"

/**
 * The AltScreen's view areas for a [width]x[height] frame, read through [value] (altscreen.<view>.area and .safe.*):
 * every [AreaPreset] in its order, the whole frame first, with altscreen.safe.* clipped to each as DiPlay builds them;
 * then every preset again for each view with margins of its own, those margins counted inside each part and given as
 * insets from the whole frame, as /info takes them. All are declared as the iPhone connects, so a view moves to any
 * part at once; only its margins wait for the next connection.
 */
class AltscreenViewParts(width: Int, height: Int, value: (String) -> String?) {
    // Where a view's presets start: 0 for the ones without margins of their own.
    private val offsets = mutableMapOf<AltscreenContent, Int>()
    private val presets = mutableMapOf<AltscreenContent, AreaPreset>()
    val areas: List<AirPlayViewArea>

    init {
        val list = AreaPreset.entries.map { it.area(width, height) }.toMutableList()
        for (view in AREA_VIEWS) {
            val areaText = value(areaKey(view))?.trim()?.ifEmpty { null }
            val preset = areaText?.let {
                AreaPreset.of(it) ?: throw IllegalArgumentException(
                    "${areaKey(view)} must be ${AreaPreset.entries.joinToString { preset -> preset.key }}, not $it")
            } ?: AreaPreset.FULL
            presets[view] = preset
            val margins = SAFE_EDGES.map { edge ->
                val key = safeKey(view, edge)
                value(key)?.trim()?.ifEmpty { null }?.let {
                    it.toIntOrNull()?.takeIf { margin -> margin >= 0 }
                        ?: throw IllegalArgumentException("$key must be a whole number of zero or more, not $it")
                }
            }
            if (margins.all { it == null }) continue
            val (top, bottom, left, right) = margins.map { it ?: 0 }
            // The margins must leave some of the narrowest part, as of every other.
            for (part in AreaPreset.entries.map { it.area(width, height) }) {
                require(top + bottom < part.height && left + right < part.width) {
                    "altscreen.${view.key}.safe.* leave nothing of its ${part.width}x${part.height} part"
                }
            }
            offsets[view] = list.size
            list += AreaPreset.entries.map { preset ->
                val area = preset.area(width, height)
                area.copy(safeArea = AirPlayInsets(top = area.originY + top, bottom = height - area.originY - area.height + bottom,
                    left = area.originX + left, right = width - area.originX - area.width + right))
            }
        }
        areas = list
    }

    /** The part [content] was set to show in as the frame was read; the HUD's is the whole frame. */
    fun presetOf(content: AltscreenContent): AreaPreset = presets[content] ?: AreaPreset.FULL

    /** The area [content] shows in when it shows in [preset]: among its own presets, or the first ones; the HUD's first. */
    fun indexOf(content: AltscreenContent, preset: AreaPreset): Int =
        if (content !in AREA_VIEWS) 0 else (offsets[content] ?: 0) + preset.ordinal

    /** The area [content] shows in, in the part it was set to. */
    fun indexOf(content: AltscreenContent): Int = indexOf(content, presetOf(content))
}

/**
 * updateViewArea for the AltScreen: DiPlay's AirPlayInfoPlist.viewAreaCommand, which it sends for the main screen
 * alone, with the AltScreen's display. The iPhone acted on the main screen's only with the animation and the adjacent
 * areas, and it moves the AltScreen's too.
 */
fun altscreenViewAreaCommand(index: Int, count: Int): Map<String, Any?> = linkedMapOf(
    "type" to "updateViewArea",
    "params" to linkedMapOf(
        "uuid" to AirPlayInfoPlist.ALT_UUID,
        "viewAreaIndex" to index,
        "animationDurationMillis" to VIEW_AREA_ANIMATION_MILLIS,
        "adjacentViewAreas" to (0 until count).filter { it != index },
    ),
)

/**
 * Moves CarPlay on the AltScreen to a view area: [send] the session's updateViewArea, then [showView], which asks for
 * the AltScreen's view again with showUI once the move is over ([AREA_MOVE_SETTLE_MILLIS]). Seen on an iPhone 15 Pro Max (AirPlay 870.14): after updateViewArea alone the
 * view stays blurred, mid-way through its move, and once asked for again it is drawn in the new area, the rest of the
 * frame near black. [count] is how many areas the connection declared.
 */
class AltscreenAreaMove(
    private val count: () -> Int,
    private val send: (Map<String, Any?>) -> Boolean,
    private val showView: () -> Unit,
) {
    /** False when the iPhone was not sent the move. */
    operator fun invoke(index: Int): Boolean {
        if (!send(altscreenViewAreaCommand(index, count()))) return false
        showView()
        return true
    }
}

/**
 * How long after updateViewArea the AltScreen's view is asked for again. Asked for 2 ms after, it stayed blurred: the
 * move's new codec configuration comes about 300 ms after the command, for its 300 ms; asked for a second after, the
 * view was drawn in the new area.
 */
const val AREA_MOVE_SETTLE_MILLIS = 1_000L

private const val VIEW_AREA_ANIMATION_MILLIS = 300
