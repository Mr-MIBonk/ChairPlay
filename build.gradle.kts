import java.net.URI
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

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
    implementation("org.lwjgl:lwjgl-nanovg:3.3.4")
    runtimeOnly("org.lwjgl:lwjgl:3.3.4:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-glfw:3.3.4:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-opengl:3.3.4:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-nanovg:3.3.4:natives-windows")
    testImplementation("junit:junit:4.13.2")
}

// TcpLiveness reaches a socket's Winsock handle by reflection to set upstream's keep-alive timing, which
// JDK 17 does not offer on Windows. Every launcher passes the same opens: the start scripts, the tests
// and the app image.
val socketHandleOpens = listOf("java.base/java.net", "java.base/java.io").map { "--add-opens=$it=ALL-UNNAMED" }

application {
    mainClass.set("io.github.numberonebot.chairplay.MainKt")
    applicationDefaultJvmArgs = socketHandleOpens
}

interface FileOps {
    @get:Inject val fs: FileSystemOperations
    @get:Inject val archives: ArchiveOperations
}

// Upstream's call echo canceller (SpeexEchoCanceller) is SpeexDSP in C behind JNI, which DiPlay builds with the
// Android NDK. Here zig's C compiler builds the same sources into speex_echo.dll. Zig is a pinned release,
// downloaded once into the Gradle user home and checked against the SHA-256 in
// https://ziglang.org/download/index.json; moving to another release means changing both values.
val zigVersion = "0.16.0"
val zigSha256 = "68659eb5f1e4eb1437a722f1dd889c5a322c9954607f5edcf337bc3684a75a7e"
val zigName = "zig-x86_64-windows-$zigVersion"
val zigRoot: File = gradle.gradleUserHomeDir.resolve("caches/chairplay/zig")
val zigExe: File = zigRoot.resolve("$zigName/zig.exe")

val installZig by tasks.registering {
    description = "Downloads zig $zigVersion into the Gradle user home, unless it is there already."
    val ops = objects.newInstance<FileOps>()
    val root = zigRoot
    val exe = zigExe
    val name = zigName
    val url = "https://ziglang.org/download/$zigVersion/$name.zip"
    val sha256 = zigSha256
    // No declared output: Gradle would create the output's folder before the run, and the finished zig folder
    // is moved into place in one step. Once zig is there, the task only checks for zig.exe.
    doLast {
        if (exe.isFile) return@doLast
        root.mkdirs()
        // Unique names, so builds in two checkouts at once do not share half-written files.
        val part = "$name.${UUID.randomUUID()}.part"
        val zip = root.resolve("$part.zip")
        val staging = root.resolve(part)
        try {
            logger.lifecycle("Downloading $url")
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            connection.getInputStream().use { input ->
                DigestOutputStream(zip.outputStream(), digest).use { input.copyTo(it) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == sha256) { "$url has SHA-256 $actual, expected $sha256" }
            ops.fs.copy {
                from(ops.archives.zipTree(zip))
                into(staging)
            }
            check(staging.resolve("$name/zig.exe").isFile) { "$url has no $name/zig.exe" }
            // An empty folder left by an interrupted run would block the move.
            exe.parentFile.delete()
            try {
                Files.move(staging.resolve(name).toPath(), exe.parentFile.toPath())
            } catch (e: FileSystemException) {
                // Another build installed it first.
                if (!exe.isFile) throw e
            }
        } finally {
            ops.fs.delete { delete(zip, staging) }
        }
    }
}

// The sources and flags of upstream's shared/src/main/jni/Android.mk (module speex_echo). The JNI headers come
// from the build's JDK 17 toolchain. Zig links the DLL against Windows' own C runtime (UCRT), so it needs no
// DLL that Windows 10 or later lacks.
val speexCflags = listOf("-O2", "-DFLOATING_POINT", "-DUSE_KISS_FFT", "-DEXPORT=", "-Wno-unused-parameter")
val speexSources = listOf("speex_echo_jni.c") +
    listOf("mdf.c", "preprocess.c", "fftwrap.c", "kiss_fft.c", "kiss_fftr.c", "filterbank.c").map { "speexdsp/$it" }
val nativeDir = layout.buildDirectory.dir("native")

val speexEchoDll by tasks.registering(Exec::class) {
    group = "build"
    description = "Compiles upstream's SpeexDSP echo canceller into build/native/speex_echo.dll."
    dependsOn(installZig)
    val jni = layout.projectDirectory.dir("upstream/shared/src/main/jni")
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }.map { it.metadata }
    val dll = nativeDir.map { it.file("speex_echo.dll") }
    // Zig writes an import library and a PDB next to the DLL, so it links in the task's own folder and only the
    // DLL is kept.
    val work = temporaryDir
    val ops = objects.newInstance<FileOps>()
    inputs.files(speexSources.map { jni.file(it) }).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(jni.dir("speexdsp")) { include("**/*.h") }).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("zig", zigVersion)
    inputs.property("cflags", speexCflags)
    inputs.property("jdk", jdk.map { it.javaRuntimeVersion })
    outputs.file(dll)
    workingDir(jni)
    // Zig's caches stay out of %LOCALAPPDATA%: the global one next to zig, the local one in build/.
    environment("ZIG_GLOBAL_CACHE_DIR", zigRoot.resolve("cache").path)
    environment("ZIG_LOCAL_CACHE_DIR", work.resolve("zig-cache").path)
    doFirst {
        val include = jdk.get().installationPath.dir("include").asFile
        commandLine(
            listOf(zigExe.path, "cc", "-target", "x86_64-windows-gnu", "-shared") + speexCflags +
                listOf("-Ispeexdsp", "-Ispeexdsp/include", "-I${include.path}", "-I${include.resolve("win32").path}") +
                speexSources + listOf("-o", work.resolve("speex_echo.dll").path),
        )
    }
    doLast {
        ops.fs.copy {
            from(work.resolve("speex_echo.dll"))
            into(dll.get().asFile.parentFile)
        }
    }
}

