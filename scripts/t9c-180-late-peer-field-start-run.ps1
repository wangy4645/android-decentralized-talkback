# #180 late-peer directed field — log collectors + run card
param(
    [string]$LogDir = "",
    [string]$LogBuffer = "16M"
)

$ErrorActionPreference = "Stop"
$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "2d73067a"
    "M03" = "MDX0220416001963"
}
$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "logs\t9c-180-late-peer-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $head) { $head = "unknown" }
$dirty = (git -C $repoRoot status --porcelain 2>$null)
$buildLabel = if ($dirty) { "$head+dirty" } else { $head }

@(
    "case=180_LATE_PEER_DIRECTED"
    "track=PAIRWISE-MESH-ADMISSION-OBLIGATION"
    "issue=#180"
    "phase=late_peer_join"
    "ssid=happy"
    "topology=M01,M02 then +M03"
    "window_s=120_after_m03"
    "build=$buildLabel"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
    "t9c_note=#179_bootstrap_PASS #180_snapshot_seam_not_exercised"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

$steps = @'
# #180 Late-peer directed test

T9/C scope (do NOT conflate):
  #179 bootstrap admission: PASS
  #180 pairwise snapshot seam: not exercised

Stop:
  .\scripts\t9c-180-late-peer-field-stop-run.ps1 -LogDir "<LogDir>"
Adjudicate:
  .\scripts\t9c-180-late-peer-field-adjudicate.ps1 -LogDir "<LogDir>"

---

## Step 1 — M01 + M02 stable base (30-60s)

  .\scripts\t9c-180-late-peer-phase1.ps1 -LogDir "<LogDir>"

Wait until M01/M02 OPERATIONAL in logs or UI (~45s built in).

## Step 2 — Join M03 (do NOT start together)

  .\scripts\t9c-180-late-peer-phase2.ps1 -LogDir "<LogDir>"

Observe 120s, then stop + adjudicate.

---

## Hard gates

- NO GROUP_BOOTSTRAP_INTENT_CREATED on M01 after T_join_M03
- YES PAIRWISE_MESH_ADMISSION_* on M01 for M03
- YES Group invite sent -> M03 + M03 acceptGroupInviteReconnect
- NO GROUP_JOIN / QUEUED_NO_SESSION
- snapshotApplied must NOT swallow admission (no snapshot-only terminate without ACCEPT)

## PASS

late peer -> obligation -> SDP INVITE -> ACCEPT -> CONNECTED
'@
$steps = $steps.Replace("<LogDir>", $LogDir)
Set-Content (Join-Path $LogDir "TEST_STEPS.txt") -Value $steps -Encoding UTF8

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $state = & $adb -s $serial get-state 2>$null
    if ($state -ne "device") { Write-Warning "SKIP $name"; continue }
    & $adb -s $serial logcat -G $LogBuffer | Out-Null
    & $adb -s $serial logcat -c
    Write-Host "CLEARED $name"
}

$pids = @()
foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $state = & $adb -s $serial get-state 2>$null
    if ($state -ne "device") { continue }
    $out = Join-Path $LogDir "$name-talkback.log"
    $proc = Start-Process -FilePath $adb -ArgumentList @(
        "-s", $serial, "logcat", "-v", "time", "-s", "Talkback:I", "*:S"
    ) -RedirectStandardOutput $out -PassThru -WindowStyle Hidden
    $pids += "collectorPid=$($proc.Id) module=$name serial=$serial log=$out"
    Write-Host "COLLECTOR $name pid=$($proc.Id)"
}
$pids | Set-Content (Join-Path $LogDir "COLLECTOR_PIDS.txt") -Encoding UTF8

Write-Host ""
Write-Host "LogDir=$LogDir"
Write-Host "Phase1: .\scripts\t9c-180-late-peer-phase1.ps1 -LogDir `"$LogDir`""
