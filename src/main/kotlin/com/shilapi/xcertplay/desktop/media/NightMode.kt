package com.shilapi.xcertplay.desktop.media

import com.shilapi.xcertplay.desktop.config.Theme
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * The night mode to send the iPhone (AirPlaySession.setNightMode) for [theme], or null to leave it to the
 * iPhone. [windowsDark] is asked only for [Theme.WINDOWS]; an unknown answer counts as light.
 */
fun nightMode(theme: Theme, windowsDark: () -> Boolean?): Boolean? = when (theme) {
    Theme.LIGHT -> false
    Theme.DARK -> true
    Theme.WINDOWS -> windowsDark() ?: false
    Theme.PHONE -> null
}

/** Whether Windows apps use the dark theme (Settings > Personalization > Colors), or null if it cannot be read. */
fun windowsAppsDark(): Boolean? = runCatching {
    Advapi32Util.registryGetIntValue(
        WinReg.HKEY_CURRENT_USER,
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "AppsUseLightTheme",
    ) == 0
}.getOrNull()
