# ADR-0031 Observation Matrix Soak v1
# Offline analyzer: parse REACHABILITY_PROBE + recovery lines into per-observer matrix.
# Verdict layers: 0030 / 0022 / 0031 / INV-MEM-001 / Diagnostics.
#
# Usage:
#   Dry-run existing logs:
#     .\scripts\soak-observation-matrix.ps1 -AnalyzeOnly -LogDir ..\logs\obligation-p1-clean-20260720-125309 -Scenario M-S1
#   Collect after repro:
#     .\scripts\soak-observation-matrix.ps1 -ClearOnly
#     .\scripts\soak-observation-matrix.ps1 -CollectOnly -Stamp 20260720-140000 -Scenario M-S2

param(
    [switch]$ClearOnly,
    [switch]$CollectOnly,
    [switch]$AnalyzeOnly,
    [string]$LogDir = "",
    [string]$Stamp = "",
    [string]$Prefix = "obs-matrix",
    [ValidateSet("auto", "M-S1", "M-S2", "M-S3", "M-S4")]
    [string]$Scenario = "auto",
    [string]$SessionId = "",
    [int]$StaleWindowSec = 5
)

$devices = @{
    M01 = "HTUBB21B09220661"
    M02 = "2d73067a"
    M03 = "MDX0220416001963"
}

$probeFilter = @(
    "REACHABILITY_PROBE",
    "RECOVERY_",
    "ICE ",
    "GROUP_LEAVE",
    "RECOVERY_EDGE_CANCELLED",
    "cancelEdge",
    "CONFERENCE_REJOIN",
    "Conference rejoin invite",
    "Host re-invited",
    "INV-MEM-001",
    "Mesh invite rejected",
    "member_evict",
    "AUTHORITY_PRUNE",
    "MEETING_ENDED"
) -join "|"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$workspaceRoot = Resolve-Path (Join-Path $repoRoot "..")
$defaultLogsDir = Join-Path $workspaceRoot "logs"
$talkbackLogsDir = Join-Path $repoRoot "logs"

function Resolve-Adb {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "adb"
    )
    foreach ($c in $candidates) {
        if ($c -eq "adb") {
            $cmd = Get-Command adb -ErrorAction SilentlyContinue
            if ($cmd) { return $cmd.Source }
        } elseif (Test-Path $c) {
            return $c
        }
    }
    throw "adb not found."
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

function Parse-BoolToken([string]$Value) {
    if ($null -eq $Value) { return "" }
    return $Value.ToLowerInvariant()
}

function Resolve-MembershipLocal([bool]$RosterContains, [bool]$ParticipantRecordExists) {
    if ($RosterContains -and $ParticipantRecordExists) { return "JOINED" }
    if (-not $RosterContains) { return "LEFT" }
    return "UNKNOWN"
}

function Resolve-LocalReachability {
    param(
        [string]$MembershipLocal,
        [string]$ReceivePathLive,
        [string]$Recovering,
        [string]$MediaUnavailable,
        [string]$MediaEverLive
    )
    if ($MembershipLocal -eq "LEFT") { return "LEFT" }
    if ($Recovering -eq "true" -or $MediaUnavailable -eq "true") { return "RECONNECTING" }
    if ($ReceivePathLive -eq "true") { return "ONLINE" }
    if ($MediaEverLive -eq "true") { return "RECONNECTING" }
    return "JOINING"
}

function Map-ResolveToFinalPresence([string]$Resolved) {
    switch ($Resolved) {
        "JOINING" { return "CONNECTING" }
        default { return $Resolved }
    }
}

function Parse-ProbeLine {
    param(
        [string]$Line,
        [string]$Observer,
        [datetime]$Timestamp
    )
    if ($Line -notmatch 'REACHABILITY_PROBE') { return $null }

    $row = [ordered]@{
        sessionId             = ""
        observer              = $Observer
        subject               = ""
        timestamp             = $Timestamp.ToString("yyyy-MM-dd HH:mm:ss.fff")
        membershipLocal       = ""
        iceState              = ""
        recovering            = ""
        mediaUnavailable      = ""
        receivePathLive       = ""
        mediaEverLive         = ""
        obligationGen         = ""
        obligationOpen        = ""
        phase                 = ""
        finalPresence         = ""
        transportEverConnected = ""
        visible               = ""
    }

    if ($Line -match 'session=([^\s]+)') { $row.sessionId = $Matches[1] }
    if ($Line -match 'module=([^\s]+)') { $row.subject = $Matches[1] }
    if ($Line -match 'rosterContains=(true|false)') { $row._roster = $Matches[1] -eq "true" }
    if ($Line -match 'participantRecordExists=(true|false)') { $row._record = $Matches[1] -eq "true" }
    if ($Line -match 'iceConnectionState=([^\s]+)') { $row.iceState = $Matches[1] }
    if ($Line -match 'controllerEdgeRecovering=(true|false)') { $row.recovering = $Matches[1] }
    if ($Line -match 'mediaUnavailable=(true|false)') { $row.mediaUnavailable = $Matches[1] }
    if ($Line -match 'receivePathLive=(true|false)') { $row.receivePathLive = $Matches[1] }
    if ($Line -match 'mediaEverLive=(true|false)') { $row.mediaEverLive = $Matches[1] }
    if ($Line -match 'obligationOpen=(true|false)') { $row.obligationOpen = $Matches[1] }
    if ($Line -match 'edgeRecoveryPhase=([^\s]+)') { $row.phase = $Matches[1] }
    if ($Line -match 'transportEverConnected=(true|false)') { $row.transportEverConnected = $Matches[1] }
    if ($Line -match 'finalPresence=([^\s]+)') { $row.finalPresence = $Matches[1] }
    if ($Line -match 'visible=(true|false)') { $row.visible = $Matches[1] }

    $row.membershipLocal = Resolve-MembershipLocal $row._roster $row._record
    $row.Remove("_roster")
    $row.Remove("_record")
    return [PSCustomObject]$row
}

