package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.PairingStore
import io.github.numberonebot.chairplay.log.LogLevel
import io.github.numberonebot.chairplay.media.DuckSetting
import io.github.numberonebot.chairplay.network.SavedWifi
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
    /** Our HUD: the iPhone sends the turn card, the window shows the HUD's own data. */
    HUD("hud", CarPlayClusterDisplay.Content.TURN_CARD),
    MAP("map", CarPlayClusterDisplay.Content.MAP),
    MAP_CARD("map-card", CarPlayClusterDisplay.Content.INSTRUMENTS),
    CARD("card", CarPlayClusterDisplay.Content.TURN_CARD);

    /** F11's next view, in the order the controls window's buttons and the AltScreen page show them. */
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

/** The main window's title, with the size the iPhone draws the CarPlay screen at and, without a session, [status]. */
fun mainWindowTitle(stream: WindowSize, status: String? = null): String =
    "ChairPlay · ${stream.width}×${stream.height}" + (status?.let { " · $it" } ?: "")

/**
 * The AltScreen window's title, with its stream size; without a route every mode looks alike, so the title says
 * which one is on. AltScreen is Apple's name for it (the altScreen feature and altScreenURLs).
 */
fun altscreenTitle(stream: WindowSize, content: AltscreenContent, cards: AltscreenCards): String =
    "ChairPlay AltScreen · ${stream.width}×${stream.height} · ${content.key} · cards ${cards.key}"

/** The second CarPlay screen, a car's instrument cluster: the stream's [size] and what it starts with. */
data class Altscreen(
    val size: WindowSize,
    val content: AltscreenContent,
    val cards: AltscreenCards = AltscreenCards.AUTO,
    /** altscreen.content and altscreen.cards as set, which a remembered view gives way to when they change. */
    val configured: Pair<AltscreenContent, AltscreenCards> = content to cards,
)

class DesktopConfig private constructor(private val dir: Path, startedWith: DesktopConfig?) {
    constructor(dir: Path) : this(dir, null)

    // As the app starts the file gains what it lacks; read again later ([airPlayNow]), it is taken as it is.
    private val settings = if (startedWith == null) loadSettings() else readSettings()
    val identity: AirPlayIdentity = startedWith?.identity ?: loadIdentity()
    val pairings: PairingStore = startedWith?.pairings ?: loadPairings()
    val iphoneAddress: String? = settings.getProperty("iphone.address")?.trim()?.ifEmpty { null }

    /**
     * iphone.join.seconds: how long an iPhone that paired before has to join the Wi-Fi after the Bluetooth start
     * before the app starts over. It joins within a second or two; one that lost the Wi-Fi meanwhile gets the network
     * again this soon instead of after the five minutes a new iPhone has to accept CarPlay.
     */
    val iphoneJoinSeconds: Int = positiveSetting("iphone.join.seconds") ?: 30

    /** True once an iPhone has paired with this app: its AirPlay pairing record is in pairings/. */
    fun iphoneKnown(): Boolean = Files.list(dir.resolve("pairings")).use { it.findAny().isPresent }

    /** The Wi-Fi network as the app started with it; null until wifi.ssid is set, which the start screen asks for. */
    val wifi: SavedWifi? = savedWifi(settings)

    /** The Wi-Fi network as settings.properties has it now: the start screen may have changed it since the start. */
    fun currentWifi(): SavedWifi? = savedWifi(readSettings())

    /** ui.autoconnect: the app connects to the iPhone as it starts; false waits for Connect on the start screen. */
    val autoconnect: Boolean = booleanSetting("ui.autoconnect", true)

    /** ui.autoshow: CarPlay covers the start screen as soon as the iPhone is there; false waits for Show CarPlay. */
    val autoshow: Boolean = booleanSetting("ui.autoshow", true)

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

    // 1280x720 by default: iOS draws at @3x from about that size, and a window of it fits most screens.
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
     * audio.start.silence.ms and audio.start.fade.ms: CarPlay's music starts silent for so long, then rises to full over so
     * long, since the iPhone's first second or two of audio come in bursts and the line runs dry in them. The sound fades
     * out over the same time as CarPlay disconnects or reconnects and as the app closes.
     */
    val musicStartSilenceMillis: Long = nonNegativeOr("audio.start.silence.ms", 1200).toLong()
    val musicStartFadeMillis: Long = nonNegativeOr("audio.start.fade.ms", 800).toLong()

