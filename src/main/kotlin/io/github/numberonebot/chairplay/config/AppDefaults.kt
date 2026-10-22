package io.github.numberonebot.chairplay.config

import io.github.numberonebot.chairplay.input.ControlsConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Writes the default settings.properties, controls.properties and car icon into the app folder [dir], each only
 * if it is not there yet, and returns the names written. deploy runs it, so the Wi-Fi can be filled in before
 * the first start; the app writes the same files on its own start too.
 */
fun writeAppDefaults(dir: Path): List<String> {
    Files.createDirectories(dir)
    val written = mutableListOf<String>()
    fun write(name: String, content: () -> Unit) {
        if (!dir.resolve(name).exists()) {
            content()
            written += name
        }
    }
    write(DesktopConfig.SETTINGS_FILE) { dir.resolve(DesktopConfig.SETTINGS_FILE).writeText(DesktopConfig.defaultSettingsText()) }
    write(ControlsConfig.FILE) { dir.resolve(ControlsConfig.FILE).writeText(ControlsConfig.defaultText()) }
    write(DesktopConfig.DEFAULT_ICON_FILE) { Files.write(dir.resolve(DesktopConfig.DEFAULT_ICON_FILE), WindowsIcon.render().data) }
    return written
}

/** `gradlew deploy` runs this with the app folder as its argument. */
fun main(args: Array<String>) {
    val dir = Path.of(args.singleOrNull() ?: error("usage: AppDefaults <app folder>"))
    val written = writeAppDefaults(dir)
    println(if (written.isEmpty()) "app folder already has its settings" else "wrote ${written.joinToString()} into $dir")
}
