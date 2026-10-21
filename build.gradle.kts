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

// The portable CarPlay core is compiled from the Android library's own sources in the DiPlay submodule
// (upstream/), so DiPlay's changes come in by moving the submodule. A Sync copy applies the file filter to
// upstream alone: filtering the source set would also hide desktop replacements that share an upstream path.
val upstreamCore by tasks.registering(Sync::class) {
    from("upstream/shared/src/main/java") {
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
    from("upstream/shared/src/test/java") {
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

// TcpLiveness reaches a socket's Winsock handle by reflection to set upstream's keep-alive timing, which
// JDK 17 does not offer on Windows. Every launcher passes the same opens: the start scripts, the tests
// and the app image.
val socketHandleOpens = listOf("java.base/java.net", "java.base/java.io").map { "--add-opens=$it=ALL-UNNAMED" }

application {
    mainClass.set("com.shilapi.xcertplay.desktop.MainKt")
    applicationDefaultJvmArgs = socketHandleOpens
}

tasks.test {
    jvmArgs(socketHandleOpens)
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

// A self-contained Windows app image: the launcher, a trimmed runtime and the jars, no installer tooling
// needed. The module list is what `jdeps --print-module-deps` reports for the installed jars (java.base,
// java.desktop, java.management, java.naming, java.sql, jdk.unsupported) plus modules reached only
// reflectively: java.logging (JmDNS, SLF4J), java.xml, and the security providers, which are looked up by
// name. A missing provider would break MFi signing at runtime only.
val appImageModules = listOf(
    "java.base", "java.desktop", "java.logging", "java.management", "java.naming", "java.sql", "java.xml",
    "jdk.unsupported", "jdk.crypto.ec", "jdk.crypto.cryptoki", "jdk.crypto.mscapi",
)

interface FileOps {
    @get:Inject val fs: FileSystemOperations
}

val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds build/jpackage/DiPlayDesktop with the launcher DiPlayDesktop.exe."
    dependsOn(tasks.installDist)
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    val installDir = layout.buildDirectory.dir("install/diplay-desktop")
    val outDir = layout.buildDirectory.dir("jpackage")
    val upstream = layout.projectDirectory.dir("upstream")
    val notices = layout.projectDirectory.dir("notices")
    val thirdParty = layout.projectDirectory.file("THIRD_PARTY_NOTICES")
    // Written by packaging/icon/make_icon.py, as are the window icon PNGs in the resources.
    val icon = layout.projectDirectory.file("packaging/diplaydesktop.ico")
    val ops = objects.newInstance<FileOps>()
    // Command-line arguments given to DiPlayDesktop.exe replace the default `run`, so `devices` and
    // `probe-bt` work by passing them explicitly.
    doFirst {
        ops.fs.delete { delete(outDir) }
        commandLine(
            launcher.get().metadata.installationPath.file("bin/jpackage.exe").asFile.path,
            "--type", "app-image",
            "--name", "DiPlayDesktop",
            "--input", installDir.get().dir("lib").asFile.path,
            "--main-jar", "diplay-desktop.jar",
            "--main-class", "com.shilapi.xcertplay.desktop.MainKt",
            "--arguments", "run",
            "--win-console",
            "--icon", icon.asFile.path,
            "--add-modules", appImageModules.joinToString(","),
            "--dest", outDir.get().asFile.path,
            *socketHandleOpens.flatMap { listOf("--java-options", it) }.toTypedArray(),
        )
    }
    // jpackage on JDK 17 has no --app-content, so notices are copied into the image afterwards.
    doLast {
        ops.fs.copy {
            from(installDir.get().file("LICENSE-concentus"))
            from(layout.projectDirectory.file("LICENSE"))
            from(thirdParty)
            from(upstream.dir("docs/licenses/dependencies")) { into("licenses") }
            from(notices) { into("licenses") }
            into(outDir.get().dir("DiPlayDesktop"))
        }
    }
}

// The app folder holds the program next to its data (settings, keys, pairings), which the app reads from
// its exe's folder. deploy refreshes the program there and never touches the data. It is dist/DiPlayDesktop
// in the project (outside build/, which clean wipes), or the path in the diplay.home property: on the
// command line (-Pdiplay.home=<path>) or, to keep one for good, in %USERPROFILE%\.gradle\gradle.properties.
val appHome: Provider<String> = providers.gradleProperty("diplay.home")
    .orElse(layout.projectDirectory.dir("dist/DiPlayDesktop").asFile.path)

val syncApp by tasks.registering(Sync::class) {
    description = "Copies the app image into the app folder (diplay.home), keeping its settings, keys and pairings."
    dependsOn(jpackageImage)
    from(layout.buildDirectory.dir("jpackage/DiPlayDesktop"))
    into(appHome)
    // The app data: settings, controls and car icon, the AirPlay identity, pairings and the MFi files.
    preserve {
        include("settings.properties", "controls.properties", "carplay-icon.png", "identity.properties", "pairings/**", "offline-mfi/**")
    }
    // Sync deletes whatever else is there, so a mistyped folder must not be one with other files in it.
    val home = appHome.map { file(it) }
    doFirst {
        val target = home.get()
        val entries = target.list().orEmpty()
        val known = setOf("DiPlayDesktop.exe", "settings.properties", "controls.properties", "carplay-icon.png", "identity.properties", "pairings", "offline-mfi")
        check(entries.isEmpty() || entries.any { it in known }) {
            "$target has files but no DiPlayDesktop.exe or app data; refusing to replace its contents"
        }
    }
}

// Then writes the default settings, controls and car icon the folder lacks, so the Wi-Fi can be filled in
// before the first start; the templates live in the app's code (AppDefaults.kt).
val deploy by tasks.registering(JavaExec::class) {
    group = "distribution"
    description = "Installs the app into the app folder (diplay.home) with default settings where it has none."
    dependsOn(syncApp)
    mainClass = "com.shilapi.xcertplay.desktop.config.AppDefaultsKt"
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs("-Djava.awt.headless=true")
    args(appHome.get())
}

// Starts the deployed app in its own console window, so it is stopped by closing its window: a task runner's
// stop button would kill it hard, and the iPhone then ignores new sessions until it restarts.
val launchApp by tasks.registering(Exec::class) {
    group = "application"
    description = "Starts DiPlayDesktop.exe from the app folder (diplay.home) in its own console window."
    val home = appHome.map { file(it) }
    doFirst {
        check(home.get().resolve("DiPlayDesktop.exe").isFile) { "no DiPlayDesktop.exe in ${home.get()}; run deploy first" }
    }
    commandLine("cmd", "/c", "start", "DiPlayDesktop", "/D", home.get().path, "DiPlayDesktop.exe")
}

val listDevices by tasks.registering(Exec::class) {
    group = "application"
    description = "Lists the Bluetooth radio, paired devices and sound devices with the deployed app."
    val home = appHome.map { file(it) }
    commandLine(home.get().resolve("DiPlayDesktop.exe").path, "devices")
}