function Parse-RecoveryEvents {
    param([string[]]$Lines, [string]$Observer)

    $events = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $Lines) {
        $ts = Parse-LogTimestamp $line
        if (-not $ts) { continue }

        if ($line -match 'remote=([^\s]+).*obligationGen=(\d+)') {
            $events.Add([PSCustomObject]@{
                    observer      = $Observer
                    subject       = $Matches[1]
                    timestamp     = $ts
                    obligationGen = [int]$Matches[2]
                    line          = $line
                })
        }
        elseif ($line -match 'edge=([^\s]+).*obligationGen=(\d+)') {
            $events.Add([PSCustomObject]@{
                    observer      = $Observer
                    subject       = $Matches[1]
                    timestamp     = $ts
                    obligationGen = [int]$Matches[2]
                    line          = $line
                })
        }
    }
    return $events
}

function Enrich-ObligationGen {
    param(
        [object[]]$Rows,
        [object[]]$RecoveryEvents
    )
    foreach ($row in $Rows) {
        $ts = [datetime]::ParseExact($row.timestamp, "yyyy-MM-dd HH:mm:ss.fff", $null)
        $match = $RecoveryEvents |
            Where-Object {
                $_.observer -eq $row.observer -and
                $_.subject -eq $row.subject -and
                $_.timestamp -le $ts
            } |
            Sort-Object timestamp -Descending |
            Select-Object -First 1
        if ($match) { $row.obligationGen = [string]$match.obligationGen }
    }
}

function Find-LogDir {
    param([string]$Requested)
    if ($Requested -and (Test-Path $Requested)) {
        return (Resolve-Path $Requested).Path
    }
    $candidates = @(
        (Join-Path $defaultLogsDir "obligation-p1-clean-*"),
        (Join-Path $defaultLogsDir "$Prefix-*"),
        (Join-Path $talkbackLogsDir "$Prefix-*")
    )
    foreach ($pattern in $candidates) {
        $dirs = Get-ChildItem -Path (Split-Path $pattern) -Filter (Split-Path $pattern -Leaf) -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending
        if ($dirs) { return $dirs[0].FullName }
    }
    throw "LogDir not found. Pass -LogDir."
}

function Load-MatrixFromLogDir {
    param([string]$Dir)

    $matrix = [System.Collections.Generic.List[object]]::new()
    $allLines = @{}

    foreach ($name in @("M01", "M02", "M03")) {
        $path = Join-Path $Dir "$name.log"
        if (-not (Test-Path $path)) { continue }
        $lines = Get-Content $path -ErrorAction Stop
        $allLines[$name] = $lines
        $recoveryEvents = Parse-RecoveryEvents -Lines $lines -Observer $name
        $rows = [System.Collections.Generic.List[object]]::new()

        foreach ($line in $lines) {
            $ts = Parse-LogTimestamp $line
            if (-not $ts) { continue }
            $probe = Parse-ProbeLine -Line $line -Observer $name -Timestamp $ts
            if ($probe) { $rows.Add($probe) }
        }

        Enrich-ObligationGen -Rows $rows -RecoveryEvents $recoveryEvents
        foreach ($r in $rows) { $matrix.Add($r) }
    }

    return [PSCustomObject]@{
        Matrix   = @($matrix)
        AllLines = $allLines
        LogDir   = $Dir
    }
}

