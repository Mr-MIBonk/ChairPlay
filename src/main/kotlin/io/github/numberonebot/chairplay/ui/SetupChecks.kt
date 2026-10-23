package io.github.numberonebot.chairplay.ui

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What Get started and the Connection page check on the PC: the paired iPhone's name, the PC's network, the firewall. */
data class SetupSnapshot(val pairedIphone: String?, val pcWifi: String?, val firewall: FirewallStatus)

/**
 * The setup as it is now, read again every few seconds so the steps tick themselves as the PC changes: an iPhone
 * paired, the PC on a network, a firewall rule added. Nothing is read while the page is not shown ([active]). The
 * firewall takes a PowerShell to read, so it is read again only while it is wrong (not while it is off), and at most every
 * [FIREWALL_EVERY_SECONDS]. A reader that fails reads as nothing found.
 */
class SetupChecks(
    private val pairedIphone: () -> String?,
    private val pcWifi: () -> String?,
    private val firewall: () -> FirewallStatus,
    /** The firewall as the start read it. */
    startFirewall: FirewallStatus,
    private val active: () -> Boolean,
    private val now: () -> Long = System::nanoTime,
) {
    @Volatile
    var snapshot = SetupSnapshot(null, null, startFirewall)
        private set

    private var firewallRead = now()

    /** One round of reading; true when something changed. */
    @Synchronized
    fun check(): Boolean {
        if (!active()) return false
        val before = snapshot
        var firewallNow = before.firewall
        val time = now()
        val settled = firewallNow == FirewallStatus.PRESENT || firewallNow == FirewallStatus.OFF
        if (!settled && time - firewallRead >= TimeUnit.SECONDS.toNanos(FIREWALL_EVERY_SECONDS)) {
            firewallRead = time
            firewallNow = runCatching(firewall).getOrDefault(firewallNow)
        }
        val after = SetupSnapshot(runCatching(pairedIphone).getOrNull(), runCatching(pcWifi).getOrNull(), firewallNow)
        snapshot = after
        return after != before
    }

    /** Reads the firewall now, as after Add the rule, whatever it was. */
    @Synchronized
    fun firewallNow() {
        firewallRead = now()
        snapshot = snapshot.copy(firewall = runCatching(firewall).getOrDefault(snapshot.firewall))
    }

    /** Reads every [EVERY_SECONDS] on a thread of its own, calling [onChange] after a round that changed something. */
    fun start(onChange: () -> Unit): Closeable {
        val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "setup-checks").apply { isDaemon = true } }
        executor.scheduleWithFixedDelay({ runCatching { if (check()) onChange() } }, 0, EVERY_SECONDS, TimeUnit.SECONDS)
        return Closeable { executor.shutdownNow() }
    }

    companion object {
        const val EVERY_SECONDS = 3L
        const val FIREWALL_EVERY_SECONDS = 10L
    }
}
