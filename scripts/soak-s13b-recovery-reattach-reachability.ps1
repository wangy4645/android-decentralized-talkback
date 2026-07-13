# S13-B — Recovery Reattach Reachability soak (#73-B Phase 1b)
# Spec: docs/audit/s13b-recovery-reattach-reachability.md §9
#
# Usage:
#   1. Install probe APK on M01/M02/M03
#   2. .\scripts\soak-s13b-recovery-reattach-reachability.ps1 -ClearOnly
#   3. Repro: M02 host 三方会 → M01 关 WiFi 20–30s → 开 WiFi → 等 60s
#   4. .\scripts\soak-s13b-recovery-reattach-reachability.ps1 -CollectOnly
#   5. .\scripts\soak-s13b-recovery-reattach-reachability.ps1 -AnalyzeOnly -OutDir .\logs-s13b-...

param(
    [switch]$ClearOnly,
    [switch]$CollectOnly,
    [switch]$AnalyzeOnly,
    [string]$OutDir = ""
)

$devices = @{
    M01 = "HTUBB21B09220661"
    M02 = "2d73067a"
    M03 = "MDX0220416001963"
}

# ADR-0022: RECOVERY_WAITING / DEFERRED are protocol state, not debug noise.
$probeFilter = @(
    "RECOVERY_WAITING",
    "RECOVERY_REATTACH_DEFERRED",
    "RECOVERY_REATTACH",
    "RECOVERY_EDGE_",
    "RECOVERY_PRUNE_DEFERRED",
    "FAILED_MEDIA_RECOVERY",
    "RECOVERY_DECISION",
    "Signal send failed",
    "Conference rejoin skipped",
    "TRANSPORT_NOT_READY",
    "HELLO"
) -join "|"

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
    throw "adb not found. Install Android SDK platform-tools or set ANDROID_HOME."
}

function Test-MatrixMarker {
    param(
        [hashtable]$Files,
        [string]$Id,
        [string]$Pattern,
        [string[]]$ExpectOn = @("M01", "M02", "M03")
    )
    $hits = @()
    foreach ($name in $ExpectOn) {
        $path = $Files[$name]
        if (-not $path -or -not (Test-Path $path)) { continue }
        $match = Select-String -Path $path -Pattern $Pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($match) { $hits += $name }
    }
    $status = if ($hits.Count -gt 0) { "YES ($($hits -join ','))" } else { "NO" }
    [PSCustomObject]@{ Id = $Id; Pattern = $Pattern; Status = $status }
}

$adb = Resolve-Adb

if (-not $OutDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path "logs-s13b-reattach-reachability-$stamp"
}

if ($AnalyzeOnly) {
    if (-not (Test-Path $OutDir)) {
        Write-Error "OutDir not found: $OutDir"
        exit 1
    }
    $files = @{}
    foreach ($name in $devices.Keys) {
        $files[$name] = Join-Path $OutDir "$name.txt"
    }
    Write-Host "S13-B matrix summary for: $OutDir"
    Write-Host ""
    $rows = @(
        (Test-MatrixMarker -Files $files -Id "G-R28-D1" -Pattern "RECOVERY_REATTACH_DEFERRED.*WAITING_FOR_ROUTE|RECOVERY_WAITING.*WAITING_FOR_ROUTE" -ExpectOn @("M01")),
        (Test-MatrixMarker -Files $files -Id "G-R28-D2" -Pattern "RECOVERY_REATTACH_SENT" -ExpectOn @("M01")),
        (Test-MatrixMarker -Files $files -Id "S13-A1" -Pattern "RECOVERY_REATTACH requested|RECOVERY_REATTACH_REQUESTED" -ExpectOn @("M01")),
        (Test-MatrixMarker -Files $files -Id "S13-A2" -Pattern "RECOVERY_REATTACH_ENQUEUED" -ExpectOn @("M01")),
        (Test-MatrixMarker -Files $files -Id "S13-A3" -Pattern "RECOVERY_REATTACH_SENT" -ExpectOn @("M01")),
        (Test-MatrixMarker -Files $files -Id "S13-A3'" -Pattern "RECOVERY_REATTACH_SEND_FAILED" -ExpectOn @("M01")),
        (Test-MatrixMarker -Files $files -Id "S13-B1" -Pattern "RECOVERY_REATTACH_INBOUND" -ExpectOn @("M02")),
        (Test-MatrixMarker -Files $files -Id "S13-B2" -Pattern "RECOVERY_REATTACH accepted|RECOVERY_REATTACH_ACCEPTED" -ExpectOn @("M02")),
        (Test-MatrixMarker -Files $files -Id "S13-E" -Pattern "RECOVERY_EDGE_RECOVERED" -ExpectOn @("M01", "M02", "M03"))
    )
    $rows | Format-Table -AutoSize
    Write-Host "R28 gate (M01):"
    Select-String -Path $files.M01 -Pattern "RECOVERY_(WAITING|REATTACH_DEFERRED|REATTACH_ENQUEUED|REATTACH_SENT|REATTACH_SEND_FAILED)" -ErrorAction SilentlyContinue |
        ForEach-Object { $_.Line }
    Write-Host ""
    Write-Host "Host inbound (M02):"
    Select-String -Path $files.M02 -Pattern "RECOVERY_REATTACH_(INBOUND|accepted)" -ErrorAction SilentlyContinue |
        ForEach-Object { $_.Line }
    Write-Host ""
    Write-Host "Hypothesis hints:"
    Write-Host "  H1a-1: A2/A3 NO or SEND_FAILED + peerReachable=false"
    Write-Host "  H1a-2: A3 YES but B1 NO (sent locally, not routed to host)"
    Write-Host "  H1b:   B1 YES but B2 NO (host rejects / lineage)"
    exit 0
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

foreach ($name in $devices.Keys) {
    $id = $devices[$name]
    if ($ClearOnly -or -not $CollectOnly) {
        Write-Host "[$name] logcat -c"
        & $adb -s $id logcat -c 2>&1 | Out-Null
    }
}

if ($ClearOnly) {
    Write-Host ""
    Write-Host "Cleared. Repro protocol (§9):"
    Write-Host "  1. M02 host 三方会，M01/M03 入会，ACTIVE"
    Write-Host "  2. M01 关 WiFi 20–30s"
    Write-Host "  3. M01 开 WiFi（尽量在 attempt_timeout ~15s 前）"
    Write-Host "  4. 等 60s，不要操作 UI"
    Write-Host "  5. Run: .\scripts\soak-s13b-recovery-reattach-reachability.ps1 -CollectOnly"
    exit 0
}

foreach ($name in $devices.Keys) {
    $id = $devices[$name]
    $out = Join-Path $OutDir "$name.txt"
    Write-Host "[$name] collecting -> $out"
    & $adb -s $id logcat -d -v time -s Talkback:I 2>&1 |
        Select-String -Pattern $probeFilter |
        Set-Content -Path $out -Encoding utf8
    $lines = (Get-Content $out -ErrorAction SilentlyContinue | Measure-Object -Line).Lines
    Write-Host "  $lines lines"
}

Write-Host ""
Write-Host "OUT=$OutDir"
Write-Host "Analyze matrix:"
Write-Host "  .\scripts\soak-s13b-recovery-reattach-reachability.ps1 -AnalyzeOnly -OutDir `"$OutDir`""

& $PSScriptRoot\soak-s13b-recovery-reattach-reachability.ps1 -AnalyzeOnly -OutDir $OutDir
