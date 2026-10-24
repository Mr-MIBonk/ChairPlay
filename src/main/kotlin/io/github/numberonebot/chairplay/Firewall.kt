package io.github.numberonebot.chairplay

import io.github.numberonebot.chairplay.session.FIREWALL_RULE
import io.github.numberonebot.chairplay.session.FirewallState
import io.github.numberonebot.chairplay.session.firewallHint
import io.github.numberonebot.chairplay.session.readFirewall
import io.github.numberonebot.chairplay.ui.FirewallStatus

/**
 * Says what keeps the iPhone out, if anything. A block rule wins over any allow rule, so one left by Windows'
 * first-run prompt is reported even when ours exists. The start screen shows the same.
 */
internal fun checkFirewall(port: Int): FirewallStatus {
    val program = ProcessHandle.current().info().command().orElse("java.exe")
    val hint = "from an administrator prompt:\n" + firewallHint(port, program)
    val state = readFirewall(program)
    if (state == null) {
        log("could not read the Windows Firewall rules; if the iPhone cannot connect, add these $hint")
        return FirewallStatus.UNKNOWN
    }
    if (state.off) {
        log("Windows Firewall is off for the networks this PC is on; ChairPlay needs no rule")
        return FirewallStatus.OFF
    }
    if (!state.allowRule) {
        // Another rule (Windows' own, from its first-run prompt) may let the iPhone in: ours is only missing.
        log("no Windows Firewall rule \"$FIREWALL_RULE\"; if the iPhone cannot connect, add it")
        log(hint)
    }
    state.blocking.forEach {
        log("Windows Firewall rule $it blocks inbound connections to $program; the iPhone cannot connect while " +
            "it applies. Delete it or set it to Allow in Windows Defender Firewall > Advanced settings > Inbound Rules")
    }
    return firewallStatus(state)
}

/** The firewall read again for the live setup checks, without the start's log lines. */
internal fun readFirewallStatus(): FirewallStatus =
    readFirewall(ProcessHandle.current().info().command().orElse("java.exe"))?.let(::firewallStatus) ?: FirewallStatus.UNKNOWN

private fun firewallStatus(state: FirewallState): FirewallStatus = when {
    state.off -> FirewallStatus.OFF
    state.blocking.isNotEmpty() -> FirewallStatus.BLOCKED
    !state.allowRule -> FirewallStatus.MISSING
    else -> FirewallStatus.PRESENT
}
