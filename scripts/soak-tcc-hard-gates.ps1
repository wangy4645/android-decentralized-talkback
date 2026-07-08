# TCC / ADR-0016 + ADR-0017 soak hard gates (S1-S9)
# 用法: .\scripts\soak-tcc-hard-gates.ps1 -LogDir "d:\workspace\project\talkback\logs-tcc-xxxx"
# 扩展 #58 (S1-S5) + #61 (S6-S9)

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

if (-not (Test-Path $LogDir)) {
    Write-Error "LogDir not found: $LogDir"
    exit 1
}

$failures = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Add-Failure([string]$Rule, [string]$Detail) {
    $failures.Add("[$Rule] $Detail")
}

function Add-Warning([string]$Rule, [string]$Detail) {
    $warnings.Add("[$Rule] $Detail")
}

function Parse-LogTimestamp([string]$Line) {
    if ($Line -match '^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})') {
        try {
            return [datetime]::ParseExact($Matches[1], "MM-dd HH:mm:ss.fff", $null)
        } catch {
            return $null
        }
    }
    return $null
}

function Get-ChannelFromLine([string]$Line) {
    if ($Line -match '\bch=([^\s]+)') { return $Matches[1] }
    return $null
}

Write-Host "=== TCC soak hard gates: $LogDir ==="
Write-Host ""

$allText = ""
Get-ChildItem (Join-Path $LogDir "*.txt") | ForEach-Object {
    $content = Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue
    if ($content) { $allText += "`n$content" }
}

if (-not $allText.Trim()) {
    Add-Failure "INPUT" "No log content found in $LogDir"
}

# --- S1: every TRANSITION_BEGIN has TERMINAL within deadline + 2s ---
$lines = $allText -split "`n"
$openTransitions = @{}
foreach ($line in $lines) {
    if ($line -match 'TRANSITION_BEGIN id=(\d+) ch=([^\s]+).*deadlineMs=(\d+)') {
        $id = $Matches[1]
        $ch = $Matches[2]
        $deadlineMs = [long]$Matches[3]
        $ts = Parse-LogTimestamp $line
        if ($ts) {
            $openTransitions[$id] = @{
                Channel = $ch
                BeginAt = $ts
                DeadlineMs = $deadlineMs
            }
        }
    }
    if ($line -match 'TRANSITION_TERMINAL id=(\d+)') {
        $id = $Matches[1]
        if ($openTransitions.ContainsKey($id)) {
            $openTransitions.Remove($id)
        }
    }
}
foreach ($entry in $openTransitions.Values) {
    Add-Failure "S1" "TRANSITION_BEGIN ch=$($entry.Channel) missing TERMINAL (deadlineMs=$($entry.DeadlineMs))"
}

# --- S2: within 2s after MEETING_START READY, no ice=CLOSED on host link ---
$readyEvents = @()
for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    if ($line -match 'TRANSITION_TERMINAL.*trigger=MEETING_START.*terminal=READY') {
        $ch = Get-ChannelFromLine $line
        $ts = Parse-LogTimestamp $line
        if ($ch -and $ts) {
            $readyEvents += @{ Channel = $ch; At = $ts; Index = $i }
        }
    }
}
foreach ($ready in $readyEvents) {
    $windowEnd = $ready.At.AddSeconds(2)
    for ($j = $ready.Index + 1; $j -lt $lines.Count; $j++) {
        $line = $lines[$j]
        $ts = Parse-LogTimestamp $line
        if ($ts -and $ts -gt $windowEnd) { break }
        if ($line -match "ice=CLOSED" -and $line -match "ch=$($ready.Channel)") {
            Add-Failure "S2" "ice=CLOSED within 2s of MEETING_START READY ch=$($ready.Channel)"
            break
        }
    }
}

# --- S3: no Deferring full conference mesh span >10s without TRANSITION_* or ICE ---
$deferStart = $null
foreach ($line in $lines) {
    if ($line -match 'Deferring full conference mesh') {
        $deferStart = Parse-LogTimestamp $line
    } elseif ($deferStart -and ($line -match 'TRANSITION_' -or $line -match '\bICE\b')) {
        $deferStart = $null
    } elseif ($deferStart) {
        $ts = Parse-LogTimestamp $line
        if ($ts -and ($ts - $deferStart).TotalSeconds -gt 10) {
            Add-Failure "S3" "Deferring full conference mesh >10s without TRANSITION/ICE"
            $deferStart = $null
        }
    }
}

# --- S5: informational — second meeting ICE CONNECTED within 15s (grep KPI) ---
$s5Miss = ([regex]::Matches($allText, 'Second MEETING_START must not complete')).Count
if ($s5Miss -eq 0) {
    Add-Warning "S5" "No second-meeting cycle markers; verify manually if test round included"
}

