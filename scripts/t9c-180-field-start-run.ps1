# #180 T9/C — pairwise mesh admission obligation field (simultaneous cold start)
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
$Activity = "com.talkback.appprod/.MainActivity"

$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "logs\t9c-180-field-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $head) { $head = "unknown" }
$dirty = (git -C $repoRoot status --porcelain 2>$null)
$buildLabel = if ($dirty) { "$head+dirty" } else { $head }

@(
    "case=T9C_180_FIELD"
    "track=PAIRWISE-MESH-ADMISSION-OBLIGATION"
    "issue=#180"
    "phase=simultaneous_cold_start"
    "topology=M01,M02,M03"
    "window_s=120"
    "build=$buildLabel"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

$steps = @'
# T9/C — #180 pairwise mesh admission (simultaneous cold start)

Build: see RUN_META.txt

Stop:
  .\scripts\t9c-180-field-stop-run.ps1 -LogDir "<LogDir>"
Adjudicate:
  .\scripts\t9c-180-field-adjudicate.ps1 -LogDir "<LogDir>"

---

## 0. 预检

- [ ] APK = #180-A/B/C build
- [ ] 无残留 Group / Meeting
- [ ] T0 wall clock: _______________

---

## 1. T0 — 三台同时 cold start (±2s)

1. 三台 force-stop Talkback
2. 在 2s 内三台重新打开 Talkback（或同时 tap channel）
3. **不要** 手动 workaround / 重试 invite
4. 观察 120s，不要 mid-run 杀进程

自动化（log 采集后）:
  .\scripts\t9c-180-field-cold-start.ps1 -LogDir "<LogDir>"

---

## 2. Hard Gate（120s 内）

### 禁止旧坏路径（三台合计）

- GROUP_JOIN = 0
- joinIngressDecision=QUEUED_NO_SESSION = 0
- Group reconnect join offered = 0
- Group mesh join offered = 0

### Obligation / activation（M01 为主）

- PAIRWISE_MESH_ADMISSION_DEFERRED 或 ACTIVATION_EVALUATED
- M01→M03: offerer=M01 answerer=M03
- PAIRWISE_MESH_ADMISSION_INVITE_ISSUED peer=M03
- Group invite sent -> ...M03

### M01→M03 SDP 主路径

- M01: GROUP_INVITE sdp!=blank
- M03: invite reconnect accepted from M01 / GROUP_ACCEPT
- 无 M03 主动 createOffer（双 offer 禁止）

### 最终

- 3/3 OPERATIONAL + transmitReady=true（120s）

---

## 3. M03 预期（PN 正确）

- meshDesiredLinks 可为 empty — **不是 fail**
- M03 不发 offer，只 wait + answer

---

## 4. Stop + adjudicate

  .\scripts\t9c-180-field-stop-run.ps1 -LogDir "<LogDir>"
  .\scripts\t9c-180-field-adjudicate.ps1 -LogDir "<LogDir>"

Follow-up (if PASS): late-peer directed test — see t9c-180-late-peer-field-start-run.ps1
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
Write-Host "Build=$buildLabel"
Write-Host "Steps: $LogDir\TEST_STEPS.txt"
Write-Host "Cold start: .\scripts\t9c-180-field-cold-start.ps1 -LogDir `"$LogDir`""
Write-Host "Stop: .\scripts\t9c-180-field-stop-run.ps1 -LogDir `"$LogDir`""
