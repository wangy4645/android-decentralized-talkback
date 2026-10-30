# E4 T9-A field adjudicate — M01 authority markers + forbidden JOIN loop
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

$m01 = Resolve-LogPath -Dir $LogDir -Module "M01"
$m02 = Resolve-LogPath -Dir $LogDir -Module "M02"

$required = [ordered]@{
    "GROUP_E4_REJOIN_EVALUATED" = (Count-Hits $m01 "GROUP_E4_REJOIN_EVALUATED")
    "GROUP_E4_REJOIN_INVITE_ISSUED" = (Count-Hits $m01 "GROUP_E4_REJOIN_INVITE_ISSUED")
    "GROUP_E4_REJOIN_ADMISSION_COMMITTED" = (Count-Hits $m01 "GROUP_E4_REJOIN_ADMISSION_COMMITTED")
    "member_joined" = (Count-Hits $m01 "reason=member_joined")
    "Group accept from M02" = (Count-Hits $m01 "Group accept from M02")
    "ICE_CONNECTED_M02" = (Count-Hits $m01 "M02.*ice=CONNECTED|ice=CONNECTED.*M02")
}

$forbidden = [ordered]@{
    "QUEUED_NO_SESSION" = (Count-Hits $m01 "joinIngressDecision=QUEUED_NO_SESSION")
    "Group reconnect join offered" = (Count-Hits $m01 "Group reconnect join offered")
    "GROUP_JOIN ingress ACCEPT" = (Count-Hits $m01 "GROUP_JOIN joinIngressDecision=ACCEPT.*from=M02")
}

Write-Host "LogDir=$LogDir"
Write-Host "M01=$m01"
Write-Host ""
Write-Host "=== REQUIRED (M01) ==="
$reqPass = $true
foreach ($k in $required.Keys) {
    $n = $required[$k]
    $ok = $n -ge 1
    if ($k -eq "ICE_CONNECTED_M02") { $ok = $n -ge 1 }
    if ($k -eq "Group accept from M02" -and $required["GROUP_E4_REJOIN_ADMISSION_COMMITTED"] -ge 1) {
        $ok = $ok -or ($required["GROUP_E4_REJOIN_ADMISSION_COMMITTED"] -ge 1)
    }
    if (-not $ok -and $k -notin @("ICE_CONNECTED_M02")) { $reqPass = $false }
    Write-Host ("  {0,-40} {1,4} {2}" -f $k, $n, $(if ($ok) { "OK" } else { "MISS" }))
}
if ($required["ICE_CONNECTED_M02"] -lt 1) {
    Write-Host "  ICE_CONNECTED_M02 (soft)                   0 WARN — check qos manually"
}

Write-Host ""
Write-Host "=== FORBIDDEN (M01) ==="
$forbPass = $true
foreach ($k in $forbidden.Keys) {
    $n = $forbidden[$k]
    $ok = $n -eq 0
    if (-not $ok) { $forbPass = $false }
    Write-Host ("  {0,-40} {1,4} {2}" -f $k, $n, $(if ($ok) { "OK" } else { "HIT" }))
}

$verdict = if ($reqPass -and $forbPass) { "PASS" } else { "FAIL" }
Write-Host ""
Write-Host "VERDICT=$verdict"
exit $(if ($verdict -eq "PASS") { 0 } else { 1 })
