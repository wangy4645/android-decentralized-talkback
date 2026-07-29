# Phase P0-a - GROUP transition readiness observation (ADR-0022)
# Scenario: M01 host meeting -> end -> PTT at t+0/5/10/15s
#
# Usage:
#   1. Install APK on M01/M02/M03
#   2. .\scripts\soak-p0a-group-transition.ps1 -ClearOnly
#   3. Run meeting + host end + PTT probes (repeat 5 rounds)
#   4. .\scripts\soak-p0a-group-transition.ps1 -CollectOnly -Stamp <stamp>
#   5. .\scripts\soak-p0a-group-transition.ps1 -AnalyzeOnly -Stamp <stamp>

param(
    [switch]$ClearOnly,
    [switch]$CollectOnly,
    [switch]$AnalyzeOnly,
    [string]$Stamp = "",
    [string]$Prefix = "soak-p0a"
)

$devices = @{
    M01 = "HTUBB21B09220661"
    M02 = "2d73067a"
    M03 = "MDX0220416001963"
}

$probeFilter = @(
    "GROUP_TRANSITION_READINESS_SNAPSHOT",
    "MEETING_END_BEGIN",
    "BOOTSTRAP_ATTEMPT",
    "TRANSITION_TERMINAL_READY",
    "TRANSITION_BEGIN",
    "TRANSITION_TERMINAL",
    "TRANSITION_PREDICATE_EVAL",
    "Waiting for primary"
) -join "|"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$logsDir = Join-Path $repoRoot "logs"

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
    throw "adb not found. Install Android SDK platform-tools or set ANDROID_HOME."
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

function Parse-KvLine([string]$Line) {
    $map = @{}
    if ($Line -notmatch '\s(\w+)=([^\s]+)') { return $map }
    $pattern = '\s([A-Za-z0-9_]+)=([^\s]+)'
    foreach ($m in [regex]::Matches($Line, $pattern)) {
        $map[$m.Groups[1].Value] = $m.Groups[2].Value
    }
    return $map
}

function Resolve-Stamp {
    param([string]$Requested)
    if ($Requested) { return $Requested }
    $latest = Get-ChildItem -Path $logsDir -Filter "$Prefix-M01-*.log" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if (-not $latest) {
        throw "No $Prefix-M01-*.log in $logsDir; pass -Stamp"
    }
    if ($latest.Name -match "$Prefix-M01-(\d{8}-\d{6})\.log") {
        return $Matches[1]
    }
    throw "Cannot parse stamp from $($latest.Name)"
}

function Build-LocalReport {
    param(
        [string]$Label,
        [string[]]$Lines
    )
    $meetingEnds = @()
    $terminals = @()
    $bootstrapAttempts = 0
    $primaryResolves = 0
    $orphanBeliefStarts = @()
    $orphanBeliefOpen = $false
    $orphanBeliefStartTs = $null
    $snapshots = @()

    foreach ($line in $Lines) {
        $ts = Parse-LogTimestamp $line
        if ($line -match 'MEETING_END_BEGIN ') {
            if ($ts) { $meetingEnds += $ts }
        }
        if ($line -match 'TRANSITION_TERMINAL_READY ') {
            $kv = Parse-KvLine $line
            $dur = $null
            if ($kv.ContainsKey('durationMs') -and $kv['durationMs'] -match '^\d+$') {
                $dur = [double]$kv['durationMs']
            }
            $terminals += [pscustomobject]@{
                Ts = $ts
                DurationMs = $dur
            }
        }
        if ($line -match 'BOOTSTRAP_ATTEMPT ') {
            $bootstrapAttempts++
        }
        if ($line -match 'GROUP_TRANSITION_READINESS_SNAPSHOT ') {
            $kv = Parse-KvLine $line
            $snapshots += [pscustomobject]@{ Ts = $ts; Kv = $kv }
            if ($kv.ContainsKey('reason') -and $kv['reason'] -eq 'primary_resolve') {
                $primaryResolves++
            }
            $belief = $kv['orphanBelief'] -eq 'true'
            if ($belief -and -not $orphanBeliefOpen) {
                $orphanBeliefOpen = $true
                $orphanBeliefStartTs = $ts
            } elseif (-not $belief -and $orphanBeliefOpen) {
                if ($orphanBeliefStartTs -and $ts) {
                    $orphanBeliefStarts += ($ts - $orphanBeliefStartTs).TotalMilliseconds
                }
                $orphanBeliefOpen = $false
                $orphanBeliefStartTs = $null
            }
        }
    }
    if ($orphanBeliefOpen -and $orphanBeliefStartTs) {
        $orphanBeliefStarts += 0
    }

    $transitionDurations = @()
    for ($i = 0; $i -lt [Math]::Min($meetingEnds.Count, $terminals.Count); $i++) {
        if ($terminals[$i].DurationMs -ne $null) {
            $transitionDurations += $terminals[$i].DurationMs
        } elseif ($meetingEnds[$i] -and $terminals[$i].Ts) {
            $transitionDurations += ($terminals[$i].Ts - $meetingEnds[$i]).TotalMilliseconds
        }
    }

    $terminalReadyTrueWhileOrphan = ($snapshots | Where-Object {
        $_.Kv['terminalReady'] -eq 'true' -and $_.Kv['orphanBelief'] -eq 'true'
    }).Count

    return [pscustomobject]@{
        Label = $Label
        MeetingEndCount = $meetingEnds.Count
        TransitionTerminalCount = $terminals.Count
        TransitionDurationMs = $transitionDurations
        BootstrapAttemptCount = $bootstrapAttempts
        PrimaryResolveCount = $primaryResolves
        OrphanBeliefDurationMs = $orphanBeliefStarts
        TerminalReadyWhileOrphanBelief = $terminalReadyTrueWhileOrphan
        SnapshotCount = $snapshots.Count
    }
}

