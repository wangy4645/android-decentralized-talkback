# #181-B directed field gate adjudicate (domain selection only)
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

$postJoinPrefix = "."
$tJoinLine = Get-Content (Join-Path $LogDir "RUN_META.txt") -ErrorAction SilentlyContinue |
    Where-Object { $_ -match "^t_join_m03=" } | Select-Object -First 1
if ($tJoinLine -match "t_join_m03=(.+)") {
    $tJoin = [datetimeoffset]::Parse($Matches[1].Trim())
    $postJoinPrefix = $tJoin.ToString("HH:mm")
}

Write-Host "LogDir=$LogDir postJoin=~$postJoinPrefix"
Write-Host ""
Write-Host "=== #181-C GATE (M01 post-join) ==="

$bootstrapM03 = Count-After $m01 "GROUP_BOOTSTRAP_INTENT_CREATED.*peer=M03" $postJoinPrefix
$pairwiseAny = (Count-After $m01 "PAIRWISE_MESH_ADMISSION" $postJoinPrefix) +
    (Count-After $m03 "PAIRWISE_MESH_ADMISSION" $postJoinPrefix)
$classifierEvidence = (Count-After $m01 "PEER_EDGE_CONTROL_BLOCKED" $postJoinPrefix) +
    (Count-After $m01 "PAIRWISE_MESH_ADMISSION_DEFERRED" $postJoinPrefix) +
    (Count-After $m01 "PAIRWISE_MESH_ADMISSION_ACTIVATION" $postJoinPrefix)
$pairwiseObligation = (Count-After $m01 "Pairwise re-invited|PAIRWISE_MESH_ADMISSION_ACTIVATION" $postJoinPrefix) +
    (Count-After $m03 "snapshotApplied.*members=3" $postJoinPrefix)
$groupJoin = (Count-Hits $m01 "GROUP_JOIN") + (Count-Hits $m02 "GROUP_JOIN") + (Count-Hits $m03 "GROUP_JOIN")
$queued = (Count-Hits $m01 "QUEUED_NO_SESSION") + (Count-Hits $m02 "QUEUED_NO_SESSION") + (Count-Hits $m03 "QUEUED_NO_SESSION")
$snapshotApplied = Count-After $m03 "snapshotApplied" $postJoinPrefix
$inviteM03 = Count-After $m01 "Group invite sent ->.*M03" $postJoinPrefix

Write-Host ("  BOOTSTRAP_INTENT M03 (post-join)     {0,4}  require=0" -f $bootstrapM03)
Write-Host ("  PAIRWISE_MESH_ADMISSION_*            {0,4}  require>=1" -f $pairwiseAny)
Write-Host ("  Classifier path evidence (aux)       {0,4}  blocked/deferred/activation" -f $classifierEvidence)
Write-Host ("  PAIRWISE domain evidence (aux)       {0,4}" -f $pairwiseObligation)
Write-Host ("  GROUP_JOIN (all)                     {0,4}  require=0" -f $groupJoin)
Write-Host ("  QUEUED_NO_SESSION (all)              {0,4}  require=0" -f $queued)
Write-Host ("  M03 snapshotApplied (context)        {0,4}" -f $snapshotApplied)
Write-Host ("  M01 Group invite sent M03            {0,4}" -f $inviteM03)

$g1 = $bootstrapM03 -eq 0
$g2 = $pairwiseAny -ge 1
$g3 = $groupJoin -eq 0 -and $queued -eq 0

Write-Host ""
if ($g1 -and $g2 -and $g3) {
    Write-Host "VERDICT=PASS_181C_DOMAIN_GATE"
    Write-Host "NOTE=Classifier routed blocked invite away from bootstrap; pairwise markers present"
    exit 0
}
if ($g1 -and -not $g2) {
    Write-Host "VERDICT=PASS_181C_PRODUCER"
    Write-Host "NOTE=Blocked invite no longer creates bootstrap; PAIRWISE needs membership-first harness"
    exit 0
}
if (-not $g1) {
    Write-Host "VERDICT=FAIL_181C"
    Write-Host "NOTE=Bootstrap intent still created for M03 late peer"
    exit 1
}
Write-Host "VERDICT=FAIL"
exit 1
