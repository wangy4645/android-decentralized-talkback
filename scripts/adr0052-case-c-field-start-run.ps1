# ADR-0052 Case C — per-edge signaling lock: clear logcat, 16M buffer, start collectors
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
    $LogDir = Join-Path $repoRoot "logs\adr0052-case-c-field-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

@(
    "case=ADR0052_CASE_C_FIELD"
    "track=CONFERENCE-PER-EDGE-SIGNAL-LOCK-001"
    "ssid=happy"
    "host=M01"
    "question=C3 lock per (session,peer) not session-global"
    "build=fix/adr0052-conference-admission-recovery-gate eb7a6fb"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

$steps = @'
# ADR-0052 Case C — per-edge signaling lock

SSID: happy
APK: PR #174 build (eb7a6fb)

目标: C3 lock 是 per-edge，不是 session 级串行

Stop:
  .\scripts\adr0052-case-c-field-stop-run.ps1 -LogDir "<LogDir>"

---

## 流程

1. 三台 force-stop → 重开 Talkback
2. M01 Start Meeting
3. M02 Accept（等 READY / 可通话）
4. M03 Accept（不等 M02 完全稳定也可，提高交错概率）
5. 观察 Members / UI 收敛

---

## 必须看到（两个 peer 独立）

```
(session, M02) INITIAL_ANSWER → READY
(session, M03) INITIAL_ANSWER → READY
```

## 允许

- 同一 peer 上 LOCK_WAIT（并发 recovery vs answer）
- 不同 peer lock 交错（M02 release 与 M03 acquire 时间重叠）

## 禁止（C3 失败）

- M03 INITIAL_ANSWER 必须等 M02 整段 80ms+ answer 完成才开始
  （session 级串行化）
- INITIAL_ANSWER 与 ICE_RESTART 同 peer 重叠 ACQUIRE

---

## grep 重点

```
CONFERENCE_SIGNAL_LOCK_ACQUIRE
CONFERENCE_SIGNAL_LOCK_RELEASE
CONFERENCE_SIGNAL_LOCK_WAIT
durationMs
CONFERENCE_ADMISSION_PHASE
peer=M02
peer=M03
```

## 成功标准

- 两个 peer 各有独立 ACQUIRE/RELEASE 对
- durationMs 均 < 500ms（参考 Case A ~85ms）
- 无 OFFER collision / setup attribute
'@
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
Write-Host "Stop: .\scripts\adr0052-case-c-field-stop-run.ps1 -LogDir `"$LogDir`""