function Format-Report {
    param($Reports, [string]$StampValue)
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("GROUP Transition Readiness (P0-a Layer 1)")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("Stamp: $StampValue")
    [void]$sb.AppendLine("")
    foreach ($r in $Reports) {
        [void]$sb.AppendLine("=== $($r.Label) ===")
        [void]$sb.AppendLine("MEETING_END_BEGIN: $($r.MeetingEndCount)")
        [void]$sb.AppendLine("TRANSITION_TERMINAL_READY: $($r.TransitionTerminalCount)")
        if ($r.TransitionDurationMs.Count -gt 0) {
            $avg = ($r.TransitionDurationMs | Measure-Object -Average).Average
            [void]$sb.AppendLine("transitionDurationMs avg=$([math]::Round($avg, 0)) n=$($r.TransitionDurationMs.Count)")
        } else {
            [void]$sb.AppendLine("transitionDurationMs: n/a")
        }
        [void]$sb.AppendLine("bootstrapAttemptCount: $($r.BootstrapAttemptCount)")
        [void]$sb.AppendLine("primaryResolveCount: $($r.PrimaryResolveCount)")
        if ($r.OrphanBeliefDurationMs.Count -gt 0) {
            $maxOrphan = ($r.OrphanBeliefDurationMs | Measure-Object -Maximum).Maximum
            [void]$sb.AppendLine("orphanBeliefDurationMs max=$([math]::Round($maxOrphan, 0)) samples=$($r.OrphanBeliefDurationMs.Count)")
        } else {
            [void]$sb.AppendLine("orphanBeliefDurationMs: n/a")
        }
        [void]$sb.AppendLine("terminalReady=true while orphanBelief=true snapshots: $($r.TerminalReadyWhileOrphanBelief)")
        [void]$sb.AppendLine("readiness snapshots: $($r.SnapshotCount)")
        [void]$sb.AppendLine("")
    }
    [void]$sb.AppendLine("Layer 2 (correlateBySessionTraceId): not implemented - placeholder")
    return $sb.ToString()
}

if ($AnalyzeOnly) {
    $stampValue = Resolve-Stamp -Requested $Stamp
    $reports = @()
    foreach ($name in $devices.Keys) {
        $path = Join-Path $logsDir "$Prefix-$name-$stampValue.log"
        if (-not (Test-Path $path)) {
            Write-Error "Log not found: $path"
            exit 1
        }
        $lines = Get-Content $path -ErrorAction Stop
        $reports += Build-LocalReport -Label $name -Lines $lines
    }
    $summary = Format-Report -Reports $reports -StampValue $stampValue
    $summaryPath = Join-Path $logsDir "$Prefix-summary-$stampValue.txt"
    Set-Content -Path $summaryPath -Value $summary -Encoding utf8
    Write-Host $summary
    Write-Host ""
    Write-Host "SUMMARY=$summaryPath"
    exit 0
}

if (-not $Stamp) {
    $Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
}

New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
$adb = Resolve-Adb

foreach ($name in $devices.Keys) {
    $id = $devices[$name]
    if ($ClearOnly -or -not $CollectOnly) {
        Write-Host "[$name] logcat -c"
        & $adb -s $id logcat -c 2>&1 | Out-Null
    }
}

if ($ClearOnly) {
    Write-Host ""
    Write-Host "Cleared. P0-a protocol:"
    Write-Host "  1. M01 host meeting, M02/M03 join"
    Write-Host "  2. M01 end meeting"
    Write-Host "  3. PTT on all devices: t+0s / t+5s / t+10s / t+15s"
    Write-Host "  4. Repeat 5 rounds"
    Write-Host "  5. Run: .\scripts\soak-p0a-group-transition.ps1 -CollectOnly -Stamp $Stamp"
    Write-Host ""
    Write-Host "STAMP=$Stamp"
    exit 0
}

foreach ($name in $devices.Keys) {
    $id = $devices[$name]
    $out = Join-Path $logsDir "$Prefix-$name-$Stamp.log"
    Write-Host "[$name] collecting -> $out"
    & $adb -s $id logcat -d -v time -s Talkback:I 2>&1 |
        Select-String -Pattern $probeFilter |
        Set-Content -Path $out -Encoding utf8
    $lineCount = (Get-Content $out -ErrorAction SilentlyContinue | Measure-Object -Line).Lines
    Write-Host "  $lineCount lines"
}

Write-Host ""
Write-Host "STAMP=$Stamp"
Write-Host "LOGS=$logsDir"

& $PSScriptRoot\soak-p0a-group-transition.ps1 -AnalyzeOnly -Stamp $Stamp -Prefix $Prefix
