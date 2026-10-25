package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.AirPlayViewArea

/**
 * A part of the AltScreen's frame a view can show in, laid out for an 8:3 cluster, all of the full height: the whole
 * frame; five eighths at the left, the middle or the right, beside a dial or between two; four eighths, a half, at the
 * left, the middle or the right; three eighths at the left, the middle or the right, between dials or as a side panel.
 * A width keeps to a multiple of 8, as DiPlay's streams do, and the middle ones to an even origin; [key] is how
 * altscreen.<group>.area names it.
 */
enum class AreaPreset(val key: String, private val eighths: Int) {
    FULL("full", 8),
    LEFT5("left5", 5),
    CENTER5("center5", 5),
    RIGHT5("right5", 5),
    LEFT4("left4", 4),
    CENTER4("center4", 4),
    RIGHT4("right4", 4),
    LEFT3("left3", 3),
    CENTER3("center3", 3),
    RIGHT3("right3", 3);

    /** The part of a [width]x[height] frame. */
    fun area(width: Int, height: Int): AirPlayViewArea {
        if (this == FULL) return AirPlayViewArea(width, height)
        val w = width * eighths / 8 / 8 * 8
        val x = when (this) {
            LEFT5, LEFT4, LEFT3 -> 0
            CENTER5, CENTER4, CENTER3 -> (width - w) / 4 * 2
            else -> width - w
        }
        return AirPlayViewArea(w, height, originX = x)
    }

