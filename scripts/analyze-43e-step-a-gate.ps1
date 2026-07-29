param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
$LogDir = Resolve-Path $LogDir
$files = Get-ChildItem $LogDir -Filter "*-talkback.log" | Sort-Object Name
if (-not $files) { throw "No *-talkback.log under $LogDir" }

function Get-Ts([string]$Line) {
    if ($Line -match '^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})') {
        try { return [datetime]::ParseExact($Matches[1], "MM-dd HH:mm:ss.fff", $null) } catch { return $null }
    }
    return $null
}

$events = New-Object System.Collections.Generic.List[object]
foreach ($f in $files) {
    $mod = if ($f.Name -match '^(M\d+)-') { $Matches[1] } else { $f.BaseName }
    Get-Content $f.FullName -Encoding UTF8 | ForEach-Object {
        $line = $_
        $kind = $null
        if ($line -match 'op=SETTLING state=ANSWERER_SETTLED') { $kind = "ANSWERER_SETTLED" }
        elseif ($line -match 'op=SETTLING state=NONE reason=ANSWERER_TRANSACTION_COMMITTED') { $kind = "ANSWERER_TRANSACTION_COMMITTED" }
        elseif ($line -match 'NEGOTIATION_RELEASED ') { $kind = "NEGOTIATION_RELEASED" }
        elseif ($line -match 'RECOVERY_MEDIA_ACTION_DEFERRED' -and $line -match 'NEGOTIATION_SETTLING') { $kind = "ICE_RESTART_DEFERRED" }
        elseif ($line -match 'RECOVERY_WAKEUP_FIRED' -and $line -match 'NEGOTIATION_RELEASED') { $kind = "DRAIN_PENDING_ICE_RESTART" }
        elseif ($line -match 'RECOVERY_ICE_RESTART_DISPATCHED') { $kind = "ICE_RESTART_DISPATCHED" }
        elseif ($line -match 'RECOVERY_WATCHDOG_STARTED') { $kind = "WATCHDOG_STARTED" }
        elseif ($line -match 'ICE_RESTART_REQUESTED') { $kind = "ICE_RESTART_REQUESTED" }
        elseif ($line -match 'op=ROLE role=OFFERER reason=createOffer' -and $line -match 'iceRestart=true') { $kind = "CREATE_OFFER_RESTART" }
        elseif ($line -match 'RECOVERY_ICE_RESTART_INTENT_EXPIRED' -and $line -match 'STALE_DISCARD') { $kind = "STALE_DISCARD" }
        elseif ($line -match 'RECOVERY_TRANSITION' -and $line -match 'new=ICE_RESTARTING') { $kind = "PHASE_ICE_RESTARTING" }
        if ($null -eq $kind) { return }
        $remote = $null
        if ($line -match 'remote=([A-Za-z0-9_-]+)') { $remote = $Matches[1] }
        elseif ($line -match 'edge=([A-Za-z0-9_-]+)') { $remote = $Matches[1] }
        $events.Add([pscustomobject]@{ Ts = Get-Ts $line; Module = $mod; Remote = $remote; Kind = $kind; Line = $line }) | Out-Null
    }
}

$ordered = $events | Sort-Object @{ Expression = { if ($_.Ts) { $_.Ts } else { [datetime]::MinValue } } }, Module
$verdictPath = Join-Path $LogDir "43e-step-a-verdict.txt"
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("4.3-E Step A — Negotiation Stabilization Gate (execution chain only)")
[void]$sb.AppendLine("logdir=$LogDir")
[void]$sb.AppendLine("events=$($ordered.Count)")
[void]$sb.AppendLine("")

$deferred = @($ordered | Where-Object { $_.Kind -eq "ICE_RESTART_DEFERRED" })
[void]$sb.AppendLine("=== DEFERRED hits: $($deferred.Count) ===")

$passImmediateOfferBlocked = $true
$passDeferredSeen = $deferred.Count -gt 0
$passReleaseWake = $false
$passDrainAfterRelease = $false
$passDispatchAfterDrain = $false
$passWatchdogAfterDispatch = $false
$f1ForeverDeferred = $false
$f2StaleAfterRelease = $false

