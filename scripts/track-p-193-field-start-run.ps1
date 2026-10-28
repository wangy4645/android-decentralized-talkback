# #193 Track P — host edge outcome directed field (M01→M02 after M02 WiFi flap)
param(
    [string]$LogDir = "",
    [switch]$InstallApk,
    [string]$ApkPath = ".\talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "logs\track-p-193-field-$stamp"
}

& (Join-Path $PSScriptRoot "t9c-180-field-start-run.ps1") -LogDir $LogDir -InstallApk:$InstallApk -ApkPath $ApkPath

@(
    "case=TRACK_P_193_DIRECTED"
    "issue=#193"
    "contract=ADR-0055-Track-P"
    "scenario=conference_3party_m02_wifi_flap_observe_m01_to_m02"
    "dut=M02"
    "observe=M01"
    "goal=RECOVERY_WATCHDOG_DEFERRED_vs_terminal_disposition"
    "pass=timeout_then_RECOVERY_DECISION_then_ATTEMPT_SUCCEEDED_or_FAILED"
    "fail=ATTEMPT_TIMEOUT_then_WATCHDOG_DEFERRED_without_terminal"
    "note=EDGE_RECOVERED_not_required"
) | Add-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

$steps = @'
# Track P directed — #193 / ADR-0055

Build: see RUN_META.txt

Stop:
  .\scripts\t9c-180-field-stop-run.ps1 -LogDir "<LogDir>"
Adjudicate:
  .\scripts\track-p-193-field-adjudicate.ps1 -LogDir "<LogDir>"

---

1. 三机直接进 Conference，等到 ONLINE
2. M02 关 WiFi ~5s 再开
3. 等 60s（不要 early stop）
4. Stop + adjudicate

Observe M01 log, edge M01→M02 only.
'@
$steps = $steps.Replace("<LogDir>", $LogDir)
Set-Content (Join-Path $LogDir "TEST_STEPS.txt") -Value $steps -Encoding UTF8

Write-Host "Track P run card: $LogDir\TEST_STEPS.txt"