tasks.test {
    jvmArgs(socketHandleOpens)
    dependsOn(speexEchoDll)
    systemProperty("java.library.path", nativeDir.get().asFile.path)
}

tasks.named<JavaExec>("run") {
    dependsOn(speexEchoDll)
    systemProperty("java.library.path", nativeDir.get().asFile.path)
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

// SpeexDSP is BSD-licensed too; its notice goes next to speex_echo.dll wherever that is shipped.
val speexLicence = layout.projectDirectory.file("upstream/shared/src/main/jni/speexdsp/COPYING")

// speex_echo.dll goes into bin/, not lib/: lib/ is jpackage's input, whose files land in the image's app/
// folder, which is not on the launcher's library path.
distributions.main {
    contents {
        from(concentusLicence) { rename { "LICENSE-concentus" } }
        from(speexLicence) { rename { "LICENSE-speexdsp" } }
        from(speexEchoDll) { into("bin") }
    }
}

// The start scripts load speex_echo.dll from bin/. The placeholder becomes the scripts' own APP_HOME variable,
// which both scripts set before DEFAULT_JVM_OPTS.
tasks.startScripts {
    defaultJvmOpts = socketHandleOpens + "-Djava.library.path=__APP_HOME__/bin"
    doLast {
        unixScript.writeText(unixScript.readText().replace("__APP_HOME__", "'\"\$APP_HOME\"'"))
        windowsScript.writeText(windowsScript.readText().replace("__APP_HOME__/bin", "%APP_HOME%\\bin"))
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
    // The UI's local HTTP server (com.sun.net.httpserver).
    "jdk.httpserver",
)

val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds build/jpackage/ChairPlay with the launcher ChairPlay.exe."
    dependsOn(tasks.installDist)
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    val installDir = layout.buildDirectory.dir("install/chairplay")
    val outDir = layout.buildDirectory.dir("jpackage")
    val upstream = layout.projectDirectory.dir("upstream")
    val notices = layout.projectDirectory.dir("notices")
    val thirdParty = layout.projectDirectory.file("THIRD_PARTY_NOTICES")
    // Written by packaging/icon/make_icon.py, as are the window icon PNGs in the resources.
    val icon = layout.projectDirectory.file("packaging/chairplay.ico")
    val ops = objects.newInstance<FileOps>()
    // Command-line arguments given to ChairPlay.exe replace the default `run`, so `devices` and
    // `probe-bt` work by passing them explicitly.
    doFirst {
        ops.fs.delete { delete(outDir) }
        commandLine(
            launcher.get().metadata.installationPath.file("bin/jpackage.exe").asFile.path,
            "--type", "app-image",
            "--name", "ChairPlay",
            "--input", installDir.get().dir("lib").asFile.path,
            "--main-jar", "chairplay.jar",
            "--main-class", "io.github.numberonebot.chairplay.MainKt",
            "--arguments", "run",
            "--win-console",
            "--icon", icon.asFile.path,
            "--add-modules", appImageModules.joinToString(","),
            "--dest", outDir.get().asFile.path,
            *socketHandleOpens.flatMap { listOf("--java-options", it) }.toTypedArray(),
        )
    }
    // jpackage on JDK 17 has no --app-content, so notices are copied into the image afterwards, and so is
    // speex_echo.dll: into the image's root next to ChairPlay.exe, because the launcher sets no
    // java.library.path and the JVM's default one on Windows starts with the folder of the running exe.
    doLast {
        ops.fs.copy {
            from(installDir.get().file("bin/speex_echo.dll"))
            from(installDir.get().file("LICENSE-concentus"))
            from(installDir.get().file("LICENSE-speexdsp"))
            from(layout.projectDirectory.file("LICENSE"))
            from(thirdParty)
            from(upstream.dir("docs/licenses/dependencies")) { into("licenses") }
            from(notices) { into("licenses") }
            into(outDir.get().dir("ChairPlay"))
        }
    }
}

// The app folder holds the program next to its data (settings, keys, pairings), which the app reads from
// its exe's folder. deploy refreshes the program there and never touches the data. It is dist/ChairPlay
// in the project (outside build/, which clean wipes), or the path in the chairplay.home property: on the
// command line (-Pchairplay.home=<path>) or, to keep one for good, in %USERPROFILE%\.gradle\gradle.properties.
val appHome: Provider<String> = providers.gradleProperty("chairplay.home")
    .orElse(layout.projectDirectory.dir("dist/ChairPlay").asFile.path)

val syncApp by tasks.registering(Sync::class) {
    description = "Copies the app image into the app folder (chairplay.home), keeping its settings, keys and pairings."
    dependsOn(jpackageImage)
    from(layout.buildDirectory.dir("jpackage/ChairPlay"))
    into(appHome)
    // The app data: settings, controls and car icon, the AirPlay identity, pairings, the MFi files and the data logs.
    preserve {
        include("settings.properties", "controls.properties", "carplay-icon.png", "window.properties", "identity.properties", "pairings/**", "offline-mfi/**", "screenshots/**", "logs/**")
        // Keep the optional Windows-style CarPlay icon alongside the active one.
        include("carplay-icon-windows.png")
    }
    // jpackage leaves ChairPlay.exe read-only, and Windows refuses to replace a read-only file: the copies stay
    // writable, so the next deploy can replace them.
    filePermissions { user { write = true } }
    // Sync deletes whatever else is there, so a mistyped folder must not be one with other files in it.
    val home = appHome.map { file(it) }
    doFirst {
        val target = home.get()
        val entries = target.list().orEmpty()
        val known = setOf("ChairPlay.exe", "settings.properties", "controls.properties", "carplay-icon.png", "identity.properties", "pairings", "offline-mfi")
        check(entries.isEmpty() || entries.any { it in known }) {
            "$target has files but no ChairPlay.exe or app data; refusing to replace its contents"
        }
        // A read-only exe left by an earlier deploy.
        target.resolve("ChairPlay.exe").takeIf { it.isFile && !it.canWrite() }?.setWritable(true)
    }
}

// Then writes the default settings, controls and car icon the folder lacks, so the Wi-Fi can be filled in
// before the first start; the templates live in the app's code (AppDefaults.kt).
val deploy by tasks.registering(JavaExec::class) {
    group = "distribution"
    description = "Installs the app into the app folder (chairplay.home) with default settings where it has none."
    dependsOn(syncApp)
    mainClass = "io.github.numberonebot.chairplay.config.AppDefaultsKt"
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs("-Djava.awt.headless=true")
    args(appHome.get())
}

// Starts the deployed app in its own console window, so it is stopped by closing its window: a task runner's
// stop button would kill it hard, and the iPhone then ignores new sessions until it restarts.
val launchApp by tasks.registering(Exec::class) {
    group = "application"
    description = "Starts ChairPlay.exe from the app folder (chairplay.home) in its own console window."
    val home = appHome.map { file(it) }
    doFirst {
        check(home.get().resolve("ChairPlay.exe").isFile) { "no ChairPlay.exe in ${home.get()}; run deploy first" }
    }
    commandLine("cmd", "/c", "start", "ChairPlay", "/D", home.get().path, "ChairPlay.exe")
}

val listDevices by tasks.registering(Exec::class) {
    group = "application"
    description = "Lists the Bluetooth radio, paired devices and sound devices with the deployed app."
    val home = appHome.map { file(it) }
    commandLine(home.get().resolve("ChairPlay.exe").path, "devices")
}

// ---- The web UI: the start and settings screens, in webui/ (React and TypeScript, built by Vite) ----

/**
 * A pinned download, as installZig does it for zig: fetched once into [target] under the Gradle user home, checked
 * against [sha256], unpacked beside it and moved into place in one step. [topFolder] is the archive's folder that
 * becomes [target] (none: the whole archive), [marker] a file that shows the install is complete.
 */
abstract class InstallArchive : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input @get:Optional abstract val topFolder: Property<String>
    /** Only these paths of the archive (Ant patterns); all of it when empty. */
    @get:Input abstract val only: ListProperty<String>
    @get:Input abstract val marker: Property<String>
    // Not an output: Gradle would create the folder before the run, and it is moved into place whole.
    @get:Internal abstract val target: Property<File>
    @get:Inject abstract val fs: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction
    fun install() {
        val dir = target.get()
        if (dir.resolve(marker.get()).isFile) return
        val root = dir.parentFile.apply { mkdirs() }
        // Unique names, so builds in two checkouts at once do not share half-written files.
        val part = "${dir.name}.${UUID.randomUUID()}.part"
        val archive = root.resolve("$part.zip")
        val staging = root.resolve(part)
        try {
            logger.lifecycle("Downloading ${url.get()}")
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = URI(url.get()).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            connection.getInputStream().use { input ->
                DigestOutputStream(archive.outputStream(), digest).use { input.copyTo(it) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == sha256.get()) { "${url.get()} has SHA-256 $actual, expected ${sha256.get()}" }
            val patterns = only.get()
            fs.copy {
                from(archives.zipTree(archive)) { if (patterns.isNotEmpty()) include(patterns) }
                into(staging)
            }
            val unpacked = topFolder.orNull?.let { staging.resolve(it) } ?: staging
            check(unpacked.resolve(marker.get()).isFile) { "${url.get()} has no ${marker.get()}" }
            // An empty folder left by an interrupted run would block the move.
            dir.delete()
            try {
                Files.move(unpacked.toPath(), dir.toPath())
            } catch (e: FileSystemException) {
                // Another build installed it first.
                if (!dir.resolve(marker.get()).isFile) throw e
            }
        } finally {
            fs.delete { delete(archive, staging) }
        }
    }
}

// Node runs the UI's build and tests only; nothing of it ships. The release is pinned like zig, with the SHA-256
// from https://nodejs.org/dist/v<version>/SHASUMS256.txt. npm checks every package against the integrity hashes
// in webui/package-lock.json, and installs them without running their install scripts.
val nodeVersion = "24.22.1"
val nodeDir: File = gradle.gradleUserHomeDir.resolve("caches/chairplay/node/node-v$nodeVersion-win-x64")
val webui = layout.projectDirectory.dir("webui")
val webuiResources = layout.buildDirectory.dir("webui/resources")

val installNode by tasks.registering(InstallArchive::class) {
    description = "Downloads Node.js $nodeVersion into the Gradle user home, unless it is there already."
    url = "https://nodejs.org/dist/v$nodeVersion/node-v$nodeVersion-win-x64.zip"
    sha256 = "d2046d28c2c3dcd211ac5145c0bf30e2047ef9dfb306693c8c6e2eccd8b95fa5"
    topFolder = "node-v$nodeVersion-win-x64"
    marker = "node.exe"
    target = nodeDir
}

/** Runs [args] with the pinned node in webui/, with that node first on the PATH for the tools it starts. */
fun Exec.node(vararg args: String) {
    dependsOn(installNode)
    workingDir(webui)
    environment("PATH", nodeDir.path + File.pathSeparator + System.getenv("PATH"))
    // npm's cache stays beside node instead of in %LOCALAPPDATA%.
    environment("npm_config_cache", nodeDir.resolveSibling("npm-cache").path)
    environment("npm_config_update_notifier", "false")
    commandLine(listOf(nodeDir.resolve("node.exe").path) + args)
}

val npmCi by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs the UI's packages from webui/package-lock.json into webui/node_modules."
    node(nodeDir.resolve("node_modules/npm/bin/npm-cli.js").path, "ci", "--ignore-scripts", "--no-audit", "--no-fund")
    inputs.files(webui.file("package.json"), webui.file("package-lock.json")).withPathSensitivity(PathSensitivity.RELATIVE)
    // npm's own record of the install, rather than the many thousand files beside it.
    outputs.file(webui.file("node_modules/.package-lock.json"))
}

