# ADR-0050 Directed Admission Validation — thin adjudicate.
# PASS focus: lease admit + dispatch + ICE/EDGE recovered on inbound edges to M02.
# Does NOT score UVCP / membership / completion-policy deltas.
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [string]$FlapTarget = "M02"
)

$ErrorActionPreference = "Stop"

function Resolve-LogPath {
    param([string]$Dir, [string]$Primary, [string]$Fallback)
    $p = Join-Path $Dir $Primary
    if (Test-Path $p) { return $p }
    $f = Join-Path $Dir $Fallback
    if (Test-Path $f) { return $f }
    return $p
}

function Get-Hits {
    param([string]$Path, [string]$Pattern)
    if (-not (Test-Path $Path)) { return @() }
    @(Select-String -Path $Path -Pattern $Pattern)
}

function Edge-Hits {
    param([string]$Path, [string]$Token, [string]$Remote)
    @(Get-Hits -Path $Path -Pattern $Token | Where-Object {
        $_.Line -match "remote=$Remote\b" -or $_.Line -match "edge=$Remote\b"
    })
}

function Summarize-Observer {
    param(
        [string]$Observer,
        [string]$LogPath,
        [string]$Remote
    )

    $blocked = @(Edge-Hits -Path $LogPath -Token "NEGOTIATION_NON_OWNER_BLOCKED" -Remote $Remote)
    $granted = @(Edge-Hits -Path $LogPath -Token "NEGOTIATION_LEASE_GRANTED" -Remote $Remote)
    $admitted = @(Edge-Hits -Path $LogPath -Token "NEGOTIATION_LEASE_ADMITTED" -Remote $Remote)
    $expired = @(Edge-Hits -Path $LogPath -Token "NEGOTIATION_LEASE_EXPIRED" -Remote $Remote)
    $dispatched = @(Edge-Hits -Path $LogPath -Token "RECOVERY_ICE_RESTART_DISPATCHED" -Remote $Remote)
    $recovered = @(Edge-Hits -Path $LogPath -Token "RECOVERY_EDGE_RECOVERED" -Remote $Remote)
    $bootstrap = @(Edge-Hits -Path $LogPath -Token "RECOVERY_NEGOTIATION_OWNER_BOOTSTRAP" -Remote $Remote)

    $ownerRemote = @($bootstrap | Where-Object { $_.Line -match "owner=$Remote\b" }).Count
    $ownerLocal = @($bootstrap | Where-Object { $_.Line -match "owner=LOCAL\b" }).Count

    $inv3Violation = $false
    if ($expired.Count -gt 0) {
        foreach ($ex in $expired) {
            $after = @(Get-Hits -Path $LogPath -Pattern "FAILED_MEDIA|enterFailedMedia|edgeRecoveryPhase=FAILED_MEDIA" | Where-Object {
                $_.LineNumber -gt $ex.LineNumber -and
                ($_.LineNumber - $ex.LineNumber) -le 5 -and
                ($_.Line -match $Remote)
            })
            if ($after.Count -gt 0) { $inv3Violation = $true; break }
        }
    }

    $passA = ($blocked.Count -eq 0)
    $passB = ($admitted.Count -ge 1)
    $passC = ($dispatched.Count -ge 1)
    $passD = ($recovered.Count -ge 1)
    $passE = ($ownerLocal -eq 0) -or ($ownerRemote -ge 1)
    # E soft: prefer seeing remote owner bootstrap; fail hard only if lease admit accompanied by owner=LOCAL rewrite in same log stream after admit.
    $ownerRewrite = $false
    if ($admitted.Count -gt 0) {
        $firstAdmit = $admitted[0].LineNumber
        $rewrite = @(Get-Hits -Path $LogPath -Pattern "RECOVERY_NEGOTIATION_OWNER_BOOTSTRAP" | Where-Object {
            $_.LineNumber -gt $firstAdmit -and
            ($_.Line -match "remote=$Remote\b" -or $_.Line -match "edge=$Remote\b") -and
            $_.Line -match "owner=LOCAL\b"
        })
        $ownerRewrite = ($rewrite.Count -gt 0)
    }
    $passE = -not $ownerRewrite

    $layer = "UNKNOWN"
    if (-not $passB -and -not $passA) { $layer = "admission_gate" }
    elseif ($passB -and -not $passC) { $layer = "admission_to_dispatch" }
    elseif ($passC -and -not $passD) { $layer = "restart_execution_or_peer_or_completion" }
    elseif ($inv3Violation) { $layer = "inv3_violation" }
    elseif ($passA -and $passB -and $passC -and $passD -and $passE) { $layer = "PASS" }
    else { $layer = "PARTIAL" }

    [pscustomobject]@{
        Observer              = $Observer
        Remote                = $Remote
        Blocked               = $blocked.Count
        LeaseGranted          = $granted.Count
        LeaseAdmitted         = $admitted.Count
        LeaseExpired          = $expired.Count
        IceRestartDispatched  = $dispatched.Count
        EdgeRecovered         = $recovered.Count
        OwnerBootstrapRemote  = $ownerRemote
        OwnerRewriteToLocal   = $ownerRewrite
        Inv3LeaseToFailedMedia = $inv3Violation
        PassA_NoBlocked       = $passA
        PassB_LeaseAdmitted   = $passB
        PassC_Dispatched      = $passC
        PassD_EdgeRecovered   = $passD
        PassE_NoOwnerRewrite  = $passE
        Layer                 = $layer
    }
}

