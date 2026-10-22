package io.github.numberonebot.chairplay.session

import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceRedactionTest {
    private val passphrase = "s3cret-Pass"
    private val passphraseHex = passphrase.toByteArray().joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun accessoryWiFiConfigurationKeepsOnlyItsHeader() {
        val frame = Iap2WirelessMessages.accessoryWiFiConfiguration(
            ssid = "Home",
            passphrase = passphrase,
            channel = 36,
            securityType = 3,
        )
        val trace = Iap2FrameFormatter.format(Iap2TraceDirection.TX, "wireless-rfcomm", frame)
        // The formatter really does print the secret both ways; otherwise this test proves nothing.
        assertTrue(trace.contains(passphrase))
        assertTrue(trace.contains(passphraseHex.substring(0, 11)))

        val redacted = redactTrace(trace)

        assertFalse(redacted.contains(passphrase))
        assertFalse(redacted.contains(passphraseHex.substring(0, 11)))
        assertFalse(redacted.contains('\n'))
        assertTrue(redacted.startsWith("IAP2 TX [wireless-rfcomm] 0x5703"))
        assertTrue(redacted.endsWith("[body redacted]"))
    }

    @Test
    fun framesWithoutAPassphraseAndOtherLinesPassUnchanged() {
        val other = "IAP2 RX [wireless-rfcomm] 0x5702 RequestAccessoryWiFiConfigurationInformation frame=16B body=0 params\n" +
            "  raw-body=<empty>"
        assertEquals(other, redactTrace(other))
        assertEquals("IAP2 CLOSE [probe]", redactTrace("IAP2 CLOSE [probe]"))
        assertEquals("iap2 tx=0x5703 accessory-wifi-configuration", redactTrace("iap2 tx=0x5703 accessory-wifi-configuration"))
    }
}
