// Switches for the CarPlay Ultra experiments (desktop-docs/carplay-ultra-experiments.md): each changes one thing the
// iPhone is told, for one start of the app.
package io.github.numberonebot.chairplay.config

/** Overrides the receiver's AirPlay version. */
const val SOURCE_VERSION_VARIABLE = "CHAIRPLAY_SOURCE_VERSION"

/** 0 leaves route guidance out of the Bluetooth link's iAP2 identification. */
const val BOOTSTRAP_ROUTE_GUIDANCE_VARIABLE = "CHAIRPLAY_BOOTSTRAP_ROUTE_GUIDANCE"

/** DiPlay's production value (CarPlayHostActivity.airPlayConfig). */
private const val DEFAULT_SOURCE_VERSION = "950.7.1"

/**
 * The AirPlay version the receiver tells the iPhone in /info, the Bonjour TXT record and CarPlayStartSession:
 * [SOURCE_VERSION_VARIABLE] when set, else DiPlay's.
 */
fun sourceVersion(environment: Map<String, String> = System.getenv()): String {
    val value = environment[SOURCE_VERSION_VARIABLE]?.trim()?.ifEmpty { null } ?: return DEFAULT_SOURCE_VERSION
    require(Regex("\\d+(\\.\\d+)*").matches(value)) { "$SOURCE_VERSION_VARIABLE must be numbers joined by dots, such as 320.17, not $value" }
    return value
}

/** 0 leaves CarPlayAvailability and CarPlayStartSession out of the identification on every link. */
const val CARPLAY_AVAILABILITY_VARIABLE = "CHAIRPLAY_CARPLAY_AVAILABILITY"

/** Whether the Bluetooth link declares route guidance: unless [BOOTSTRAP_ROUTE_GUIDANCE_VARIABLE] is 0. */
fun bootstrapRouteGuidance(environment: Map<String, String> = System.getenv()): Boolean =
    switchOn(BOOTSTRAP_ROUTE_GUIDANCE_VARIABLE, environment)

/** Whether the identification declares 0x4300 and 0x4301: unless [CARPLAY_AVAILABILITY_VARIABLE] is 0. */
fun carPlayAvailability(environment: Map<String, String> = System.getenv()): Boolean =
    switchOn(CARPLAY_AVAILABILITY_VARIABLE, environment)

private fun switchOn(variable: String, environment: Map<String, String>): Boolean =
    when (val value = environment[variable]?.trim()) {
        null, "", "1" -> true
        "0" -> false
        else -> throw IllegalArgumentException("$variable must be 0 or 1, not $value")
    }