function Detect-Scenarios {
    param(
        [object[]]$Matrix,
        [hashtable]$AllLines
    )
    $scenarios = [System.Collections.Generic.List[string]]::new()

    $text = ($AllLines.Values | ForEach-Object { $_ }) -join "`n"
    if ($text -match 'obligationGen=2.*NEW_OBLIGATION_EPISODE|pathway=NEW_OBLIGATION_EPISODE') {
        $scenarios.Add("M-S1")
    }
    if ($text -match 'RECOVERY_EDGE_CANCELLED|GROUP_LEAVE') {
        $scenarios.Add("M-S3")
    }

    $bySubject = $Matrix | Where-Object { $_.finalPresence -ne "NOT_PROJECTED" } |
        Group-Object subject
    foreach ($g in $bySubject) {
        $presences = $g.Group | Select-Object -ExpandProperty finalPresence -Unique
        $recovering = $g.Group | Where-Object { $_.recovering -eq "true" }
        if ($recovering.Count -gt 0 -and $presences.Count -gt 1) {
            if (-not $scenarios.Contains("M-S2")) { $scenarios.Add("M-S2") }
        }
    }

    $steady = $Matrix | Where-Object {
        $_.finalPresence -eq "ONLINE" -and $_.recovering -eq "false"
    }
    if ($steady.Count -ge 10) {
        $scenarios.Add("M-S4")
    }

    if ($scenarios.Count -eq 0) { $scenarios.Add("M-S4") }
    return @($scenarios)
}

function New-Verdict {
    return [PSCustomObject]@{
        Adr0030 = [PSCustomObject]@{
            Status     = "PASS"
            Failures   = [System.Collections.Generic.List[string]]::new()
            Projection = [System.Collections.Generic.List[object]]::new()
        }
        Adr0022 = [PSCustomObject]@{ Status = "PASS"; Failures = [System.Collections.Generic.List[string]]::new() }
        Adr0031 = [PSCustomObject]@{ Status = "PASS"; Failures = [System.Collections.Generic.List[string]]::new() }
        InvMem001 = [PSCustomObject]@{ Status = "PASS"; Failures = [System.Collections.Generic.List[string]]::new() }
        Diagnostics = [System.Collections.Generic.List[string]]::new()
    }
}

function Format-StructuredFailure {
    param(
        [string]$Category,
        [string]$Observer,
        [string]$Subject,
        [string]$Expected,
        [string]$Actual,
        [string]$Detail = ""
    )
    $msg = "category=$Category observer=$Observer subject=$Subject expected=$Expected actual=$Actual"
    if ($Detail) { $msg += " detail=$Detail" }
    return $msg
}

function Add-Failure {
    param(
        [object]$Verdict,
        [string]$Layer,
        [string]$Message
    )
    switch ($Layer) {
        "0030" { $Verdict.Adr0030.Failures.Add($Message) }
        "0022" { $Verdict.Adr0022.Failures.Add($Message) }
        "0031" { $Verdict.Adr0031.Failures.Add($Message) }
        "inv-mem" { $Verdict.InvMem001.Failures.Add($Message) }
        "diag" { $Verdict.Diagnostics.Add($Message) }
    }
}

function Finalize-Verdict([object]$Verdict) {
    foreach ($layer in @("Adr0030", "Adr0022", "Adr0031", "InvMem001")) {
        if ($Verdict.$layer.Failures.Count -gt 0) {
            $Verdict.$layer.Status = "FAIL"
        }
    }
    return $Verdict
}

