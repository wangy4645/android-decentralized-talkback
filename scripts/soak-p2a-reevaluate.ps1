# P2-A — Completion Re-evaluate Seam soak (G-P2-A)
# Spec: docs/audit/p2a-completion-re-evaluate-seam.md §5
#
# Usage:
#   1. Install P2-A APK on M01/M02/M03
#   2. .\scripts\soak-p2a-reevaluate.ps1 -ClearOnly
#   3. Repro: M02 host 三方会 → M01 关 WiFi 20–30s → 开 WiFi（尽量在 timeout 前）→ 等 60s
#   4. .\scripts\soak-p2a-reevaluate.ps1 -CollectOnly
#   5. .\scripts\soak-p2a-reevaluate.ps1 -AnalyzeOnly -OutDir .\logs-p2a-reevaluate-...

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

$probeFilter = @(
    "RECOVERY_REEVALUATE",
    "RECOVERY_FINAL_EVALUATION",
    "RECOVERY_WAITING",
    "RECOVERY_REATTACH_DEFERRED",
    "RECOVERY_REATTACH",
    "RECOVERY_EDGE_",
    "RECOVERY_DECISION",
    "FAILED_MEDIA_RECOVERY",
    "RECOVERY_PRUNE_DEFERRED"
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
    $OutDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path "logs-p2a-reevaluate-$stamp"
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
    Write-Host "P2-A matrix summary for: $OutDir"
    Write-Host ""
    $rows = @(
        (Test-MatrixMarker -Files $files -Id "G-P2-A1" -Pattern "RECOVERY_REEVALUATE|RECOVERY_DECISION|RECOVERY_WAITING" -ExpectOn @("M01")),
        (Test-MatrixMarker -Files $files -Id "G-P2-A1b" -Pattern "RECOVERY_REEVALUATE" -ExpectOn @("M01")),
        (Test-MatrixMarker -Files $files -Id "G-R28-D" -Pattern "RECOVERY_REATTACH_DEFERRED.*WAITING_FOR_ROUTE|RECOVERY_WAITING.*WAITING_FOR_ROUTE" -ExpectOn @("M01")),
        (Test-MatrixMarker -Files $files -Id "G-P2-A-Final" -Pattern "RECOVERY_FINAL_EVALUATION" -ExpectOn @("M01", "M02")),
        (Test-MatrixMarker -Files $files -Id "Post-timeout-reeval" -Pattern "RECOVERY_REEVALUATE" -ExpectOn @("M01"))
    )
    $rows | Format-Table -AutoSize
    Write-Host "M01 re-evaluate chain:"
    Select-String -Path $files.M01 -Pattern "RECOVERY_(REEVALUATE|FINAL_EVALUATION|WAITING|DECISION|REATTACH_DEFERRED|FAILED_MEDIA)" -ErrorAction SilentlyContinue |
        ForEach-Object { $_.Line }
    Write-Host ""
    Write-Host "Gate hints:"
    Write-Host "  G-P2-A1 PASS: M01 has REEVALUATE or new DECISION/WAITING after WiFi restore"
    Write-Host "  G-P2-A3 OK: no premature RECOVERY_REATTACH_SENT required in P2-A"
    exit 0
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$connected = & $adb devices | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] }

foreach ($name in $devices.Keys) {
    $id = $devices[$name]
    if ($id -notin $connected) {
        Write-Host "[$name] skip (not connected: $id)"
        continue
    }
    if ($ClearOnly -or -not $CollectOnly) {
        Write-Host "[$name] logcat -c"
        & $adb -s $id logcat -c 2>&1 | Out-Null
    }
}

if ($ClearOnly) {
    Write-Host ""
    Write-Host "Cleared. Repro protocol:"
    Write-Host "  1. M02 host 三方会，M01/M03 入会，ACTIVE"
    Write-Host "  2. M01 关 WiFi 20–30s"
    Write-Host "  3. M01 开 WiFi（尽量在 attempt_timeout ~15s 前）"
    Write-Host "  4. 等 60s，不要操作 UI"
    Write-Host "  5. Run: .\scripts\soak-p2a-reevaluate.ps1 -CollectOnly"
    exit 0
}

foreach ($name in $devices.Keys) {
    $id = $devices[$name]
    if ($id -notin $connected) {
        Write-Host "[$name] skip collect (not connected)"
        continue
    }
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
& $PSScriptRoot\soak-p2a-reevaluate.ps1 -AnalyzeOnly -OutDir $OutDir