// The page draws with the HUD's font files and shows the window's icon, which Vite copies from the app's resources.
val webuiSources = files(
    webui.dir("src"), webui.file("index.html"), webui.file("vite.config.ts"), webui.file("tsconfig.json"),
    webui.file("package-lock.json"), layout.projectDirectory.dir("src/main/resources/fonts"),
    layout.projectDirectory.dir("src/main/resources/icons"),
)

val webuiTypecheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Type-checks the UI's TypeScript."
    dependsOn(npmCi)
    node("node_modules/typescript/bin/tsc", "--noEmit", "-p", "tsconfig.json")
    val done = layout.buildDirectory.file("webui/typecheck.done")
    inputs.files(webuiSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(done)
    doLast { done.get().asFile.writeText("type-checked\n") }
}

val webuiBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the UI with Vite into build/webui/resources/ui, which goes into the jar."
    dependsOn(npmCi, webuiTypecheck)
    val out = webuiResources.map { it.dir("ui") }
    node("node_modules/vite/bin/vite.js", "build", "--outDir", out.get().asFile.path, "--emptyOutDir")
    inputs.files(webuiSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(out)
}

val webuiTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the UI's Vitest tests; the report goes to build/test-results/webui."
    dependsOn(npmCi)
    val report = layout.buildDirectory.file("test-results/webui/TEST-webui.xml")
    node(
        "node_modules/vitest/vitest.mjs", "run", "--reporter=default", "--reporter=junit",
        "--outputFile.junit=${report.get().asFile.path}",
    )
    inputs.files(webuiSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(report)
}