function Get-ExpectedFinalPresence {
    param(
        [string]$MembershipLocal,
        [string]$ReceivePathLive,
        [string]$Recovering,
        [string]$MediaUnavailable,
        [string]$MediaEverLive
    )
    $resolved = Resolve-LocalReachability `
        $MembershipLocal $ReceivePathLive $Recovering $MediaUnavailable $MediaEverLive
    return Map-ResolveToFinalPresence $resolved
}

function Test-ProjectionConsistency {
    param(
        [object[]]$Rows,
        [object]$Verdict,
        [string]$SessionFilter,
        [switch]$RecordPasses
    )
    $checked = 0
    $mismatches = 0
    foreach ($row in $Rows) {
        if ($SessionFilter -and $row.sessionId -ne $SessionFilter) { continue }
        if ($row.finalPresence -eq "NOT_PROJECTED") { continue }
        if ($row.visible -ne "true") { continue }

        $expectedFinal = Get-ExpectedFinalPresence `
            $row.membershipLocal $row.receivePathLive $row.recovering `
            $row.mediaUnavailable $row.mediaEverLive

        $checked++
        $entry = [PSCustomObject]@{
            observer = $row.observer
            subject  = $row.subject
            expected = $expectedFinal
            actual   = $row.finalPresence
            status   = if ($row.finalPresence -eq $expectedFinal) { "PASS" } else { "FAIL" }
            timestamp = $row.timestamp
        }
        if ($RecordPasses) {
            $Verdict.Adr0030.Projection.Add($entry)
        }

        if ($row.finalPresence -ne $expectedFinal) {
            $mismatches++
            Add-Failure $Verdict "0030" (Format-StructuredFailure `
                    -Category "PRESENCE_PROJECTION" `
                    -Observer $row.observer `
                    -Subject $row.subject `
                    -Expected $expectedFinal `
                    -Actual $row.finalPresence `
                    -Detail "ts=$($row.timestamp) recovering=$($row.recovering) receivePathLive=$($row.receivePathLive) mediaUnavailable=$($row.mediaUnavailable) mediaEverLive=$($row.mediaEverLive)")
        }
    }
    Add-Failure $Verdict "diag" "projection rows checked: $checked mismatches: $mismatches"
}

function Test-Rule2Lock {
    param(
        [object[]]$Rows,
        [object]$Verdict,
        [string]$SessionFilter
    )
    $violations = 0
    foreach ($row in $Rows) {
        if ($SessionFilter -and $row.sessionId -ne $SessionFilter) { continue }
        if ($row.finalPresence -ne "ONLINE") { continue }

        if ($row.recovering -eq "true" -and $row.receivePathLive -eq "true") {
            $violations++
            Add-Failure $Verdict "0030" (Format-StructuredFailure `
                    -Category "RULE2_LOCK" `
                    -Observer $row.observer `
                    -Subject $row.subject `
                    -Expected "not ONLINE" `
                    -Actual "ONLINE" `
                    -Detail "recovering=true receivePathLive=true")
        }
        if ($row.mediaUnavailable -eq "true") {
            $violations++
            Add-Failure $Verdict "0030" (Format-StructuredFailure `
                    -Category "RULE2_LOCK" `
                    -Observer $row.observer `
                    -Subject $row.subject `
                    -Expected "not ONLINE" `
                    -Actual "ONLINE" `
                    -Detail "mediaUnavailable=true")
        }
    }
    Add-Failure $Verdict "diag" "Rule2 lock violations: $violations"
}

function Test-InvMem001 {
    param(
        [object[]]$Matrix,
        [hashtable]$AllLines,
        [object]$Verdict,
        [string]$SessionFilter
    )

    $busyEvents = [System.Collections.Generic.List[object]]::new()
    foreach ($observer in $AllLines.Keys) {
        foreach ($line in $AllLines[$observer]) {
            $ts = Parse-LogTimestamp $line
            if (-not $ts) { continue }

            if ($line -match 'CONFERENCE_REJOIN_RESPONSE[^\n]*target=([^\s]+)[^\n]*response=BUSY') {
                $busyEvents.Add([PSCustomObject]@{
                        observer  = $observer
                        subject   = $Matches[1]
                        timestamp = $ts
                        source    = "CONFERENCE_REJOIN_RESPONSE"
                        line      = $line.Trim()
                    })
            }
            elseif ($line -match 'Mesh invite rejected by ([^\s]+) reason=BUSY') {
                $subject = $Matches[1]
                $recentRejoin = $false
                foreach ($prior in $AllLines[$observer]) {
                    if ($prior -notmatch "Conference rejoin invite sent -> $subject|Host re-invited $subject") { continue }
                    $priorTs = Parse-LogTimestamp $prior
                    if ($priorTs -and $priorTs -le $ts -and ($ts - $priorTs).TotalSeconds -le 30) {
                        $recentRejoin = $true
                        break
                    }
                }
                if ($recentRejoin) {
                    $busyEvents.Add([PSCustomObject]@{
                            observer  = $observer
                            subject   = $subject
                            timestamp = $ts
                            source    = "MESH_INVITE_REJECTED"
                            line      = $line.Trim()
                        })
                }
            }
        }
    }

    if ($busyEvents.Count -eq 0) {
        Add-Failure $Verdict "diag" "INV-MEM-001: no rejoin BUSY events found (skip membership invariant)"
        return
    }

    foreach ($event in $busyEvents) {
        $windowStart = $event.timestamp.AddSeconds(-2)
        $windowEnd = $event.timestamp.AddSeconds(5)

        $preRow = $Matrix | Where-Object {
            $_.observer -eq $event.observer -and
            $_.subject -eq $event.subject -and
            ([datetime]::ParseExact($_.timestamp, "yyyy-MM-dd HH:mm:ss.fff", $null)) -le $event.timestamp -and
            ([datetime]::ParseExact($_.timestamp, "yyyy-MM-dd HH:mm:ss.fff", $null)) -ge $windowStart
        } | Sort-Object { [datetime]::ParseExact($_.timestamp, "yyyy-MM-dd HH:mm:ss.fff", $null) } -Descending |
            Select-Object -First 1

        $postRow = $Matrix | Where-Object {
            $_.observer -eq $event.observer -and
            $_.subject -eq $event.subject -and
            ([datetime]::ParseExact($_.timestamp, "yyyy-MM-dd HH:mm:ss.fff", $null)) -gt $event.timestamp -and
            ([datetime]::ParseExact($_.timestamp, "yyyy-MM-dd HH:mm:ss.fff", $null)) -le $windowEnd
        } | Sort-Object { [datetime]::ParseExact($_.timestamp, "yyyy-MM-dd HH:mm:ss.fff", $null) } |
            Select-Object -First 1

        $hadJoinedMembership = $false
        if ($preRow -and $preRow.membershipLocal -eq "JOINED") {
            $hadJoinedMembership = $true
        }

        $explicitDeparture = $false
        foreach ($line in $AllLines[$event.observer]) {
            $ts = Parse-LogTimestamp $line
            if (-not $ts) { continue }
            if ($ts -lt $event.timestamp -or $ts -gt $windowEnd) { continue }
            if ($line -notmatch $event.subject) { continue }
            if ($line -match 'GROUP_LEAVE|AUTHORITY_PRUNE|MEETING_ENDED|member_evict|applyPrune|Conference terminated') {
                $explicitDeparture = $true
                break
            }
        }

        $guardFired = ($AllLines[$event.observer] | Where-Object {
                $_ -match 'INV-MEM-001|Ignoring BUSY from established member ' + [regex]::Escape($event.subject)
            }).Count -gt 0

        if ($hadJoinedMembership -and -not $explicitDeparture) {
            if ($postRow -and $postRow.membershipLocal -ne "JOINED") {
                Add-Failure $Verdict "inv-mem" (Format-StructuredFailure `
                        -Category "INV_MEM_001" `
                        -Observer $event.observer `
                        -Subject $event.subject `
                        -Expected "JOINED" `
                        -Actual $postRow.membershipLocal `
                        -Detail "rejoin BUSY at $($event.timestamp) source=$($event.source)")
            }
            elseif (-not $postRow -and -not $guardFired) {
                Add-Failure $Verdict "inv-mem" (
                    "category=INV_MEM_001 observer=$($event.observer) subject=$($event.subject) " +
                    "expected=JOINED actual=UNKNOWN detail=no post-BUSY probe; guardFired=$guardFired"
                )
            }
        }

        Add-Failure $Verdict "diag" (
            "INV-MEM-001 event observer=$($event.observer) subject=$($event.subject) " +
            "source=$($event.source) preMembership=$($preRow.membershipLocal) " +
            "postMembership=$($postRow.membershipLocal) guardFired=$guardFired explicitDeparture=$explicitDeparture"
        )
    }
}

function Test-MS1 {
    param(
        [object[]]$Matrix,
        [hashtable]$AllLines,
        [object]$Verdict,
        [string]$SessionFilter
    )
    Test-Rule2Lock -Rows $Matrix -Verdict $Verdict -SessionFilter $SessionFilter
    Test-ProjectionConsistency -Rows $Matrix -Verdict $Verdict -SessionFilter $SessionFilter -RecordPasses

    $hostLines = $AllLines["M02"]
    if (-not $hostLines) {
        Add-Failure $Verdict "0022" "M-S1: M02 host log missing"
        return
    }
    $text = $hostLines -join "`n"
    if ($text -notmatch 'RECOVERY_OBLIGATION_CLOSED[^\n]+reason=RECOVERED') {
        Add-Failure $Verdict "0022" "M-S1: missing RECOVERY_OBLIGATION_CLOSED(RECOVERED)"
    }
    if ($text -notmatch 'obligationGen=2.*NEW_OBLIGATION_EPISODE|pathway=NEW_OBLIGATION_EPISODE[^\n]+obligationGen=2') {
        Add-Failure $Verdict "0022" "M-S1: missing obligationGen=2 NEW_OBLIGATION_EPISODE"
    }

    $coexist = ($Matrix | Where-Object {
            $_.recovering -eq "true" -and $_.receivePathLive -eq "true"
        }).Count
    Add-Failure $Verdict "diag" "M-S1: recovering+receivePathLive coexist rows: $coexist (allowed)"
}

