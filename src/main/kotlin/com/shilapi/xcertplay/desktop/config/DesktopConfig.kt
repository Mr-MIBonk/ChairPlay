package com.shilapi.xcertplay.desktop.config

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.desktop.network.SavedWifi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.reader
import kotlin.io.path.writeText

/**
 * The receiver's settings and keys in [dir] (the exe's folder, see [appDirectory]): `settings.properties`
 * written by the user, `identity.properties` generated on first use, and one file per iPhone
 * pairing in `pairings/`, the desktop counterpart of upstream's AirPlayPersistence.
 */
data class WindowSize(val width: Int, val height: Int)

enum class Theme { WINDOWS, LIGHT, DARK, PHONE }

class DesktopConfig(private val dir: Path) {
    private val settings = loadSettings()
    val identity: AirPlayIdentity = loadIdentity()
    val pairings: PairingStore = loadPairings()
    val iphoneAddress: String? = settings.getProperty("iphone.address")?.trim()?.ifEmpty { null }
    val wifi = SavedWifi(
        ssid = settings.getProperty("wifi.ssid")?.ifBlank { null } ?: error("Set wifi.ssid in ${dir.resolve(SETTINGS)}"),
        passphrase = settings.getProperty("wifi.passphrase").orEmpty(),
        security = Iap2WirelessSecurity.valueOf(settings.getProperty("wifi.security", "WPA_WPA2").trim()),
    )

    /**
     * The car's name on the iPhone: in its CarPlay settings, in /info, in the iAP2 identification and as the
     * Bonjour service name, which holds at most 63 bytes of UTF-8. The window title does not follow it.
     */
    private val deviceName: String = run {
        val name = settings.getProperty("device.name")?.trim()?.ifEmpty { null } ?: DEFAULT_DEVICE_NAME
        require(name.toByteArray(Charsets.UTF_8).size <= 63 && name.none(Char::isISOControl)) {
            "device.name must be at most 63 bytes of UTF-8 without control characters, not \"$name\""
        }
        name
    }

    private val displayWidth = evenSetting("display.width", 1280)
    private val displayHeight = evenSetting("display.height", 720)
    private val fps = (positiveSetting("display.fps") ?: 60).also {
        require(it in 30..60) { "display.fps must be between 30 and 60, not $it" }
    }

    /**
     * The physical size in /info, from which iOS picks its scale: millimetres as given, else from
     * display.ppi, else upstream's 200 mm width. A missing height follows the aspect ratio, as /info would.
     */
    private val physicalMm: Pair<Int, Int> = run {
        val ppi = positiveSetting("display.ppi")
        val widthMm = positiveSetting("display.physical.width.mm")
        fun fromPpi(pixels: Int) = ppi?.let { Math.round(pixels * MM_PER_INCH / it).toInt() }
        val width = widthMm ?: fromPpi(displayWidth) ?: DEFAULT_WIDTH_MM
        val height = positiveSetting("display.physical.height.mm")
            ?: (if (widthMm == null) fromPpi(displayHeight) else null)
            ?: Math.round(width * displayHeight.toDouble() / displayWidth).toInt()
        width to height
    }

    /** The CarPlay window on the PC; the picture is scaled into it, so it needs not match the display. */
    val window = WindowSize(
        positiveSetting("window.width") ?: displayWidth,
        positiveSetting("window.height") ?: displayHeight,
    )

    /** windows follows the Windows dark mode; phone sends nothing and leaves it to the iPhone. */
    val theme: Theme = choiceSetting("display.theme", Theme.WINDOWS, Theme.entries.associateBy { it.name.lowercase() })

    /** Right-hand drive puts CarPlay's dock and controls on the right. */
    private val rightHandDrive = booleanSetting("display.right.hand.drive", false)

    /** One view area over the whole screen carrying the dock edge; auto declares none, as before. */
    private val viewAreas: List<AirPlayViewArea>? = choiceSetting(
        "display.dock", null,
        mapOf("auto" to null, "driver" to AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, "bottom" to AirPlayInfoPlist.DOCK_EDGE_BOTTOM),
    )?.let { edge -> listOf(AirPlayViewArea(displayWidth, displayHeight, dockEdge = edge)) }

