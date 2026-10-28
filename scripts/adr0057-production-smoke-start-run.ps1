# P0.2 — Production Case A Smoke: force-stop, clear logcat, start collectors
param(
    [string]$LogDir = "",
    [string]$LogBuffer = "16M"
)

$ErrorActionPreference = "Continue"
$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "DSJ-2407041"
    "M03" = "MDX0220416001963"
}
$M04 = "DSJ-2407011"
$Package = "com.talkback.appprod"
$ProdAarVersion = "1.3.10-talkback-f8-fix1"
$ProdSoArm64 = "e1c28990307c3ed20bb29760e1f30278655d11f4100e52742269b51c8af3cc84"
$ProdSoV7 = "3289469ce96907ac031d81b5b68db8ff67a49c6a6b4ef856e3bdf1dbfd445ded"

$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "logs\adr0057-production-smoke-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $head) { $head = "unknown" }

@(
    "case=ADR0057_PRODUCTION_CASE_A_SMOKE"
    "phase=P0.2"
    "build=$head"
    "apk=talkback-app-debug.apk"
    "artifact=talkback-production-stream-webrtc-android.aar"
    "webrtcVersion=$ProdAarVersion"
    "instrumentation=FORBIDDEN"
    "adr0057=CLOSED_UNCHANGED"
    "mode=PRODUCTION_SMOKE"
    "observe=GROUP_INVITE;GROUP_ACCEPT;IGIE_;ICE_CONNECTED;ICE_TRANSPORT_CANDIDATE;transmitReady;GROUP_TRANSITION_READINESS_SNAPSHOT;GENERATED;meshRecoveryState"
    "forbiddenObserve=F8_NATIVE_;F8_PORT_;F8_CANDIDATE_;pnsrd-inst"
    "prodSoArm64=$ProdSoArm64"
    "prodSoV7=$ProdSoV7"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

$steps = @'
# P0.2 — Production Case A Smoke

Artifact: talkback-production-stream-webrtc-android.aar (1.3.10-talkback-f8-fix1)
Instrumentation: FORBIDDEN
ADR-0057: CLOSED / UNCHANGED

LogDir: <LogDir>
Stop:
  .\scripts\adr0057-production-smoke-stop-run.ps1 -LogDir "<LogDir>"
Adjudicate:
  .\scripts\adr0057-production-smoke-adjudicate.ps1 -LogDir "<LogDir>"

F2: M04 force-stopped — MUST NOT open Talkback on M04

---

## Procedure

1. M04: keep force-stopped
2. Cold start Talkback: M01 → M02 → M03 (force-stop then reopen)
3. Each device: Talk page only (no Meeting / PTT)
4. Idle ~5 minutes
5. Run stop script, then adjudicate

## Gates

G1  Production artifact identity (pre-run verify)
G2  Required triad ICE edges → ICE_CONNECTED
G3  M01/M02/M03 transmitReady=true joinedMembers=M01,M02,M03 OPERATIONAL
G4  Post-triad stability (~5 min): no sustained transmitReady=false, no terminal ICE loss

Do NOT require fallback_same_network_best_ip (inst-only evidence).
'@
$steps = $steps.Replace("<LogDir>", $LogDir)
Set-Content (Join-Path $LogDir "TEST_STEPS.txt") -Value $steps -Encoding UTF8

$m04State = (& $adb -s $M04 get-state 2>&1 | Out-String).Trim()
if ($m04State -notmatch '^device$') { $m04State = "offline" }
if ($m04State -eq "device") {
    & $adb -s $M04 shell am force-stop $Package
    Write-Host "FORCE-STOP M04 ($M04) [F2 isolation]"
} else {
    Write-Host "M04 ($M04) offline — isolation OK if app not running on LAN"
}

$online = @()
foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $state = & $adb -s $serial get-state 2>$null
    if ($state -ne "device") {
        Write-Warning "OFFLINE $name ($serial) state=$state"
        continue
    }
    $online += $name
    & $adb -s $serial shell am force-stop $Package
    Write-Host "FORCE-STOP $name ($serial)"
}

foreach ($name in $online) {
    $serial = $devices[$name]
    & $adb -s $serial logcat -G $LogBuffer | Out-Null
    & $adb -s $serial logcat -c
    Write-Host "CLEARED $name buffer=$LogBuffer"
}

$pids = @()
foreach ($name in $online) {
    $serial = $devices[$name]
    $out = Join-Path $LogDir "$name-talkback.log"
    $proc = Start-Process -FilePath $adb -ArgumentList @(
        "-s", $serial, "logcat", "-v", "time", "-s", "Talkback:I", "*:S"
    ) -RedirectStandardOutput $out -PassThru -WindowStyle Hidden
    $pids += "collectorPid=$($proc.Id) module=$name serial=$serial log=$out"
    Write-Host "COLLECTOR $name pid=$($proc.Id) -> $out"
}
$pids | Set-Content (Join-Path $LogDir "COLLECTORS.txt") -Encoding UTF8

Write-Host ""
Write-Host "LogDir: $LogDir"
Write-Host "Online: $($online -join ', ')"
if ($online.Count -lt 3) {
    Write-Warning "Triad requires M01+M02+M03 — connect M03 before cold start"
}
Write-Host "Next: reopen Talkback M01->M02->M03 ONLY, Talk page, idle 5 min, then stop script."
