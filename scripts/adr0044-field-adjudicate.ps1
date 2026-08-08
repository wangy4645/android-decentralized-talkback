# ADR-0044 thin field adjudicate — presentation only.
# Qualifying window: FAILED_MEDIA_RECOVERY + mediaUnavailable=true + controllerEdgeRecovering=false
# Expect: finalPresence=DEGRADED (not RECONNECTING)
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)
$ErrorActionPreference = "Stop"

function Get-ProbeHits {
    param([string]$Path, [string]$PeerModule)
    if (-not (Test-Path $Path)) { return @() }
    Select-String -Path $Path -Pattern "REACHABILITY_PROBE" |
        Where-Object {
            $_.Line -match "module=$PeerModule\b" -and
            $_.Line -match "edgeRecoveryPhase=FAILED_MEDIA_RECOVERY" -and
            $_.Line -match "mediaUnavailable=true" -and
            $_.Line -match "controllerEdgeRecovering=false"
        }
}

function Summarize-Observer {
    param(
        [string]$Observer,
        [string]$LogPath,
        [string]$PeerModule
    )
    $hits = @(Get-ProbeHits -Path $LogPath -PeerModule $PeerModule)
    $degraded = @($hits | Where-Object { $_.Line -match "finalPresence=DEGRADED" })
    $reconnecting = @($hits | Where-Object { $_.Line -match "finalPresence=RECONNECTING" })
    [pscustomobject]@{
        Observer = $Observer
        Peer = $PeerModule
        QualifyingProbes = $hits.Count
        Degraded = $degraded.Count
        Reconnecting = $reconnecting.Count
        LastQualifying = if ($hits.Count -gt 0) { $hits[-1].Line } else { $null }
    }
}

$m02 = Join-Path $LogDir "M02-talkback.log"
$m03 = Join-Path $LogDir "M03-talkback.log"
# Fallbacks used by some dump workflows
if (-not (Test-Path $m02)) { $m02 = Join-Path $LogDir "M02-logcat-dump.txt" }
if (-not (Test-Path $m03)) { $m03 = Join-Path $LogDir "M03-logcat-dump.txt" }

$rows = @(
    (Summarize-Observer -Observer "M02" -LogPath $m02 -PeerModule "M03"),
    (Summarize-Observer -Observer "M03" -LogPath $m03 -PeerModule "M02")
)

Write-Host "=== ADR-0044 thin field adjudicate ==="
Write-Host "LogDir=$LogDir"
Write-Host "Scope=presentation only (non-goals excluded)"
Write-Host ""
$rows | Format-Table -AutoSize | Out-String | Write-Host

$envInvalid = ($rows | Where-Object { $_.QualifyingProbes -eq 0 }).Count -gt 0
$caseB = ($rows | Where-Object { $_.Reconnecting -gt 0 -and $_.Degraded -eq 0 }).Count -gt 0
# Mixed: had RECONNECTING in qualifying window after we expect terminal — still Case B if last is RECONNECTING
$mixedBad = $false
foreach ($r in $rows) {
    if ($r.QualifyingProbes -eq 0) { continue }
    if ($r.LastQualifying -match "finalPresence=RECONNECTING") { $mixedBad = $true }
}

if ($envInvalid) {
    Write-Host "VERDICT=ENV_INVALID"
    Write-Host "Reason=no qualifying FAILED_MEDIA_RECOVERY + mediaUnavailable + !recovering window on one or both observers"
    exit 2
}

$allDegradedLast = ($rows | Where-Object {
    $_.LastQualifying -match "finalPresence=DEGRADED"
}).Count -eq $rows.Count

if ($allDegradedLast -and -not $mixedBad) {
    Write-Host "VERDICT=PASS"
    Write-Host "Case=A (terminal residency → DEGRADED; no active recovery → no RECONNECTING)"
    exit 0
}

if ($caseB -or $mixedBad) {
    Write-Host "VERDICT=FAIL"
    Write-Host "Case=B (ADR-0044 projection regression — not ADR-0043 / not recovery bug)"
    exit 1
}

Write-Host "VERDICT=FAIL"
Write-Host "Case=C_or_INCONCLUSIVE (check authority / finalPresence mapping)"
exit 1