function Test-MS2 {
    param(
        [object[]]$Matrix,
        [hashtable]$AllLines,
        [object]$Verdict,
        [string]$SessionFilter
    )
    # M-S2: observer asymmetry validation — per-observer projection only; no cross-observer equality.
    Test-ProjectionConsistency -Rows $Matrix -Verdict $Verdict -SessionFilter $SessionFilter -RecordPasses

    $subjects = $Matrix | Select-Object -ExpandProperty subject -Unique
    foreach ($subject in $subjects) {
        $views = $Matrix | Where-Object {
            $_.subject -eq $subject -and $_.finalPresence -ne "NOT_PROJECTED" -and $_.visible -eq "true"
        }
        if ($views.Count -lt 2) { continue }

        $byObserver = $views | Group-Object observer | ForEach-Object {
            $latest = $_.Group[-1]
            $expected = Get-ExpectedFinalPresence `
                $latest.membershipLocal $latest.receivePathLive $latest.recovering `
                $latest.mediaUnavailable $latest.mediaEverLive
            [PSCustomObject]@{
                observer   = $latest.observer
                subject    = $latest.subject
                expected   = $expected
                actual     = $latest.finalPresence
                recovering = $latest.recovering
            }
        }

        $presenceSet = $byObserver | Select-Object -ExpandProperty actual -Unique
        $observerList = ($byObserver | Select-Object -ExpandProperty observer -Unique) -join ","
        Add-Failure $Verdict "diag" (
            "M-S2 asymmetry subject=$subject observers=$observerList " +
            "finalPresence values=$($presenceSet -join ',') (cross-observer equality NOT required)"
        )

        foreach ($view in $byObserver) {
            Add-Failure $Verdict "diag" (
                "M-S2 observer=$($view.observer) -> $($view.subject) " +
                "expected=$($view.expected) actual=$($view.actual) recovering=$($view.recovering)"
            )
        }
    }

    Add-Failure $Verdict "diag" "M-S2: observer-local projection only (ADR-0031 v1 non-goal: no cross-observer convergence)"
}

