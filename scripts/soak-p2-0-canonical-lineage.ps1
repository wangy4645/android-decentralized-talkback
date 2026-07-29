# Phase P2-0 - Canonical session lineage observation (ADR-0027)
# Extends P0-a soak with resolver / lineage / recreate metrics.
#
# Usage:
#   1. Install APK with P2-0 observation build
#   2. .\scripts\soak-p2-0-canonical-lineage.ps1 -ClearOnly
#   3. Run meeting + wifi flap + meeting end + PTT probes
#   4. .\scripts\soak-p2-0-canonical-lineage.ps1 -CollectOnly -Stamp <stamp>

param(
    [switch]$ClearOnly,
    [switch]$CollectOnly,
    [switch]$AnalyzeOnly,
    [string]$Stamp = "",
    [string]$Prefix = "soak-p2-0"
)

$devices = @{
    M01 = "HTUBB21B09220661"
    M02 = "2d73067a"
    M03 = "MDX0220416001963"
}

$probeFilter = @(
    "CONVERGENCE_WINDOW_BEGIN",
    "GROUP_TRANSITION_READINESS_SNAPSHOT",
    "MEETING_END_BEGIN",
    "BOOTSTRAP_ATTEMPT",
    "PRIMARY_RESOLVE",
    "GROUP_SESSION_CREATE",
    "GROUP_SESSION_RECREATE",
    "TRANSITION_TERMINAL_READY",
    "CANONICAL_DECISION",
    "CANONICAL_DECISION_APPLIED",
    "LOCAL_TERMINAL_SELF_LEASE",
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
    throw "adb not found."
}

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
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $latest) { throw "No $Prefix-M01-*.log in $logsDir; pass -Stamp" }
    if ($latest.Name -match "$Prefix-M01-(\d{8}-\d{6})\.log") { return $Matches[1] }
    throw "Cannot parse stamp from $($latest.Name)"
}

function Build-P2Report {
    param([string]$Label, [string[]]$Lines)

    $primaryResolveLines = @()
    $primaryChanged = 0
    $sessionCreates = 0
    $sessionRecreates = 0
    $recreateByPrimaryChange = 0
    $meshCreateAttempts = 0
    $canonicalDecisions = 0
    $canonicalApplied = 0
    $localSelfLease = 0
    $localOperationalTerminals = 0
    $canonicalTerminals = 0
    $lineageIds = [System.Collections.Generic.HashSet[string]]::new()
    $lastPrimaryResolveNoMutation = 0
    $lastPrimaryChangeCount = 0
    $silentRecreate = 0

    foreach ($line in $Lines) {
        if ($line -match 'CONVERGENCE_WINDOW_BEGIN ') {
            $kv = Parse-KvLine $line
            if ($kv.ContainsKey('sessionLineageId')) { [void]$lineageIds.Add($kv['sessionLineageId']) }
        }
        if ($line -match 'PRIMARY_RESOLVE ') {
            $kv = Parse-KvLine $line
            $primaryResolveLines += $kv
            if ($kv['primaryChanged'] -eq 'true') { $primaryChanged++ }
            if ($kv.ContainsKey('primaryResolveNoMutationCount')) {
                $lastPrimaryResolveNoMutation = [int]$kv['primaryResolveNoMutationCount']
            }
            if ($kv.ContainsKey('primaryChangeCount')) {
                $lastPrimaryChangeCount = [int]$kv['primaryChangeCount']
            }
        }
        if ($line -match 'GROUP_SESSION_CREATE ') { $sessionCreates++ }
        if ($line -match 'GROUP_SESSION_RECREATE ') {
            $sessionRecreates++
            $kv = Parse-KvLine $line
            if ($kv['recreateByPrimaryChange'] -eq 'true') { $recreateByPrimaryChange++ }
            if (-not $kv.ContainsKey('reason') -or $kv['reason'] -eq '') { $silentRecreate++ }
        }
        if ($line -match 'BOOTSTRAP_ATTEMPT ' -and $line -match 'bootstrapAttemptReason=mesh_create') {
            $meshCreateAttempts++
        }
        if ($line -match 'CANONICAL_DECISION ') { $canonicalDecisions++ }
        if ($line -match 'CANONICAL_DECISION_APPLIED ') { $canonicalApplied++ }
        if ($line -match 'LOCAL_TERMINAL_SELF_LEASE ') { $localSelfLease++ }
        if ($line -match 'TRANSITION_TERMINAL_READY ' -and $line -match 'terminalAuthority=LOCAL_OPERATIONAL') {
            $localOperationalTerminals++
        }
        if ($line -match 'TRANSITION_TERMINAL_READY ' -and $line -match 'terminalAuthority=CANONICAL') {
            $canonicalTerminals++
        }
    }

  $primaryResolveCount = $primaryResolveLines.Count

    return [pscustomobject]@{
        Label = $Label
        LineageIdCount = $lineageIds.Count
        PrimaryResolveCount = $primaryResolveCount
        PrimaryChangeCount = $lastPrimaryChangeCount
        PrimaryResolveNoMutationCount = $lastPrimaryResolveNoMutation
        PrimaryChangedEvents = $primaryChanged
        SessionCreateCount = $sessionCreates
        SessionRecreateCount = $sessionRecreates
        SessionRecreateByPrimaryChange = $recreateByPrimaryChange
        SilentRecreateCount = $silentRecreate
        MeshCreateBootstrapAttempts = $meshCreateAttempts
        CanonicalDecisionCount = $canonicalDecisions
        CanonicalDecisionAppliedCount = $canonicalApplied
        LocalTerminalSelfLeaseCount = $localSelfLease
        LocalOperationalTerminalCount = $localOperationalTerminals
        CanonicalTerminalCount = $canonicalTerminals
    }
}

