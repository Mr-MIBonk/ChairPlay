package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity

/** When a changed setting takes effect. */
enum class Applies {
    /** At once. */
    NOW,

    /** With the next connection to the iPhone; Reconnect now gets there sooner. */
    RECONNECT,

    /** When the app starts again. */
    RESTART,
}

/**
 * The settings the start and settings screens change, how each is checked and when a change takes effect. The
 * checks match [DesktopConfig]'s, so a saved value never stops the next start, and add what a Wi-Fi network allows.
 */
object EditableSettings {
    /** [caseless]: a switch or a choice, saved in lower case as the app reads it. */
    private class Setting(val applies: Applies, val caseless: Boolean = false, val check: (String) -> String?)

    private fun switch(applies: Applies) = Setting(applies, caseless = true, check = ::checkBoolean)
    private fun choice(vararg values: String) = Setting(Applies.RESTART, caseless = true) { value ->
        if (value.trim().lowercase() in values) null else "Must be one of ${values.joinToString()}"
    }

    // Everything the app reads only as it starts applies after a restart: the iPhone's, the screens', and the sound's.
    private val settings: Map<String, Setting> = mapOf(
        "wifi.ssid" to Setting(Applies.RECONNECT, check = ::checkSsid),
        // Checked together with the security in validate().
        "wifi.passphrase" to Setting(Applies.RECONNECT) { null },
        "wifi.security" to Setting(Applies.RECONNECT, check = ::checkSecurity),
        "ui.autoconnect" to switch(Applies.RESTART),
        "ui.autoshow" to switch(Applies.NOW),
        "ui.controls.keys" to switch(Applies.NOW),
        "device.name" to Setting(Applies.RESTART, check = ::checkDeviceName),
        "iphone.address" to Setting(Applies.RESTART, check = ::checkAddress),
        "iphone.join.seconds" to Setting(Applies.RESTART) { checkPositive(it, "of seconds") },
        "display.width" to Setting(Applies.RESTART, check = ::checkPositiveEven),
        "display.height" to Setting(Applies.RESTART, check = ::checkPositiveEven),
        "display.fps" to Setting(Applies.RESTART, check = ::checkFps),
        "display.physical.width.mm" to Setting(Applies.RESTART, check = ::checkOptionalPositive),
        "display.physical.height.mm" to Setting(Applies.RESTART, check = ::checkOptionalPositive),
        "display.ppi" to Setting(Applies.RESTART, check = ::checkOptionalPositive),
        "display.theme" to choice("windows", "light", "dark", "phone"),
        "display.dock" to choice("auto", "driver", "bottom"),
        "display.right.hand.drive" to switch(Applies.RESTART),
        "display.safe.draw.outside" to switch(Applies.RESTART),
        "display.corners" to choice("rounded", "square"),
        "display.safe.top" to Setting(Applies.RESTART, check = ::checkNonNegative),
        "display.safe.bottom" to Setting(Applies.RESTART, check = ::checkNonNegative),
        "display.safe.left" to Setting(Applies.RESTART, check = ::checkNonNegative),
        "display.safe.right" to Setting(Applies.RESTART, check = ::checkNonNegative),
        "window.width" to Setting(Applies.RESTART) { checkOptionalSize(it, MAIN_WINDOW_MIN_SIZE.width) },
        "window.height" to Setting(Applies.RESTART) { checkOptionalSize(it, MAIN_WINDOW_MIN_SIZE.height) },
        "altscreen.enabled" to switch(Applies.RESTART),
        "altscreen.width" to Setting(Applies.RESTART, check = ::checkPositiveEven),
        "altscreen.height" to Setting(Applies.RESTART, check = ::checkPositiveEven),
        "altscreen.content" to choice("map", "map-card", "hud", "card"),
        "altscreen.cards" to choice("auto", "left", "right", "top", "off"),
        "altscreen.safe.top" to Setting(Applies.RESTART, check = ::checkNonNegative),
        "altscreen.safe.bottom" to Setting(Applies.RESTART, check = ::checkNonNegative),
        "altscreen.safe.left" to Setting(Applies.RESTART, check = ::checkNonNegative),
        "altscreen.safe.right" to Setting(Applies.RESTART, check = ::checkNonNegative),
        // "default" or a device's name; a name no device has stops the next start, as a typo in the file would.
        "audio.output" to Setting(Applies.RESTART) { null },
        "audio.input" to Setting(Applies.RESTART) { null },
        "audio.echo.cancellation" to switch(Applies.RESTART),
    )

    /** The keys the screens may change. */
    val keys: Set<String> get() = settings.keys

    /** When a change of [key], one of [keys], takes effect. */
    fun applies(key: String): Applies = settings.getValue(key).applies

    /** Whether [key], one of [keys], is a switch or a choice, saved in lower case. */
    fun caseless(key: String): Boolean = settings[key]?.caseless ?: false

