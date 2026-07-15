# Phase R1 — Recovery Completion baseline (ADR-0022)
# Scenario: M01 host + M02/M03 participant → M02 WiFi off 20s → recover → wait RECOVERED
#
# Usage:
#   1. Install APK on M01/M02/M03
#   2. .\scripts\soak-r1-recovery-completion.ps1 -ClearOnly
#   3. Repro: M01 host 三方会 → M02 关 WiFi 20s → 开 WiFi → 等 RECOVERED (~60s)
#   4. .\scripts\soak-r1-recovery-completion.ps1 -CollectOnly
#   5. .\scripts\soak-r1-recovery-completion.ps1 -AnalyzeOnly -Stamp 20260714-180000

param(
    [switch]$ClearOnly,
    [switch]$CollectOnly,
    [switch]$AnalyzeOnly,
    [string]$Stamp = "",
    [string]$Prefix = "soak-r1",
    [string]$RemotePeer = "M02",
    [string]$HostModule = "M01"
)

$devices = @{
    M01 = "HTUBB21B09220661"
    M02 = "2d73067a"
    M03 = "MDX0220416001963"
}

$probeFilter = @(
    "RECOVERY_",
    "RECOVERY_CONTROL_PLANE_",
    "ICE_",
    "REATTACH",
    "OBLIGATION",
    "FAILED_MEDIA_RECOVERY",
    "ATTEMPT_TIMEOUT",
    "ROUTE_"
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

function Format-DurationSec([double]$Ms) {
    if ($null -eq $Ms) { return "n/a" }
    return "{0:N1}s" -f ($Ms / 1000.0)
}

function Percentile([double[]]$Sorted, [double]$P) {
    if ($Sorted.Count -eq 0) { return $null }
    $idx = [math]::Floor(($Sorted.Count - 1) * ($P / 100.0))
    if ($idx -lt 0) { $idx = 0 }
    return $Sorted[$idx]
}

function Resolve-Stamp {
    param([string]$Requested)
    if ($Requested) { return $Requested }
    $latest = Get-ChildItem -Path $logsDir -Filter "$Prefix-$HostModule-*.log" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if (-not $latest) {
        throw "No $Prefix-$HostModule-*.log in $logsDir; pass -Stamp"
    }
    if ($latest.Name -match "$Prefix-$HostModule-(\d{8}-\d{6})\.log") {
        return $Matches[1]
    }
    throw "Cannot parse stamp from $($latest.Name)"
}

function Test-IceDisconnected([string]$Line, [string]$Peer) {
    return ($Line -match "ICE $Peer state=DISCONNECTED") -or
        ($Line -match "Conference peer ICE DISCONNECTED for $Peer")
}

function Test-IceConnected([string]$Line, [string]$Peer) {
    return ($Line -match "ICE_CONNECTED.*peer=$Peer") -or
        ($Line -match "ICE $Peer state=CONNECTED")
}

function Get-RecoveryEpisodes {
    param(
        [string[]]$Lines,
        [string]$Peer
    )
    $episodes = [System.Collections.Generic.List[object]]::new()
    $anchor = $null
    $sessionId = $null

    foreach ($line in $Lines) {
        $ts = Parse-LogTimestamp $line
        if (-not $ts) { continue }

        if ($line -match 'RECOVERY_ATTEMPT_OPENED session=([^\s]+) remote=' + [regex]::Escape($Peer)) {
            if (-not $sessionId) { $sessionId = $Matches[1] }
        }

        if (Test-IceDisconnected $line $Peer) {
            if ($null -ne $anchor) {
                $episodes.Add([PSCustomObject]@{
                        SessionId = $sessionId
                        Anchor = $anchor
                        End = $ts
                        Lines = $Lines
                    })
            }
            $anchor = $ts
            $sessionId = $null
        }
    }

    if ($null -ne $anchor) {
        $episodes.Add([PSCustomObject]@{
                SessionId = $sessionId
                Anchor = $anchor
                End = $null
                Lines = $Lines
            })
    }

    if ($episodes.Count -eq 0) { return @() }

    # Keep only the last episode (typical single WiFi-off soak).
    return @($episodes[-1])
}

function Measure-Episode {
    param(
        [string[]]$Lines,
        [datetime]$Anchor,
        [string]$Peer,
        [string]$SessionHint
    )
    $mediaMs = $null
    $completeMs = $null
    $recoveredAt = $null
    $sessionId = $SessionHint
    $attempts = 0
    $seenAttempts = @{}
    $failedAttempts = @{}

    foreach ($line in $Lines) {
        $ts = Parse-LogTimestamp $line
        if (-not $ts -or $ts -lt $Anchor) { continue }

        if ($line -match 'RECOVERY_ATTEMPT_OPENED session=([^\s]+) remote=' + [regex]::Escape($Peer)) {
            $sessionId = $Matches[1]
            $aid = $null
            if ($line -match 'attemptId=(\d+)') { $aid = $Matches[1] }
            elseif ($line -match 'newAttempt=(\d+)') { $aid = $Matches[1] }
            if ($aid -and -not $seenAttempts.ContainsKey($aid)) {
                $seenAttempts[$aid] = $true
                $attempts++
            } elseif (-not $aid) {
                $attempts++
            }
        }

        if ($line -match "FAILED_MEDIA_RECOVERY session=[^\s]+ remote=$Peer attempt=(\d+)") {
            $failedAttempts[$Matches[1]] = $true
        }
        if ($line -match "RECOVERY_FINAL_EVALUATION session=[^\s]+ edge=$Peer attempt=(\d+) reason=ATTEMPT_TIMEOUT") {
            $failedAttempts[$Matches[1]] = $true
        }

        if ($null -eq $mediaMs -and (Test-IceConnected $line $Peer)) {
            $mediaMs = ($ts - $Anchor).TotalMilliseconds
        }

        if ($line -match "RECOVERY_EDGE_RECOVERED session=([^\s]+) remote=$Peer") {
            if (-not $sessionId) { $sessionId = $Matches[1] }
            if ($null -eq $completeMs) {
                $completeMs = ($ts - $Anchor).TotalMilliseconds
                $recoveredAt = $ts
            }
        }
    }

    $terminalFailures = $failedAttempts.Count
    $timeoutRate = if ($attempts -gt 0) { 100.0 * $terminalFailures / $attempts } else { $null }

    return [PSCustomObject]@{
        SessionId = $sessionId
        Anchor = $Anchor
        RecoveredAt = $recoveredAt
        TRecoverMediaMs = $mediaMs
        TRecoverCompleteMs = $completeMs
        AttemptCount = $attempts
        FailedAttemptCount = $terminalFailures
        TimeoutRate = $timeoutRate
    }
}

function Build-RecoverySummary {
    param(
        [object[]]$Episodes,
        [string]$StampValue,
        [string]$Peer
    )

    $media = [System.Collections.Generic.List[double]]::new()
    $complete = [System.Collections.Generic.List[double]]::new()
    $attempts = [System.Collections.Generic.List[int]]::new()
    $totalAttempts = 0
    $totalFailures = 0
    $worst = $null

    foreach ($ep in $Episodes) {
        if ($null -ne $ep.TRecoverMediaMs -and $ep.TRecoverMediaMs -ge 0) {
            $media.Add($ep.TRecoverMediaMs)
        }
        if ($null -ne $ep.TRecoverCompleteMs -and $ep.TRecoverCompleteMs -ge 0) {
            $complete.Add($ep.TRecoverCompleteMs)
        }
        $attempts.Add($ep.AttemptCount)
        $totalAttempts += $ep.AttemptCount
        $totalFailures += $ep.FailedAttemptCount

        if ($null -eq $worst -or $ep.TRecoverCompleteMs -gt $worst.TRecoverCompleteMs) {
            $worst = $ep
        }
    }

    $mediaSorted = @($media | Sort-Object)
    $completeSorted = @($complete | Sort-Object)
    $attemptSorted = @($attempts | Sort-Object)

    $avgAttempts = if ($attempts.Count -gt 0) {
        [math]::Round(($attempts | Measure-Object -Average).Average, 1)
    } else { $null }

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("Recovery Summary (Phase R1)")
    $lines.Add("")
    $lines.Add("Stamp: $StampValue")
    $lines.Add("Host: $HostModule  Remote peer: $Peer")
    $lines.Add("Episodes: $($Episodes.Count)")
    $lines.Add("")

    if ($Episodes.Count -gt 0 -and $Episodes[0].SessionId) {
        $lines.Add("Session: $($Episodes[0].SessionId)")
        $lines.Add("")
    }

    $lines.Add("T_recover_media:")
    if ($mediaSorted.Count -gt 0) {
        $lines.Add("  P50: $(Format-DurationSec (Percentile $mediaSorted 50))")
        $lines.Add("  P95: $(Format-DurationSec (Percentile $mediaSorted 95))")
        $lines.Add("  target: P50<5s P95<10s")
    } else {
        $lines.Add("  n/a (no post-disconnect ICE CONNECTED for $Peer)")
    }
    $lines.Add("")

    $lines.Add("T_recover_complete:")
    if ($completeSorted.Count -gt 0) {
        $lines.Add("  P50: $(Format-DurationSec (Percentile $completeSorted 50))")
        $lines.Add("  P95: $(Format-DurationSec (Percentile $completeSorted 95))")
        $lines.Add("  target: P50<10s P95<20s")
    } else {
        $lines.Add("  n/a (no RECOVERY_EDGE_RECOVERED for $Peer)")
    }
    $lines.Add("")

    $lines.Add("Attempts:")
    if ($attemptSorted.Count -gt 0) {
        $lines.Add("  avg: $avgAttempts")
        $lines.Add("  p95: $(Percentile $attemptSorted 95)")
        $lines.Add("  target: P50<=2 P95<=4 (informational)")
    } else {
        $lines.Add("  n/a")
    }
    $lines.Add("")

    $lines.Add("Timeout:")
    if ($totalAttempts -gt 0) {
        $rate = [math]::Round(100.0 * $totalFailures / $totalAttempts, 1)
        $lines.Add("  $totalFailures / $totalAttempts")
        $lines.Add("  rate: ${rate}%")
    } else {
        $lines.Add("  n/a")
    }
    $lines.Add("")

    if ($worst) {
        $lines.Add("Worst recovery:")
        $lines.Add("")
        $lines.Add("  $Peer")
        $lines.Add("  disconnect: $($worst.Anchor.ToString('HH:mm:ss'))")
        if ($worst.RecoveredAt) {
            $lines.Add("  recovered: $($worst.RecoveredAt.ToString('HH:mm:ss'))")
        } else {
            $lines.Add("  recovered: n/a")
        }
        $lines.Add("  attempts: $($worst.AttemptCount)")
        $lines.Add("  T_recover_media: $(Format-DurationSec $worst.TRecoverMediaMs)")
        $lines.Add("  T_recover_complete: $(Format-DurationSec $worst.TRecoverCompleteMs)")
    }

    return ($lines -join "`n")
}

if ($AnalyzeOnly) {
    $stampValue = Resolve-Stamp $Stamp
    $hostLog = Join-Path $logsDir "$Prefix-$HostModule-$stampValue.log"
    if (-not (Test-Path $hostLog)) {
        Write-Error "Host log not found: $hostLog"
        exit 1
    }

    $lines = Get-Content $hostLog -ErrorAction Stop
    $episodeAnchors = Get-RecoveryEpisodes -Lines $lines -Peer $RemotePeer
    $measured = @()
    foreach ($ep in $episodeAnchors) {
        $measured += Measure-Episode -Lines $lines -Anchor $ep.Anchor -Peer $RemotePeer -SessionHint $ep.SessionId
    }

    $summary = Build-RecoverySummary -Episodes $measured -StampValue $stampValue -Peer $RemotePeer
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
    Write-Host "Cleared. Repro protocol (Phase R1):"
    Write-Host "  1. M01 host 三方会，M02/M03 入会，ACTIVE"
    Write-Host "  2. M02 关 WiFi 20s"
    Write-Host "  3. M02 开 WiFi"
    Write-Host "  4. 等 RECOVERED (~60s)，不要操作 UI"
    Write-Host "  5. Run: .\scripts\soak-r1-recovery-completion.ps1 -CollectOnly -Stamp $Stamp"
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

& $PSScriptRoot\soak-r1-recovery-completion.ps1 -AnalyzeOnly -Stamp $Stamp -Prefix $Prefix -RemotePeer $RemotePeer -HostModule $HostModule
