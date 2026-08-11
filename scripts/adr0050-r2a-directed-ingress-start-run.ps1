# ADR-0050 R2a directed ingress — clear logcat, 16M buffer, start collectors
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
    $LogDir = Join-Path $repoRoot "logs\adr0050-r2a-ingress-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

@(
    "case=ADR0050_R2A_DIRECTED_NEGOTIATION_INGRESS"
    "pr=167"
    "ssid=happy"
    "apk=talkback-app-debug (#167/#168 / origin/main 53fb42b+)"
    "observe=LEASE_ADMITTED;INGRESS_PENDING;REMOTE_NEGOTIATION_READY;ICE_RESTART_DISPATCHED;ANSWER;REMOTE_INGRESS_ABSENT;T1;T2;T3"
    "notScore=EDGE_RECOVERED;DEGRADED;UVCP;UI"
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
# ADR-0050 R2a — Directed Negotiation Ingress Soak

**APK:** #167 merged（`origin/main` ≥ `40a984c` / `53fb42b`）已装 M01/M03；M02 已 push 需确认已手动安装  
**SSID:** **happy** only  
**Goal（唯一）:** lease 已授权时，避免 offer 发到尚无 negotiation ingress 的 peer。  
**Do not:** R2b · timeout 调大 · retry · ICE policy · residency · UVCP · 以 EDGE_RECOVERED/DEGRADED 判成败

## 冻结

```text
❌ R2b / single offerer
❌ enlarge timeout / expand retry
❌ ICE policy / residency / UVCP
❌ USER_LEAVE
❌ 以 EDGE_RECOVERED / DEGRADED / UI 作 R2a FAIL
```

## Pre（开始 flap 前）

1. 三台均已装含 #167 的 debug APK（M02 从 `/sdcard/Download/talkback/talkback-app-debug.apk` 安装）
2. SSID = **happy**（不要 happy_5G）
3. M01 host + M02 + M03 入会稳定，三方 media OK
4. 本 LogDir collectors 已在跑（见 `COLLECTOR_PIDS.txt`）

## 步骤

| 标注 | 动作 |
|------|------|
| **Pre** | 确认三方 CONNECTED / 可通话 |
| **T0** | **M02** WiFi OFF → 等 ~10–20s → WiFi ON（**不要** USER_LEAVE；不要动 M01/M03） |
| **T1-field** | 等 M02 自身 outbound 有恢复迹象（对照，不作为 R2a PASS） |
| **Soak** | 再等 **60–120s**，重点观察 M01→M02、M03→M02 inbound |
| **Stop** | 停采集后跑 adjudicate |

## 现场只盯 P0 链（口头核对）

```text
NEGOTIATION_LEASE_ADMITTED
  → NEGOTIATION_INGRESS_PENDING (optional)
  → REMOTE_NEGOTIATION_READY
  → RECOVERY_ICE_RESTART_DISPATCHED
  → ANSWER（合理窗口）
```

| 指标 | 期望 |
|------|------|
| `REMOTE_INGRESS_ABSENT` | 相对 154011 下降/消失 |
| READY 后有 DISPATCH | 有 |
| OFFER→ANSWER (T3) | 有界（不要再 ~47s） |
| `NEGOTIATION_NON_OWNER_BLOCKED` | 0 |

## 不看（出现也不当 R2a FAIL）

- `EDGE_RECOVERED`
- DEGRADED pill（若出现：只问 media 是否 CONNECTED，不改 UVCP）

## 三个时间差（事后 adjudicate）

| Id | From → To |
|----|-----------|
| T1 | `LEASE_ADMITTED` → `REMOTE_NEGOTIATION_READY` |
| T2 | `READY` → `ICE_RESTART_DISPATCHED` |
| T3 | `DISPATCHED` → ANSWER（**最关键**） |

## Case 裁决（事后）

| Case | 现象 | 结论 |
|------|------|------|
| A | READY→OFFER→ANSWER，T3 有界，ingress-absent↓ | **R2a VERIFIED** |
| B | READY→OFFER，仍无 ANSWER | R2a OK；问题在 execution/answer — **不回滚** |
| C | 一直 PENDING→DEADLINE | 门闩过保守 — 再评 readiness |

## Stop + adjudicate

```powershell
.\scripts\adr0050-r2a-directed-ingress-stop-run.ps1 -LogDir "<LogDir>"
.\scripts\adr0050-r2a-directed-ingress-adjudicate.ps1 -LogDir "<LogDir>"
```
'@ | Set-Content (Join-Path $LogDir "TEST_STEPS.md") -Encoding UTF8

Write-Host "LogDir=$LogDir"
Write-Host "Steps: $LogDir\TEST_STEPS.md"
Write-Host "Stop: .\scripts\adr0050-r2a-directed-ingress-stop-run.ps1 -LogDir `"$LogDir`""
