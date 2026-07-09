# Feedback loop: USER_REJOIN must not be approved with ICE_RESTART_ONLY / REATTACH.
# Red = leave→rejoin path still routes Membership Rejoin through Connectivity Recovery Policy.
# Usage: .\scripts\assert-user-rejoin-recovery-mispolicy.ps1 -LogDir <dir>
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $LogDir)) {
    Write-Error "LogDir not found: $LogDir"
    exit 2
}

$failures = [System.Collections.Generic.List[string]]::new()
$evidence = [System.Collections.Generic.List[string]]::new()

$lines = Get-ChildItem (Join-Path $LogDir "*.txt") | ForEach-Object {
    Get-Content $_.FullName -ErrorAction SilentlyContinue
}

$userRejoinApproved = 0
$sdpSetupErrors = 0
$connectingStuck = 0
$budgetExhausted = 0
$duplicateIgnored = 0

foreach ($line in $lines) {
    if ($line -match 'RECOVERY_DECISION.*recoveryReason=USER_REJOIN.*policy=ICE_RESTART_ONLY.*approved=true') {
        $userRejoinApproved++
        if ($evidence.Count -lt 5) { $evidence.Add($line.Trim()) }
    }
    if ($line -match 'Answerer must use either active or passive value for setup attribute') {
        $sdpSetupErrors++
    }
    if ($line -match 'phase=CONNECTING host=false authority=false.*conferenceUiReady=false') {
        $connectingStuck++
    }
    if ($line -match 'ice_restart_budget_exhausted') {
        $budgetExhausted++
    }
    if ($line -match 'rejectReason=duplicate_reattach_accepted') {
        $duplicateIgnored++
    }
}

Write-Host "=== assert-user-rejoin-recovery-mispolicy ==="
Write-Host "USER_REJOIN + ICE_RESTART_ONLY approved=true : $userRejoinApproved"
Write-Host "SDP setup-attribute errors                  : $sdpSetupErrors"
Write-Host "CONNECTING authority=false uiReady=false    : $connectingStuck"
Write-Host "ice_restart_budget_exhausted                : $budgetExhausted"
Write-Host "duplicate_reattach_accepted (idempotent OK) : $duplicateIgnored"

if ($userRejoinApproved -gt 0) {
    $failures.Add("USER_REJOIN approved with Connectivity ICE_RESTART_ONLY policy (count=$userRejoinApproved)")
}
if ($sdpSetupErrors -gt 0 -and $userRejoinApproved -gt 0) {
    $failures.Add("SDP setup-attribute errors after USER_REJOIN recovery path (count=$sdpSetupErrors)")
}
if ($connectingStuck -gt 0 -and $userRejoinApproved -gt 0) {
    $failures.Add("Participant stuck CONNECTING/authority=false after USER_REJOIN (count=$connectingStuck)")
}

Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "GREEN: no USER_REJOIN→ICE_RESTART_ONLY mispolicy evidence"
    exit 0
}

Write-Host "RED: $($failures.Count) assertion(s) failed"
$failures | ForEach-Object { Write-Host "  FAIL $_" -ForegroundColor Red }
if ($evidence.Count -gt 0) {
    Write-Host ""
    Write-Host "Sample evidence:"
    $evidence | ForEach-Object { Write-Host "  $_" }
}
exit 1