sourceSets {
    main {
        resources {
            srcDir(files(webuiResources).builtBy(webuiBuild))
        }
    }
}

// The About page's build: this repository's commit and the DiPlay core's (the upstream submodule), each with its
// date, in build-info.properties (BuildInfo). The commits move without a file Gradle watches, so the task runs every
// build; it writes the file only when that changes, which leaves the jar up to date otherwise.
val buildInfoResources = layout.buildDirectory.dir("generated/build-info")
val buildInfo by tasks.registering {
    group = "build"
    description = "Writes the app's and the DiPlay core's commits for the About page."
    val out = buildInfoResources.map { it.file("build-info.properties") }
    val root = layout.projectDirectory.asFile
    outputs.file(out)
    outputs.upToDateWhen { false }
    doLast {
        fun commit(dir: File): List<String> = runCatching {
            val process = ProcessBuilder("git", "log", "-1", "--format=%h %cs", "--abbrev=8").directory(dir).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0) text.split(' ') else emptyList()
        }.getOrDefault(emptyList())
        val lines = listOf("app" to commit(root), "core" to commit(root.resolve("upstream")))
            .filter { (_, commit) -> commit.size == 2 }
            .flatMap { (name, commit) -> listOf("$name.commit=${commit[0]}", "$name.date=${commit[1]}") }
        val text = lines.joinToString("") { "$it\n" }
        val file = out.get().asFile
        if (!file.exists() || file.readText() != text) {
            file.parentFile.mkdirs()
            file.writeText(text)
        }
    }
}

