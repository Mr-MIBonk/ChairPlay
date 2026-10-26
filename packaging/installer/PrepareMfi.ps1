# CI only: decoded files live outside the checkout and are never Gradle cache inputs.
param([Parameter(Mandatory = $true)][string]$Destination)
$ErrorActionPreference = 'Stop'
$key = $null
$certificate = $null
try {
    try {
        $key = [Convert]::FromBase64String($env:MFI_PRIVATE_KEY_B64)
        $certificate = [Convert]::FromBase64String($env:MFI_CERTIFICATE_B64)
    } catch { throw 'Both MFi secrets must contain valid Base64.' }
    if ($key.Length -lt 1 -or $key.Length -gt 16384 -or $certificate.Length -lt 1 -or $certificate.Length -gt 16384) {
        throw 'Both MFi secrets must decode to 1–16384 bytes.'
    }
    if (Test-Path -LiteralPath $Destination) { throw 'MFi staging directory already exists.' }
    $null = New-Item -ItemType Directory -Path $Destination
    [IO.File]::WriteAllBytes((Join-Path $Destination 'identity.pk8'), $key)
    [IO.File]::WriteAllBytes((Join-Path $Destination 'certificate.p7b'), $certificate)
} finally {
    if ($null -ne $key) { [Array]::Clear($key, 0, $key.Length) }
    if ($null -ne $certificate) { [Array]::Clear($certificate, 0, $certificate.Length) }
}