    companion object {
        fun of(key: String): AreaPreset? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * The AltScreen's views that share a part of the frame: the two maps (Map, Map + card) in one, the turn card in another.
 * [key] is how altscreen.<group>.area names it. The HUD is ChairPlay's and has none.
 */
enum class AreaGroup(val key: String) { MAP("map"), CARD("card") }

/** The group whose part of the frame this view shows in; null for the HUD. */
val AltscreenContent.areaGroup: AreaGroup?
    get() = when (this) {
        AltscreenContent.MAP, AltscreenContent.MAP_CARD -> AreaGroup.MAP
        AltscreenContent.CARD -> AreaGroup.CARD
        AltscreenContent.HUD -> null
    }

/** The edges of a group's margins, as altscreen.<group>.safe.<edge> names them. */
val SAFE_EDGES = listOf("top", "bottom", "left", "right")

/**
 * A group's part when altscreen.<group>.area is blank, as the owner set the views up (2026-10-24): the turn card in the
 * middle three eighths, between the dials; the maps on the whole frame.
 */
fun defaultArea(group: AreaGroup): AreaPreset = if (group == AreaGroup.CARD) AreaPreset.CENTER3 else AreaPreset.FULL

/** altscreen.<group>.area: the group's part of the frame, an [AreaPreset]'s key. */
fun areaKey(group: AreaGroup) = "altscreen.${group.key}.area"

/** altscreen.<group>.safe.<edge>: a margin inside the group's part, in pixels. */
fun safeKey(group: AreaGroup, edge: String) = "altscreen.${group.key}.safe.$edge"

/**
 * The AltScreen's view areas for a [width]x[height] frame, read through [value] (altscreen.<group>.area, .area.custom
 * and .safe.*): every [AreaPreset] in its order, the whole frame first, then each group's custom part (the maps', then
 * the turn card's), with altscreen.safe.* clipped to each as DiPlay builds them; then, for each group with margins of
 * its own, every preset and its custom part again, those margins counted inside each part and given as insets from the
 * whole frame, as /info takes them. All are declared as the iPhone connects, so a view moves to any part, its custom one
 * too, at once; its margins and its custom rectangle wait for the next connection.
 */
class AltscreenViewParts(width: Int, height: Int, value: (String) -> String?) {
    // Where a group's own block starts: none for the groups without margins of their own.
    private val offsets = mutableMapOf<AreaGroup, Int>()
    private val choices = mutableMapOf<AreaGroup, AreaChoice>()
    private val problems = mutableListOf<String>()
    val areas: List<AirPlayViewArea>

    /** What reading the parts had to put right, for the log: a saved rectangle clamped into a smaller frame. */
    val warnings: List<String> get() = problems

    init {
        val presets = AreaPreset.entries.map { it.area(width, height) }
        val customs = AreaGroup.entries.associateWith { group ->
            val choice = value(areaKey(group))?.trim()?.ifEmpty { null }?.let {
                AreaChoice.of(it) ?: throw IllegalArgumentException(
                    "${areaKey(group)} must be ${AreaPreset.entries.joinToString { preset -> preset.key }} or custom, not $it")
            } ?: AreaChoice.Preset(defaultArea(group))
            choices[group] = choice
            customArea(group, choice, width, height, value)
        }
        val list = (presets + AreaGroup.entries.map(customs::getValue)).toMutableList()
        for (view in AreaGroup.entries) {
            val margins = SAFE_EDGES.map { edge ->
                val key = safeKey(view, edge)
                value(key)?.trim()?.ifEmpty { null }?.let {
                    it.toIntOrNull()?.takeIf { margin -> margin >= 0 }
                        ?: throw IllegalArgumentException("$key must be a whole number of zero or more, not $it")
                }
            }
            // Margins all zero are none: the view keeps to the plain frame's areas, where a stream starts.
            if (margins.all { it == null || it == 0 }) continue
            val (top, bottom, left, right) = margins.map { it ?: 0 }
            // The margins must leave some of the view's own part.
            val custom = customs.getValue(view)
            val own = (choices.getValue(view) as? AreaChoice.Preset)?.preset?.area(width, height) ?: custom
            require(top + bottom < own.height && left + right < own.width) {
                "altscreen.${view.key}.safe.* leave nothing of its ${own.width}x${own.height} part"
            }
            offsets[view] = list.size
            list += (presets + custom).map { area ->
                // A part too narrow or low for them takes them shrunk with it, as a share of the view's own part.
                val across = if (left + right < area.width) 1.0 else area.width.toDouble() / own.width
                val down = if (top + bottom < area.height) 1.0 else area.height.toDouble() / own.height
                fun scaled(margin: Int, by: Double) = (margin * by).toInt()
                area.copy(safeArea = AirPlayInsets(
                    top = area.originY + scaled(top, down), bottom = height - area.originY - area.height + scaled(bottom, down),
                    left = area.originX + scaled(left, across), right = width - area.originX - area.width + scaled(right, across)))
            }
        }
        areas = list
    }

    /**
     * [group]'s custom part: its saved rectangle on the grid, clamped into a frame made smaller since; while none is
     * saved, its preset, or its default one while it is set to custom, holds the place.
     */
    private fun customArea(group: AreaGroup, choice: AreaChoice, width: Int, height: Int, value: (String) -> String?): AirPlayViewArea {
        val key = customKey(group)
        val text = value(key)?.trim()?.ifEmpty { null }
            ?: return ((choice as? AreaChoice.Preset)?.preset ?: defaultArea(group)).area(width, height)
        val rect = AreaRect.parseValid(text)?.snapped()
            ?: throw IllegalArgumentException("$key must be four whole numbers x, y, width, height, at least $MIN_AREA each way, not $text")
        val clamped = rect.clampedInto(width, height)
        if (clamped != rect) problems += "$key ($rect) does not fit the ${width}x$height altscreen; it is shown at $clamped"
        return clamped.area()
    }

    /** What [content]'s group was set to show in as the frame was read; the HUD's is the whole frame. */
    fun choiceOf(content: AltscreenContent): AreaChoice = content.areaGroup?.let(choices::get) ?: AreaChoice.Preset(AreaPreset.FULL)

    /**
     * The area [content] shows in when set to [choice]: among its group's own block, or the plain areas; the HUD's the
     * first.
     */
    fun indexOf(content: AltscreenContent, choice: AreaChoice): Int {
        val group = content.areaGroup ?: return 0
        val offset = offsets[group]
        return when (choice) {
            is AreaChoice.Preset -> (offset ?: 0) + choice.preset.ordinal
            AreaChoice.Custom -> offset?.let { it + AreaPreset.entries.size } ?: (AreaPreset.entries.size + group.ordinal)
        }
    }

    /** The area [content] shows in, in the part it was set to. */
    fun indexOf(content: AltscreenContent): Int = indexOf(content, choiceOf(content))
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