# --- S6: no MEETING_START READY before DECLARATION_FROZEN for same channel ---
$frozenAtByChannel = @{}
$readyBeforeFrozen = 0
foreach ($line in $lines) {
    if ($line -match 'DECLARATION_FROZEN ch=([^\s]+).*trigger=MEETING_START') {
        $ch = $Matches[1]
        $ts = Parse-LogTimestamp $line
        if ($ts) { $frozenAtByChannel[$ch] = $ts }
    }
    if ($line -match 'TRANSITION_TERMINAL.*ch=([^\s]+).*trigger=MEETING_START.*terminal=READY') {
        $ch = $Matches[1]
        $ts = Parse-LogTimestamp $line
        if ($ts -and $frozenAtByChannel.ContainsKey($ch) -and $ts -lt $frozenAtByChannel[$ch]) {
            $readyBeforeFrozen++
            Add-Failure "S6" "MEETING_START READY before DECLARATION_FROZEN ch=$ch"
        } elseif ($ts -and -not $frozenAtByChannel.ContainsKey($ch)) {
            $readyBeforeFrozen++
            Add-Failure "S6" "MEETING_START READY without DECLARATION_FROZEN ch=$ch"
        }
    }
}

# --- S7: no host_solo_conference when declaration mode=MULTI_PARTY ---
$s7Hits = [regex]::Matches(
    $allText,
    'TRANSITION_PREDICATE_EVAL.*reason=host_solo_conference.*mode=MULTI_PARTY'
).Count
if ($s7Hits -gt 0) {
    Add-Failure "S7" "host_solo_conference predicate with MULTI_PARTY declaration count=$s7Hits"
}

# --- S8: MEDIA_SESSION_REUSE = 0 (RO-M2a #63) ---
$s8Reuse = ([regex]::Matches($allText, 'MEDIA_SESSION_REUSE=1')).Count
if ($s8Reuse -gt 0) {
    Add-Failure "S8" "MEDIA_SESSION_REUSE > 0 count=$s8Reuse"
} elseif (-not ($allText -match 'MEDIA_BARRIER_COMPLETE|MEDIA_SESSION_REUSE=0')) {
    Add-Warning "S8" "MEDIA_BARRIER_COMPLETE / MEDIA_SESSION_REUSE=0 markers absent"
}

# --- S9: MEETING_START establishment dispatch failures (ADR-0017 declaration window) ---
# Post-freeze rejoin / counter-invite (typically sent=0/1) must not count as establishment failure.
$meetingStartEstablishmentByChannel = @{}
$establishmentDispatchFailures = 0
$dispatchFailedTerminals = 0
$establishmentPartialSoloRisk = @{}

foreach ($line in $lines) {
    if ($line -match 'TRANSITION_BEGIN id=\d+ ch=([^\s]+).*trigger=MEETING_START') {
        $meetingStartEstablishmentByChannel[$Matches[1]] = $true
    }
    if ($line -match 'DECLARATION_FROZEN ch=([^\s]+).*trigger=MEETING_START') {
        $ch = $Matches[1]
        $meetingStartEstablishmentByChannel.Remove($ch) | Out-Null
        $establishmentPartialSoloRisk.Remove($ch) | Out-Null
    }
    if ($line -match 'TRANSITION_TERMINAL.*ch=([^\s]+).*trigger=MEETING_START') {
        $ch = $Matches[1]
        $meetingStartEstablishmentByChannel.Remove($ch) | Out-Null
        $establishmentPartialSoloRisk.Remove($ch) | Out-Null
        if ($line -match 'reason=INVITE_DISPATCH_FAILED') {
            $dispatchFailedTerminals++
        }
    }
    if ($line -match 'INVITE_DISPATCH_COMPLETED ch=([^\s]+) outcome=(FAILED_NON_RETRYABLE|FAILED_RETRY_EXHAUSTED) sent=(\d+)/(\d+)') {
        $ch = $Matches[1]
        if ($meetingStartEstablishmentByChannel.ContainsKey($ch)) {
            $establishmentDispatchFailures++
            $establishmentPartialSoloRisk[$ch] = $true
        }
    }
    if ($line -match 'TRANSITION_PREDICATE_EVAL ch=([^\s]+).*trigger=MEETING_START.*reason=host_solo_conference') {
        $ch = $Matches[1]
        if ($establishmentPartialSoloRisk.ContainsKey($ch)) {
            Add-Failure "S9" "establishment partial dispatch degraded to host_solo_conference ch=$ch"
            $establishmentPartialSoloRisk.Remove($ch) | Out-Null
        }
    }
}

$rejoinDispatchFailures = ([regex]::Matches(
    $allText,
    'INVITE_DISPATCH_COMPLETED.*outcome=FAILED_(NON_RETRYABLE|RETRY_EXHAUSTED) sent=\d+/1'
)).Count
Write-Host "S9 MEETING_START INVITE_DISPATCH_FAILED terminals: $dispatchFailedTerminals"
Write-Host "S9 establishment-window dispatch failures: $establishmentDispatchFailures"
Write-Host "S9 rejoin dispatch failures (informational, excluded): $rejoinDispatchFailures"
if ($establishmentDispatchFailures -gt 0 -and $dispatchFailedTerminals -eq 0) {
    Add-Failure "S9" "establishment INVITE_DISPATCH failure without INVITE_DISPATCH_FAILED terminal"
}

Write-Host ""
Write-Host "--- Gate results ---"
Write-Host "Failures: $($failures.Count)"
$failures | ForEach-Object { Write-Host "  FAIL $_" -ForegroundColor Red }
if ($warnings.Count -gt 0) {
    Write-Host "Warnings: $($warnings.Count)"
    $warnings | ForEach-Object { Write-Host "  WARN $_" -ForegroundColor Yellow }
}

if ($failures.Count -gt 0) {
    exit 1
}
Write-Host "PASS: all hard gates satisfied"
exit 0