    /**
     * audio.duck.percent and audio.duck.iphone: the music under a navigation prompt plays at the iPhone's level when one
     * comes, as a car's head unit takes Apple's, unless turned off; else at this percentage of its volume (30, about
     * -10 dB, by default).
     */
    val duckSetting: DuckSetting = DuckSetting(
        nonNegativeOr("audio.duck.percent", 30).also {
            require(it <= 100) { "audio.duck.percent must be from 0 to 100, not $it" }
        } / 100f,
        booleanSetting("audio.duck.iphone", true),
    )

    private fun nonNegativeOr(key: String, default: Int): Int =
        if (settings.getProperty(key)?.trim().isNullOrEmpty()) default else nonNegativeSetting(key)

    /**
     * altscreen.*: DiPlay's cluster display, with even margins as for its virtual cluster rather than a BYD
     * cluster's crop; null when off. The iPhone learns of it only when it connects.
     */
    /** altscreen.width and altscreen.height, on or off: the AltScreen's window waits at this size while it is off. */
    val altscreenSize = WindowSize(evenSetting("altscreen.width", DEFAULT_ALTSCREEN_WIDTH), evenSetting("altscreen.height", DEFAULT_ALTSCREEN_HEIGHT))

    val altscreen: Altscreen? = if (!booleanSetting("altscreen.enabled", false)) null else {
        val content = choiceSetting("altscreen.content", AltscreenContent.MAP, AltscreenContent.entries.associateBy { it.key })
        val size = altscreenSize
        val cards = choiceSetting("altscreen.cards", AltscreenCards.AUTO, AltscreenCards.entries.associateBy { it.key })
        // The view F11 and Shift+F11 left it on, unless these settings changed since.
        val (viewContent, viewCards) = AltscreenViewStore(dir).load(content, cards)
        Altscreen(size, viewContent, viewCards, configured = content to cards)
    }
    /**
     * DiPlay rounds its cluster stream to multiples of 8; the AltScreen goes out at the size set, 1440x540 by
     * default for Audi's panel (the MHI2Q CarPlay project declares it as 1440x542). Nothing covers a window on a PC, so the safe area (where Apple Maps keeps its
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

    /** window.controls: a window of on-screen car controls beside CarPlay's. */
    val controlsWindow: Boolean = booleanSetting("window.controls", false)

    /** log.carplay.data: the iPhone's route, song, call and battery messages to logs\carplay-data-<time>.jsonl. */
    val logCarPlayData: Boolean = booleanSetting("log.carplay.data", false)

    /** log.level: how much the app's log, in the console and logs\chairplay-<time>.log, tells. */
    val logLevel: LogLevel = choiceSetting("log.level", LogLevel.INFO, LogLevel.entries.associateBy { it.key })

    /** windows follows the Windows dark mode; phone sends nothing and leaves it to the iPhone. */
    val theme: Theme = choiceSetting("display.theme", Theme.WINDOWS, Theme.entries.associateBy { it.name.lowercase() })

    /**
     * ui.accent: the accent colour of the screens, the controls window, and the HUD, written #rrggbb; every lighter or
     * fainter shade of it is made from it.
     */
    val accentText: String = (settings.getProperty("ui.accent")?.trim()?.ifEmpty { null } ?: DEFAULT_ACCENT).lowercase().also {
        require(Regex("#[0-9a-f]{6}").matches(it)) { "ui.accent must be a colour written #rrggbb, such as $DEFAULT_ACCENT, not $it" }
    }

    /** [accentText] as 0xRRGGBB. */
    val accent: Int = accentText.drop(1).toInt(16)

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

    /**
     * display.touch, what the screen's touch can do, as /info's features and primaryInputDevice say it. With touch,
     * swipe or tap, CarPlay shows scroll buttons in its lists; none (the default, for a mouse) makes the knob (the keys
     * and the controls window, always declared) the primary input, and CarPlay leaves them out, while clicks and
     * drags still reach it as taps.
     */
    private val touch: Pair<Int, Int> = choiceSetting(
        "display.touch", TOUCH_NONE,
        mapOf("swipe" to TOUCH_SWIPE, "tap" to (FEATURE_KNOB or FEATURE_TAP_TOUCH to INPUT_TOUCH), "none" to TOUCH_NONE),
    )

