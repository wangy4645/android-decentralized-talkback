# ADR-0056 Phase 4-8 — Field shadow evidence run START (operational only).
# Shadow-only · control path=legacy · no migration · STOP on control contamination.
param(
    [ValidateSet("A", "B", "C", "D", "E")]
    [string]$Case = "A",
    [string]$RunId = "",
    [string]$ApkVersion = "",
    [string]$NetworkEnvironment = "field-wifi",
    [int]$ParticipantCount = 8,
    [string[]]$DeviceSerials = @(
        "HTUBB21B09220661",
        "DSJ-2407041",
        "MDX0220416001963"
    ),
    [string]$PackageName = "com.talkback.appprod"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "phase48-field-shadow-$Case-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $head) { $head = "unknown" }
if ([string]::IsNullOrWhiteSpace($ApkVersion)) {
    $ApkVersion = $head
}

$caseMap = @{
    "A" = "CASE_A_COLD_JOIN — 8 participant cold join"
    "B" = "CASE_B_STEADY_STATE — 30-60 min steady observation"
    "C" = "CASE_C_LEAVE_REJOIN — edge invalidation / rejoin"
    "D" = "CASE_D_WIFI_FLAP — legacy recovery vs shadow would-action"
    "E" = "CASE_E_LONG_RUN — 2-4 h observer health"
}

$logDir = Join-Path $repoRoot "logs/$RunId"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCmd) {
    $adb = $adbCmd.Source
    foreach ($serial in $DeviceSerials) {
        try {
            $state = (& $adb -s $serial get-state 2>&1 | Out-String).Trim()
        } catch {
            Write-Warning "Device unreachable serial=$serial"
            continue
        }
        if ($state -ne "device") {
            Write-Warning "Device offline serial=$serial state=$state"
        } else {
            & $adb -s $serial logcat -c | Out-Null
            Write-Host "logcat cleared serial=$serial"
        }
    }
}

@(
    "phase=4-8"
    "mode=field_shadow_evidence"
    "runId=$RunId"
    "case=$Case"
    "caseLabel=$($caseMap[$Case])"
    "started=$(Get-Date -Format o)"
    "apkVersion=$ApkVersion"
    "participantCount=$ParticipantCount"
    "networkEnvironment=$NetworkEnvironment"
    "deviceSerials=$($DeviceSerials -join ',')"
    "shadowEnabled=true"
    "controlPath=legacy"
    "observationSource=DEVICE"
    "logTag=SHADOW_FIELD_EVIDENCE"
    "logDir=$logDir"
    "STOP_ON=CONTROL_CONTAMINATION|AUTHORITY_VIOLATION"
    "forbidden=Coordinator_mutation|shadow_recovery_execute|Authority_publish|Meeting_UI"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

@(
    "Phase 4-8 Field Shadow Run — operator checklist"
    ""
    "1. Production build on all devices; shadow enabled=true; control path=legacy."
    "2. Clear stale Meeting sessions before Case A."
    "3. Execute case $($caseMap[$Case])."
    "4. On session end, app emits SHADOW_FIELD_EVIDENCE log lines."
    "5. Run phase48-field-shadow-run-stop.ps1 -RunId $RunId when finished."
    ""
    "Case notes:"
    "  A — create 8-person meeting, wait steady, observe join topology."
    "  B — hold steady 30-60 min; record ordering / observer health."
    "  C — member leave then rejoin; watch edge identity + generation."
    "  D — single-device WiFi flap; record wouldAction only (no shadow execute)."
    "  E — 2-4 h meeting; watch listener leak / backlog / memory counters."
) | Set-Content (Join-Path $logDir "RUN_CARD.txt") -Encoding UTF8

Write-Host "Phase 4-8 START runId=$RunId case=$Case"
Write-Host "LogDir=$logDir"
Write-Host "Next: execute field case, then stop script."