function Build-Layer2LineageSplit {
    param($Reports, [hashtable]$DeviceLines)

    $windows = @{}
    foreach ($name in $DeviceLines.Keys) {
        foreach ($line in $DeviceLines[$name]) {
            if ($line -notmatch 'CONVERGENCE_WINDOW_BEGIN ') { continue }
            $kv = Parse-KvLine $line
            $lid = $kv['sessionLineageId']
            if (-not $lid) { continue }
            if (-not $windows.ContainsKey($lid)) {
                $windows[$lid] = @{ Devices = @{}; Baselines = @{} }
            }
            $windows[$lid].Devices[$name] = $true
            if ($kv.ContainsKey('baselineMembers')) {
                $windows[$lid].Baselines[$name] = $kv['baselineMembers']
            }
        }
    }

    $splitCount = 0
    foreach ($lid in $windows.Keys) {
        if ($windows[$lid].Devices.Count -lt 2) { continue }
        $baselines = $windows[$lid].Baselines.Values | Sort-Object -Unique
        if ($baselines.Count -gt 1) { $splitCount++ }
    }

    return [pscustomobject]@{
        ConvergenceWindowCount = $windows.Count
        BaselineDivergenceWindows = $splitCount
    }
}

function Format-P2Summary {
    param($Reports, $Layer2, [string]$StampValue)

    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("P2-0 / P2-0.5 Canonical Lineage + Terminal Authority Observation")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("Stamp: $StampValue")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("P2-0 gate (proceed to P2-0.5 / P2-1 if ALL devices):")
    [void]$sb.AppendLine("  primaryChangeCount == 0")
    [void]$sb.AppendLine("  silentRecreateCount == 0")
    [void]$sb.AppendLine("  primaryResolveNoMutationCount ~= primaryResolveCount (resolver noise only)")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("P2-0.5 terminal authority (G-P2-TERM-1 observation):")
    [void]$sb.AppendLine("  M01 (primary): CANONICAL_DECISION per convergence window")
    [void]$sb.AppendLine("  participants: CANONICAL_DECISION_APPLIED or LOCAL_OPERATIONAL (flags premature local terminal)")
    [void]$sb.AppendLine("  LOCAL_TERMINAL_SELF_LEASE = legal partition fallback")
    [void]$sb.AppendLine("")

    foreach ($r in $Reports) {
        [void]$sb.AppendLine("=== $($r.Label) ===")
        [void]$sb.AppendLine("lineageIds (windows): $($r.LineageIdCount)")
        [void]$sb.AppendLine("primaryResolveCount: $($r.PrimaryResolveCount)")
        [void]$sb.AppendLine("primaryChangeCount (final): $($r.PrimaryChangeCount)")
        [void]$sb.AppendLine("primaryResolveNoMutationCount (final): $($r.PrimaryResolveNoMutationCount)")
        [void]$sb.AppendLine("primaryChanged events: $($r.PrimaryChangedEvents)")
        [void]$sb.AppendLine("sessionCreateCount: $($r.SessionCreateCount)")
        [void]$sb.AppendLine("sessionRecreateCount: $($r.SessionRecreateCount)")
        [void]$sb.AppendLine("sessionRecreateByPrimaryChange: $($r.SessionRecreateByPrimaryChange)")
        [void]$sb.AppendLine("silentRecreateCount: $($r.SilentRecreateCount)")
        [void]$sb.AppendLine("mesh_create bootstrap attempts: $($r.MeshCreateBootstrapAttempts)")
        [void]$sb.AppendLine("canonicalDecisionCount: $($r.CanonicalDecisionCount)")
        [void]$sb.AppendLine("canonicalDecisionAppliedCount: $($r.CanonicalDecisionAppliedCount)")
        [void]$sb.AppendLine("localTerminalSelfLeaseCount: $($r.LocalTerminalSelfLeaseCount)")
        [void]$sb.AppendLine("localOperationalTerminalCount: $($r.LocalOperationalTerminalCount)")
        [void]$sb.AppendLine("canonicalTerminalCount: $($r.CanonicalTerminalCount)")
        [void]$sb.AppendLine("")
    }

    [void]$sb.AppendLine("=== Layer 2 (cross-device) ===")
    [void]$sb.AppendLine("convergenceWindowCount: $($Layer2.ConvergenceWindowCount)")
    [void]$sb.AppendLine("baselineDivergenceWindows: $($Layer2.BaselineDivergenceWindows)")
    return $sb.ToString()
}