sourceSets {
    main {
        resources {
            srcDir(files(buildInfoResources).builtBy(buildInfo))
        }
    }
}

// The car button's default icon, drawn by packaging/icon/make_icon.py: the app writes it into its folder (CarIcon).
tasks.processResources {
    from("packaging/icon/carplay-icon.png") { into("icons") }
}

// The UI's tests are part of the suite.
tasks.test {
    dependsOn(webuiTest)
}

// ---- The UI's web view: WebView2 as a child of the main window, through chairplay_webview.dll ----

// The webview library (https://github.com/webview/webview, MIT) embeds WebView2 into an existing window; zig's
// C++ compiler builds it with our few additions (src/native/webview) into chairplay_webview.dll. The headers
// come from Microsoft's WebView2 SDK on NuGet, whose WebView2Loader.dll ships beside the DLL: the library uses
// it when it is there, which honours WebView2's environment variables (the profile folder among them), and
// finds the runtime itself when it is not. Both downloads are pinned by version and SHA-256 (GitHub's archive of
// the release tag; the .nupkg from api.nuget.org).
val webviewVersion = "0.12.0"
val webview2SdkVersion = "1.0.4258.31"
val webviewSources: File = gradle.gradleUserHomeDir.resolve("caches/chairplay/webview/webview-$webviewVersion")
val webview2Sdk: File = gradle.gradleUserHomeDir.resolve("caches/chairplay/webview2/microsoft.web.webview2.$webview2SdkVersion")