    /** What the iPhone is told about its screen, for the start-up log. */
    fun displaySummary(): String {
        val (widthMm, heightMm) = physicalMm
        val ppi = Math.round(displayWidth * MM_PER_INCH / widthMm)
        return "display ${displayWidth}x$displayHeight px, ${widthMm}x$heightMm mm ($ppi ppi), $fps fps; " +
            "window ${window.width}x${window.height}"
    }

    /**
     * DiPlay's production values (CarPlayHostActivity.airPlayConfig); btMac is replaced by the radio's. The
     * CarPlay home screen shows the receiver's icon where a car shows its maker's; tapping it opens the settings screen.
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
            features = touch.first,
            primaryInputDevice = touch.second,
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

    /** The settings as settings.properties has them now, with this start's identity and pairings. */
    fun now(): DesktopConfig = DesktopConfig(dir, this)

    /**
     * [airPlay] for the next connection: what the iPhone learns of its screens, which it asks at each connection, as
     * [now] has it; the name as the app started with it, as the radio keeps it.
     */
    fun airPlayNow(now: DesktopConfig = now()): AirPlayConfig = now.airPlay.copy(deviceName = airPlay.deviceName)

    fun mfiDirectory(): File = dir.resolve("offline-mfi").toFile()

    private fun readSettings() = Properties().apply { dir.resolve(SETTINGS_FILE).reader().use(::load) }

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
     * ChairPlay's armchair ([CarIcon]) the first time, to be replaced; any square image is sent at [MAX_ICON_PIXELS] at most,
     * as upstream's AirPlayIconSizing does, because a large icon in /info stopped the iPhone connecting (#567).
     */
    private fun carIcon(): AirPlayIcon {
        val name = settings.getProperty("car.icon")?.trim()?.ifEmpty { null } ?: DEFAULT_ICON_FILE
        val file = dir.resolve(name)
        if (name == DEFAULT_ICON_FILE && !file.exists()) Files.write(file, CarIcon.png())
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
            SettingBlock(
                "Seconds an iPhone that paired before has to join the Wi-Fi before the app starts over (a new one has five minutes).",
                "iphone.join.seconds" to "30",
            ),
            SettingBlock(
                "'true' connects to the iPhone as the app starts, 'false' waits for Connect.",
                "ui.autoconnect" to "true",
            ),
            SettingBlock(
                "'true' shows CarPlay over the settings as soon as the iPhone connects, 'false' stays on the settings\n" +
                    "until you choose CarPlay.",
                "ui.autoshow" to "true",
            ),
            SettingBlock(
                "'true' shows each controls window button's key on it; 'false' shows them only while Alt is held.",
                "ui.controls.keys" to "false",
            ),
            SettingBlock(
                "The accent colour of the screens, the controls window, and the HUD, written #rrggbb; its lighter and\n" +
                    "fainter shades are made from it.",
                "ui.accent" to DEFAULT_ACCENT,
            ),
            SettingBlock("The car's name on the iPhone (CarPlay settings, the network); blank is 'ChairPlay'.", "device.name" to ""),
            SettingBlock(
                "The car button on the CarPlay home screen: its label, and a square image in this folder\n" +
                    "(sent at 192 pixels at most). carplay-icon.png is written with ChairPlay's armchair to replace.",
                "car.label" to DEFAULT_CAR_LABEL, "car.icon" to "carplay-icon.png",
            ),
            SettingBlock(
                "Sound devices: 'default' (the Windows default) or a part of a device name that\n" +
                    "`ChairPlay.exe devices` lists.",
                "audio.output" to "default", "audio.input" to "default",
            ),
            SettingBlock(
                "Echo cancellation for calls through speakers, without headphones (experimental): 'true' or 'false'.",
                "audio.echo.cancellation" to "false",
            ),
            SettingBlock(
                "How CarPlay's music starts, since the iPhone's first audio comes in bursts: silent for this many\n" +
                    "milliseconds, then rising to full over this many (both 0 play it at once).",
                "audio.start.silence.ms" to "1200",
                "audio.start.fade.ms" to "800",
            ),
            SettingBlock(
                "How loud CarPlay's music stays under a navigation prompt, in percent of its volume (30 is\n" +
                    "about -10 dB), and whether a level the iPhone sends wins over it: 'true' or 'false'.",
                "audio.duck.percent" to "30",
                "audio.duck.iphone" to "true",
            ),
            SettingBlock(
                "Write every message the iPhone sends about the route, the song, calls and its battery to\n" +
                    "logs\\carplay-data-<date-time>.jsonl in this folder, one JSON object per line: 'true' or 'false'.",
                "log.carplay.data" to "false",
            ),
            SettingBlock(
                "How much the app's log tells, in the console and in logs\\chairplay-<date-time>.log in this folder\n" +
                    "(the ten newest are kept): error, warning, info, debug, or trace (with every packet).",
                "log.level" to "info",
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
                "'true' opens a window of on-screen car controls: the knob, back, home, media, calls, Siri and the\n" +
                    "AltScreen's zoom; 'false' does not.",
                "window.controls" to "false",
            ),
            SettingBlock(
                "Altscreen (experimental): the car's instrument cluster as a second CarPlay screen in a window of its\n" +
                    "own, 'true' or 'false' (the iPhone learns of it when it connects); its size in pixels (positive even\n" +
                    "numbers); what it starts with: 'map', 'map-card', 'hud' or 'card' (the turn card).",
                "altscreen.enabled" to "false", "altscreen.width" to "1440", "altscreen.height" to "540",
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
            SettingBlock(
                "The touchscreen CarPlay is told of: 'none' (CarPlay leaves out its scroll buttons; clicks and drags still\n" +
                    "tap, the keys move the focus), 'swipe' or 'tap' (a touchscreen that swipes or only taps; CarPlay shows\n" +
                    "scroll buttons).",
                "display.touch" to "none",
            ),
        )

        /** settings.properties with every setting at its default, as the first start or deploy writes it. */
        fun defaultSettingsText(): String =
            "# ChairPlay settings, read at start. Delete this file to get the defaults back (the Wi-Fi too).\n" +
                "# A setting this file lacks is added at its end, at its default, when the app starts.\n\n" +
                TEMPLATE.joinToString("") { it.text(it.entries) }

        /** Every key settings.properties knows. */
        val SETTING_KEYS: List<String> = TEMPLATE.flatMap { block -> block.entries.map { it.first } }

        // /info's display features and primary input device (CarPlay's knob, low- and high-fidelity touch).
        private const val FEATURE_KNOB = 0x02
        private const val FEATURE_TAP_TOUCH = 0x04
        private const val FEATURE_SWIPE_TOUCH = 0x08
        private const val INPUT_TOUCH = 1
        private const val INPUT_KNOB = 3
        private val TOUCH_SWIPE = FEATURE_KNOB or FEATURE_SWIPE_TOUCH to INPUT_TOUCH
        private val TOUCH_NONE = FEATURE_KNOB to INPUT_KNOB

        const val DEFAULT_DEVICE_NAME = "ChairPlay"
        const val DEFAULT_ICON_FILE = "carplay-icon.png"
        private const val DEFAULT_CAR_LABEL = DEFAULT_DEVICE_NAME
        private const val MAX_ICON_PIXELS = 192
        const val SETTINGS_FILE = "settings.properties"
        // The Audi Virtual Cockpit's 12.3-inch Kombi panel; DiPlay's cluster display reports the same 292 mm.
        /** iOS's system blue, CarPlay's own accent. */
        const val DEFAULT_ACCENT = "#0a84ff"
        private const val DEFAULT_ALTSCREEN_WIDTH = 1440
        private const val DEFAULT_ALTSCREEN_HEIGHT = 540
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

        /** wifi.ssid, wifi.passphrase and wifi.security from [settings]; null while wifi.ssid is blank. */
        private fun savedWifi(settings: Properties): SavedWifi? {
            val ssid = settings.getProperty("wifi.ssid")?.ifBlank { null } ?: return null
            val security = settings.getProperty("wifi.security")?.trim()?.ifEmpty { null } ?: "WPA_WPA2"
            val choices = Iap2WirelessSecurity.entries.associateBy { it.name }
            require(security in choices) { "wifi.security must be one of ${choices.keys.joinToString()}, not $security" }
            return SavedWifi(ssid, settings.getProperty("wifi.passphrase").orEmpty(), choices.getValue(security))
        }

        /** DiPlayBootstrap.deviceId: a locally administered MAC derived from the AirPlay public key. */
        fun deviceId(identity: AirPlayIdentity): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).copyOf(6)
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        }
    }
}
