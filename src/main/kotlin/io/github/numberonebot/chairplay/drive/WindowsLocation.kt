package io.github.numberonebot.chairplay.drive

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Where Windows locates this PC, through its location service as desktop programs reach it (.NET's
 * GeoCoordinateWatcher, in Windows PowerShell), for putting the GPS car there. It answers within [WAIT_SECONDS], with a
 * place, a refusal (location off, or desktop apps not let use it), or no fix.
 */
object WindowsLocation {
    sealed interface Result

    data class Found(val place: GeoPoint) : Result

    /** Location is off for this PC, or desktop apps may not use it. */
    data object Denied : Result

    /** No position came in time, or the service could not be asked. */
    data object Unknown : Result

    private const val WAIT_SECONDS = 10L

    private val SCRIPT = """
        Add-Type -AssemblyName System.Device
        ${'$'}watcher = New-Object System.Device.Location.GeoCoordinateWatcher([System.Device.Location.GeoPositionAccuracy]::High)
        if (-not ${'$'}watcher.TryStart(${'$'}false, [TimeSpan]::FromSeconds($WAIT_SECONDS)) -or ${'$'}watcher.Permission -eq 'Denied') { 'denied'; exit 0 }
        ${'$'}until = (Get-Date).AddSeconds($WAIT_SECONDS)
        while (${'$'}watcher.Position.Location.IsUnknown -and (Get-Date) -lt ${'$'}until) { Start-Sleep -Milliseconds 200 }
        ${'$'}place = ${'$'}watcher.Position.Location
        ${'$'}watcher.Stop()
        if (${'$'}place.IsUnknown) { 'unknown' } else {
            ${'$'}place.Latitude.ToString([cultureinfo]::InvariantCulture) + ',' + ${'$'}place.Longitude.ToString([cultureinfo]::InvariantCulture)
        }
    """.trimIndent()

    /** Asks Windows; blocks for up to about twice [WAIT_SECONDS]. */
    fun read(): Result {
        val encoded = Base64.getEncoder().encodeToString(SCRIPT.toByteArray(Charsets.UTF_16LE))
        // A file rather than a pipe, so a PowerShell that hangs cannot hold the caller past the timeout.
        val output = File.createTempFile("chairplay-location", ".txt")
        return try {
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
            // With its standard input left open, powershell.exe can wait on it instead of exiting.
            process.outputStream.close()
            if (!process.waitFor(WAIT_SECONDS * 2 + 10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Unknown
            } else {
                parse(output.readText(Charsets.UTF_8))
            }
        } catch (_: Exception) {
            Unknown
        } finally {
            output.delete()
        }
    }

    /** What the script printed: a place as "latitude,longitude", "denied", or anything else for no fix. */
    internal fun parse(output: String): Result {
        val text = output.trim()
        if (text == "denied") return Denied
        return Coordinates.parse(text)?.let(::Found) ?: Unknown
    }
}
