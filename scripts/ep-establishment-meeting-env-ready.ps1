# Fail-fast Meeting ENV readiness gate (EP1 host lookups only; no field start)
param(
    [Parameter(Mandatory = $true)]
    [string]$BatchDir,
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "MDX0220416001963",
    [string]$M04Serial = "192.168.31.206:5555",
    [string]$HostModule = "M01",
    [switch]$Quad
)

$ErrorActionPreference = "Stop"
$scripts = $PSScriptRoot

if (-not (Test-Path (Join-Path $BatchDir "PROVISIONING_MANIFEST.txt"))) {
    Write-Error "missing batch manifest in $BatchDir — run ep-establishment-operator-provision-all.ps1 -Quad first"
}

$preArgs = @{
    BatchDir = $BatchDir
    M01Serial = $M01Serial
    M02Serial = $M02Serial
    HostModule = $HostModule
}
if ($Quad) {
    $preArgs.Quad = $true
    $preArgs.M03Serial = $M03Serial
    $preArgs.M04Serial = $M04Serial
} else {
    $manifest = Get-Content (Join-Path $BatchDir "PROVISIONING_MANIFEST.txt") -Raw
    if ($manifest -match "provisioning_topology=quad") {
        $preArgs.Quad = $true
        $preArgs.M03Serial = $M03Serial
        $preArgs.M04Serial = $M04Serial
    }
}

& (Join-Path $scripts "ep-establishment-operator-pre-check.ps1") @preArgs
exit $LASTEXITCODE
