# #180 late-peer field adjudicate
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"

function Resolve-LogPath {
    param([string]$Dir, [string]$Module)
    foreach ($name in @("$Module-logcat-dump.txt", "$Module-talkback.log")) {
        $p = Join-Path $Dir $name
        if (Test-Path $p) { return $p }
    }
    return Join-Path $Dir "$Module-talkback.log"
}

function Count-Hits {
    param([string]$Path, [string]$Pattern)
    if (-not (Test-Path $Path)) { return 0 }
    @(Select-String -Path $Path -Pattern $Pattern).Count
}

function Count-After {
    param([string]$Path, [string]$Pattern, [string]$TimePrefix)
    if (-not (Test-Path $Path)) { return 0 }
    @(Select-String -Path $Path -Pattern $Pattern | Where-Object { $_.Line -match $TimePrefix }).Count
}

$m01 = Resolve-LogPath -Dir $LogDir -Module "M01"
$m02 = Resolve-LogPath -Dir $LogDir -Module "M02"
$m03 = Resolve-LogPath -Dir $LogDir -Module "M03"

# Post M03 join window — use t_join from meta if present, else all logs
$tJoinLine = Get-Content (Join-Path $LogDir "RUN_META.txt") -ErrorAction SilentlyContinue |
    Where-Object { $_ -match "^t_join_m03=" } | Select-Object -First 1
$postJoinPrefix = "."
if ($tJoinLine -match "t_join_m03=(.+)") {
    $tJoin = [datetimeoffset]::Parse($Matches[1].Trim())
    $postJoinPrefix = $tJoin.ToString("HH:mm")
    Write-Host "Post-join filter: lines after ~$postJoinPrefix"
}

Write-Host "LogDir=$LogDir"
Write-Host ""

Write-Host "=== FORBIDDEN (all, post-join emphasis on M01) ==="
$forbidden = [ordered]@{
    "GROUP_JOIN (all)" = (Count-Hits $m01 "GROUP_JOIN") + (Count-Hits $m02 "GROUP_JOIN") + (Count-Hits $m03 "GROUP_JOIN")
    "QUEUED_NO_SESSION" = (Count-Hits $m01 "QUEUED_NO_SESSION") + (Count-Hits $m02 "QUEUED_NO_SESSION") + (Count-Hits $m03 "QUEUED_NO_SESSION")
    "Group reconnect join" = (Count-Hits $m01 "Group reconnect join offered") + (Count-Hits $m02 "Group reconnect join offered")
    "BOOTSTRAP_INTENT post-M03 (M01)" = (Count-After $m01 "GROUP_BOOTSTRAP_INTENT_CREATED.*peer=M03" $postJoinPrefix)
}
$forbPass = $true
foreach ($k in $forbidden.Keys) {
    $n = $forbidden[$k]
    $ok = if ($k -eq "BOOTSTRAP_INTENT post-M03 (M01)") { $n -eq 0 } else { $n -eq 0 }
    if (-not $ok) { $forbPass = $false }
    Write-Host ("  {0,-40} {1,4} {2}" -f $k, $n, $(if ($ok) { "OK" } else { "HIT" }))
}

