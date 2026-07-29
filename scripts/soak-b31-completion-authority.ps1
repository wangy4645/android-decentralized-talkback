# B3.1 Recovery completion authority gold soak (ADR-0022 Q10-Q14)
# Scenario: host + peer conference -> peer WiFi flap that forces NEGOTIATION defer
#           (SIGNALING_NOT_STABLE / HAVE_LOCAL_OFFER) then signaling stable -> CAN_EXECUTE
#           -> EXECUTED -> post-dispatch ICE CONNECTED -> RECOVERED
#
# Usage:
#   1. Install APK built from fix/ignore-late-ice-after-recovered (post 8c0d004+)
#   2. .\scripts\soak-b31-completion-authority.ps1 -ClearOnly
#   3. Repro WiFi flap on recovery peer; wait until EDGE_RECOVERED (~60-90s)
#   4. .\scripts\soak-b31-completion-authority.ps1 -CollectOnly
#   5. .\scripts\soak-b31-completion-authority.ps1 -AnalyzeOnly -Stamp <stamp>
#
# AnalyzeOnly delegates to analyze-b31-completion-authority.ps1

param(
    [switch]$ClearOnly,
    [switch]$CollectOnly,
    [switch]$AnalyzeOnly,
    [string]$Stamp = "",
    [string]$Prefix = "b31-completion",
    [string]$HostModule = "M02",
    [string]$RecoveryPeer = "M03"
)

$devices = @{
    M01 = "HTUBB21B09220661"
    M02 = "2d73067a"
    M03 = "MDX0220416001963"
}

$probeFilter = @(
    "RECOVERY_",
    "ICE_RESTART_",
    "NEGOTIATION_",
    "OBLIGATION",
    "WAKEUP_",
    "COMPLETION_",
    "MEDIA_PATH",
    "CONTROL_PLANE",
    "GATE_BLOCKED",
    "DEFERRED"
) -join "|"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$logsDir = Join-Path $repoRoot "logs"

function Resolve-Adb {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "adb"
    )
    foreach ($c in $candidates) {
        if ($c -eq "adb") {
            $cmd = Get-Command adb -ErrorAction SilentlyContinue
            if ($cmd) { return $cmd.Source }
        } elseif (Test-Path $c) {
            return $c
        }
    }
    throw "adb not found."
}

function Ensure-Stamp {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return (Get-Date -Format "yyyyMMdd-HHmmss")
    }
    return $Value
}

$adb = Resolve-Adb

if ($ClearOnly) {
    foreach ($name in $devices.Keys) {
        $id = $devices[$name]
        & $adb -s $id logcat -c 2>$null
        Write-Host "cleared logcat $name ($id)"
    }
    Write-Host "Next: repro WiFi flap on $RecoveryPeer (host=$HostModule), wait EDGE_RECOVERED."
    Write-Host "Then: .\scripts\soak-b31-completion-authority.ps1 -CollectOnly"
    exit 0
}

if ($CollectOnly) {
    $Stamp = Ensure-Stamp $Stamp
    $outDir = Join-Path $logsDir "$Prefix-$Stamp"
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    @(
        "B3.1 completion authority soak"
        "host=$HostModule recoveryPeer=$RecoveryPeer"
        "started=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    ) | Set-Content (Join-Path $outDir "meta.txt") -Encoding UTF8

    foreach ($name in $devices.Keys) {
        $id = $devices[$name]
        $out = Join-Path $outDir "$name-talkback.log"
        Write-Host "dumping $name -> $out"
        & $adb -s $id logcat -d -v threadtime 2>$null |
            Select-String -Pattern $probeFilter |
            ForEach-Object { $_.Line } |
            Set-Content -Path $out -Encoding UTF8
    }
    Write-Host "Collect done: $outDir"
    Write-Host "Analyze: .\scripts\soak-b31-completion-authority.ps1 -AnalyzeOnly -Stamp $Stamp"
    exit 0
}

if ($AnalyzeOnly) {
    $Stamp = Ensure-Stamp $Stamp
    $outDir = Join-Path $logsDir "$Prefix-$Stamp"
    if (-not (Test-Path $outDir)) { throw "LogDir not found: $outDir" }
    & (Join-Path $PSScriptRoot "analyze-b31-completion-authority.ps1") -LogDir $outDir
    exit $LASTEXITCODE
}

Write-Host "Specify -ClearOnly, -CollectOnly, or -AnalyzeOnly"
exit 1