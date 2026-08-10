# RCA-003 IC gray — clear logcat, 16M buffer, start collectors
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
    $LogDir = Join-Path $repoRoot "logs\rca003-ic-uvcp-gray-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

@(
    "case=RCA003_IC_UVCP_RESIDENCY_DECOUPLING_GRAY"
    "pr=157"
    "ssid=happy"
    "apk=talkback-app-debug (#157 / origin/main f79b277)"
    "observe=EDGE_RECOVERED;MEDIA_CONNECTED;pill;FAILED_MEDIA_residency;mediaUnavailable"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

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

@'
# RCA-003 IC — UVCP residency decoupling (gray)

**APK:** #157 merged (`origin/main` / `f79b277`) already on M01/M02/M03  
**SSID:** **happy** only  
**Goal:** Confirm pill tracks **current** media availability, not FAILED_MEDIA incident residency.  
**Do not:** reopen recovery / Phase-2 / ICE / clear predicate · new flap matrix · Directed #5

## Pre

1. M01 host + M02 + M03 入会稳定（三方 media OK，pill 无 sticky）
2. 确认三台均为 #157 灰度包
3. Collectors 已在本 LogDir 运行

## Case 1 — 恢复成功（轻 flap 即可）

1. 短断 **M02** WiFi ~3–5s（off → on），**不要** USER_LEAVE
2. 等恢复收敛（~30–60s）
3. 期待：
   - `RECOVERY_EDGE_RECOVERED`（或等价 terminal）
   - `MEDIA_LIFECYCLE …=CONNECTED`
   - **pill healthy / clear**（无 `M0x degraded...` sticky）
4. 对照：即使仍见 `FAILED_MEDIA_RECOVERY` / residency 诊断，**不得**单独把 pill 钉在 degraded

## Case 2 — 真正未恢复（仅在 Case 1 PASS 后、有机会时）

不必强行制造；若自然出现 timeout / 无 EDGE_RECOVERED：

- 期待 pill = **degraded / reconnecting**（不能因去掉 residency OR 而假 healthy）

## Case 3 — 历史残留（#157 核心验收）

在 Case 1 恢复后立刻看（或对齐 rprobe / 日志）：

```text
FAILED_MEDIA residency 仍可能 true
+ iceConnected / media CONNECTED
+ receivePathLive=true
→ pill MUST be healthy
```

若 pill 仍 degraded 而 media=CONNECTED + receive live → **FAIL**（投影仍吃错信号）

## 只看三个字段

| 字段 | 用途 |
|------|------|
| `EDGE_RECOVERED` / recovery terminal | 协议是否已恢复 |
| `MEDIA … CONNECTED` / `MEDIA_LIFECYCLE` | 当前媒体事实 |
| Meeting pill / `mediaUnavailable`（UVCP） | 用户是否看到当前事实 |

可选 grep：`FAILED_MEDIA` · `FAILED_MEDIA_RESIDENCY` · `rprobe` · `MeetingPresence` · `degraded`

## Adjudication

| Result | Meaning |
|--------|---------|
| Case1 + Case3：EDGE_RECOVERED + MEDIA CONNECTED + pill healthy | **IC gray PASS** → 可关 RCA-003 |
| MEDIA CONNECTED + residency 诊断仍在 + pill degraded | **FAIL** — UVCP 仍耦合 residency |
| 无 EDGE_RECOVERED 却 pill healthy | **FAIL** — Case 2 回归（过度解耦） |
| Protocol 没恢复 | **不判 IC** — 正交，不重开 Phase-2 |

## Stop

```powershell
.\scripts\rca003-ic-uvcp-gray-stop-run.ps1 -LogDir "<LogDir>"
```
'@ | Set-Content (Join-Path $LogDir "TEST_STEPS.md") -Encoding UTF8

Write-Host "LogDir=$LogDir"
Write-Host "Steps: $LogDir\TEST_STEPS.md"
Write-Host "Stop: .\scripts\rca003-ic-uvcp-gray-stop-run.ps1 -LogDir `"$LogDir`""
