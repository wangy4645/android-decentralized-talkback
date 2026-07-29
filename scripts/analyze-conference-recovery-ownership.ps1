# Conference recovery ownership causal timeline analyzer (ADR-0022 observation).
# Answers Q1-Q3 from a single-node or multi-node log set.
#
# Usage:
#   .\scripts\analyze-conference-recovery-ownership.ps1 -LogPath logs\wifi-presence-M02-20260715-121008.log
#   .\scripts\analyze-conference-recovery-ownership.ps1 -LogDir logs -Stamp 20260715-121008

param(
    [string]$LogPath = "",
    [string]$LogDir = "",
    [string]$Stamp = "",
    [string]$Prefix = "wifi-presence"
)

$probeFilter = @(
    "CONFERENCE_RECOVERY_OWNERSHIP_SNAPSHOT",
    "CONFERENCE_REJOIN_RESPONSE",
    "FAILED_MEDIA_RECOVERY",
    "RECOVERY_EDGE_RECOVERED",
    "RECOVERY_REEVALUATE",
    "ICE_CONNECTED",
    "MEDIA_LIFECYCLE",
    "AUTHORITY_MEMBER_DECISION",
    "Conference membership mutation",
    "Mesh invite rejected",
    "RECOVERY_ATTEMPT_OPENED"
) -join "|"

function Parse-LogTimestamp([string]$Line) {
    if ($Line -match '^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})') {
        try {
            return [datetime]::ParseExact($Matches[1], "MM-dd HH:mm:ss.fff", $null)
        } catch { return $null }
    }
    return $null
}

function Parse-KvLine([string]$Line) {
    $map = @{}
    foreach ($m in [regex]::Matches($Line, '\s([A-Za-z0-9_]+)=([^\s]+)')) {
        $map[$m.Groups[1].Value] = $m.Groups[2].Value
    }
    return $map
}

