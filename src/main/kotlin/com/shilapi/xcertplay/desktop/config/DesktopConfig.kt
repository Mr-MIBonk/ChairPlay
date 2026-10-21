package com.shilapi.xcertplay.desktop.config

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
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
import kotlin.io.path.appendText
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

/** What the altscreen shows: one of the cluster contents the iPhone lists in its altScreenURLs. */
enum class AltscreenContent(val key: String, val cluster: CarPlayClusterDisplay.Content) {
    MAP("map", CarPlayClusterDisplay.Content.MAP),
    CARD("card", CarPlayClusterDisplay.Content.TURN_CARD),
    MAP_CARD("map-card", CarPlayClusterDisplay.Content.INSTRUMENTS);

    fun next(): AltscreenContent = entries[(ordinal + 1) % entries.size]
}

/**
 * Where CarPlay lays out the navigation app's ETA and turn card on the altscreen. DashBoard reads it from the
 * cluster URL for every app (notes of the MHI2Q CarPlay project); without one it chooses, and a third-party app
 * on a wide cluster keeps a column at the left free, even showing the map alone.
 */
enum class AltscreenCards(val key: String, val query: String) {
    AUTO("auto", ""),
    LEFT("left", "showETA=yes&maneuverLayout=leftAligned"),
    RIGHT("right", "showETA=yes&maneuverLayout=rightAligned"),
    TOP("top", "showETA=yes&maneuverLayout=topAligned"),
    OFF("off", "showETA=no&maneuverLayout=no");

    fun next(): AltscreenCards = entries[(ordinal + 1) % entries.size]
}

/** The cluster URL the iPhone is asked for: [content] with the [cards] layout. */
fun altscreenUrl(content: AltscreenContent, cards: AltscreenCards): String =
    if (cards.query.isEmpty()) content.cluster.url else "${content.cluster.url}?${cards.query}"

/** The main window's title, with the size the iPhone draws the CarPlay screen at. */
fun mainWindowTitle(stream: WindowSize): String = "DiPlayDesktop · ${stream.width}×${stream.height}"

/**
 * The AltScreen window's title, with its stream size; without a route every mode looks alike, so the title says
 * which one is on. AltScreen is Apple's name for it (the altScreen feature and altScreenURLs).
 */
fun altscreenTitle(stream: WindowSize, content: AltscreenContent, cards: AltscreenCards): String =
    "DiPlayDesktop AltScreen · ${stream.width}×${stream.height} · ${content.key} · cards ${cards.key}"

/** The second CarPlay screen, a car's instrument cluster: the stream's [size] and what it starts with. */
data class Altscreen(val size: WindowSize, val content: AltscreenContent, val cards: AltscreenCards = AltscreenCards.AUTO)

