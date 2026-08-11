# Conference Admission Observation — clear logcat, 16M buffer, start collectors
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
    $LogDir = Join-Path $repoRoot "logs\conference-admission-obs-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

@(
    "case=CONFERENCE_ADMISSION_OBSERVATION"
    "track=CONFERENCE-INSTANCE-ADMISSION-001"
    "ssid=happy"
    "dut=M02"
    "apk=talkback-app-debug.apk observation-only"
    "observe=JOIN_MEETING_TRACE;ADMISSION_DECISION;SESSION_CREATED;CHANNEL_SESSION_BIND;READINESS_BINDING;SESSION_TERMINATED;SESSION_REMOVE;MEDIA_RUNTIME_RELEASE"
    "notPatch=admission;prepareForGroupInvite;acceptGroupInvite;meshSessionForChannel;isConferenceUiReady"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

$steps = @"
# Conference Admission Observation — Test Steps

SSID: happy
APK: observation-only (JOIN_MEETING_TRACE / ADMISSION_DECISION / BIND / READINESS)
DUT: M02
Do NOT force USER_LEAVE mid-case unless the case says so.

Grep after stop:
  JOIN_MEETING_TRACE|ADMISSION_DECISION|SESSION_CREATED|SESSION_TERMINATED|CHANNEL_SESSION_BIND|READINESS_BINDING|SESSION_REMOVE|MEDIA_RUNTIME_RELEASE

First look (5 lines only):
  1. JOIN_MEETING_TRACE
  2. SESSION_CREATED (localRole / initiator / creationSource)
  3. CHANNEL_SESSION_BIND
  4. READINESS_BINDING
  5. SESSION_REMOVE_COMPLETE

---

## Case 0 — Cold Host Baseline

1. Force-stop Talkback on M01 / M02 / M03 (kill app).
2. Start ONLY M02 app.
3. On M02: tap Meeting (Start Meeting).
4. Observe ~30–60s: expect LIVE (Host).
5. Note wall clock of tap.

Expect:
  JOIN_MEETING_TRACE chosenPath=CREATE
  ADMISSION_DECISION decision=CREATE
  SESSION_CREATED localModuleId=M02 initiator=M02 localRole=HOST creationSource=USER_CREATE
  READINESS_BINDING isHostSession=true uiReady=true

If Case 0 fails: stop. Do not run Case 2. Investigate Intent → Admission → Identity only.

---

## Case 1 — Clean Host Create (M02)

1. Leave/end any meeting; prefer force-stop all three then start all.
2. Confirm no active conference on CH-01.
3. M02 tap Meeting.
4. Observe ~30–60s.

Expect:
  chosenPath=CREATE AND SESSION_CREATED localRole=HOST
Hidden fail:
  chosenPath=CREATE but localRole=PARTICIPANT  → Intent/Identity break

---

## Case 2 — M01 cancel then M02 create (PRIMARY)

1. All three apps up on SSID happy.
2. M01 Start Meeting; wait until meeting is up (optional: M02 may join once).
3. M01 cancel / end meeting for all (or leave as Host end).
4. Wait ~5–10s (note time).
5. M02 tap Meeting.
6. Observe if M02 stuck CONNECTING (~60–90s). Note tap time.

Timeline check:
  Ideal: SESSION_TERMINATED(old) → SESSION_REMOVE_COMPLETE(old) → M02 CREATE(new)
  Bad:   M02 CREATE while old still present / no REMOVE_COMPLETE

Adjudicate:
  F1 Intent/Role: chosenPath!=CREATE OR localRole=PARTICIPANT initiator!=local
  F2 Binding: HOST created but BIND selected PARTICIPANT
  B Lifecycle: old missing REMOVE_COMPLETE while new CREATE
  D Readiness/media: HOST + selected HOST + uiReady=false → then hostIce

---

Stop:
  .\scripts\conference-admission-obs-stop-run.ps1 -LogDir `"$LogDir`"
"@
Set-Content (Join-Path $LogDir "TEST_STEPS.txt") -Value $steps -Encoding UTF8

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $state = & $adb -s $serial get-state 2>$null
    if ($state -ne "device") {
        Write-Warning "SKIP $name ($serial) state=$state"
        continue
    }
    & $adb -s $serial logcat -G $LogBuffer | Out-Null
    & $adb -s $serial logcat -c
    Write-Host "CLEARED $name buffer=$LogBuffer"
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
Write-Host "Steps: $LogDir\TEST_STEPS.txt"
Write-Host "Stop: .\scripts\conference-admission-obs-stop-run.ps1 -LogDir `"$LogDir`""
