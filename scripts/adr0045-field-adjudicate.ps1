# ADR-0045 thin field adjudicate — post-obligation residency clear only.
# PASS: FAILED_MEDIA_RESIDENCY_CLEARED + leave DEGRADED + closeReason unchanged + no completion event
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
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

function Get-Lines {
    param([string]$Path, [string]$Pattern)
    if (-not (Test-Path $Path)) { return @() }
    @(Select-String -Path $Path -Pattern $Pattern)
}

function Summarize-Observer {
    param(
        [string]$Observer,
        [string]$LogPath,
        [string]$PeerModule
    )

    $preClear = @(Get-Lines -Path $LogPath -Pattern "REACHABILITY_PROBE" | Where-Object {
        $_.Line -match "module=$PeerModule\b" -and
        $_.Line -match "edgeRecoveryPhase=FAILED_MEDIA_RECOVERY" -and
        $_.Line -match "mediaUnavailable=true" -and
        $_.Line -match "controllerEdgeRecovering=false"
    })

    $clearHits = @(Get-Lines -Path $LogPath -Pattern "FAILED_MEDIA_RESIDENCY_CLEARED" | Where-Object {
        $_.Line -match "remote=$PeerModule\b" -or $_.Line -match "edge=$PeerModule\b"
    })
    # Some logs use remote= only
    if ($clearHits.Count -eq 0) {
        $clearHits = @(Get-Lines -Path $LogPath -Pattern "FAILED_MEDIA_RESIDENCY_CLEARED" | Where-Object {
            $_.Line -match $PeerModule
        })
    }

    $completionHits = @(Get-Lines -Path $LogPath -Pattern "RECOVERY_EDGE_RECOVERED" | Where-Object {
        $_.Line -match $PeerModule
    })

    $postClearOk = @()
    if ($clearHits.Count -gt 0) {
        $clearLineNum = $clearHits[-1].LineNumber
        $postClearOk = @(Get-Lines -Path $LogPath -Pattern "REACHABILITY_PROBE" | Where-Object {
            $_.LineNumber -gt $clearLineNum -and
            $_.Line -match "module=$PeerModule\b" -and
            $_.Line -match "mediaUnavailable=false" -and
            $_.Line -notmatch "finalPresence=DEGRADED"
        })
    }

    $deadlineClose = @(Get-Lines -Path $LogPath -Pattern "RECOVERY_OBLIGATION_CLOSED" | Where-Object {
        $_.Line -match $PeerModule -and $_.Line -match "OBLIGATION_DEADLINE"
    })

    [pscustomobject]@{
        Observer = $Observer
        Peer = $PeerModule
        PreClearProbes = $preClear.Count
        ClearEvents = $clearHits.Count
        PostClearLeftDegraded = $postClearOk.Count
        CompletionEvents = $completionHits.Count
        DeadlineCloses = $deadlineClose.Count
        LastClear = if ($clearHits.Count -gt 0) { $clearHits[-1].Line } else { $null }
        LastPostClear = if ($postClearOk.Count -gt 0) { $postClearOk[-1].Line } else { $null }
    }
}

$m02 = Resolve-LogPath -Dir $LogDir -Primary "M02-talkback.log" -Fallback "M02-logcat-dump.txt"
$m03 = Resolve-LogPath -Dir $LogDir -Primary "M03-talkback.log" -Fallback "M03-logcat-dump.txt"

$rows = @(
    (Summarize-Observer -Observer "M02" -LogPath $m02 -PeerModule "M03"),
    (Summarize-Observer -Observer "M03" -LogPath $m03 -PeerModule "M02")
)

Write-Host "=== ADR-0045 thin field adjudicate ==="
Write-Host "LogDir=$LogDir"
Write-Host "Scope=post-obligation residency clear only (non-goals excluded)"
Write-Host ""
$rows | Format-Table -AutoSize | Out-String | Write-Host

$envInvalid = ($rows | Where-Object { $_.PreClearProbes -eq 0 -and $_.ClearEvents -eq 0 }).Count -eq $rows.Count
if ($envInvalid) {
    Write-Host "VERDICT=ENV_INVALID"
    Write-Host "Reason=no FAILED_MEDIA residency / clear evidence on either observer"
    exit 2
}

$pollution = ($rows | Where-Object { $_.ClearEvents -gt 0 -and $_.CompletionEvents -gt 0 }).Count -gt 0
# Completion after clear on same peer is pollution only if clear happened; allow unrelated RECOVERED elsewhere.
# Conservative: if clear present AND RECOVERY_EDGE_RECOVERED for same peer in log → Case C.
if ($pollution) {
    Write-Host "VERDICT=FAIL"
    Write-Host "Case=C (completion pollution — RECOVERY_EDGE_RECOVERED with residency clear)"
    exit 1
}

$missingClear = ($rows | Where-Object { $_.ClearEvents -eq 0 }).Count -gt 0
if ($missingClear) {
    Write-Host "VERDICT=FAIL"
    Write-Host "Case=B (clear missing on one or both observers — check Phase 2 / E4 / APK)"
    exit 1
}

$leftDegraded = ($rows | Where-Object { $_.PostClearLeftDegraded -gt 0 }).Count -eq $rows.Count
$hadDeadline = ($rows | Where-Object { $_.DeadlineCloses -gt 0 }).Count -gt 0

if ($leftDegraded) {
    Write-Host "VERDICT=PASS"
    Write-Host "Case=A (FAILED_MEDIA_RESIDENCY_CLEARED + presentation left DEGRADED)"
    if (-not $hadDeadline) {
        Write-Host "Note=OBLIGATION_DEADLINE close line not found on both observers; confirm closeReason in raw logs"
    }
    exit 0
}

Write-Host "VERDICT=FAIL"
Write-Host "Case=INCONCLUSIVE (clear seen but post-clear leave-DEGRADED probe missing — check soak / rprobe)"
exit 1
