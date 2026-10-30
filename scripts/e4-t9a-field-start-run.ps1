# ADR-0053 E4 T9-A — formerly-admitted rejoin field: logcat + run card
param(
    [string]$LogDir = "",
    [string]$LogBuffer = "16M",
    [switch]$InstallApk,
    [string]$ApkPath = ".\talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
)

$ErrorActionPreference = "Stop"
$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "2d73067a"
    "M03" = "MDX0220416001963"
}
$Package = "com.talkback.appprod"

$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "logs\e4-t9a-field-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $head) { $head = "unknown" }

@(
    "case=E4_T9A_FIELD"
    "track=ADR-0053-E4-FORMERLY-ADMITTED-REJOIN"
    "issue=#178"
    "authority=M01"
    "dut=M02"
    "observer=M03"
    "build=$head"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

$steps = @'
# E4 T9-A — formerly-admitted peer rejoin (field)

APK: main @ build in RUN_META.txt (#178 E4)
Authority: M01 | DUT: M02 | Observer: M03

Stop:
  .\scripts\e4-t9a-field-stop-run.ps1 -LogDir "<LogDir>"
Adjudicate:
  .\scripts\e4-t9a-field-adjudicate.ps1 -LogDir "<LogDir>"

---

## 0. 预检

- [ ] 三台为 #178 build
- [ ] 无残留 Group / Meeting
- [ ] wall clock 起点: _______________

---

## 1. 建立 Group READY（必须人工，不要等自然 prune）

1. 三台 force-stop Talkback → 重新打开
   adb: am force-stop com.talkback.appprod
2. **M01** 进入目标 Group channel，确保 M02、M03 在 channel roster
3. **M01** 发起 group mesh / Sync Channel，M02、M03 接受 invite
4. 等待三台 group mesh **OPERATIONAL**（ICE CONNECTED，UI 非永久 Sync Channel）
5. 记录 READY wall clock: _______________

---

## 2. 触发 asymmetric loss + prune（固定脚本，勿 WiFi 随机 flap）

在 repo 根目录（log 已在采集）:

  .\scripts\e4-t9a-field-trigger-prune.ps1 -LogDir "<LogDir>"

等价手工:
1. force-stop **M02** only
2. 在 **M01** 等待 prune（约 30–40s，见 EVICTED member M02）
3. 重新启动 **M02** Talkback

---

## 3. 验收（M01 为主）

必须看到（顺序可略有交错）:

  GROUP_E4_REJOIN_EVALUATED
  GROUP_E4_REJOIN_INVITE_ISSUED
  Group accept from M02          (或 GROUP_ACCEPT 路径)
  GROUP_E4_REJOIN_ADMISSION_COMMITTED
  rosterEpoch=... reason=member_joined
  ICE CONNECTED（M01↔M02）

必须 **没有**（旧 JOIN loop）:

  joinIngressDecision=QUEUED_NO_SESSION
  Group reconnect join offered -> M02

禁止用 Meeting 绕过。

---

## 4. Stop + adjudicate

  .\scripts\e4-t9a-field-stop-run.ps1 -LogDir "<LogDir>"
  .\scripts\e4-t9a-field-adjudicate.ps1 -LogDir "<LogDir>"

结果贴 #176。
'@
$steps = $steps.Replace("<LogDir>", $LogDir)
Set-Content (Join-Path $LogDir "TEST_STEPS.txt") -Value $steps -Encoding UTF8

if ($InstallApk) {
    $apkFull = Join-Path $repoRoot $ApkPath
    if (-not (Test-Path $apkFull)) {
        throw "APK not found: $apkFull (run: .\gradlew.bat :talkback-app:assembleDebug)"
    }
    foreach ($name in @("M01", "M02", "M03")) {
        $serial = $devices[$name]
        Write-Host "INSTALL $name ($serial)"
        & $adb -s $serial install -r $apkFull
    }
}

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
Write-Host "After READY: .\scripts\e4-t9a-field-trigger-prune.ps1 -LogDir `"$LogDir`""
Write-Host "Stop: .\scripts\e4-t9a-field-stop-run.ps1 -LogDir `"$LogDir`""
