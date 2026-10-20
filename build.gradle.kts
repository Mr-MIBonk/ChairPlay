plugins {
    kotlin("jvm") version "2.2.10"
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

// The portable CarPlay core is compiled from the Android library's own sources, so upstream
// changes merge without conflicts. A Sync copy applies the file filter to upstream alone:
// filtering the source set would also hide desktop replacements that share an upstream path.
val upstreamCore by tasks.registering(Sync::class) {
    from("../shared/src/main/java") {
        include(
            "com/shilapi/xcertplay/airplay/**",
            "com/shilapi/xcertplay/iap2/**",
            "com/shilapi/xcertplay/mfi/**",
            "com/shilapi/xcertplay/compat/Base64Compat.kt",
            "com/shilapi/xcertplay/media/MediaCodecSupport.kt",
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
    }
    into(layout.buildDirectory.dir("upstream-core"))
}

// Upstream unit tests for the same core, copied the same way. Tests are excluded when they use
// Robolectric or android.* (no Android runtime here) or when they exercise a class outside the
// core set; the excluded groups are listed below.
val upstreamCoreTests by tasks.registering(Sync::class) {
    from("../shared/src/test/java") {
        include(
            "com/shilapi/xcertplay/airplay/**",
            "com/shilapi/xcertplay/iap2/**",
            "com/shilapi/xcertplay/mfi/**",
            "com/shilapi/xcertplay/compat/Base64CompatTest.kt",
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
    }
    into(layout.buildDirectory.dir("upstream-core-tests"))
}

sourceSets {
    test {
        kotlin {
            srcDir(upstreamCoreTests)
        }
    }
    main {
        // Concentus (Opus) is Java: a Kotlin srcDir alone would leave it uncompiled.
        java {
            srcDir(upstreamCore)
        }
        kotlin {
            srcDir(upstreamCore)
            srcDir("src/shim/kotlin")
        }
    }
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.jmdns:jmdns:3.6.3")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    // Native classifiers are pinned to windows-x86_64: the *-platform artifacts of javacpp presets pull
    // every OS, hundreds of megabytes this Windows-only app never loads.
    implementation("org.bytedeco:javacpp:1.5.11")
    implementation("org.bytedeco:javacpp:1.5.11:windows-x86_64")
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11")
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:windows-x86_64")
    implementation("org.lwjgl:lwjgl:3.3.4")
    implementation("org.lwjgl:lwjgl-glfw:3.3.4")
    implementation("org.lwjgl:lwjgl-opengl:3.3.4")
    runtimeOnly("org.lwjgl:lwjgl:3.3.4:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-glfw:3.3.4:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-opengl:3.3.4:natives-windows")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.shilapi.xcertplay.desktop.MainKt")
}

// Concentus is BSD-licensed and its notice must travel with the binaries: inside the jar, and next to it in
// the distribution.
val concentusLicence = layout.buildDirectory.file("upstream-core/org/concentus/LICENSE")

tasks.jar {
    from(concentusLicence) {
        rename { "LICENSE-concentus" }
        into("META-INF")
    }
}

distributions.main {
    contents {
        from(concentusLicence) { rename { "LICENSE-concentus" } }
    }
}