$m01 = Resolve-LogPath $LogDir "M01-talkback.log" "M01.log"
$m02 = Resolve-LogPath $LogDir "M02-talkback.log" "M02.log"
$m03 = Resolve-LogPath $LogDir "M03-talkback.log" "M03.log"

$s01 = Summarize-Observer -Observer "M01" -LogPath $m01 -Remote $FlapTarget
$s03 = Summarize-Observer -Observer "M03" -LogPath $m03 -Remote $FlapTarget

# Dual-restart watch (coarse): HOST_RESTART + PARTICIPANT_REATTACH near flap target on any log
$dualWatch = @()
foreach ($pair in @(
        @{ N = "M01"; P = $m01 },
        @{ N = "M02"; P = $m02 },
        @{ N = "M03"; P = $m03 }
    )) {
    if (-not (Test-Path $pair.P)) { continue }
    $hostHits = @(Get-Hits -Path $pair.P -Pattern "MEDIA_ACTION_OWNER|assignMediaActionOwner|owner=HOST_RESTART" | Where-Object {
        $_.Line -match $FlapTarget
    })
    $partHits = @(Get-Hits -Path $pair.P -Pattern "PARTICIPANT_REATTACH" | Where-Object {
        $_.Line -match $FlapTarget
    })
    if ($hostHits.Count -gt 0 -and $partHits.Count -gt 0) {
        $dualWatch += "$($pair.N): HOST_RESTART_hits=$($hostHits.Count) PARTICIPANT_REATTACH_hits=$($partHits.Count)"
    }
}

$anyPass = ($s01.Layer -eq "PASS") -or ($s03.Layer -eq "PASS")
$bothPass = ($s01.Layer -eq "PASS") -and ($s03.Layer -eq "PASS")

Write-Host "=== ADR-0050 Directed Admission Adjudication ==="
Write-Host "LogDir=$LogDir FlapTarget=$FlapTarget"
Write-Host ""
$s01, $s03 | Format-List Observer, Remote, Blocked, LeaseGranted, LeaseAdmitted, LeaseExpired, IceRestartDispatched, EdgeRecovered, OwnerRewriteToLocal, Inv3LeaseToFailedMedia, PassA_NoBlocked, PassB_LeaseAdmitted, PassC_Dispatched, PassD_EdgeRecovered, PassE_NoOwnerRewrite, Layer

Write-Host "Dual-restart watch (coarse):"
if ($dualWatch.Count -eq 0) { Write-Host "  (none flagged)" } else { $dualWatch | ForEach-Object { Write-Host "  $_" } }

Write-Host ""
if ($bothPass) {
    Write-Host "VERDICT: PASS (M01 and M03)"
    exit 0
}
elseif ($anyPass) {
    Write-Host "VERDICT: PARTIAL_PASS (one observer PASS — review other)"
    exit 0
}
else {
    Write-Host "VERDICT: FAIL / CLASSIFY — see Layer columns"
    Write-Host "  admission_gate | admission_to_dispatch | restart_execution_or_peer_or_completion | inv3_violation"
    exit 1
}
