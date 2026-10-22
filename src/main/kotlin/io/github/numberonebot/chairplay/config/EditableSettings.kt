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
    private class Setting(val applies: Applies, val check: (String) -> String?)

    private val settings: Map<String, Setting> = mapOf(
        "wifi.ssid" to Setting(Applies.RECONNECT, ::checkSsid),
        // Checked together with the security in validate().
        "wifi.passphrase" to Setting(Applies.RECONNECT) { null },
        "wifi.security" to Setting(Applies.RECONNECT, ::checkSecurity),
        "ui.autoconnect" to Setting(Applies.RESTART, ::checkBoolean),
        "ui.autoshow" to Setting(Applies.NOW, ::checkBoolean),
        "altscreen.enabled" to Setting(Applies.RESTART, ::checkBoolean),
        "window.width" to Setting(Applies.RESTART, ::checkOptionalPositive),
        "window.height" to Setting(Applies.RESTART, ::checkOptionalPositive),
        "altscreen.width" to Setting(Applies.RESTART, ::checkPositiveEven),
        "altscreen.height" to Setting(Applies.RESTART, ::checkPositiveEven),
    )

    /** The keys the screens may change. */
    val keys: Set<String> get() = settings.keys

    /** When a change of [key], one of [keys], takes effect. */
    fun applies(key: String): Applies = settings.getValue(key).applies

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
        return errors
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

    private fun checkOptionalPositive(value: String): String? {
        if (value.isBlank()) return null
        val number = value.trim().toIntOrNull()
        return if (number != null && number in 1..MAX_PIXELS) null
            else "Enter a positive whole number of pixels (at most $MAX_PIXELS), or leave it blank"
    }

    private fun checkPositiveEven(value: String): String? {
        val number = value.trim().toIntOrNull()
        return if (number != null && number in 1..MAX_PIXELS && number % 2 == 0) null
            else "Enter a positive even number of pixels (at most $MAX_PIXELS)"
    }

    private const val MAX_PIXELS = 16384
}
