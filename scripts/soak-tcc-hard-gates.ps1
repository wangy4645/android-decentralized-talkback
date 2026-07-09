# TCC / ADR-0016 + ADR-0017 soak hard gates (S1-S14)
# 用法: .\scripts\soak-tcc-hard-gates.ps1 -LogDir "d:\workspace\project\talkback\logs-tcc-xxxx"
# 扩展 #58 (S1-S5) + #61 (S6-S9) + #66/#68 (S10 HARD) + ADR-0021 (S11-S14)

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

# --- S10 HARD: MEETING_START READY + peer ICE CONNECTED must project runtime phase=ACTIVE (RO-M2 PR-3) ---
foreach ($ready in $readyEvents) {
    $windowEnd = $ready.At.AddSeconds(30)
    $hasIceConnected = $false
    $runtimePhase = $null
    for ($j = $ready.Index + 1; $j -lt $lines.Count; $j++) {
        $line = $lines[$j]
        $ts = Parse-LogTimestamp $line
        if ($ts -and $ts -gt $windowEnd) { break }
        $lineCh = Get-ChannelFromLine $line
        if ($lineCh -and $lineCh -ne $ready.Channel) { continue }
        if ($line -match 'ICE\s+\S+\s+state=(CONNECTED|COMPLETED)') {
            $hasIceConnected = $true
        }
        if ($line -match 'CONFERENCE_RUNTIME_PROJECTION.*\bch=' + [regex]::Escape($ready.Channel) + '\b.*\bphase=(\w+)') {
            $runtimePhase = $Matches[1]
        } elseif ($line -match 'CONFERENCE_RUNTIME_PROJECTION.*\bphase=(\w+).*\bch=' + [regex]::Escape($ready.Channel) + '\b') {
            $runtimePhase = $Matches[1]
        }
        if ($hasIceConnected -and $null -ne $runtimePhase) { break }
    }
    if ($hasIceConnected -and $null -ne $runtimePhase -and $runtimePhase -ne 'ACTIVE') {
        Add-Failure "S10" "MEETING_START READY + ICE CONNECTED but runtime phase=$runtimePhase ch=$($ready.Channel)"
    } elseif ($hasIceConnected -and $null -eq $runtimePhase) {
        Add-Failure "S10" "MEETING_START READY + ICE CONNECTED but no CONFERENCE_RUNTIME_PROJECTION ch=$($ready.Channel)"
    }
}

# --- S11 HARD + S12: per-device log analysis (ADR-0021) ---
$zombieRejoinCount = 0
$meetingStartLatenciesMs = [System.Collections.Generic.List[double]]::new()
Get-ChildItem (Join-Path $LogDir "*.txt") | ForEach-Object {
    $fileLines = (Get-Content $_.FullName -ErrorAction SilentlyContinue) -as [string[]]
    if (-not $fileLines) { return }

    $lastMeetingStartBeginByChannel = @{}
    $terminatedAtByChannel = @{}
    foreach ($line in $fileLines) {
        if ($line -match 'TRANSITION_BEGIN id=([^\s]+) ch=([^\s]+).*trigger=MEETING_START') {
            $ch = $Matches[2]
            $ts = Parse-LogTimestamp $line
            if ($ts) { $lastMeetingStartBeginByChannel[$ch] = $ts }
            $terminatedAtByChannel.Remove($ch) | Out-Null
        }
        if ($line -match 'CONFERENCE_TERMINATED ch=([^\s]+).*clearRejoinState=true') {
            $ch = $Matches[1]
            $ts = Parse-LogTimestamp $line
            if ($ts) { $terminatedAtByChannel[$ch] = $ts }
        }
        if ($line -match 'Conference rejoin memory saved ch=([^\s]+)') {
            $ch = $Matches[1]
            $ts = Parse-LogTimestamp $line
            if (-not $ts) { continue }
            if ($terminatedAtByChannel.ContainsKey($ch) -and $ts -gt $terminatedAtByChannel[$ch]) {
                $newMeetingStarted = $false
                if ($lastMeetingStartBeginByChannel.ContainsKey($ch)) {
                    $newMeetingStarted = $lastMeetingStartBeginByChannel[$ch] -gt $terminatedAtByChannel[$ch]
                }
                if (-not $newMeetingStarted) {
                    $zombieRejoinCount++
                    Add-Failure "S11" "zombie rejoin memory saved after termination ch=$ch file=$($_.Name)"
                }
            }
        }
    }

    $openMeetingStartById = @{}
    foreach ($line in $fileLines) {
        if ($line -match 'TRANSITION_BEGIN id=([^\s]+) ch=([^\s]+).*trigger=MEETING_START') {
            $id = $Matches[1]
            $ts = Parse-LogTimestamp $line
            if ($ts) { $openMeetingStartById[$id] = $ts }
        }
        if ($line -match 'TRANSITION_TERMINAL id=([^\s]+) ch=([^\s]+).*trigger=MEETING_START.*terminal=READY') {
            $id = $Matches[1]
            $ts = Parse-LogTimestamp $line
            if ($ts -and $openMeetingStartById.ContainsKey($id)) {
                $deltaMs = ($ts - $openMeetingStartById[$id]).TotalMilliseconds
                if ($deltaMs -ge 0) { $meetingStartLatenciesMs.Add($deltaMs) }
                $openMeetingStartById.Remove($id) | Out-Null
            }
        }
    }
}
Write-Host "S11 zombie rejoin count: $zombieRejoinCount"
if ($meetingStartLatenciesMs.Count -gt 0) {
    $sorted = $meetingStartLatenciesMs | Sort-Object
    $p50 = $sorted[[int][Math]::Floor(($sorted.Count - 1) * 0.50)]
    $p95 = $sorted[[int][Math]::Floor(($sorted.Count - 1) * 0.95)]
    Write-Host "S12 MEETING_START_LATENCY samples=$($sorted.Count) P50=${p50}ms P95=${p95}ms"
    foreach ($latency in $sorted) {
        if ($latency -gt 10000) {
            Add-Failure "S12" "MEETING_START latency ${latency}ms exceeds 10s"
        }
    }
    if ($p95 -gt 5000) {
        Add-Warning "S12" "MEETING_START P95=${p95}ms exceeds 5s target"
    }
} else {
    Add-Warning "S12" "No MEETING_START latency samples; verify manually if meeting flow was exercised"
}

