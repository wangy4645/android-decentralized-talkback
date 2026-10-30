# ADR-0057 Track C Case A — force-stop, clear logcat, start collectors
param(
    [string]$LogDir = "",
    [string]$LogBuffer = "16M"
)

$ErrorActionPreference = "Continue"
$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "DSJ-2407041"
    "M03" = "MDX0220416001963"
}
$M04 = "DSJ-2407011"
$Package = "com.talkback.appprod"

$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "logs\adr0057-track-c-case-a-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $head) { $head = "unknown" }

@(
    "case=ADR0057_TRACK_C_CASE_A"
    "build=$head"
    "apk=talkback-app-debug.apk"
    "mode=FIELD_VALIDATION"
    "gieRepair=FIELD_PASS_CLOSED"
    "igieRepair=FIELD_PASS_CLOSED"
    "gidrRepair=IMPLEMENTATION_CONFORMANCE_PASS_FIELD_PENDING"
    "primaryGate=GIDR_delivery_stale_retry_INVITE_receive_IGIE_GROUP_ACCEPT"
    "f2Gate=M04_ABSENT"
    "observe=GIDR_;GROUP_INVITE;IGIE_;GROUP_ACCEPT;SRD;GIE_;MEDIA_PROVISIONED;MEDIA_PENDING_DRAIN;HELLO;FACT_*;GBC_EFFECT;bootstrap;transmitReady;ICE_TRANSPORT_;F8_BRIDGE_;F8_REGISTRY_;F8_DETECTOR_;F8_OBSERVER_;F8_NATIVE_;F8_PORT_;F8_CANDIDATE_;F8_SOCKET_;F8_DEFAULT_;F8_PORT_ADDRESS_;F8_QUERY_DEFAULT_;F8_SET_DEFAULT_;F8_GET_DEFAULT_"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

$steps = @'
# ADR-0057 Track C Case A — GIDR field validation

Build: see RUN_META.txt (GIDR cut on cac628d+)
LogDir: <LogDir>
Stop:
  .\scripts\adr0057-track-c-case-a-stop-run.ps1 -LogDir "<LogDir>"

F2: M04 force-stopped — MUST NOT open Talkback on M04

---

## 现在做（采集已启动）

1. **M04：保持强制停止，不要打开 Talkback**
2. **依次 force-stop 后重新打开** Talkback：**M01 → M02 → M03**（冷启动顺序）
3. 每台进入 **Talk 页**（不要 Meeting / 不要 PTT）
4. **idle 观察约 5 分钟**，不要操作
5. 执行 stop 脚本结束采集

## 开跑后立刻目视硬门

任意台日志/UI 若出现 M04（HELLO from M04 / currentMembers 含 M04）→ **本轮作废**，先停采集。

## 本轮主判据（GIDR / F6）

正常 completion（足够 FIELD PASS）:
  M03 receives GROUP_INVITE
  → IGIE (if deferred)
  → GROUP_ACCEPT on wire

加分 recovery evidence（自然 miss 时）:
  INVITE #1 handoff
  → no M03 receive / no ACCEPT
  → GIDR_EXECUTION_OPPORTUNITY (delivery_retry / stale)
  → INVITE #2
  → GROUP_ACCEPT

transmitReady 本轮 NOT required.

'@
$steps = $steps.Replace("<LogDir>", $LogDir)
Set-Content (Join-Path $LogDir "TEST_STEPS.txt") -Value $steps -Encoding UTF8

# F2: M04 force-stop first (hard isolation)
$m04State = (& $adb -s $M04 get-state 2>&1 | Out-String).Trim()
if ($m04State -notmatch '^device$') { $m04State = "offline" }
if ($m04State -eq "device") {
    & $adb -s $M04 shell am force-stop $Package
    Write-Host "FORCE-STOP M04 ($M04) [F2 isolation]"
} else {
    Write-Host "M04 ($M04) not online as device (state=$m04State) — isolation OK if app not running on LAN"
}

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $state = & $adb -s $serial get-state 2>$null
    if ($state -ne "device") {
        Write-Warning "SKIP force-stop $name ($serial) state=$state"
        continue
    }
    & $adb -s $serial shell am force-stop $Package
    Write-Host "FORCE-STOP $name ($serial)"
}

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $state = & $adb -s $serial get-state 2>$null
    if ($state -ne "device") { continue }
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
$pids | Set-Content (Join-Path $LogDir "COLLECTORS.txt") -Encoding UTF8

Write-Host ""
Write-Host "LogDir: $LogDir"
Write-Host "Next: reopen Talkback M01->M02->M03 ONLY (NOT M04), Talk page, idle 5 min, then stop script."
