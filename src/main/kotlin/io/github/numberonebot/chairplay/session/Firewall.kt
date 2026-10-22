package io.github.numberonebot.chairplay.session

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The name of the rules [firewallHint] adds. Windows names the rules it creates from its own first-run
 * prompt after the program, "ChairPlay" for the packaged exe; answering Cancel there creates a block
 * rule, and allowing only private networks does nothing on a public one. A check by that name would find
 * Windows' rule and stay silent exactly when the iPhone cannot connect.
 */
const val FIREWALL_RULE = "ChairPlay CarPlay inbound"

/** Whether our allow rule exists, and the enabled inbound block rules for the program with their profiles. */
internal data class FirewallState(val allowRule: Boolean, val blocking: List<String>)

/**
 * Reads the rules only; changing them needs an administrator and is left to the user. Returns null when they
 * could not be read.
 *
 * netsh's verbose listing translates both its labels and its values (Action: Allow / Block) into the display
 * language, so it is not parsed. The NetSecurity cmdlets report Enabled, Direction, Action and Profile as enum
 * names on every language. The program filter compares paths as stored, so a rule written with an environment
 * variable (%ProgramFiles%\...) is not found.
 */
internal fun readFirewall(program: String): FirewallState? {
    val script = """
        [Console]::OutputEncoding = [Text.Encoding]::UTF8
        ${'$'}allow = @(Get-NetFirewallRule -DisplayName ${'$'}env:CHAIRPLAY_RULE -ErrorAction SilentlyContinue |
            Where-Object { ${'$'}_.Enabled -eq 'True' -and ${'$'}_.Direction -eq 'Inbound' -and ${'$'}_.Action -eq 'Allow' })
        "allow`t" + ${'$'}allow.Count
        Get-NetFirewallApplicationFilter -Program ${'$'}env:CHAIRPLAY_PROGRAM -ErrorAction SilentlyContinue | Get-NetFirewallRule |
            Where-Object { ${'$'}_.Enabled -eq 'True' -and ${'$'}_.Direction -eq 'Inbound' -and ${'$'}_.Action -eq 'Block' } |
            ForEach-Object { "block`t" + ${'$'}_.Profile + "`t" + ${'$'}_.DisplayName }
        # A program without rules is a silenced error, which would still make the exit code 1.
        exit 0
    """.trimIndent()
    // An encoded command and environment variables keep quotes and spaces in the path out of command-line parsing.
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    // A file rather than a pipe, so a PowerShell that hangs cannot hold the start-up past the timeout.
    val output = File.createTempFile("chairplay-firewall", ".txt")
    return try {
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
            .redirectOutput(output)
            .apply {
                environment()["CHAIRPLAY_RULE"] = FIREWALL_RULE
                environment()["CHAIRPLAY_PROGRAM"] = program
            }
            .start()
        // With its standard input left open, powershell.exe can wait on it instead of exiting.
        process.outputStream.close()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() != 0) {
            null
        } else {
            parseFirewallReport(output.readText(Charsets.UTF_8))
        }
    } catch (_: Exception) {
        null
    } finally {
        output.delete()
    }
}

internal fun parseFirewallReport(report: String): FirewallState? {
    val lines = report.lines().map { it.trim('\uFEFF', '\r', ' ').split('\t') }
    val allow = lines.firstOrNull { it.size == 2 && it[0] == "allow" }?.get(1)?.toIntOrNull() ?: return null
    val blocking = lines.filter { it.size >= 3 && it[0] == "block" }
        .map { "\"${it[2]}\" (profiles ${it[1]})" }
    return FirewallState(allow > 0, blocking)
}
