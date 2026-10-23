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

/**
 * Whether our allow rule exists, the enabled inbound block rules for the program with their profiles, and whether the
 * firewall is off for the networks the PC is on, which lets the iPhone in without any rule.
 */
internal data class FirewallState(val allowRule: Boolean, val blocking: List<String>, val off: Boolean = false)

/**
 * Reads the rules only; changing them needs an administrator ([addFirewallRules] asks for the consent). Returns null
 * when they could not be read.
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
        Get-NetFirewallProfile -ErrorAction SilentlyContinue | ForEach-Object { "profile`t" + ${'$'}_.Name + "`t" + ${'$'}_.Enabled }
        Get-NetConnectionProfile -ErrorAction SilentlyContinue | ForEach-Object { "network`t" + ${'$'}_.NetworkCategory }
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

/**
 * Runs [commands] (one per line, as [firewallHint] writes them) in one cmd.exe started with the administrator's
 * consent, so Windows asks once. True when the consent was given and every command succeeded; false when it was
 * refused, a command failed, or nothing came back within five minutes, as a prompt left open would.
 */
fun addFirewallRules(commands: String): Boolean {
    val script = """
        try {
            ${'$'}line = ${'$'}env:CHAIRPLAY_COMMANDS -replace "`r?`n", ' && '
            ${'$'}process = Start-Process -FilePath cmd.exe -ArgumentList ('/c ' + ${'$'}line) -Verb RunAs -Wait -PassThru -WindowStyle Hidden
            exit ${'$'}process.ExitCode
        } catch {
            # The consent refused: Start-Process throws "The operation was canceled by the user".
            exit 1223
        }
    """.trimIndent()
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    return try {
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["CHAIRPLAY_COMMANDS"] = commands }
            .start()
        process.outputStream.close()
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    } catch (_: Exception) {
        false
    }
}

internal fun parseFirewallReport(report: String): FirewallState? {
    val lines = report.lines().map { it.trim('\uFEFF', '\r', ' ').split('\t') }
    val allow = lines.firstOrNull { it.size == 2 && it[0] == "allow" }?.get(1)?.toIntOrNull() ?: return null
    val blocking = lines.filter { it.size >= 3 && it[0] == "block" }
        .map { "\"${it[2]}\" (profiles ${it[1]})" }
    val enabled = lines.filter { it.size == 3 && it[0] == "profile" }.associate { it[1] to (it[2] == "True") }
    // A connected network's category names the profile that applies to it; DomainAuthenticated is the Domain one.
    val networks = lines.filter { it.size == 2 && it[0] == "network" }.map { if (it[1] == "DomainAuthenticated") "Domain" else it[1] }
    val applying = networks.ifEmpty { enabled.keys.toList() }
    val off = enabled.isNotEmpty() && applying.all { enabled[it] == false }
    return FirewallState(allow > 0, blocking, off)
}
