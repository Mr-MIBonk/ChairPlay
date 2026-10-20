package com.shilapi.xcertplay.desktop.config

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopConfigTest {
    @Test
    fun `identity persists across loads`() {
        val dir = Files.createTempDirectory("diplay-config")
        writeTestSettings(dir)

        val first = DesktopConfig(dir).identity
        val second = DesktopConfig(dir).identity

        assertEquals(first.pairingId, second.pairingId)
        assertEquals(first.publicKeyHex, second.publicKeyHex)
    }
}

internal fun writeTestSettings(dir: Path) {
    Files.writeString(dir.resolve("settings.properties"), "wifi.ssid=Home\nwifi.passphrase=secret123\n")
}