val installWebviewSources by tasks.registering(InstallArchive::class) {
    description = "Downloads the webview library's $webviewVersion sources into the Gradle user home."
    url = "https://github.com/webview/webview/archive/refs/tags/$webviewVersion.zip"
    sha256 = "446ec9730ad4a1574f5364a62ec5989e78c8a59d0497082e6fbf6b423654a9bb"
    topFolder = "webview-$webviewVersion"
    only = listOf("webview-$webviewVersion/core/include/**")
    marker = "core/include/webview/webview.h"
    target = webviewSources
}

val installWebView2Sdk by tasks.registering(InstallArchive::class) {
    description = "Downloads the WebView2 SDK $webview2SdkVersion (headers and loader) into the Gradle user home."
    url = "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$webview2SdkVersion/microsoft.web.webview2.$webview2SdkVersion.nupkg"
    sha256 = "56f7f4b8bf9aee4b8efefbbdd4f67d5f74ebd1b100ed0806da71bf76af481aa9"
    only = listOf("build/native/include/**", "build/native/x64/WebView2Loader.dll")
    marker = "build/native/include/WebView2.h"
    target = webview2Sdk
}

val webviewDll by tasks.registering(Exec::class) {
    group = "build"
    description = "Compiles the UI's web view host into build/native/chairplay_webview.dll, with WebView2Loader.dll beside it."
    dependsOn(installZig, installWebviewSources, installWebView2Sdk)
    val source = layout.projectDirectory.dir("src/native/webview")
    val dll = nativeDir.map { it.file("chairplay_webview.dll") }
    val loader = nativeDir.map { it.file("WebView2Loader.dll") }
    val work = temporaryDir
    val ops = objects.newInstance<FileOps>()
    val sources = webviewSources
    val sdk = webview2Sdk
    inputs.dir(source).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("zig", zigVersion)
    inputs.property("webview", webviewVersion)
    inputs.property("webview2", webview2SdkVersion)
    outputs.files(dll, loader)
    workingDir(source)
    environment("ZIG_GLOBAL_CACHE_DIR", zigRoot.resolve("cache").path)
    environment("ZIG_LOCAL_CACHE_DIR", work.resolve("zig-cache").path)
    // libc++ is linked in statically, and Windows' own C runtime (UCRT) is used, as for speex_echo.dll.
    commandLine(
        zigExe.path, "c++", "-target", "x86_64-windows-gnu", "-shared", "-O2", "-std=c++17",
        "-I${sources.resolve("core/include").path}", "-I${sdk.resolve("build/native/include").path}", "-Icompat",
        "chairplay_webview.cc", "-ladvapi32", "-lole32", "-lshell32", "-lshlwapi", "-luser32", "-lversion",
        "-o", work.resolve("chairplay_webview.dll").path,
    )
    doLast {
        ops.fs.copy {
            from(work.resolve("chairplay_webview.dll"))
            from(sdk.resolve("build/native/x64/WebView2Loader.dll"))
            into(dll.get().asFile.parentFile)
        }
    }
}

tasks.test {
    dependsOn(webviewDll)
}

tasks.named<JavaExec>("run") {
    dependsOn(webviewDll)
}