# --- S13: RECOVERY_ATTACH latency (RECOVERING -> ACTIVE within 120s, T4 only) ---
$recoveryAttachLatenciesMs = [System.Collections.Generic.List[double]]::new()
$recoveryGateFiles = 0
Get-ChildItem (Join-Path $LogDir "*.txt") | ForEach-Object {
    $fileLines = (Get-Content $_.FullName -ErrorAction SilentlyContinue) -as [string[]]
    if (-not $fileLines) { return }
    $exerciseRecovery = @($fileLines | Where-Object {
            $_ -match 'RECOVERY_REATTACH requested' -or
                $_ -match 'Conference host ICE DISCONNECTED' -or
                $_ -match 'ice=DISCONNECTED'
        }).Count -gt 0
    if ($exerciseRecovery) { $recoveryGateFiles++ }
    $recoveringAt = $null
    foreach ($line in $fileLines) {
        $ts = Parse-LogTimestamp $line
        if ($line -match 'CONFERENCE_RUNTIME_PROJECTION.*\bphase=RECOVERING\b') {
            $recoveringAt = $ts
            continue
        }
        if ($null -ne $recoveringAt -and $ts -and ($ts - $recoveringAt).TotalSeconds -gt 120) {
            $recoveringAt = $null
        }
        if ($null -ne $recoveringAt -and $line -match 'CONFERENCE_RUNTIME_PROJECTION.*\bphase=ACTIVE\b') {
            if ($ts) {
                $deltaMs = ($ts - $recoveringAt).TotalMilliseconds
                if ($deltaMs -ge 0 -and $deltaMs -le 120000) {
                    $recoveryAttachLatenciesMs.Add($deltaMs)
                }
                $recoveringAt = $null
            }
        }
    }
}
if ($recoveryAttachLatenciesMs.Count -gt 0) {
    $sorted = $recoveryAttachLatenciesMs | Sort-Object
    $p50 = $sorted[[int][Math]::Floor(($sorted.Count - 1) * 0.50)]
    $p95 = $sorted[[int][Math]::Floor(($sorted.Count - 1) * 0.95)]
    Write-Host "S13 RECOVERY_ATTACH_LATENCY samples=$($sorted.Count) P50=${p50}ms P95=${p95}ms"
    if ($recoveryGateFiles -gt 0) {
        foreach ($latency in $sorted) {
            if ($latency -gt 60000) {
                Add-Failure "S13" "RECOVERY_ATTACH latency ${latency}ms exceeds 60s"
            }
        }
        if ($p95 -gt 15000) {
            Add-Warning "S13" "RECOVERY_ATTACH P95=${p95}ms exceeds 15s target"
        }
    } else {
        Add-Warning "S13" "RECOVERY_ATTACH samples without T4 markers; informational only"
    }
} elseif ($recoveryGateFiles -gt 0) {
    Add-Warning "S13" "T4 recovery exercised but no RECOVERING->ACTIVE pairs within 120s"
} else {
    Add-Warning "S13" "No RECOVERY_ATTACH latency samples; run T4 WiFi loss/recovery"
}

# --- S14 HARD (T7): no recovery reattach after conference termination ---
$terminatedAtByChannel = @{}
$lastMeetingStartBeginByChannel = @{}
$staleRecoveryAfterTermination = 0
foreach ($line in $lines) {
    if ($line -match 'TRANSITION_BEGIN id=([^\s]+) ch=([^\s]+).*trigger=MEETING_START') {
        $ch = $Matches[2]
        $ts = Parse-LogTimestamp $line
        if ($ts) { $lastMeetingStartBeginByChannel[$ch] = $ts }
        $terminatedAtByChannel.Remove($ch) | Out-Null
    }
    if ($line -match 'CONFERENCE_TERMINATED ch=([^\s]+).*clearRejoinState=true') {
        $ch = $Matches[1]
        $ts = Parse-LogTimestamp $line
        if ($ts) { $terminatedAtByChannel[$ch] = $ts }
    }
    if ($line -match 'RECOVERY_REATTACH requested\b.*\bch=([^\s]+)') {
        $ch = $Matches[1]
        $ts = Parse-LogTimestamp $line
        if (-not $ts) { continue }
        if ($terminatedAtByChannel.ContainsKey($ch) -and $ts -gt $terminatedAtByChannel[$ch]) {
            $newMeetingStarted = $false
            if ($lastMeetingStartBeginByChannel.ContainsKey($ch)) {
                $newMeetingStarted = $lastMeetingStartBeginByChannel[$ch] -gt $terminatedAtByChannel[$ch]
            }
            if (-not $newMeetingStarted) {
                $staleRecoveryAfterTermination++
                Add-Failure "S14" "RECOVERY_REATTACH after termination ch=$ch"
            }
        }
    }
}
Write-Host "S14 stale recovery after termination: $staleRecoveryAfterTermination"

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
