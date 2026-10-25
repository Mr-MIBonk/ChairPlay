package io.github.numberonebot.chairplay.config

/**
 * Each AltScreen group's part must lie inside the frame and leave some of itself inside its margins, all taken as they
 * will be after [changes]; an error goes on the key that is wrong, when it or the frame's size changed. A custom
 * rectangle saved now must fit the frame; one saved before is clamped into a frame made smaller since, as the app reads
 * it. [EditableSettings] checks with it.
 */
internal fun checkViewParts(changes: Map<String, String>, current: Map<String, String>, errors: MutableMap<String, String>) {
    fun value(key: String) = (changes[key] ?: current[key])?.trim()?.ifEmpty { null }
    val sizeChanged = "altscreen.width" in changes || "altscreen.height" in changes
    val width = value("altscreen.width")?.toIntOrNull() ?: DesktopConfig.DEFAULT_ALTSCREEN_WIDTH
    val height = value("altscreen.height")?.toIntOrNull() ?: DesktopConfig.DEFAULT_ALTSCREEN_HEIGHT
    for (view in AreaGroup.entries) {
        val keys = SAFE_EDGES.map { safeKey(view, it) }
        val custom = customKey(view)
        if (!sizeChanged && areaKey(view) !in changes && custom !in changes && keys.none { it in changes }) continue
        if (areaKey(view) in errors || custom in errors || keys.any { it in errors }) continue
        val rect = value(custom)?.let(AreaRect::parse)?.snapped()
        if (custom in changes && rect != null && !rect.fitsInto(width, height)) {
            errors[custom] = "The area runs past the $width × $height px frame. Move it or make it smaller."
            continue
        }
        val choice = value(areaKey(view))?.let(AreaChoice::of) ?: AreaChoice.Preset(defaultArea(view))
        val area = when (choice) {
            is AreaChoice.Preset -> choice.preset.area(width, height)
            // While no rectangle is saved, the group's default preset holds its place.
            AreaChoice.Custom -> rect?.clampedInto(width, height)?.area() ?: defaultArea(view).area(width, height)
        }
        val (top, bottom, left, right) = keys.map { value(it)?.toIntOrNull() ?: 0 }
        val across = left + right >= area.width
        val down = top + bottom >= area.height
        if (!across && !down) continue
        // On what made them too wide: a side of the axis that fails changed, else the part (the rectangle of a custom
        // one, or the choice), else the frame's size.
        val sides = (if (across) listOf("left", "right") else emptyList()) + (if (down) listOf("top", "bottom") else emptyList())
        val blamed = sides.map { safeKey(view, it) }.firstOrNull { it in changes }
            ?: custom.takeIf { it in changes && choice == AreaChoice.Custom }
            ?: areaKey(view).takeIf { it in changes }
            ?: listOfNotNull("altscreen.width".takeIf { across }, "altscreen.height".takeIf { down }).firstOrNull { it in changes }
            ?: safeKey(view, sides.first())
        errors[blamed] = "Leave some of the area inside the margins"
    }
}