// The DLLs go where speex_echo.dll goes: bin/ in the distribution, and the app image's root, next to the exe,
// where Windows looks for a DLL's own dependencies (WebView2Loader.dll) too. Their licences are in notices/.
distributions.main {
    contents {
        from(webviewDll) { into("bin") }
    }
}

// The HUD's maneuver renderer: the MHI2Q project's maneuver_render (native/maneuver, see its README) with
// ChairPlay's engine, GL layer and JNI entry points (native/maneuver/chairplay), built by the same zig into
// chairplay_maneuver.dll. It goes wherever speex_echo.dll goes: build/native for tests and run, bin/ of the
// distribution, the app image's root. The C++ part links zig's static libc++, so the DLL needs only Windows' UCRT.
val maneuverCSources = listOf("render.c", "maneuver.c", "route_path.c") +
    listOf("engine.c", "gl_core.c", "jni.c").map { "chairplay/$it" }
val maneuverCxxSources = listOf("scene.cpp", "geometry.cpp", "layout.cpp", "lane_panel.cpp").map { "scene/$it" }
// Upstream's flags (maneuver_render/Makefile).
val maneuverDefines = listOf("-DPLATFORM_CHAIRPLAY")
val maneuverCFlags = listOf("-std=gnu99", "-Wall", "-Wextra", "-Wno-unused-parameter")
val maneuverCxxFlags = listOf("-std=c++11", "-fno-exceptions", "-fno-rtti", "-Wall")

val maneuverDll by tasks.registering(Exec::class) {
    group = "build"
    description = "Compiles the HUD's maneuver renderer into build/native/chairplay_maneuver.dll."
    dependsOn(installZig)
    val src = layout.projectDirectory.dir("native/maneuver")
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }.map { it.metadata }
    val dll = nativeDir.map { it.file("chairplay_maneuver.dll") }
    val work = temporaryDir
    val ops = objects.newInstance<FileOps>()
    inputs.files(fileTree(src) { include("**/*.c", "**/*.cpp", "**/*.h", "**/*.hpp") }).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("zig", zigVersion)
    inputs.property("flags", maneuverDefines + maneuverCFlags + maneuverCxxFlags)
    inputs.property("jdk", jdk.map { it.javaRuntimeVersion })
    outputs.file(dll)
    workingDir(src)
    environment("ZIG_GLOBAL_CACHE_DIR", zigRoot.resolve("cache").path)
    environment("ZIG_LOCAL_CACHE_DIR", work.resolve("zig-cache").path)
    doFirst {
        val include = jdk.get().installationPath.dir("include").asFile
        commandLine(
            listOf(
                zigExe.path, "build-lib", "-dynamic", "-target", "x86_64-windows-gnu", "-O", "ReleaseFast",
                "-lc", "-lc++", "-lopengl32", "--name", "chairplay_maneuver",
                "-femit-bin=${work.resolve("chairplay_maneuver.dll").path}",
                "-I.", "-Ichairplay", "-I${include.path}", "-I${include.resolve("win32").path}",
            ) + maneuverDefines + listOf("-cflags") + maneuverCFlags + listOf("--") + maneuverCSources +
                listOf("-cflags") + maneuverCxxFlags + listOf("--") + maneuverCxxSources,
        )
    }
    doLast {
        ops.fs.copy {
            from(work.resolve("chairplay_maneuver.dll"))
            into(dll.get().asFile.parentFile)
        }
    }
}

// The render test runs the DLL: a new build of it runs the tests again.
tasks.test { inputs.files(maneuverDll).withPropertyName("maneuverDll") }
tasks.named<JavaExec>("run") { dependsOn(maneuverDll) }
distributions.main { contents { from(maneuverDll) { into("bin") } } }

tasks.named<Exec>("jpackageImage") {
    val installDir = layout.buildDirectory.dir("install/chairplay")
    val outDir = layout.buildDirectory.dir("jpackage")
    val ops = objects.newInstance<FileOps>()
    doLast {
        ops.fs.copy {
            from(installDir.get().file("bin/chairplay_webview.dll"), installDir.get().file("bin/WebView2Loader.dll"))
            from(installDir.get().file("bin/chairplay_maneuver.dll"))
            into(outDir.get().dir("ChairPlay"))
        }
    }
}