Write-Host ""
Write-Host "=== #180 PAIRWISE ACTIVATION (M01, post-join) ==="
$pairwise = [ordered]@{
    "ACTIVATION_EVALUATED M03" = (Count-After $m01 "PAIRWISE_MESH_ADMISSION_ACTIVATION_EVALUATED.*peer=M03" $postJoinPrefix)
    "ACTIVATION_ISSUE_INVITE" = (Count-After $m01 "decision=ISSUE_INVITE.*peer=M03|ACTIVATION_EVALUATED.*peer=M03.*ISSUE_INVITE" $postJoinPrefix)
    "INVITE_ISSUED M03" = (Count-After $m01 "PAIRWISE_MESH_ADMISSION_INVITE_ISSUED.*peer=M03" $postJoinPrefix)
    "DEFERRED snapshot (M03)" = (Count-After $m03 "PAIRWISE_MESH_ADMISSION_DEFERRED.*peer=M01" $postJoinPrefix)
    "Group invite sent M03" = (Count-After $m01 "Group invite sent ->.*M03" $postJoinPrefix)
}
$pairPass = $true
foreach ($k in $pairwise.Keys) {
    $n = $pairwise[$k]
    $ok = $n -ge 1
    if ($k -match "DEFERRED") { $ok = $true }
    if ($k -eq "ACTIVATION_ISSUE_INVITE") {
        $ok = $ok -or ($pairwise["Group invite sent M03"] -ge 1)
    }
    if ($k -eq "INVITE_ISSUED M03") {
        $ok = $ok -or ($pairwise["Group invite sent M03"] -ge 1)
    }
    if (-not $ok -and $k -notin @("DEFERRED snapshot (M03)")) { $pairPass = $false }
    Write-Host ("  {0,-40} {1,4} {2}" -f $k, $n, $(if ($ok) { "OK" } else { "MISS" }))
}

Write-Host ""
Write-Host "=== M03 ACCEPT PATH (post-join) ==="
$accept = [ordered]@{
    "snapshotApplied" = (Count-After $m03 "snapshotApplied" $postJoinPrefix)
    "reconnect accept M01" = (Count-After $m03 "invite reconnect accepted from M01" $postJoinPrefix)
    "GROUP_ACCEPT" = (Count-After $m03 "GROUP_ACCEPT" $postJoinPrefix)
    "M03 offer to M01 (bad)" = (Count-After $m03 "Group invite sent ->.*M01" $postJoinPrefix)
}
$acceptPass = $true
foreach ($k in $accept.Keys) {
    $n = $accept[$k]
    $ok = if ($k -eq "M03 offer to M01 (bad)") { $n -eq 0 } else { $n -ge 1 }
    if (-not $ok) { $acceptPass = $false }
    Write-Host ("  {0,-40} {1,4} {2}" -f $k, $n, $(if ($ok) { "OK" } else { "MISS" }))
}

Write-Host ""
Write-Host "=== FINAL STATE (soft, post-join tail) ==="
$tail = "21:0"
if ($postJoinPrefix -ne ".") { $tail = $postJoinPrefix.Substring(0, [Math]::Min(4, $postJoinPrefix.Length)) }
foreach ($pair in @(@("M01", $m01), @("M02", $m02), @("M03", $m03))) {
    $n = Count-After $pair[1] "meshIceConnectedPeers=.*CONNECTED|transmitReady=true.*OPERATIONAL|mappedChannelReadiness=READY" $tail
    Write-Host ("  {0,-6} readiness hits (tail) {1,4}" -f $pair[0], $n)
}

Write-Host ""
Write-Host "=== VERDICT ==="
if ($forbPass -and $pairPass -and $acceptPass -and $pairwise["Group invite sent M03"] -ge 1 -and $accept["reconnect accept M01"] -ge 1) {
    if ($pairwise["INVITE_ISSUED M03"] -ge 1) {
        $verdict = "PASS_180"
        $note = "Late peer -> pairwise activation -> SDP -> ACCEPT -> CONNECTED"
    } else {
        $verdict = "PASS_PARTIAL"
        $note = "SDP path OK but PAIRWISE_MESH_ADMISSION_INVITE_ISSUED marker missing (bootstrap overlap?)"
    }
} elseif ($accept["snapshotApplied"] -ge 1 -and $accept["reconnect accept M01"] -eq 0) {
    $verdict = "FAIL_180B"
    $note = "snapshotApplied without accept — seam still swallows admission"
} elseif ($pairwise["ACTIVATION_EVALUATED M03"] -eq 0 -and $pairwise["Group invite sent M03"] -eq 0) {
    $verdict = "FAIL_ACTIVATION"
    $note = "Obligation/trigger miss — edge ready before obligation?"
} else {
    $verdict = "FAIL"
    $note = "Incomplete late-peer pairwise convergence"
}
Write-Host "VERDICT=$verdict"
Write-Host "NOTE=$note"
exit $(if ($verdict -match "^PASS") { 0 } else { 1 })