if ($AnalyzeOnly) {
    $stampValue = Resolve-Stamp -Requested $Stamp
    $reports = @()
    $deviceLines = @{}
    foreach ($name in $devices.Keys) {
        $path = Join-Path $logsDir "$Prefix-$name-$stampValue.log"
        if (-not (Test-Path $path)) { Write-Error "Log not found: $path"; exit 1 }
        $lines = Get-Content $path -ErrorAction Stop
        $deviceLines[$name] = $lines
        $reports += Build-P2Report -Label $name -Lines $lines
    }
    $layer2 = Build-Layer2LineageSplit -Reports $reports -DeviceLines $deviceLines
    $summary = Format-P2Summary -Reports $reports -Layer2 $layer2 -StampValue $stampValue
    $summaryPath = Join-Path $logsDir "$Prefix-summary-$stampValue.txt"
    Set-Content -Path $summaryPath -Value $summary -Encoding utf8
    Write-Host $summary
    Write-Host ""
    Write-Host "SUMMARY=$summaryPath"
    exit 0
}

if (-not $Stamp) { $Stamp = Get-Date -Format "yyyyMMdd-HHmmss" }

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
    Write-Host "Cleared. P2-0 protocol:"
    Write-Host "  1. M01 host meeting, M02/M03 join"
    Write-Host "  2. Optional: M02 wifi flap + reconnect"
    Write-Host "  3. M01 end meeting"
    Write-Host "  4. PTT on all devices: t+0/5/10/15s"
    Write-Host "  5. Collect: .\scripts\soak-p2-0-canonical-lineage.ps1 -CollectOnly -Stamp $Stamp"
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

& $PSScriptRoot\soak-p2-0-canonical-lineage.ps1 -AnalyzeOnly -Stamp $Stamp -Prefix $Prefix