    /** Pixels at each edge CarPlay keeps its controls out of; all zero declares none, as before. */
    private val safeArea: AirPlayInsets? = run {
        val insets = AirPlayInsets(
            top = nonNegativeSetting("display.safe.top"),
            bottom = nonNegativeSetting("display.safe.bottom"),
            left = nonNegativeSetting("display.safe.left"),
            right = nonNegativeSetting("display.safe.right"),
        )
        require(insets.left + insets.right < displayWidth) {
            "display.safe.left and display.safe.right leave no width on a $displayWidth-pixel screen"
        }
        require(insets.top + insets.bottom < displayHeight) {
            "display.safe.top and display.safe.bottom leave no height on a $displayHeight-pixel screen"
        }
        insets.takeIf { it != AirPlayInsets() }
    }

    private val safeAreaDrawOutside: Boolean? = settings.getProperty("display.safe.draw.outside")?.trim()?.ifEmpty { null }
        ?.let { booleanSetting("display.safe.draw.outside", true) }

    /** square: the picture fills the screen with right-angled corners (mainDisplayCornerMasks). */
    private val squareCorners: Boolean? = choiceSetting("display.corners", null, mapOf("rounded" to null, "square" to true))

    /** What the iPhone is told about its screen, for the start-up log. */
    fun displaySummary(): String {
        val (widthMm, heightMm) = physicalMm
        val ppi = Math.round(displayWidth * MM_PER_INCH / widthMm)
        return "display ${displayWidth}x$displayHeight px, ${widthMm}x$heightMm mm ($ppi ppi), $fps fps; " +
            "window ${window.width}x${window.height}"
    }

    /**
     * DiPlay's production values (CarPlayHostActivity.airPlayConfig); btMac is replaced by the radio's. The
     * CarPlay home screen shows the Windows icon where a car shows its maker's; tapping it minimizes the window.
     */
    val airPlay = AirPlayConfig(
        deviceName = deviceName,
        deviceId = deviceId(identity),
        btMac = deviceId(identity),
        sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(
            widthPixels = displayWidth,
            heightPixels = displayHeight,
            widthPhysicalMm = physicalMm.first,
            heightPhysicalMm = physicalMm.second,
            fps = fps,
            viewAreas = viewAreas,
            safeArea = safeArea,
            safeAreaDrawOutside = safeAreaDrawOutside,
            mainDisplayCornerMasks = squareCorners,
        ),
        rightHandDrive = rightHandDrive,
        port = 7000,
        hevc = false,
        microphone = true,
        // Without Opus the wireless iPhone opens no audio stream at all and plays on its own speaker.
        microphoneOpus = true,
        videoInCar = false,
        mainBufferedAudio = false,
        oemLabel = settings.getProperty("car.label")?.trim()?.ifEmpty { null } ?: DEFAULT_CAR_LABEL,
        icons = listOf(carIcon()),
    )

    fun mfiDirectory(): File = dir.resolve("offline-mfi").toFile()

    private fun loadSettings(): Properties {
        val file = dir.resolve(SETTINGS)
        if (!file.exists()) {
            Files.createDirectories(dir)
            file.writeText(
                "# Wi-Fi network shared by this PC and the iPhone.\nwifi.ssid=\nwifi.passphrase=\n" +
                    "# NONE, WEP, WPA_WPA2, WPA3_TRANSITION or WPA3_ONLY\nwifi.security=WPA_WPA2\n" +
                    "# Paired iPhone's Bluetooth address, needed only when several phones are paired.\niphone.address=\n" +
                    "# The car's name on the iPhone (CarPlay settings, the network); blank is DiPlayDesktop.\n" +
                    "device.name=\n" +
                    "# The car button on the CarPlay home screen: its label, and a square image in this folder\n" +
                    "# (sent at 192 pixels at most). carplay-icon.png is written with the Windows icon to replace.\n" +
                    "car.label=Windows\ncar.icon=carplay-icon.png\n" +
                    "# The CarPlay screen the iPhone draws, in pixels (positive even numbers), and its frame rate (30-60).\n" +
                    "display.width=1280\ndisplay.height=720\ndisplay.fps=60\n" +
                    "# Its physical size, from which iOS picks its scale (larger or smaller UI). Give the millimetres,\n" +
                    "# or a pixel density; blank uses 200 mm wide with the height from the aspect ratio.\n" +
                    "display.physical.width.mm=\ndisplay.physical.height.mm=\ndisplay.ppi=\n" +
                    "# The window on this PC; the picture is scaled into it. Blank uses the display size.\n" +
                    "window.width=\nwindow.height=\n" +
                    "# windows (follow the Windows theme), light, dark, or phone (the iPhone decides).\n" +
                    "display.theme=windows\n" +
                    "# true puts CarPlay's dock and controls on the right.\n" +
                    "display.right.hand.drive=false\n" +
                    "# Where the dock goes: auto (the iPhone decides), driver (the driver's side) or bottom.\n" +
                    "display.dock=auto\n" +
                    "# Pixels at each edge CarPlay keeps its controls out of, and whether it may still draw there.\n" +
                    "display.safe.top=0\ndisplay.safe.bottom=0\ndisplay.safe.left=0\ndisplay.safe.right=0\n" +
                    "display.safe.draw.outside=true\n" +
                    "# rounded (CarPlay rounds the picture's corners) or square.\n" +
                    "display.corners=rounded\n",
            )
        }
        return Properties().apply { file.reader().use(::load) }
    }