function Test-MS3 {
    param(
        [object[]]$Matrix,
        [hashtable]$AllLines,
        [object]$Verdict,
        [string]$SessionFilter,
        [int]$WindowSec
    )

    Test-InvMem001 -Matrix $Matrix -AllLines $AllLines -Verdict $Verdict -SessionFilter $SessionFilter

    $cancels = [System.Collections.Generic.List[object]]::new()
    foreach ($observer in $AllLines.Keys) {
        foreach ($line in $AllLines[$observer]) {
            if ($line -notmatch 'RECOVERY_EDGE_CANCELLED|GROUP_LEAVE_SENT|GROUP_LEAVE_RECEIVED') { continue }
            $ts = Parse-LogTimestamp $line
            if (-not $ts) { continue }
            $subject = ""
            if ($line -match 'remote=([^\s]+)') { $subject = $Matches[1] }
            elseif ($line -match 'participant=([^\s]+)') { $subject = $Matches[1] }
            elseif ($line -match 'from=([^\s]+)') { $subject = $Matches[1] }
            $cancels.Add([PSCustomObject]@{
                    observer  = $observer
                    subject   = $subject
                    timestamp = $ts
                    line      = $line.Trim()
                })
        }
    }

    if ($cancels.Count -eq 0) {
        Add-Failure $Verdict "diag" "M-S3: no RECOVERY_EDGE_CANCELLED / GROUP_LEAVE events (freshness checks skipped)"
    } else {
        foreach ($cancel in $cancels) {
            $windowEnd = $cancel.timestamp.AddSeconds($WindowSec)
            $preGen = ""
            $preRows = $Matrix | Where-Object {
                $_.observer -eq $cancel.observer -and
                $_.subject -eq $cancel.subject -and
                ([datetime]::ParseExact($_.timestamp, "yyyy-MM-dd HH:mm:ss.fff", $null)) -lt $cancel.timestamp -and
                $_.obligationGen -ne ""
            } | Sort-Object { [datetime]::ParseExact($_.timestamp, "yyyy-MM-dd HH:mm:ss.fff", $null) } -Descending
            if ($preRows) { $preGen = $preRows[0].obligationGen }

            $postRows = $Matrix | Where-Object {
                $_.observer -eq $cancel.observer -and
                $_.subject -eq $cancel.subject -and
                ([datetime]::ParseExact($_.timestamp, "yyyy-MM-dd HH:mm:ss.fff", $null) -gt $cancel.timestamp) -and
                ([datetime]::ParseExact($_.timestamp, "yyyy-MM-dd HH:mm:ss.fff", $null) -le $windowEnd)
            }

            foreach ($row in $postRows) {
                if ($row.recovering -eq "true") {
                    Add-Failure $Verdict "0031" (
                        "M-S3 stale promotion: recovering=true within ${WindowSec}s after cancel " +
                        "observer=$($cancel.observer) subject=$($cancel.subject) ts=$($row.timestamp)"
                    )
                }
                if ($preGen -and $row.obligationGen -eq $preGen -and $row.recovering -eq "true") {
                    Add-Failure $Verdict "0031" (
                        "M-S3 stale obligationGen=$preGen still recovering after cancel " +
                        "observer=$($cancel.observer) subject=$($cancel.subject) ts=$($row.timestamp)"
                    )
                }
                if ($row.phase -match 'RECOVERY_PENDING|DISCONNECTED|ICE_RESTART' -and $row.recovering -eq "true") {
                    Add-Failure $Verdict "0031" (
                        "M-S3 stale phase=$($row.phase) in resolve path after cancel " +
                        "observer=$($cancel.observer) subject=$($cancel.subject) ts=$($row.timestamp)"
                    )
                }
            }
            Add-Failure $Verdict "diag" "M-S3 cancel event observer=$($cancel.observer) subject=$($cancel.subject) preGen=$preGen postRows=$($postRows.Count)"
        }
    }
}

