# RRA-005 Field EP — Recovery Protocol Observability Validation
# clear logcat, set 16M buffer, start collectors
# Usage:
#   .\scripts\rra005-field-ep-start-run.ps1
#   .\scripts\rra005-field-ep-start-run.ps1 -LogDir .\logs\rra005-field-ep-YYYYMMDD-HHMMSS

param(
    [string]$LogDir = "",
    [string]$LogBuffer = "16M",
    [int]$Episodes = 5,
    [int]$FlapSec = 5,
    [int]$ObserveSec = 60
)

$ErrorActionPreference = "Stop"
$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "2d73067a"
    "M03" = "MDX0220416001963"
}

$adb = $null
foreach ($c in @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "C:\Users\happy\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
    )) {
    if (Test-Path $c) { $adb = $c; break }
}
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) { throw "adb not found" }

$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "logs\rra005-field-ep-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$head = ""
try { $head = (git -C $repoRoot rev-parse --short HEAD).Trim() } catch { $head = "unknown" }

$meta = @(
    "case=rra-005-field-ep"
    "objective=Recovery Protocol Observability Validation"
    "NOT=Recovery Success Validation"
    "phase2=ReattachDeliveryProgressFacade"
    "apk=talkback-app-debug"
    "ssid=happy"
    "dut=M02"
    "flapSec=$FlapSec"
    "observeSec=$ObserveSec"
    "episodes=$Episodes"
    "logBuffer=$LogBuffer"
    "HEAD=$head"
    "runCard=docs/analysis/rra-005-field-ep-delivery-progress-observation-card.md"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
)
$meta | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    & $adb -s $serial logcat -G $LogBuffer | Out-Null
    $buf = (& $adb -s $serial logcat -g 2>&1 | Out-String).Trim()
    Write-Host "BUFFER $name ($serial): $buf"
    & $adb -s $serial logcat -c
    Write-Host "CLEARED logcat $name ($serial)"
}

$pids = @()
foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $out = Join-Path $LogDir "$name-talkback.log"
    $proc = Start-Process -FilePath $adb -ArgumentList @(
        "-s", $serial, "logcat", "-v", "time", "-s", "Talkback:I", "*:S"
    ) -RedirectStandardOutput $out -PassThru -WindowStyle Hidden
    $pids += "collectorPid=$($proc.Id) module=$name serial=$serial log=$out"
    Write-Host "COLLECTOR $name -> $out (pid=$($proc.Id))"
}
$pids | Set-Content (Join-Path $LogDir "COLLECTOR_PIDS.txt") -Encoding UTF8

$wallclock = 1..$Episodes | ForEach-Object {
    $n = "{0:D2}" -f $_
    "EP=$n OFF= ON= END="
}
$wallclock | Set-Content (Join-Path $LogDir "EPISODE_WALLCLOCK.txt") -Encoding UTF8

@'
# RRA-005 Field EP — TEST STEPS

## Objective

```text
Recovery Protocol Observability Validation
NOT Recovery Success Validation
```

Success: Phase-2 coverage ≈100% AND every failed episode classified.
Do NOT retarget on EXPIRED↑. No code change / no optimize mid-run.

## Preflight

1. SSID **happy** only (not happy_5G)
2. Three phones same Meeting conference; media healthy baseline
3. M01/M02/M03 on Phase-2 APK (already installed)

## Per episode (default 5×)

```text
stable ≥30s
  → M02 WiFi OFF ~5s
  → WiFi ON
  → observe ~60s
  → record wallclock OFF/ON/END in EPISODE_WALLCLOCK.txt
  → next EP (recreate clean if USER_LEAVE)
```

Do **not** USER_LEAVE to "help" recovery. Do **not** flap M01/M03 as primary.

## Observation order (strict)

1. Phase-2 coverage = ARMED / TRANSPORT_SENT
2. Delivery: ARMED → RECEIPT | EXPIRED  (EXPIRED is valid)
3. Completion only if RECEIPT: RECEIPT → EDGE_RECOVERED?

## Markers

```text
RECOVERY_REATTACH_SEND_FAILED
TRANSPORT / SENT / PROGRESS_WINDOW_SATISFIED
REATTACH_DELIVERY_PROGRESS_ARMED
REATTACH_DELIVERY_PROGRESS_OBTAINED | REMOTE_RECEIPT
REATTACH_DELIVERY_PROGRESS_EXPIRED
EDGE_RECOVERED
```

## Stop

```powershell
.\scripts\rra005-field-ep-stop-run.ps1 -LogDir "<LogDir>"
```

See: docs/analysis/rra-005-field-ep-delivery-progress-observation-card.md
'@ | Set-Content (Join-Path $LogDir "TEST_STEPS.md") -Encoding UTF8

@'
# Field EP adjudication (fill after stop)

## Gate 1 Phase-2 coverage

TRANSPORT_SENT=
ARMED=
coverage=

## Gate 2 classification

| EP | T0 trigger | T2 SENT | ARMED | RECEIPT|EXPIRED | EDGE_RECOVERED | Class A–E/E1/E2 |
|----|------------|---------|-------|-----------------|----------------|-----------------|
| 01 |            |         |       |                 |                |                 |
| 02 |            |         |       |                 |                |                 |
| 03 |            |         |       |                 |                |                 |
| 04 |            |         |       |                 |                |                 |
| 05 |            |         |       |                 |                |                 |

## Tables

### Delivery Truth
SENT / ARMED / RECEIPT / EXPIRED / coverage / acquisition=

### Completion Truth
RECEIPT / EDGE_RECOVERED / conversion=

### Ownership Integrity
EXPIRED count · episode governed? · completion unchanged? · no implicit retry?
EXPIRED ≠ FAILED ≠ RECOVERED ≠ RETRY

## Verdict

- [ ] Gate1 PASS
- [ ] Gate2 PASS
- [ ] FIELD EP PASS (observability)
- [ ] PARTIAL / FAIL / ENV_INVALID
'@ | Set-Content (Join-Path $LogDir "ADJUDICATION.txt") -Encoding UTF8

Write-Host ""
Write-Host "RRA-005 Field EP capture READY"
Write-Host "LogDir=$LogDir"
Write-Host "Stop: .\scripts\rra005-field-ep-stop-run.ps1 -LogDir `"$LogDir`""
