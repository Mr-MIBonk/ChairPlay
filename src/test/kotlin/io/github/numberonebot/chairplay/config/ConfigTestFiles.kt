package io.github.numberonebot.chairplay.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** A settings file with just the Wi-Fi network, as a set-up app has. */
internal fun writeTestSettings(dir: Path) {
    Files.writeString(dir.resolve("settings.properties"), "wifi.ssid=Home\nwifi.passphrase=secret123\n")
}

/** A DesktopConfig read from a fresh folder whose settings file has the Wi-Fi network and [lines]. */
internal fun configWith(vararg lines: String): DesktopConfig {
    val dir = Files.createTempDirectory("chairplay-config")
    writeTestSettings(dir)
    Files.writeString(dir.resolve("settings.properties"), lines.joinToString("") { "$it\n" }, StandardOpenOption.APPEND)
    return DesktopConfig(dir)
}