function Test-MS4 {
    param(
        [object[]]$Matrix,
        [object]$Verdict,
        [string]$SessionFilter
    )
    foreach ($row in $Matrix) {
        if ($SessionFilter -and $row.sessionId -ne $SessionFilter) { continue }
        if ($row.observer -notin @("M01", "M02", "M03")) {
            Add-Failure $Verdict "0031" "M-S4: unknown observer $($row.observer)"
        }
        if ($row.subject -eq $row.observer) {
            Add-Failure $Verdict "diag" "M-S4: self-subject row observer=$($row.observer) (informational)"
        }
    }

    $foreign = $Matrix | Where-Object { $_.observer -notin @("M01", "M02", "M03") }
    if ($foreign.Count -gt 0) {
        Add-Failure $Verdict "0031" "M-S4: foreign observer rows in matrix: $($foreign.Count)"
    }

    Test-ProjectionConsistency -Rows ($Matrix | Where-Object { $_.finalPresence -eq "ONLINE" }) `
        -Verdict $Verdict -SessionFilter $SessionFilter -RecordPasses
    Add-Failure $Verdict "diag" "M-S4: R31-O-1 enforced via log-file observer attribution (no cross-process rows)"
}

function Sort-Scenarios([string[]]$Scenarios) {
    $order = @{ "M-S4" = 1; "M-S1" = 2; "M-S2" = 3; "M-S3" = 4 }
    return $Scenarios | Sort-Object { if ($order.ContainsKey($_)) { $order[$_] } else { 99 } }
}

function Write-ProjectionSummary {
    param(
        [System.Collections.Generic.List[object]]$ProjectionRows,
        [System.Collections.Generic.List[string]]$Lines
    )
    if ($ProjectionRows.Count -eq 0) { return }

    $seen = @{}
    $unique = $ProjectionRows | Where-Object {
        $key = "$($_.observer)|$($_.subject)|$($_.expected)|$($_.actual)|$($_.status)"
        if ($seen.ContainsKey($key)) { return $false }
        $seen[$key] = $true
        return $true
    } | Sort-Object observer, subject

    $Lines.Add("0030-PROJECTION:")
  foreach ($row in $unique) {
        $Lines.Add("  $($row.status)")
        $Lines.Add("    $($row.observer) -> $($row.subject)")
        $Lines.Add("      expected=$($row.expected)")
        $Lines.Add("      actual=$($row.actual)")
    }
    $Lines.Add("")
}

function Build-Summary {
    param(
        [object]$Verdict,
        [string[]]$ScenariosRun,
        [string]$LogDirPath,
        [string]$CsvPath
    )
    $v = Finalize-Verdict $Verdict
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("Observation Matrix Soak v1")
    $lines.Add("")
    $lines.Add("LogDir: $LogDirPath")
    $lines.Add("Matrix: $CsvPath")
    $lines.Add("Scenarios: $($ScenariosRun -join ', ')")
    $lines.Add("")

    Write-ProjectionSummary -ProjectionRows $v.Adr0030.Projection -Lines $lines

    $lines.Add("[ADR-0030 Presence Projection]")
    $lines.Add($v.Adr0030.Status)
    foreach ($f in $v.Adr0030.Failures) {
        if ($f -match '^category=') {
            $lines.Add("  FAIL:")
            $lines.Add("    $f")
        } else {
            $lines.Add("  - $f")
        }
    }
    $lines.Add("")

    $lines.Add("[ADR-0022 Obligation Episode]")
    $lines.Add($v.Adr0022.Status)
    foreach ($f in $v.Adr0022.Failures) { $lines.Add("  - $f") }
    $lines.Add("")

    $lines.Add("[ADR-0031 Observation Scope]")
    $lines.Add($v.Adr0031.Status)
    foreach ($f in $v.Adr0031.Failures) { $lines.Add("  - $f") }
    $lines.Add("")

    $lines.Add("[INV-MEM-001 Membership Invariant]")
    $lines.Add($v.InvMem001.Status)
    foreach ($f in $v.InvMem001.Failures) {
        if ($f -match '^category=') {
            $lines.Add("  FAIL:")
            $lines.Add("    $f")
        } else {
            $lines.Add("  - $f")
        }
    }
    $lines.Add("")

    $lines.Add("[Diagnostics]")
    $lines.Add("INFO ONLY")
    foreach ($d in $v.Diagnostics) { $lines.Add("  - $d") }

    return ($lines -join "`n")
}

