package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import java.util.Properties

/** What the altscreen shows: one of the cluster contents the iPhone lists in its altScreenURLs. */
enum class AltscreenContent(val key: String, val cluster: CarPlayClusterDisplay.Content) {
    /** Our HUD: the iPhone sends the turn card, the window shows the HUD's own data. */
    HUD("hud", CarPlayClusterDisplay.Content.TURN_CARD),
    MAP("map", CarPlayClusterDisplay.Content.MAP),
    MAP_CARD("map-card", CarPlayClusterDisplay.Content.INSTRUMENTS),
    CARD("card", CarPlayClusterDisplay.Content.TURN_CARD);

    /** Whether altscreen.cards places anything in this view: Map + card's turn card; the others have none to place. */
    val placesCards: Boolean get() = this == MAP_CARD

    /** F11's view after this one: the HUD goes to [mapView], the iPhone's view picked last; any other to the HUD. */
    fun toggled(mapView: AltscreenContent): AltscreenContent = if (this == HUD) mapView else HUD
}

/**
 * Where CarPlay lays out the navigation app's ETA and turn card on the altscreen, on Map + card: the other views
 * have none to place. DashBoard reads it from the cluster URL for every app (notes of the MHI2Q CarPlay project).
 * Without one, AUTO, it takes the driver's side the main screen was declared with (display.right.hand.drive): seen
 * with Google Maps, the left for a left-hand drive car, the right for a right-hand drive one. Neither Google Maps nor
 * Apple Maps takes the card away for showETA=no&maneuverLayout=no, so there is no off.
 */
enum class AltscreenCards(val key: String, val query: String) {
    AUTO("auto", ""),
    LEFT("left", "showETA=yes&maneuverLayout=leftAligned"),
    RIGHT("right", "showETA=yes&maneuverLayout=rightAligned"),
    TOP("top", "showETA=yes&maneuverLayout=topAligned");

    fun next(): AltscreenCards = entries[(ordinal + 1) % entries.size]
}

/** The cluster URL the iPhone is asked for: [content] with the [cards] layout. */
fun altscreenUrl(content: AltscreenContent, cards: AltscreenCards): String =
    if (cards.query.isEmpty()) content.cluster.url else "${content.cluster.url}?${cards.query}"

/**
 * The AltScreen window's title, with its stream size; without a route every mode looks alike, so the title says
 * which one is on. AltScreen is Apple's name for it (the altScreen feature and altScreenURLs).
 */
fun altscreenTitle(stream: WindowSize, content: AltscreenContent, cards: AltscreenCards): String =
    "AltScreen · ${stream.width}×${stream.height} · ${content.key} · cards ${cards.key}"

/** The second CarPlay screen, a car's instrument cluster: the stream's [size] and what it starts with. */
data class Altscreen(
    val size: WindowSize,
    val content: AltscreenContent,
    val cards: AltscreenCards = AltscreenCards.TOP,
    /** altscreen.content and altscreen.cards as set, which a remembered view gives way to when they change. */
    val configured: Pair<AltscreenContent, AltscreenCards> = content to cards,
)

// The Audi Virtual Cockpit's 12.3-inch Kombi panel; DiPlay's cluster display reports the same 292 mm.
private const val ALTSCREEN_WIDTH_MM = 292

/**
 * The AltScreen as /info declares it: DiPlay's cluster display, with even margins as for its virtual cluster rather
 * than its car cluster's crop, and the view [areas]. DiPlay rounds its cluster stream to multiples of 8; the AltScreen
 * goes out at the size set, 1440x540 by default for Audi's panel (the MHI2Q CarPlay project declares it as 1440x542).
 * Nothing covers a window on a PC, so the safe area (where Apple Maps keeps its attribution and car marker) is the
 * whole panel unless altscreen.safe.* in [settings] leave room for an overlay.
 */
internal fun altscreenDisplay(alt: Altscreen, settings: Properties, areas: List<AirPlayViewArea>?): AirPlayDisplayConfig {
    val (width, height) = alt.size.width to alt.size.height
    val safe = AirPlayInsets(
        top = settings.nonNegativeSetting("altscreen.safe.top"),
        bottom = settings.nonNegativeSetting("altscreen.safe.bottom"),
        left = settings.nonNegativeSetting("altscreen.safe.left"),
        right = settings.nonNegativeSetting("altscreen.safe.right"),
    )
    require(safe.top + safe.bottom < height && safe.left + safe.right < width) {
        "altscreen.safe.* leave nothing of the ${width}x$height altscreen"
    }
    return CarPlayClusterDisplay.config(
        widthPixels = width,
        heightPixels = height,
        scalePercent = 100,
        content = alt.content.cluster,
    ).copy(
        widthPixels = width,
        heightPixels = height,
        heightPhysicalMm = Math.round(ALTSCREEN_WIDTH_MM * height.toDouble() / width).toInt(),
        safeArea = safe.takeIf { it != AirPlayInsets() },
        initialUrl = altscreenUrl(alt.content, alt.cards),
        viewAreas = areas,
    )
}
