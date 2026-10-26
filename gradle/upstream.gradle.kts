// The portable CarPlay core is compiled from the Android library's own sources in the DiPlay submodule
// (upstream/), so DiPlay's changes come in by moving the submodule. A Sync copy applies the file filter to
// upstream alone: filtering the source set would also hide desktop replacements that share an upstream path.
// build.gradle.kts adds both copies to the source sets.
val upstreamCore by tasks.registering(Sync::class) {
    from("upstream/shared/src/main/java") {
        include(
            "com/shilapi/xcertplay/airplay/**",
            "com/shilapi/xcertplay/iap2/**",
            "com/shilapi/xcertplay/mfi/**",
            "com/shilapi/xcertplay/compat/Base64Compat.kt",
            // Upstream's route guidance and now playing parsers, for the CarPlay data bus.
            "com/shilapi/xcertplay/hud/BydHudRouteState.kt",
            "com/shilapi/xcertplay/hud/BydManeuverCodes.kt",
            "com/shilapi/xcertplay/media/CarPlayPlaybackStatus.kt",
            "com/shilapi/xcertplay/media/EchoReference.kt",
            "com/shilapi/xcertplay/media/MediaCodecSupport.kt",
            "com/shilapi/xcertplay/media/SpeexEchoCanceller.kt",
            "com/shilapi/xcertplay/media/TouchLatencyProbe.kt",
            "com/shilapi/xcertplay/network/AirPlayPortSelector.kt",
            "com/shilapi/xcertplay/network/HotspotAddressPolicy.kt",
            "com/shilapi/xcertplay/network/WirelessHotspotManager.kt",
            "com/shilapi/xcertplay/transport/BlockingDuplexByteStream.kt",
            "com/shilapi/xcertplay/transport/BluetoothRfcommDuplexStream.kt",
            "com/shilapi/xcertplay/transport/Iap2*.kt",
            "com/shilapi/xcertplay/transport/I2cTransport.kt",
            "com/shilapi/xcertplay/transport/VehicleSpeedNmea.kt",
            "org/concentus/**",
        )
        // USB multiplexing belongs to wired CarPlay.
        exclude("com/shilapi/xcertplay/transport/Iap2UsbMuxHost.kt")
        // Replaced in src/main/kotlin: the identification asks for lane guidance too, and the catalog knows the
        // route guidance component's lane limits it gives.
        exclude("com/shilapi/xcertplay/transport/Iap2IdentificationClient.kt")
        exclude("com/shilapi/xcertplay/iap2/catalog/Iap2Endpoint.kt")
    }
    into(layout.buildDirectory.dir("upstream-core"))
    // A view area carries a safe area of its own, where DiPlay clips the display's to every area: the AltScreen's
    // views each have their own. Patched into the copy, each anchor checked once, so a newer DiPlay that moved one
    // stops the build here instead of dropping the patch.
    val patches = mapOf(
        "com/shilapi/xcertplay/airplay/AirPlayConfig.kt" to (
            "    val dockEdge: Int? = null,\n)" to
                "    val dockEdge: Int? = null,\n" +
                "    /** ChairPlay: this area's own safe area, as insets from the whole display; null clips the display's to it. */\n" +
                "    val safeArea: AirPlayInsets? = null,\n)"
            ),
        "com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt" to (
            "val displaySafe = display.safeArea ?: AirPlayInsets()" to "val displaySafe = area?.safeArea ?: display.safeArea ?: AirPlayInsets()"
            ),
    )
    val target = layout.buildDirectory.dir("upstream-core")
    doLast {
        for ((path, change) in patches) {
            val file = target.get().file(path).asFile
            // A checkout with core.autocrlf=true (git's default on Windows, GitHub's runners) has CRLF: match on LF.
            val text = file.readText().replace("\r\n", "\n")
            val found = text.split(change.first).size - 1
            check(found == 1) { "upstream $path: the line to patch is there $found times, not once; update gradle/upstream.gradle.kts" }
            file.writeText(text.replace(change.first, change.second))
        }
    }
}

// Upstream unit tests for the same core, copied the same way. Tests are excluded when they use
// Robolectric or android.* (no Android runtime here) or when they exercise a class outside the
// core set; the excluded groups are listed below.
val upstreamCoreTests by tasks.registering(Sync::class) {
    from("upstream/shared/src/test/java") {
        include(
            "com/shilapi/xcertplay/airplay/**",
            "com/shilapi/xcertplay/iap2/**",
            "com/shilapi/xcertplay/mfi/**",
            "com/shilapi/xcertplay/compat/Base64CompatTest.kt",
            "com/shilapi/xcertplay/hud/BydHudRouteStateTest.kt",
            "com/shilapi/xcertplay/media/CarPlayPlaybackStatusTest.kt",
            "com/shilapi/xcertplay/media/EchoReferenceTest.kt",
            "com/shilapi/xcertplay/network/AirPlayPortSelectorTest.kt",
            "com/shilapi/xcertplay/network/HotspotAddressPolicyTest.kt",
            "com/shilapi/xcertplay/network/WirelessHotspotManagerTest.kt",
            "com/shilapi/xcertplay/transport/BlockingDuplexByteStream*.kt",
            "com/shilapi/xcertplay/transport/BluetoothRfcommDuplexStreamTest.kt",
            "com/shilapi/xcertplay/transport/Iap2*.kt",
            "com/shilapi/xcertplay/transport/I2cTransport*.kt",
            "com/shilapi/xcertplay/transport/VehicleSpeedNmeaTest.kt",
        )
        // Robolectric runner: needs an Android runtime.
        exclude(
            "com/shilapi/xcertplay/airplay/AirPlayCryptoAndroidCompatibilityTest.kt",
            "com/shilapi/xcertplay/airplay/BufferedAudioSessionTest.kt",
            "com/shilapi/xcertplay/airplay/CarPlayAudioStreamIsolationTest.kt",
            "com/shilapi/xcertplay/airplay/ClusterContentTest.kt",
            "com/shilapi/xcertplay/airplay/CurrentScreenListenerTest.kt",
            "com/shilapi/xcertplay/airplay/IphoneCornerMaskSessionTest.kt",
        )
        // Uses KeyEvent constants beyond the shim's, which carries only what the main code references.
        exclude("com/shilapi/xcertplay/airplay/CarPlayMediaButtonTest.kt")
        // Written against Android's Conscrypt: it expects the platform ChaCha20-Poly1305 to seal the same
        // key and nonce twice, which a desktop JDK refuses (the core then falls back to BouncyCastle, so
        // 5 of its 9 cases fail on the JVM only).
        exclude("com/shilapi/xcertplay/airplay/AirPlayCryptoChachaTest.kt")
        // SoftwareOpusEncoder is not in the core set (replaced in a later task).
        exclude("com/shilapi/xcertplay/airplay/AirPlayInfoPlistTest.kt")
        // Pins DiPlay's RouteGuidance component, which ChairPlay's identification replaces with LuKa's; its checks
        // are in ChairPlay's Iap2LaneGuidanceIdentificationTest with ChairPlay's values.
        exclude("com/shilapi/xcertplay/transport/Iap2RouteGuidanceSubscriptionTest.kt")
    }
    into(layout.buildDirectory.dir("upstream-core-tests"))
}
