# #180 T9/C field adjudicate — pairwise obligation + M01→M03 SDP path
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

function Count-AllModules {
    param([hashtable]$Paths, [string]$Pattern)
    $n = 0
    foreach ($p in $Paths.Values) { $n += (Count-Hits $p $Pattern) }
    return $n
}

$m01 = Resolve-LogPath -Dir $LogDir -Module "M01"
$m02 = Resolve-LogPath -Dir $LogDir -Module "M02"
$m03 = Resolve-LogPath -Dir $LogDir -Module "M03"
$all = @{ M01 = $m01; M02 = $m02; M03 = $m03 }

Write-Host "LogDir=$LogDir"
Write-Host "M01=$m01"
Write-Host "M02=$m02"
Write-Host "M03=$m03"
Write-Host ""

# --- Hard Gate: forbidden paths (all devices) ---
Write-Host "=== FORBIDDEN (all devices) ==="
$forbidden = [ordered]@{
    "GROUP_JOIN" = (Count-AllModules $all "SignalType\.GROUP_JOIN|GROUP_JOIN ")
    "QUEUED_NO_SESSION" = (Count-AllModules $all "joinIngressDecision=QUEUED_NO_SESSION")
    "Group reconnect join offered" = (Count-AllModules $all "Group reconnect join offered")
    "Group mesh join offered" = (Count-AllModules $all "Group mesh join offered")
}
$forbPass = $true
foreach ($k in $forbidden.Keys) {
    $n = $forbidden[$k]
    $ok = $n -eq 0
    if (-not $ok) { $forbPass = $false }
    Write-Host ("  {0,-35} {1,4} {2}" -f $k, $n, $(if ($ok) { "OK" } else { "HIT" }))
}

Write-Host ""
Write-Host "=== OBLIGATION / ACTIVATION (M01) ==="
$obligation = [ordered]@{
    "PAIRWISE_MESH_ADMISSION_DEFERRED" = (Count-Hits $m01 "PAIRWISE_MESH_ADMISSION_DEFERRED")
    "ACTIVATION_EVALUATED" = (Count-Hits $m01 "PAIRWISE_MESH_ADMISSION_ACTIVATION_EVALUATED")
    "ACTIVATION_INVITE_ISSUED M03" = (Count-Hits $m01 "PAIRWISE_MESH_ADMISSION_INVITE_ISSUED.*peer=M03")
    "Group invite sent M03" = (Count-Hits $m01 "Group invite sent ->.*M03")
}
$oblPass = $true
foreach ($k in $obligation.Keys) {
    $n = $obligation[$k]
    $ok = $n -ge 1
    if ($k -eq "PAIRWISE_MESH_ADMISSION_DEFERRED") {
        $ok = $ok -or ($obligation["ACTIVATION_EVALUATED"] -ge 1)
    }
    if (-not $ok) { $oblPass = $false }
    Write-Host ("  {0,-35} {1,4} {2}" -f $k, $n, $(if ($ok) { "OK" } else { "MISS" }))
}

Write-Host ""
Write-Host "=== M01->M03 SDP PATH ==="
$sdp = [ordered]@{
    "M01 invite sent M03" = (Count-Hits $m01 "Group invite sent ->.*M03")
    "M03 reconnect accept M01" = (Count-Hits $m03 "invite reconnect accepted from M01|Group invite reconnect accepted from M01")
    "M03 GROUP_ACCEPT path" = (Count-Hits $m03 "GROUP_ACCEPT_HANDOFF.*M01|GROUP_ACCEPT")
    "M01 ICE CONNECTED M03" = (Count-Hits $m01 "M03.*ice=CONNECTED|ice=CONNECTED.*M03")
}
$sdpPass = $true
foreach ($k in $sdp.Keys) {
    $n = $sdp[$k]
    $ok = $n -ge 1
    if ($k -eq "M01 ICE CONNECTED M03") {
        $ok = $n -ge 1
        if (-not $ok) { Write-Host ("  {0,-35} {1,4} WARN" -f $k, $n); continue }
    }
    if (-not $ok -and $k -ne "M01 ICE CONNECTED M03") { $sdpPass = $false }
    Write-Host ("  {0,-35} {1,4} {2}" -f $k, $n, $(if ($ok) { "OK" } else { "MISS" }))
}

Write-Host ""
Write-Host "=== DOUBLE OFFER GUARD (M03 must not offer M01) ==="
$m03OfferM01 = (Count-Hits $m03 "Group invite sent ->.*M01")
$m03CreateOffer = (Count-Hits $m03 "createOffer")
$doubleOfferOk = ($m03OfferM01 -eq 0)
Write-Host ("  M03 Group invite sent -> M01       {0,4} {1}" -f $m03OfferM01, $(if ($doubleOfferOk) { "OK" } else { "HIT" }))
if ($m03OfferM01 -gt 0) { $sdpPass = $false }

Write-Host ""
Write-Host "=== OPERATIONAL (soft) ==="
$operational = [ordered]@{
    "M01 OPERATIONAL/transmitReady" = (Count-Hits $m01 "OPERATIONAL|transmitReady=true")
    "M02 OPERATIONAL/transmitReady" = (Count-Hits $m02 "OPERATIONAL|transmitReady=true")
    "M03 OPERATIONAL/transmitReady" = (Count-Hits $m03 "OPERATIONAL|transmitReady=true")
}
foreach ($k in $operational.Keys) {
    Write-Host ("  {0,-35} {1,4}" -f $k, $operational[$k])
}

Write-Host ""
Write-Host "=== INTERPRETATION ==="
if ($forbPass -and $oblPass -and $sdpPass) {
    $verdict = "PASS"
    $note = "#180 PASS — obligation drove M01->M03 SDP path"
} elseif ($forbPass -and $obligation["ACTIVATION_EVALUATED"] -ge 1 -and $sdp["M01 invite sent M03"] -eq 0) {
    $verdict = "PARTIAL"
    $note = "#180-C trigger miss — obligation present but no INVITE"
} elseif (-not $forbPass) {
    $verdict = "FAIL"
    $note = "Old bad path (GROUP_JOIN / snapshot seam leak)"
} elseif ($m03OfferM01 -gt 0) {
    $verdict = "FAIL"
    $note = "PN double-offer regression"
} else {
    $verdict = "FAIL"
    $note = "Incomplete M01->M03 SDP convergence"
}
Write-Host "VERDICT=$verdict"
Write-Host "NOTE=$note"
exit $(if ($verdict -eq "PASS") { 0 } else { 1 })
