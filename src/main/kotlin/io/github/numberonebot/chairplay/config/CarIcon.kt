package io.github.numberonebot.chairplay.config

/**
 * The car button's default icon: the armchair of the app's own icon, 192 pixels square, as
 * packaging/icon/make_icon.py draws it and the build puts it among the resources. Upstream sends one square PNG of at
 * most 192 pixels (AirPlayIconSizing): the iPhone stopped connecting when a large icon was in /info (#567). iOS rounds
 * the corners itself.
 */
object CarIcon {
    fun png(): ByteArray =
        checkNotNull(CarIcon::class.java.getResourceAsStream("/icons/carplay-icon.png")) { "the car icon is missing from the app" }
            .use { it.readBytes() }
}
