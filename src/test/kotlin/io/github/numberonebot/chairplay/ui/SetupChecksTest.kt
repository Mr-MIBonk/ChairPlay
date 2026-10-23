package io.github.numberonebot.chairplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SetupChecksTest {
    private var iphone: String? = null
    private var wifi: String? = null
    private var firewall = FirewallStatus.MISSING
    private var firewallReads = 0
    private var nanos = 0L
    private var active = true

    private fun checks(start: FirewallStatus = FirewallStatus.MISSING) = SetupChecks(
        pairedIphone = { iphone },
        pcWifi = { wifi },
        firewall = { firewallReads++; firewall },
        startFirewall = start,
        active = { active },
        now = { nanos },
    )

    private fun seconds(n: Long) {
        nanos += TimeUnit.SECONDS.toNanos(n)
    }

    @Test
    fun `a round reads the paired iPhone and the PC's Wi-Fi, and says whether anything changed`() {
        val checks = checks()
        assertEquals(SetupSnapshot(null, null, FirewallStatus.MISSING), checks.snapshot)

        iphone = "Kate's iPhone"
        wifi = "ChairPlay-5G"
        assertTrue(checks.check())
        assertEquals(SetupSnapshot("Kate's iPhone", "ChairPlay-5G", FirewallStatus.MISSING), checks.snapshot)

        seconds(3)
        assertFalse(checks.check())
    }

    // PowerShell is slow to start: the firewall is read again only while it is wrong, and at most every 10 s.
    @Test
    fun `the firewall is read again at most every ten seconds, and only while it is wrong`() {
        val checks = checks()

        checks.check()
        assertEquals("not again right after the start's own reading", 0, firewallReads)
        seconds(9)
        checks.check()
        assertEquals(0, firewallReads)

        seconds(1)
        firewall = FirewallStatus.PRESENT
        assertTrue(checks.check())
        assertEquals(1, firewallReads)
        assertEquals(FirewallStatus.PRESENT, checks.snapshot.firewall)

        seconds(60)
        checks.check()
        assertEquals("not once the rule is there", 1, firewallReads)
    }

    @Test
    fun `a firewall right at the start is not read again`() {
        for (start in listOf(FirewallStatus.PRESENT, FirewallStatus.OFF)) {
            val checks = checks(start = start)

            seconds(60)
            checks.check()

            assertEquals(start.name, 0, firewallReads)
        }
    }

    // Nothing is read while CarPlay covers the page: no one would see it.
    @Test
    fun `nothing is read while the page is not shown`() {
        val checks = checks()
        active = false
        iphone = "Kate's iPhone"
        seconds(30)

        assertFalse(checks.check())
        assertNull(checks.snapshot.pairedIphone)
        assertEquals(0, firewallReads)
    }

    @Test
    fun `a reader that fails reads as nothing found`() {
        val checks = SetupChecks({ error("no Bluetooth radio") }, { wifi }, { firewall }, FirewallStatus.MISSING, { true }, { nanos })
        wifi = "Home"

        assertTrue(checks.check())

        assertEquals(SetupSnapshot(null, "Home", FirewallStatus.MISSING), checks.snapshot)
    }
}