function Invoke-Analyze {
    param([string]$Dir)

    $loaded = Load-MatrixFromLogDir -Dir $Dir
    $matrix = $loaded.Matrix
    if ($matrix.Count -eq 0) {
        throw "No REACHABILITY_PROBE rows in $Dir"
    }

    $sessionFilter = $SessionId
    if (-not $sessionFilter) {
        $sessionFilter = ($matrix | Group-Object sessionId | Sort-Object Count -Descending | Select-Object -First 1).Name
    }

    $matrix = $matrix | Where-Object { $_.sessionId -eq $sessionFilter }
    $scenariosToRun = if ($Scenario -eq "auto") {
        Sort-Scenarios (Detect-Scenarios -Matrix $matrix -AllLines $loaded.AllLines)
    } else {
        @($Scenario)
    }

    $verdict = New-Verdict
    foreach ($sc in $scenariosToRun) {
        switch ($sc) {
            "M-S1" { Test-MS1 -Matrix $matrix -AllLines $loaded.AllLines -Verdict $verdict -SessionFilter $sessionFilter }
            "M-S2" { Test-MS2 -Matrix $matrix -AllLines $loaded.AllLines -Verdict $verdict -SessionFilter $sessionFilter }
            "M-S3" { Test-MS3 -Matrix $matrix -AllLines $loaded.AllLines -Verdict $verdict -SessionFilter $sessionFilter -WindowSec $StaleWindowSec }
            "M-S4" { Test-MS4 -Matrix $matrix -Verdict $verdict -SessionFilter $sessionFilter }
        }
    }

    $stampSuffix = Split-Path $Dir -Leaf
    $csvPath = Join-Path $Dir "observation-matrix.csv"
    $matrix | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8

    $summary = Build-Summary -Verdict $verdict -ScenariosRun $scenariosToRun -LogDirPath $Dir -CsvPath $csvPath
    $summaryPath = Join-Path $Dir "obs-matrix-summary.txt"
    Set-Content -Path $summaryPath -Value $summary -Encoding UTF8

    Write-Host $summary
    Write-Host ""
    Write-Host "CSV=$csvPath"
    Write-Host "SUMMARY=$summaryPath"

    $final = Finalize-Verdict $verdict
    if ($final.Adr0030.Status -eq "FAIL" -or $final.Adr0022.Status -eq "FAIL" -or `
        $final.Adr0031.Status -eq "FAIL" -or $final.InvMem001.Status -eq "FAIL") {
        exit 1
    }
    exit 0
}

if ($AnalyzeOnly) {
    $dir = if ($LogDir) { (Resolve-Path $LogDir).Path } else { Find-LogDir $Stamp }
    Invoke-Analyze -Dir $dir
    exit $LASTEXITCODE
}

if (-not $Stamp) {
    $Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
}

$outDir = Join-Path $defaultLogsDir "$Prefix-$Stamp"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$adb = Resolve-Adb

if ($ClearOnly -or -not $CollectOnly) {
    foreach ($name in $devices.Keys) {
        Write-Host "[$name] logcat -c"
        & $adb -s $devices[$name] logcat -c 2>&1 | Out-Null
    }
}

if ($ClearOnly) {
    Write-Host ""
    Write-Host "Observation Matrix Soak v1 — repro hints:"
    Write-Host "  M-S4: 30s steady ONLINE baseline (run first)"
    Write-Host "  M-S1: M02 host, M01 WiFi off ~20s, recover, repeat once (no leave)"
    Write-Host "  M-S2: observer asymmetry — M01 participant disconnects; M02 authority stays online;"
    Write-Host "        M03 observes independently; no cross-observer presence equality required"
    Write-Host "  M-S3: WiFi flap / rejoin + INV-MEM-001 (rejoin BUSY must not evict JOINED member)"
    Write-Host ""
    Write-Host "Recommended order: M-S4 -> M-S1 -> M-S2 -> M-S3"
    Write-Host ""
    Write-Host "Collect: .\scripts\soak-observation-matrix.ps1 -CollectOnly -Stamp $Stamp -Scenario <M-Sx>"
    Write-Host "OUT_DIR=$outDir"
    exit 0
}

foreach ($name in $devices.Keys) {
    $out = Join-Path $outDir "$name.log"
    Write-Host "[$name] collecting -> $out"
    & $adb -s $devices[$name] logcat -d -v time -s Talkback:I 2>&1 |
        Select-String -Pattern $probeFilter |
        Set-Content -Path $out -Encoding utf8
    $metaLine = "observer=$name collected=$Stamp scenario=$Scenario"
    Add-Content -Path (Join-Path $outDir "meta.txt") -Value $metaLine -Encoding utf8
}

Write-Host ""
Write-Host "OUT_DIR=$outDir"
& $PSScriptRoot\soak-observation-matrix.ps1 -AnalyzeOnly -LogDir $outDir -Scenario $Scenario
