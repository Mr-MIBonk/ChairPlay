package io.github.numberonebot.chairplay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallTest {
    @Test
    fun `report with our rule and a block rule from the first-run prompt`() {
        val report = "\uFEFFallow\t3\r\n" +
            "block\tPublic\tChairPlay\r\n" +
            "block\tPrivate, Public\tЗаблоковано ChairPlay\r\n"

        val state = parseFirewallReport(report)!!

        assertTrue(state.allowRule)
        assertEquals(
            listOf("\"ChairPlay\" (profiles Public)", "\"Заблоковано ChairPlay\" (profiles Private, Public)"),
            state.blocking,
        )
    }

    @Test
    fun `no rule of ours and nothing blocking`() {
        assertEquals(FirewallState(allowRule = false, blocking = emptyList()), parseFirewallReport("allow\t0\n"))
    }

    @Test
    fun `output without the allow line means the query failed`() {
        assertNull(parseFirewallReport(""))
        assertNull(parseFirewallReport("Get-NetFirewallRule : Access is denied.\n"))
    }

    /** Reads this machine's rules (never changes them): the script must at least run and report. */
    @Test
    fun `the query runs against the real firewall`() {
        val state = readFirewall("C:\\no such folder\\DiPlay test.exe")

        assertNotNull(state)
        assertFalse(state!!.blocking.any())
    }
}