    /**
     * The problems with [changes] to the settings [current], by key, as sentences for the screen; empty when all can
     * be saved. The password is checked against the security it goes with, changed or not.
     */
    fun validate(changes: Map<String, String>, current: Map<String, String>): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        for ((key, value) in changes) {
            val setting = settings[key]
            if (setting == null) {
                errors[key] = "$key cannot be changed here; edit settings.properties instead"
                continue
            }
            setting.check(value)?.let { errors[key] = it }
        }
        if (("wifi.passphrase" in changes || "wifi.security" in changes) && "wifi.security" !in errors) {
            val security = (changes["wifi.security"] ?: current["wifi.security"] ?: "WPA_WPA2").trim()
            val passphrase = changes["wifi.passphrase"] ?: current["wifi.passphrase"].orEmpty()
            checkPassphrase(passphrase, security)?.let { errors["wifi.passphrase"] = it }
        }
        for (screen in listOf("display", "altscreen")) checkSafeArea(screen, changes, current, errors)
        return errors
    }

    /**
     * A safe area must leave some of [screen]'s picture, as [DesktopConfig] requires: the two sides of each axis
     * together less than the size, both taken as they will be after [changes]. The error goes on a side that changed.
     */
    private fun checkSafeArea(screen: String, changes: Map<String, String>, current: Map<String, String>, errors: MutableMap<String, String>) {
        fun value(key: String) = (changes[key] ?: current[key])?.trim()?.ifEmpty { null }
        for ((first, second, size, axis) in listOf(
            listOf("left", "right", "width", "left and right"),
            listOf("top", "bottom", "height", "top and bottom"),
        )) {
            val keys = listOf("$screen.safe.$first", "$screen.safe.$second", "$screen.$size")
            if (keys.none { it in changes } || keys.any { it in errors }) continue
            val a = value(keys[0])?.toIntOrNull() ?: 0
            val b = value(keys[1])?.toIntOrNull() ?: 0
            val total = value(keys[2])?.toIntOrNull() ?: continue
            if (a + b >= total) {
                val key = keys.take(2).firstOrNull { it in changes } ?: keys[0]
                errors[key] = "The $axis safe areas together must be less than the $size ($total px)"
            }
        }
    }

    private fun checkSsid(value: String): String? = when {
        value.isBlank() -> "The network name is empty"
        value.any(Char::isISOControl) -> "The network name cannot hold control characters"
        value.toByteArray(Charsets.UTF_8).size > 32 -> "A network name is at most 32 bytes (fewer letters outside English)"
        else -> null
    }

    private val securities = Iap2WirelessSecurity.entries.map { it.name }

    private fun checkSecurity(value: String): String? =
        if (value.trim() in securities) null else "The security must be one of ${securities.joinToString()}"

    private fun checkPassphrase(value: String, security: String): String? {
        val hex = value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        val ascii = value.all { it in ' '..'~' }
        return when (security) {
            "NONE" -> null
            "WEP" -> if ((ascii && value.length in setOf(5, 13)) || (hex && value.length in setOf(10, 26))) null
                else "A WEP password is 5 or 13 characters, or 10 or 26 hexadecimal digits"
            else -> if ((ascii && value.length in 8..63) || (hex && value.length == 64)) null
                else "A WPA password is 8 to 63 characters (English letters, digits and symbols), or 64 hexadecimal digits"
        }
    }

    private fun checkBoolean(value: String): String? =
        if (value.trim().lowercase() in setOf("true", "false")) null else "Must be true or false"

    private fun checkOptionalSize(value: String, min: Int): String? {
        if (value.isBlank()) return null
        val number = value.trim().toIntOrNull()
        return when {
            number == null || number < 1 -> "Enter a positive whole number of pixels, or leave it blank"
            number < min -> "The main window is at least $min pixels"
            number > MAX_PIXELS -> "The main window is at most $MAX_PIXELS pixels"
            else -> null
        }
    }

    private fun checkDeviceName(value: String): String? = when {
        value.any(Char::isISOControl) -> "The name cannot hold control characters"
        value.trim().toByteArray(Charsets.UTF_8).size > 63 -> "The name is at most 63 bytes (fewer letters outside English)"
        else -> null
    }

    private val address = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")

    private fun checkAddress(value: String): String? =
        if (value.isBlank() || address.matches(value.trim())) null
        else "Enter a Bluetooth address such as 01:23:45:67:89:AB, or leave it blank"

    private fun checkPositive(value: String, unit: String): String? =
        if ((value.trim().toIntOrNull() ?: 0) > 0) null else "Enter a positive whole number $unit"

    private fun checkOptionalPositive(value: String): String? =
        if (value.isBlank() || (value.trim().toIntOrNull() ?: 0) > 0) null else "Enter a positive whole number, or leave it blank"

    private fun checkNonNegative(value: String): String? =
        if ((value.trim().toIntOrNull() ?: -1) >= 0) null else "Enter zero or more pixels"

    private fun checkFps(value: String): String? =
        if (value.trim().toIntOrNull() in 30..60) null else "Enter a frame rate from 30 to 60"

    private fun checkPositiveEven(value: String): String? {
        val number = value.trim().toIntOrNull()
        return if (number != null && number in 1..MAX_PIXELS && number % 2 == 0) null
            else "Enter a positive even number of pixels (at most $MAX_PIXELS)"
    }

    private const val MAX_PIXELS = 16384
}