function Resolve-LogFiles {
    if ($LogPath -and (Test-Path $LogPath)) {
        return @((Resolve-Path $LogPath).Path)
    }
    $dir = if ($LogDir) { $LogDir } else { Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")) "logs" }
    if (-not (Test-Path $dir)) {
        throw "Log directory not found: $dir"
    }
    if ($Stamp) {
        return Get-ChildItem -Path $dir -Filter "${Prefix}-*-${Stamp}.log" | ForEach-Object { $_.FullName }
    }
    return Get-ChildItem -Path $dir -Filter "${Prefix}-*.log" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 3 |
        ForEach-Object { $_.FullName }
}

$files = @(Resolve-LogFiles)
if ($files.Count -eq 0) {
    throw "No log files found."
}

Write-Host "Analyzing $($files.Count) log file(s)..."

$events = New-Object System.Collections.Generic.List[object]

foreach ($file in $files) {
    $node = if ($file -match 'M0[123]') { $Matches[0] } else { Split-Path $file -Leaf }
    Get-Content $file -Encoding UTF8 | ForEach-Object {
        $line = $_
        if ($line -notmatch $probeFilter) { return }
        $ts = Parse-LogTimestamp $line
        $kv = Parse-KvLine $line
        $events.Add([pscustomobject]@{
            Node = $node
            File = Split-Path $file -Leaf
            Ts = $ts
            Line = $line
            Tag = if ($line -match 'CONFERENCE_RECOVERY_OWNERSHIP_SNAPSHOT') { "OWNERSHIP" }
                  elseif ($line -match 'CONFERENCE_REJOIN_RESPONSE') { "REJOIN_RESPONSE" }
                  elseif ($line -match 'FAILED_MEDIA_RECOVERY') { "FAILED_MEDIA" }
                  elseif ($line -match 'RECOVERY_EDGE_RECOVERED') { "EDGE_RECOVERED" }
                  elseif ($line -match 'transport_recovered|RECOVERY_REEVALUATE') { "TRANSPORT_RECOVERY" }
                  elseif ($line -match 'ICE_CONNECTED|MEDIA_LIFECYCLE.*CONNECTED') { "ICE_CONNECTED" }
                  elseif ($line -match 'decision=REMOVE|decisionType=PRUNE|AUTHORITY_PRUNE|Conference membership mutation') { "PRUNE" }
                  elseif ($line -match 'CONFERENCE_REJOIN_RESPONSE|Mesh invite rejected.*BUSY') { "BUSY" }
                  elseif ($line -match 'RECOVERY_ATTEMPT_OPENED.*SUPERSEDE|attempt_superseded') { "SUPERSEDE" }
                  else { "OTHER" }
            Kv = $kv
        })
    }
}

$sorted = $events | Where-Object { $_.Ts } | Sort-Object Ts
Write-Host ""
Write-Host "=== Event counts ==="
$sorted | Group-Object Tag | Sort-Object Name | ForEach-Object {
    Write-Host ("  {0,-18} {1}" -f $_.Name, $_.Count)
}

Write-Host ""
Write-Host "=== Q1: FAILED_MEDIA_RECOVERY after transport recovery? ==="
$transport = $sorted | Where-Object { $_.Tag -in @("ICE_CONNECTED", "TRANSPORT_RECOVERY", "EDGE_RECOVERED") }
$failed = $sorted | Where-Object { $_.Tag -eq "FAILED_MEDIA" }
$q1Hits = @()
foreach ($f in $failed) {
    $remote = $f.Kv["remote"]
    if (-not $remote) { $remote = $f.Kv["participantId"] }
    $prior = $transport | Where-Object {
        $_.Ts -lt $f.Ts -and (
            ($_.Kv["remote"] -eq $remote) -or
            ($_.Kv["participantId"] -eq $remote) -or
            ($_.Line -match "module=$remote")
        )
    } | Select-Object -Last 1
    if ($prior) {
        $q1Hits += [pscustomobject]@{
            Remote = $remote
            TransportAt = $prior.Ts
            FailedAt = $f.Ts
            DeltaMs = [int](($f.Ts - $prior.Ts).TotalMilliseconds)
            FailedLine = $f.Line
        }
    }
}
if ($q1Hits.Count -eq 0) {
    Write-Host "  No causal hits found (or missing transport markers before FAILED_MEDIA)."
} else {
    $q1Hits | ForEach-Object {
        Write-Host ("  remote={0} transport@{1:HH:mm:ss.fff} -> FAILED@{2:HH:mm:ss.fff} (+{3}ms)" -f $_.Remote, $_.TransportAt, $_.FailedAt, $_.DeltaMs)
    }
}

Write-Host ""
Write-Host "=== Q2: BUSY followed by PRUNE? ==="
$busy = $sorted | Where-Object { $_.Tag -in @("BUSY", "REJOIN_RESPONSE") -and $_.Line -match "BUSY" }
$prunes = $sorted | Where-Object { $_.Tag -eq "PRUNE" }
$q2Hits = @()
foreach ($b in $busy) {
    $target = $b.Kv["target"]
    if (-not $target) {
        if ($b.Line -match 'rejected by (\S+)') { $target = $Matches[1] }
    }
    $laterPrune = $prunes | Where-Object {
        $_.Ts -gt $b.Ts -and (
            ($_.Kv["participant"] -eq $target) -or
            ($_.Kv["participantId"] -eq $target) -or
            ($_.Line -match "remote=$target")
        )
    } | Select-Object -First 1
    if ($laterPrune) {
        $q2Hits += [pscustomobject]@{
            Target = $target
            BusyAt = $b.Ts
            PruneAt = $laterPrune.Ts
            DeltaMs = [int](($laterPrune.Ts - $b.Ts).TotalMilliseconds)
        }
    }
}
if ($q2Hits.Count -eq 0) {
    Write-Host "  No BUSY -> PRUNE sequence found."
} else {
    $q2Hits | ForEach-Object {
        Write-Host ("  target={0} BUSY@{1:HH:mm:ss.fff} -> PRUNE@{2:HH:mm:ss.fff} (+{3}ms)" -f $_.Target, $_.BusyAt, $_.PruneAt, $_.DeltaMs)
    }
}

Write-Host ""
Write-Host "=== Q3: Supersede missing after new attempt / self recovery? ==="
$supersedes = $sorted | Where-Object { $_.Tag -eq "SUPERSEDE" -or $_.Line -match "attempt_superseded|decision=SUPERSEDED" }
$opened = $sorted | Where-Object { $_.Line -match "RECOVERY_ATTEMPT_OPENED" }
$q3Hits = @()
foreach ($open in $opened) {
    if ($open.Line -notmatch "newAttempt=(\d+)") { continue }
    $attempt = [long]$Matches[1]
    if ($attempt -le 1) { continue }
    $remote = $open.Kv["remote"]
    $priorFailed = $failed | Where-Object {
        $_.Kv["remote"] -eq $remote -and $_.Ts -lt $open.Ts
    } | Select-Object -Last 1
    $hasSupersede = $supersedes | Where-Object {
        $_.Ts -ge $open.Ts.AddMilliseconds(-500) -and $_.Ts -le $open.Ts.AddMilliseconds(500) -and $_.Line -match "remote=$remote"
    } | Select-Object -First 1
    if ($priorFailed -and -not $hasSupersede) {
        $q3Hits += [pscustomobject]@{
            Remote = $remote
            NewAttempt = $attempt
            PriorFailedAt = $priorFailed.Ts
            OpenedAt = $open.Ts
        }
    }
}
if ($q3Hits.Count -eq 0) {
    Write-Host "  No obvious supersede gap detected."
} else {
    $q3Hits | ForEach-Object {
        Write-Host ("  remote={0} newAttempt={1} after FAILED@{2:HH:mm:ss.fff} without nearby SUPERSEDE" -f $_.Remote, $_.NewAttempt, $_.PriorFailedAt)
    }
}

Write-Host ""
Write-Host "=== Ownership snapshots (last 20) ==="
$sorted | Where-Object { $_.Tag -eq "OWNERSHIP" } | Select-Object -Last 20 | ForEach-Object {
    Write-Host ("  [{0:HH:mm:ss.fff}] {1}" -f $_.Ts, $_.Line.Substring(0, [Math]::Min(220, $_.Line.Length)))
}
