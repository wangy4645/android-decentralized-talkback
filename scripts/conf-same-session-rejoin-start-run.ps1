# CONFERENCE_SAME_SESSION_REJOIN_ACCEPTANCE — clear logcat, 16M buffer, start collectors
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
    $LogDir = Join-Path $repoRoot "logs\conf-same-session-rejoin-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

@(
    "case=CONFERENCE_SAME_SESSION_REJOIN_ACCEPTANCE_MISSING"
    "ssid=happy"
    "dut=M02"
    "observe=RECONNECT_ACCEPT;GROUP_ACCEPT;EDGE_RECOVERED;duplicate_still_BUSY"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    & $adb -s $serial logcat -G $LogBuffer | Out-Null
    & $adb -s $serial logcat -c
    Write-Host "CLEARED $name buffer=$LogBuffer"
}

$pids = @()
foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $out = Join-Path $LogDir "$name-talkback.log"
    $proc = Start-Process -FilePath $adb -ArgumentList @(
        "-s", $serial, "logcat", "-v", "time", "-s", "Talkback:I", "*:S"
    ) -RedirectStandardOutput $out -PassThru -WindowStyle Hidden
    $pids += "collectorPid=$($proc.Id) module=$name serial=$serial log=$out"
    Write-Host "COLLECTOR $name pid=$($proc.Id)"
}
$pids | Set-Content (Join-Path $LogDir "COLLECTOR_PIDS.txt") -Encoding UTF8

@'
# CONFERENCE same-session rejoin — field steps

## Pre
- APK: patch already on M01/M03; M02 manually installed from `/sdcard/Download/talkback/`
- SSID: **happy** only
- Topology: M01 host, M02 + M03 joined (display=3)
- Do **not**: USER_LEAVE, mid-run retarget, reopen Phase-2 / ownership

## Run
1. Confirm 3-party conference stable (media OK)
2. Flap **M02 WiFi** ~5s (off → on), stay in conference
3. Wait until pill settles or ~30–45s
4. Optional: second flap if first is ambiguous
5. Stop collectors (script below)

## Observe only (4 signals)

| # | Signal | Expect on PASS |
|---|--------|----------------|
| 1 | Reconnect hit | `Conference invite reconnect accepted` **or** `GROUP_ACCEPT_HANDOFF path=RECONNECT` |
| 2 | BUSY class | Host **rejoin** → RECONNECT (not BUSY); ordinary duplicate may still BUSY |
| 3 | Answer | Host path reaches `GROUP_ACCEPT` after HOST_RESTART / rejoin invite |
| 4 | Terminal | `RECOVERY_EDGE_RECOVERED` (do **not** score on ICE_CONNECTED alone) |

## Adjudication

| Result | Meaning |
|--------|---------|
| RECONNECT_ACCEPT + EDGE_RECOVERED | **闭环 PASS** |
| RECONNECT_ACCEPT + no RECOVERED | next layer = SDP/ICE/media (patch hit) |
| Still BUSY while rejoin+SDP+host | patch miss / not deployed |
| Ordinary invite accepted as reconnect | predicate too wide |

## Anti-signals (must stay closed)

- `RECOVERY_MEDIA_OWNER_REJECTED` existing=PARTICIPANT requested=HOST → still **0**
- Do not reopen INV-T3 / Phase-2 / ownership

## Stop

```powershell
.\scripts\conf-same-session-rejoin-stop-run.ps1 -LogDir "<LogDir>"
```
'@ | Set-Content (Join-Path $LogDir "TEST_STEPS.md") -Encoding UTF8

Write-Host "LogDir=$LogDir"
Write-Host "Stop: .\scripts\conf-same-session-rejoin-stop-run.ps1 -LogDir `"$LogDir`""