class DesktopConfig(private val dir: Path) {
    private val settings = loadSettings()
    val identity: AirPlayIdentity = loadIdentity()
    val pairings: PairingStore = loadPairings()
    val iphoneAddress: String? = settings.getProperty("iphone.address")?.trim()?.ifEmpty { null }
    val wifi = SavedWifi(
        ssid = settings.getProperty("wifi.ssid")?.ifBlank { null } ?: error("Set wifi.ssid in ${dir.resolve(SETTINGS_FILE)}"),
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

    val displayWidth = evenSetting("display.width", 1280)
    val displayHeight = evenSetting("display.height", 720)
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

    /** audio.output and audio.input as written: blank or "default" for the Windows default, else a device name. */
    val audioOutput: String? = settings.getProperty("audio.output")
    val audioInput: String? = settings.getProperty("audio.input")

    /** audio.echo.cancellation: DiPlay's SpeexDSP echo canceller on calls, for speakers without headphones. */
    val echoCancellation: Boolean = booleanSetting("audio.echo.cancellation", false)

    /**
     * altscreen.*: DiPlay's cluster display, with even margins as for its virtual cluster rather than a BYD
     * cluster's crop; null when off. The iPhone learns of it only when it connects.
     */
    val altscreen: Altscreen? = if (!booleanSetting("altscreen.enabled", false)) null else {
        val content = choiceSetting("altscreen.content", AltscreenContent.MAP, AltscreenContent.entries.associateBy { it.key })
        val size = WindowSize(evenSetting("altscreen.width", DEFAULT_ALTSCREEN_WIDTH), evenSetting("altscreen.height", DEFAULT_ALTSCREEN_HEIGHT))
        val cards = choiceSetting("altscreen.cards", AltscreenCards.AUTO, AltscreenCards.entries.associateBy { it.key })
        Altscreen(size, content, cards)
    }
    /**
     * DiPlay rounds its cluster stream to multiples of 8; Audi's 1440x542 panel goes out as it is, as the MHI2Q
     * CarPlay project declares it. Nothing covers a window on a PC, so the safe area (where Apple Maps keeps its
     * attribution and car marker) is the whole panel unless altscreen.safe.* leave room for an overlay.
     */
    private val altscreenDisplay = altscreen?.let {
        val (width, height) = it.size.width to it.size.height
        val safe = AirPlayInsets(
            top = nonNegativeSetting("altscreen.safe.top"),
            bottom = nonNegativeSetting("altscreen.safe.bottom"),
            left = nonNegativeSetting("altscreen.safe.left"),
            right = nonNegativeSetting("altscreen.safe.right"),
        )
        require(safe.top + safe.bottom < height && safe.left + safe.right < width) {
            "altscreen.safe.* leave nothing of the ${width}x$height altscreen"
        }
        CarPlayClusterDisplay.config(
            widthPixels = width,
            heightPixels = height,
            scalePercent = 100,
            content = it.content.cluster,
        ).copy(
            widthPixels = width,
            heightPixels = height,
            heightPhysicalMm = Math.round(ALTSCREEN_WIDTH_MM * height.toDouble() / width).toInt(),
            safeArea = safe.takeIf { it != AirPlayInsets() },
            initialUrl = altscreenUrl(it.content, it.cards),
        )
    }

    /** window.keep.aspect: the windows resize only with the picture's shape; a maximized one fills the screen. */
    val keepAspect: Boolean = booleanSetting("window.keep.aspect", true)

    /** log.carplay.data: the iPhone's route, song, call and battery messages to logs\carplay-data-<time>.jsonl. */
    val logCarPlayData: Boolean = booleanSetting("log.carplay.data", false)

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

    /** /info's default is true too, so an older file that lacks it declares the same. */
    private val safeAreaDrawOutside: Boolean = booleanSetting("display.safe.draw.outside", true)

    /**
     * square (the default, as a window is rectangular): the picture fills the screen with right-angled corners
     * (mainDisplayCornerMasks); rounded leaves CarPlay's rounded corners.
     */
    private val squareCorners: Boolean? = choiceSetting("display.corners", true, mapOf("rounded" to null, "square" to true))

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
            // Upstream leaves the safe area out with square corners, to avoid a conflict with them; a set one stays.
            mainDisplayDisableSafeArea = squareCorners == true && safeArea == null,
        ),
        rightHandDrive = rightHandDrive,
        port = 7000,
        hevc = false,
        microphone = true,
        // Without Opus the wireless iPhone opens no audio stream at all and plays on its own speaker.
        microphoneOpus = true,
        videoInCar = false,
        mainBufferedAudio = false,
        cluster = altscreenDisplay,
        oemLabel = settings.getProperty("car.label")?.trim()?.ifEmpty { null } ?: DEFAULT_CAR_LABEL,
        icons = listOf(carIcon()),
    )

    fun mfiDirectory(): File = dir.resolve("offline-mfi").toFile()

    private fun loadSettings(): Properties {
        val file = dir.resolve(SETTINGS_FILE)
        if (!file.exists()) {
            Files.createDirectories(dir)
            file.writeText(defaultSettingsText())
        } else {
            // A file from an older version gains what it lacks, after its own lines, which stay as they were.
            val saved = Properties().apply { file.reader().use(::load) }
            val missing = TEMPLATE.mapNotNull { block ->
                block.entries.filter { (key, _) -> !saved.containsKey(key) }.takeIf { it.isNotEmpty() }?.let(block::text)
            }
            if (missing.isNotEmpty()) {
                val text = file.readText()
                file.appendText((if (text.isEmpty() || text.endsWith("\n")) "" else "\n") + missing.joinToString(""))
            }
        }
        return Properties().apply { file.reader().use(::load) }
    }

    /** A commented group of settings in the template, with their default values. */
    private class SettingBlock(val comment: String, vararg entries: Pair<String, String>) {
        val entries = entries.toList()

        fun text(shown: List<Pair<String, String>>): String =
            comment.lines().joinToString("") { "# $it\n" } + shown.joinToString("") { (key, value) -> "$key=$value\n" }
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
        /** settings.properties as the first start writes it, and what an older file is completed with. */
        private val TEMPLATE = listOf(
            SettingBlock("Wi-Fi network shared by this PC and the iPhone.", "wifi.ssid" to "", "wifi.passphrase" to ""),
            SettingBlock("'NONE', 'WEP', 'WPA_WPA2', 'WPA3_TRANSITION' or 'WPA3_ONLY'", "wifi.security" to "WPA_WPA2"),
            SettingBlock("Paired iPhone's Bluetooth address, needed only when several phones are paired.", "iphone.address" to ""),
            SettingBlock("The car's name on the iPhone (CarPlay settings, the network); blank is 'DiPlayDesktop'.", "device.name" to ""),
            SettingBlock(
                "The car button on the CarPlay home screen: its label, and a square image in this folder\n" +
                    "(sent at 192 pixels at most). carplay-icon.png is written with the Windows icon to replace.",
                "car.label" to "Windows", "car.icon" to "carplay-icon.png",
            ),
            SettingBlock(
                "Sound devices: 'default' (the Windows default) or a part of a device name that\n" +
                    "`DiPlayDesktop.exe devices` lists.",
                "audio.output" to "default", "audio.input" to "default",
            ),
            SettingBlock(
                "Echo cancellation for calls through speakers, without headphones (experimental): 'true' or 'false'.",
                "audio.echo.cancellation" to "false",
            ),
            SettingBlock(
                "Write every message the iPhone sends about the route, the song, calls and its battery to\n" +
                    "logs\\carplay-data-<date-time>.jsonl in this folder, one JSON object per line: 'true' or 'false'.",
                "log.carplay.data" to "false",
            ),
            SettingBlock(
                "The CarPlay screen the iPhone draws, in pixels (positive even numbers), and its frame rate (30-60).\n" +
                    "iOS draws at @3x from about 1280x720 on both sides, at @2x below; more pixels fit more on screen.",
                "display.width" to "1280", "display.height" to "720", "display.fps" to "60",
            ),
            SettingBlock(
                "Its physical size: the millimetres, or a pixel density. Blank (best) uses 200 mm wide with the height\n" +
                    "from the aspect ratio; other proportions than the pixels' squeeze or stretch the picture.",
                "display.physical.width.mm" to "", "display.physical.height.mm" to "", "display.ppi" to "",
            ),
            SettingBlock(
                "The window on this PC; the picture is scaled into it. Blank uses the display size.\n" +
                    "The app then remembers where you leave the window and its size, until these change.",
                "window.width" to "", "window.height" to "",
            ),
            SettingBlock(
                "'true' resizes the windows only with the picture's shape (a maximized one still fills the screen),\n" +
                    "'false' lets them take any shape.",
                "window.keep.aspect" to "true",
            ),
            SettingBlock(
                "Altscreen (experimental): the car's instrument cluster as a second CarPlay screen in a window of its\n" +
                    "own, 'true' or 'false' (the iPhone learns of it when it connects); its size in pixels (positive even\n" +
                    "numbers); what it starts with: 'map', 'card' (the turn card) or 'map-card'.",
                "altscreen.enabled" to "false", "altscreen.width" to "1440", "altscreen.height" to "542",
                "altscreen.content" to "map",
            ),
            SettingBlock(
                "Where the altscreen shows the navigation app's ETA and turn card: 'auto' (CarPlay decides), 'left',\n" +
                    "'right', 'top', or 'off' (the map alone).",
                "altscreen.cards" to "auto",
            ),
            SettingBlock(
                "Altscreen pixels at each edge the iPhone keeps its map text and car marker out of, for an overlay;\n" +
                    "all zero uses the whole panel.",
                "altscreen.safe.top" to "0", "altscreen.safe.bottom" to "0", "altscreen.safe.left" to "0",
                "altscreen.safe.right" to "0",
            ),
            SettingBlock("'windows' (follow the Windows theme), 'light', 'dark', or 'phone' (the iPhone decides).", "display.theme" to "windows"),
            SettingBlock("'true' puts CarPlay's dock and controls on the right, 'false' on the left.", "display.right.hand.drive" to "false"),
            SettingBlock("Where the dock goes: 'auto' (the iPhone decides), 'driver' (the driver's side) or 'bottom'.", "display.dock" to "auto"),
            SettingBlock(
                "Pixels at each edge CarPlay keeps its controls out of, and whether it may still draw there ('true' or 'false').",
                "display.safe.top" to "0", "display.safe.bottom" to "0", "display.safe.left" to "0", "display.safe.right" to "0",
                "display.safe.draw.outside" to "true",
            ),
            SettingBlock("'square' (the picture fills the window to its corners) or 'rounded' (CarPlay rounds them).",
                "display.corners" to "square"),
        )

        /** settings.properties with every setting at its default, as the first start or deploy writes it. */
        fun defaultSettingsText(): String =
            "# DiPlayDesktop settings, read at start. Delete this file to get the defaults back (the Wi-Fi too).\n" +
                "# A setting this file lacks is added at its end, at its default, when the app starts.\n\n" +
                TEMPLATE.joinToString("") { it.text(it.entries) }

        /** Every key settings.properties knows. */
        val SETTING_KEYS: List<String> = TEMPLATE.flatMap { block -> block.entries.map { it.first } }

        const val DEFAULT_DEVICE_NAME = "DiPlayDesktop"
        const val DEFAULT_ICON_FILE = "carplay-icon.png"
        private const val DEFAULT_CAR_LABEL = "Windows"
        private const val MAX_ICON_PIXELS = 192
        const val SETTINGS_FILE = "settings.properties"
        // The Audi Virtual Cockpit's 12.3-inch Kombi panel; DiPlay's cluster display reports the same 292 mm.
        private const val DEFAULT_ALTSCREEN_WIDTH = 1440
        private const val DEFAULT_ALTSCREEN_HEIGHT = 542
        private const val ALTSCREEN_WIDTH_MM = 292
        private const val MM_PER_INCH = 25.4
        /** Upstream AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM. */
        private const val DEFAULT_WIDTH_MM = 200
        private val HEX = HexFormat.of()

        /**
         * The folder of the packaged exe, whose path jpackage's launcher puts in `jpackage.app-path`: the
         * program and its data live together there. Run any other way (installDist, an IDE), the working directory.
         */
        fun appDirectory(appPath: String? = System.getProperty("jpackage.app-path")): Path =
            appPath?.let { Path.of(it).toAbsolutePath().parent } ?: Path.of(System.getProperty("user.dir")).toAbsolutePath()

        /** DiPlayBootstrap.deviceId: a locally administered MAC derived from the AirPlay public key. */
        fun deviceId(identity: AirPlayIdentity): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).copyOf(6)
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        }
    }
}
