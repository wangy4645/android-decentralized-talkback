# ADR-0052 Phase 2 — Case A field: clear logcat, 16M buffer, start collectors
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
    $LogDir = Join-Path $repoRoot "logs\adr0052-case-a-field-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

@(
    "case=ADR0052_CASE_A_FIELD"
    "track=CONFERENCE-SCOPE-ADMISSION-SIGNALING-001"
    "ssid=happy"
    "host=M01"
    "participant=M02"
    "observer=M03"
    "apk=talkback-app-debug.apk (local dirty tree on main efae88b + PR-A/B/C1/C2/C3)"
    "successMetrics=A_RECOVERY_REATTACH_BEFORE_READY=0;B_OFFER_COLLISION=0;C_conferenceUiReady=true"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

$steps = @'
# ADR-0052 Case A Field — 测试步骤

SSID: happy（不要用 happy_5G）
APK: talkback-app-debug.apk（ADR-0052 Phase 2 本地构建）
Host: M01 (HTUBB21B09220661)
Participant (DUT): M02 (2d73067a) — 需已从 /sdcard/Download/talkback/ 安装最新包
Observer: M03 (MDX0220416001963) — 本 Case 可不操作，仅采集 log

采集已开始。完成测试后执行:
  .\scripts\adr0052-case-a-field-stop-run.ps1 -LogDir "<LogDir>"

---

## 0. 预检（log 采集前已完成 force-stop 建议在步骤 1 做）

- [ ] 三台均在 SSID happy
- [ ] M01 / M03 已 adb install 最新 debug APK
- [ ] M02 已从 Download/talkback/talkback-app-debug.apk 手动安装最新版
- [ ] 记录开始 wall clock: _______________

---

## Case A1 — 原始复现路径（PRIMARY，必做）

目标: 验证 accept 后不再出现 500ms kick → RECOVERY_REATTACH → offer collision

1. 三台 Talkback **强制停止**（设置 → 应用 → 强制停止，或 adb shell am force-stop com.talkback.appprod）
2. 仅启动 **M01** Talkback，确认无残留会议
3. **M01**: 进入 Meeting → **Start Meeting**（建会）
4. 等待 M02 收到 invite（或 M01 主动 invite M02，按你们常规建会流程）
5. **M02**: 收到会议邀请 → 点 **Accept**
6. **Accept 后 30 秒内不要点击任何按钮**（不切 Tab、不开 Members、不 mute）
7. 观察 M02 UI：Connecting 是否收敛为可通话 / conferenceUiReady
8. 记录以下 wall clock:
   - M01 Start Meeting: ___
   - M02 Accept: ___
   - UI 收敛（或 30s 超时）: ___

### Case A1 期望 log（M02 为主，M01 辅证）

**Admission 链（必须完整）**
```
CONFERENCE_ADMISSION_PHASE ... phase=INVITED
CONFERENCE_ADMISSION_PHASE ... phase=ACCEPTING
CONFERENCE_ADMISSION_PHASE ... phase=NEGOTIATING
CONFERENCE_ADMISSION_PHASE ... phase=READY
```

**Recovery gate（READY 前）**
```
CONFERENCE_RECOVERY_GATE ... phase=NEGOTIATING allowed=false
source=scheduleConferenceHostLinkKick
```
禁止 READY 前出现:
```
RECOVERY_REATTACH
joinIntent=RECOVERY_REATTACH
```

**Signaling lock（正常应为 INITIAL_ANSWER 独占，不应与 ICE_RESTART 重叠）**
允许:
```
CONFERENCE_SIGNAL_LOCK_ACQUIRE ... owner=INITIAL_ANSWER
CONFERENCE_SIGNAL_LOCK_RELEASE ... owner=INITIAL_ANSWER
```
禁止 accept 窗口内同时:
```
owner=INITIAL_ANSWER ... (未 release)
owner=ICE_RESTART ... ACQUIRE
```

**致命关键词（应为 0 或仅出现在 READY 后 recovery Case B）**
```
setup attribute
localDesc=OFFER ... remoteDesc=OFFER
setRemoteDescription
SRD fail
```

### Case A1 成功标准
- A = READY 前 RECOVERY_REATTACH 次数 = 0
- B = OFFER/OFFER collision 关键词 = 0
- C = conferenceUiReady / UI 可通话 = true

---

## Case A2 — 压力触发（可选，提高复现概率）

在 **新开一轮** log（或 stop 后 restart 采集）后:

1. force-stop 三台 → 重新 Start Meeting
2. M02 Accept 后 **立即**: 切 Meeting tab → 打开 Members → 返回 Talk
3. 再等 30s，记录 UI 与 wall clock

期望: 同 A1（gate blocked + 无 collision）

---

## Case A3 — 第三人窗口（可选）

1. M01 Start Meeting
2. M02 Accept（不操作 10s）
3. M03 同时 Accept / Join（若流程支持）
4. 观察 per-edge lock: (session,M02) 与 (session,M03) 独立，无 session 级串死

---

## Stop 后 grep 命令（在 LogDir 内）

PowerShell:
```
Select-String -Path M02-talkback.log,M02-logcat-dump.txt -Pattern `
  "CONFERENCE_ADMISSION_PHASE|CONFERENCE_RECOVERY_GATE|CONFERENCE_SIGNAL_LOCK|RECOVERY_REATTACH|setup attribute|localDesc=OFFER|remoteDesc=OFFER|SRD|setRemoteDescription|conferenceUiReady"
```

优先看 M02；M01 对照 host offer / invite 时序。

---

## 失败分流（不要加新 patch，先定位链）

| 现象 | 优先怀疑 |
|------|----------|
| NEGOTIATING 时 allowed=true + RECOVERY_REATTACH | C2 gate 漏点 |
| GROUP CLOSED 出现在 CONFERENCE host 路径 | PR-A/B scope |
| INITIAL_ANSWER + ICE_RESTART 重叠 ACQUIRE | C3 lock 漏点 / 未走 lock 路径 |
| READY + allowed=true + recovery 仍 SDP fail | 独立 SDP/role（非并发副作用） |
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
    Write-Host "COLLECTOR $name pid=$($proc.Id) -> $out"
}
$pids | Set-Content (Join-Path $LogDir "COLLECTOR_PIDS.txt") -Encoding UTF8

Write-Host ""
Write-Host "LogDir=$LogDir"
Write-Host "Steps: $LogDir\TEST_STEPS.txt"
Write-Host "Stop: .\scripts\adr0052-case-a-field-stop-run.ps1 -LogDir `"$LogDir`""