    /** A whole number of zero or more; blank means 0. */
    private fun nonNegativeSetting(key: String): Int {
        val raw = settings.getProperty(key)?.trim()?.ifEmpty { null } ?: return 0
        val value = raw.toIntOrNull()
        require(value != null && value >= 0) { "$key must be a whole number of zero or more, not $raw" }
        return value
    }

    private fun booleanSetting(key: String, default: Boolean): Boolean =
        when (val raw = settings.getProperty(key)?.trim()?.lowercase()?.ifEmpty { null } ?: return default) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("$key must be true or false, not $raw")
        }

    /** One of [choices] by its (lower-case) name; blank means [default]. */
    private fun <T> choiceSetting(key: String, default: T, choices: Map<String, T>): T {
        val raw = settings.getProperty(key)?.trim()?.lowercase()?.ifEmpty { null } ?: return default
        require(raw in choices) { "$key must be one of ${choices.keys.joinToString()}, not $raw" }
        @Suppress("UNCHECKED_CAST")
        return choices[raw] as T
    }

    /**
     * The icon on the CarPlay home screen, from car.icon in the app folder. The default file is written with
     * the Windows icon the first time, to be replaced; any square image is sent at [MAX_ICON_PIXELS] at most,
     * as upstream's AirPlayIconSizing does, because a large icon in /info stopped the iPhone connecting (#567).
     */
    private fun carIcon(): AirPlayIcon {
        val name = settings.getProperty("car.icon")?.trim()?.ifEmpty { null } ?: DEFAULT_ICON_FILE
        val file = dir.resolve(name)
        if (name == DEFAULT_ICON_FILE && !file.exists()) Files.write(file, WindowsIcon.render().data)
        require(file.exists()) { "car.icon: $file does not exist" }
        val image = runCatching { ImageIO.read(file.toFile()) }.getOrNull()
            ?: throw IllegalArgumentException("car.icon: $file is not an image ImageIO can read (PNG, JPEG, BMP, GIF)")
        require(image.width == image.height) { "car.icon: $file is ${image.width}x${image.height}; the icon must be square" }
        if (image.width <= MAX_ICON_PIXELS) return AirPlayIcon(image.width, image.height, png(image))
        val scaled = BufferedImage(MAX_ICON_PIXELS, MAX_ICON_PIXELS, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(image, 0, 0, MAX_ICON_PIXELS, MAX_ICON_PIXELS, null)
            dispose()
        }
        return AirPlayIcon(MAX_ICON_PIXELS, MAX_ICON_PIXELS, png(scaled))
    }

    private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().use {
        check(ImageIO.write(image, "png", it)) { "no PNG writer" }
        it.toByteArray()
    }

    /** An optional positive whole number; blank means unset. */
    private fun positiveSetting(key: String): Int? {
        val raw = settings.getProperty(key)?.trim()?.ifEmpty { null } ?: return null
        val value = raw.toIntOrNull()
        require(value != null && value > 0) { "$key must be a positive whole number, not $raw" }
        return value
    }

    private fun evenSetting(key: String, default: Int): Int {
        val value = settings.getProperty(key)?.trim()?.toIntOrNull() ?: default
        require(value > 0 && value % 2 == 0) { "$key must be a positive even number, not $value" }
        return value
    }

    private fun loadIdentity(): AirPlayIdentity {
        val file = dir.resolve("identity.properties")
        if (file.exists()) {
            val saved = Properties().apply { file.reader().use(::load) }
            return AirPlayIdentity(
                HEX.parseHex(saved.getProperty("privateKey")),
                HEX.parseHex(saved.getProperty("publicKey")),
                saved.getProperty("pairingId"),
            )
        }
        return AirPlayIdentity.generate().also {
            file.writeText(
                "privateKey=${HEX.formatHex(it.privateKey)}\npublicKey=${HEX.formatHex(it.publicKey)}\n" +
                    "pairingId=${it.pairingId}\n",
            )
        }
    }

    /** File names are the identifier's hex, as identifiers may hold characters Windows forbids. */
    private fun loadPairings(): PairingStore {
        val pairingDir = Files.createDirectories(dir.resolve("pairings"))
        var loaded = false
        val store = PairingStore { identifier, key ->
            if (loaded) pairingDir.resolve(HEX.formatHex(identifier.toByteArray())).writeText(HEX.formatHex(key))
        }
        for (file in pairingDir.listDirectoryEntries()) {
            store.save(String(HEX.parseHex(file.name)), HEX.parseHex(file.readText().trim()))
        }
        loaded = true
        return store
    }

    companion object {
        const val DEFAULT_DEVICE_NAME = "DiPlayDesktop"
        const val DEFAULT_ICON_FILE = "carplay-icon.png"
        private const val DEFAULT_CAR_LABEL = "Windows"
        private const val MAX_ICON_PIXELS = 192
        private const val SETTINGS = "settings.properties"
        private const val MM_PER_INCH = 25.4
        /** Upstream AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM. */
        private const val DEFAULT_WIDTH_MM = 200
        private val HEX = HexFormat.of()

        /** %APPDATA%\DiPlayDesktop: where everything lived before the app folder, and still does outside the exe. */
        val LEGACY_DIRECTORY: Path? = System.getenv("APPDATA")?.let { Path.of(it, "DiPlayDesktop") }

        /** What the app folder holds besides the program; `deploy` never replaces these. */
        val DATA_ENTRIES = listOf(SETTINGS, "controls.properties", DEFAULT_ICON_FILE, "identity.properties", "pairings", "offline-mfi")

        /**
         * The folder of the packaged exe, whose path jpackage's launcher puts in `jpackage.app-path`: the
         * program and its data live together there. Run any other way, the legacy folder.
         */
        fun appDirectory(appPath: String? = System.getProperty("jpackage.app-path")): Path =
            appPath?.let { Path.of(it).toAbsolutePath().parent }
                ?: checkNotNull(LEGACY_DIRECTORY) { "APPDATA is not set and the app was not started from its exe" }

        /**
         * Copies the settings, keys and pairings from [legacy] into a [target] that has none, so the iPhone
         * keeps knowing this PC as the same car. The legacy folder stays as it was, a backup.
         */
        fun adoptLegacy(target: Path, legacy: Path?): Boolean {
            if (legacy == null || target == legacy || !Files.isDirectory(legacy)) return false
            if (Files.exists(target.resolve(SETTINGS)) || Files.exists(target.resolve("identity.properties"))) return false
            val present = DATA_ENTRIES.map(legacy::resolve).filter(Files::exists)
            if (present.isEmpty()) return false
            for (entry in present) {
                Files.walk(entry).use { paths ->
                    paths.forEach { source ->
                        val copy = target.resolve(legacy.relativize(source).toString())
                        if (Files.isDirectory(source)) Files.createDirectories(copy) else {
                            Files.createDirectories(copy.parent)
                            Files.copy(source, copy)
                        }
                    }
                }
            }
            return true
        }

        /** DiPlayBootstrap.deviceId: a locally administered MAC derived from the AirPlay public key. */
        fun deviceId(identity: AirPlayIdentity): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).copyOf(6)
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        }
    }
}