foreach ($d in $deferred) {
    $edgeKey = "$($d.Module)->$($d.Remote)"
    $t0 = $d.Ts
    $window = @($ordered | Where-Object {
        $_.Module -eq $d.Module -and
        ($null -eq $d.Remote -or $_.Remote -eq $d.Remote -or $_.Kind -in @("ANSWERER_SETTLED","ANSWERER_TRANSACTION_COMMITTED","NEGOTIATION_RELEASED","CREATE_OFFER_RESTART","ICE_RESTART_REQUESTED")) -and
        ($null -eq $t0 -or ($null -ne $_.Ts -and $_.Ts -ge $t0.AddSeconds(-30) -and $_.Ts -le $t0.AddSeconds(30)))
    })
    $rel = @($window | Where-Object { $_.Kind -eq "NEGOTIATION_RELEASED" -or $_.Kind -eq "ANSWERER_TRANSACTION_COMMITTED" } | Select-Object -First 1)
    $drain = @($window | Where-Object { $_.Kind -eq "DRAIN_PENDING_ICE_RESTART" } | Select-Object -First 1)
    $disp = @($window | Where-Object { $_.Kind -eq "ICE_RESTART_DISPATCHED" } | Select-Object -First 1)
    $wd = @($window | Where-Object { $_.Kind -eq "WATCHDOG_STARTED" } | Select-Object -First 1)
    $stale = @($window | Where-Object { $_.Kind -eq "STALE_DISCARD" } | Select-Object -First 1)
    $offerBeforeRelease = @($window | Where-Object {
        $_.Kind -eq "CREATE_OFFER_RESTART" -and $null -ne $t0 -and $null -ne $_.Ts -and $_.Ts -ge $t0 -and
        ($rel.Count -eq 0 -or ($null -ne $rel[0].Ts -and $_.Ts -lt $rel[0].Ts))
    })
    if ($offerBeforeRelease.Count -gt 0) { $passImmediateOfferBlocked = $false }

    $notes = @(); $chainOk = $true
    if ($rel.Count -eq 0) {
        $f1ForeverDeferred = $true; $chainOk = $false
        $notes += "F1: no RELEASED/COMMITTED after DEFERRED"
    } else {
        $passReleaseWake = $true
        if ($drain.Count -eq 0) { $chainOk = $false; $notes += "no DRAIN after release" }
        elseif ($null -ne $rel[0].Ts -and $null -ne $drain[0].Ts -and $drain[0].Ts -lt $rel[0].Ts) {
            $chainOk = $false; $notes += "FAIL order: DRAIN before RELEASED"
        } else { $passDrainAfterRelease = $true }

        if ($stale.Count -gt 0 -and $disp.Count -eq 0) {
            $f2StaleAfterRelease = $true; $chainOk = $false; $notes += "F2: STALE without DISPATCH"
        }
        if ($disp.Count -eq 0) { $chainOk = $false; $notes += "no DISPATCH after drain" }
        else {
            $passDispatchAfterDrain = $true
            if ($wd.Count -gt 0) {
                if ($null -ne $disp[0].Ts -and $null -ne $wd[0].Ts -and $wd[0].Ts -lt $disp[0].Ts) {
                    $chainOk = $false; $notes += "watchdog before DISPATCH"
                } else { $passWatchdogAfterDispatch = $true }
            } else { $notes += "WARN: no WATCHDOG in +/-30s" }
        }
    }
    $tsLabel = if ($t0) { $t0.ToString("HH:mm:ss.fff") } else { "?" }
    [void]$sb.AppendLine(("EDGE {0} deferred@{1} ok={2} {3}" -f $edgeKey, $tsLabel, $chainOk, ($notes -join "; ")))
}

[void]$sb.AppendLine("")
[void]$sb.AppendLine("=== Dual-defer observation (Step B note only) ===")
$dualNote = "NOT_EVALUATED_AS_FAIL"
$mPairs = @{}
foreach ($d in $deferred) {
    if ($d.Remote) {
        $pair = (@($d.Module, $d.Remote) | Sort-Object) -join "/"
        if (-not $mPairs.ContainsKey($pair)) { $mPairs[$pair] = New-Object 'System.Collections.Generic.HashSet[string]' }
        [void]$mPairs[$pair].Add($d.Module)
    }
}
foreach ($k in @($mPairs.Keys)) {
    [void]$sb.AppendLine("pair=$k deferredModules=$($mPairs[$k].Count)")
    if ($mPairs[$k].Count -ge 2) { $dualNote = "BOTH_SIDES_DEFERRED_OBSERVED" }
}
[void]$sb.AppendLine("dualDeferStatus=$dualNote")

[void]$sb.AppendLine("")
[void]$sb.AppendLine("=== PASS checklist (execution chain only) ===")
$allPass = $true
$checks = @(
    @{ Name = "no immediate createOffer after settle/defer"; Pass = ($passImmediateOfferBlocked -or (-not $passDeferredSeen)) },
    @{ Name = "deferred intent visible (NEGOTIATION_SETTLING)"; Pass = $passDeferredSeen },
    @{ Name = "release wakes (COMMITTED/RELEASED)"; Pass = ($passReleaseWake -or (-not $passDeferredSeen)) },
    @{ Name = "drain after release"; Pass = ($passDrainAfterRelease -or (-not $passDeferredSeen)) },
    @{ Name = "dispatch on original path"; Pass = ($passDispatchAfterDrain -or (-not $passDeferredSeen)) },
    @{ Name = "watchdog only after dispatch"; Pass = ($passWatchdogAfterDispatch -or (-not $passDeferredSeen)) }
)
foreach ($c in $checks) {
    if (-not $c.Pass) { $allPass = $false }
    [void]$sb.AppendLine("$(if ($c.Pass) {'PASS'} else {'FAIL'})  $($c.Name)")
}

[void]$sb.AppendLine("")
if (-not $passDeferredSeen) {
    [void]$sb.AppendLine("OVERALL=INCONCLUSIVE (no NEGOTIATION_SETTLING deferred hit)")
} elseif ($f1ForeverDeferred) {
    [void]$sb.AppendLine("OVERALL=FAIL F1 (forever DEFERRED / missing commit seam)")
} elseif ($f2StaleAfterRelease) {
    [void]$sb.AppendLine("OVERALL=FAIL F2 (DEFERRED then STALE without dispatch)")
} elseif ($allPass) {
    [void]$sb.AppendLine("OVERALL=PASS (gate changed negotiation timeline; NOT judging recovery ONLINE)")
} else {
    [void]$sb.AppendLine("OVERALL=FAIL (see checklist)")
}

[void]$sb.AppendLine("")
[void]$sb.AppendLine("=== Timeline excerpt (first 100 matched events) ===")
$ordered | Select-Object -First 100 | ForEach-Object {
    $ts = if ($_.Ts) { $_.Ts.ToString("HH:mm:ss.fff") } else { "??:??:??.???" }
    [void]$sb.AppendLine("$ts $($_.Module) $($_.Kind) remote=$($_.Remote)")
}

$text = $sb.ToString()
$enc = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($verdictPath, $text, $enc)
Write-Output $text
Write-Output ""
Write-Output "Wrote $verdictPath"