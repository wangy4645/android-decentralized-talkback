# Conference P0/P1 three-device soak runner
# 用法:
#   .\scripts\soak-conference-p0-p1.ps1 -Profile P0Quick
#   .\scripts\soak-conference-p0-p1.ps1 -Profile P1Full -InstallApk
#   .\scripts\soak-conference-p0-p1.ps1 -Profile P0Quick -OutDir "d:\workspace\project\talkback\logs-p0-xxxx"
#
# Profile:
#   P0Quick  ~15 min  #71 merge gate: T1/T7 + S11/S12
#   P1Full   ~35 min  #72 merge gate: T0-T7 + S11-S14

param(
    [ValidateSet("P0Quick", "P1Full")]
    [string]$Profile = "P0Quick",
    [switch]$InstallApk,
    [string]$OutDir = "",
    [string]$ApkPath = ".\talkback-app\build\outputs\apk\debug\talkback-app-debug.apk",
    [string]$M01 = "HTUBB21B09220661",
    [string]$M02 = "2d73067a",
    [string]$M03 = "MDX0220416001963"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$durationMin = if ($Profile -eq "P1Full") { 35 } else { 15 }

if (-not $OutDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $tag = if ($Profile -eq "P1Full") { "p1-full" } else { "p0-quick" }
    $OutDir = Join-Path (Split-Path $repoRoot -Parent) "logs-conference-$tag-$stamp"
}

function Write-Step([string]$Title) {
    Write-Host ""
    Write-Host "=== $Title ===" -ForegroundColor Cyan
}

Write-Step "Conference soak profile: $Profile (${durationMin} min)"
Write-Host "Log output: $OutDir"
Write-Host ""

if ($Profile -eq "P0Quick") {
    Write-Host @"
#71 快速验收（约 15 分钟）

[T1] Host Solo ACTIVE
  M01 建会 CH-01，不邀请他人
  期望: CONFERENCE_RUNTIME_PROJECTION phase=ACTIVE host=true connected=0

[T7] 终止后不得 rejoin
  1. M01 建会，邀请 M02/M03，全员入会
  2. M02 Leave（soft-leave）
  3. M01 Hangup 结束会议
  4. M02 检查: 无「可重连」提示；尝试 Join/重连应失败
  期望日志: CONFERENCE_TERMINATED ... clearRejoinState=true
  硬门禁: S11 zombie=0, S14 stale recovery=0

[T1b] 可选 30s: M03 仍在会时 M01 hangup，确认全员 Remote hangup
"@
} else {
    Write-Host @"
#72 全量回归（约 35 分钟）

[T0] 冷启动三台在线，CH-01 默认可拨
[T1] Host solo ACTIVE（同 P0Quick）
[T2] 三人入会，建会初期 CONNECTING 3-4s 可接受
[T3] 一人 Leave，其余继续；Host roster 正确
[T4] WiFi 断连恢复（重点）
  1. 三人入会稳定 ACTIVE
  2. M02 关 WiFi 20-30s
  3. UI 应显示 RECOVERING（非首次 CONNECTING）
  4. 开 WiFi，60s 内恢复 ACTIVE
  期望: RECOVERY_REATTACH requested/accepted 或 phase RECOVERING->ACTIVE
  硬门禁: S13 RECOVERY_ATTACH_LATENCY P95<=15s warn / >60s fail
[T5] 二次建会（可选）
[T6] Host Leave，全员结束
[T7] 终止后不得 rejoin（同 P0Quick）

全程结束后运行 hard gates (S1-S14)。
"@
}

Write-Host ""
Write-Host "按 Enter 开始采集（先在三台清空 logcat）..."
[void][System.Console]::ReadLine()

$stageArgs = @(
    "-DurationMin", $durationMin,
    "-OutDir", $OutDir,
    "-M01", $M01,
    "-M02", $M02,
    "-M03", $M03
)
if ($InstallApk) { $stageArgs += "-InstallApk" }

& (Join-Path $PSScriptRoot "soak-stage-a.ps1") @stageArgs

Write-Step "Hard gates (S1-S14)"
$gateScript = Join-Path $PSScriptRoot "soak-tcc-hard-gates.ps1"
& $gateScript -LogDir $OutDir
$gateExit = $LASTEXITCODE

Write-Step "T4/T7 快速 grep"
Get-ChildItem (Join-Path $OutDir "*.txt") | ForEach-Object {
    $name = $_.Name
    $c = Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue
    if (-not $c) { return }
    $terminated = ([regex]::Matches($c, 'CONFERENCE_TERMINATED')).Count
    $recoveryReq = ([regex]::Matches($c, 'RECOVERY_REATTACH requested')).Count
    $recoveryOk = ([regex]::Matches($c, 'RECOVERY_REATTACH accepted')).Count
    $recovering = ([regex]::Matches($c, 'phase=RECOVERING')).Count
    $active = ([regex]::Matches($c, 'CONFERENCE_RUNTIME_PROJECTION.*phase=ACTIVE')).Count
    $zombie = ([regex]::Matches($c, 'Conference rejoin memory saved')).Count
    Write-Host "$name TERMINATED=$terminated RECOVERY_REQ=$recoveryReq RECOVERY_OK=$recoveryOk RECOVERING=$recovering ACTIVE_PROJ=$active REJOIN_SAVED=$zombie"
}

Write-Host ""
if ($gateExit -eq 0) {
    Write-Host "PASS: gates satisfied. Logs: $OutDir" -ForegroundColor Green
} else {
    Write-Host "FAIL: see gate output above. Logs: $OutDir" -ForegroundColor Red
}
exit $gateExit