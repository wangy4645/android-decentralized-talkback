# ADR-0052 Case B — READY-state recovery field: clear logcat, 16M buffer, start collectors
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
    $LogDir = Join-Path $repoRoot "logs\adr0052-case-b-field-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

@(
    "case=ADR0052_CASE_B_FIELD"
    "track=CONFERENCE-READY-RECOVERY-001"
    "host=M01"
    "participant=DUT=M02"
    "build=fix/adr0052-conference-admission-recovery-gate eb7a6fb"
    "question=Phase2 blocked bad recovery without killing good recovery"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

$steps = @'
# ADR-0052 Case B — READY 后 recovery 验证

APK: 与 Case A 相同 build（PR #174 / eb7a6fb）
Host: M01 | DUT: M02 | M03 可选观察

目标: Phase 2 没误杀正常 recovery

Stop:
  .\scripts\adr0052-case-b-field-stop-run.ps1 -LogDir "<LogDir>"

---

## 0. 预检

- [ ] Talkback 为 ADR-0052 build
- [ ] 无残留会议（建议 force-stop 三台后重启 app）
- [ ] 记录 wall clock 起点: _______________

---

## Case B1 — PRIMARY（WiFi 断链 recovery）

### 阶段 1：建立 READY

1. 三台 force-stop Talkback → 重新打开
2. **M01** Start Meeting，invite **M02**
3. **M02** Accept
4. 等待 **conferenceUiReady=true**（M02 UI 可通话，约 10–30s）
5. 确认 log 已有:
   ```
   CONFERENCE_ADMISSION_PHASE ... phase=READY
   conferenceUiReady=true
   ```
6. 记录 READY wall clock: _______________

### 阶段 2：触发断链

任选一种（推荐 A，可复现性较好）:

**A. M02 WiFi 断链（推荐）**
1. M02 关闭 WiFi **5–8 秒**
2. 观察 UI 进入 reconnecting / degraded
3. M02 重新打开 WiFi
4. 等待 **30–60s** 观察是否恢复

**B. M02 飞行模式**
1. 开飞行模式 5s → 关
2. 等 30–60s

**C. 仅 ICE 观察（弱触发）**
1. 若 A/B 不便，可尝试 M02 切后台 30s 再回前台（成功率较低）

### 阶段 3：记录

- 断链开始: ___
- WiFi 恢复: ___
- UI 恢复可通话: ___ 或 超时否

---

## Case B1 期望 log（M02 为主）

**Admission 仍 READY（不应回退 NEGOTIATING）**
```
CONFERENCE_ADMISSION_PHASE ... phase=READY
```

**Recovery gate 允许**
```
CONFERENCE_RECOVERY_GATE ... allowed=true ... phase=READY
```
（source 可能是 attemptConferencePeerIceRestart / evaluateRecoveryAdmission / dispatchRecoveryOffer）

**Signaling lock**
```
CONFERENCE_SIGNAL_LOCK_ACQUIRE ... owner=ICE_RESTART
CONFERENCE_SIGNAL_LOCK_RELEASE ... owner=ICE_RESTART
```

**Recovery 执行**
```
RECOVERY_REATTACH
或 RECOVERY_ICE_RESTART_DISPATCHED scope=CONFERENCE
```

**禁止（Case B 失败信号）**
```
phase=READY 但 allowed=false        → C2 过严
allowed=true 但无 ICE_RESTART       → trigger 路径
ICE_RESTART 后 setup attribute error → SDP/role（独立域）
```

---

## Case B2 — 可选：M01 侧断链

M02 已 READY 后，M01 关 WiFi 5s 再开；看 host/participant 哪侧发起 recovery。
期望仍: gate allowed=true + recovery 尝试。

---

## Stop 后 grep（M02）

```
CONFERENCE_ADMISSION_PHASE|CONFERENCE_RECOVERY_GATE|CONFERENCE_SIGNAL_LOCK|RECOVERY_REATTACH|RECOVERY_ICE_RESTART|ICE_RESTART|setup attribute|conferenceUiReady|hostIce=
```

---

## 失败分流（先定位，不补 patch）

| 现象 | 路由 |
|------|------|
| READY 后 phase 变 NEGOTIATING/FAILED | admission 回退 bug |
| allowed=false @ READY | C2 gate 过严 |
| allowed=true 无 offer | recovery scheduler / kick 未触发 |
| offer 后 SDP fail | SDP role（非 Phase 2） |
| UI 不恢复但 ICE CONNECTED | UI projection 域 |
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
Write-Host "Stop: .\scripts\adr0052-case-b-field-stop-run.ps1 -LogDir `"$LogDir`""
