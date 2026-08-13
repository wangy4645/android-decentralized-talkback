# #180 membership-first directed field adjudicate (Run 7 gates)
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

function Parse-LogLineTime {
    param([string]$Line)
    if ($Line -match '(\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})\.(\d{3})') {
        return [datetime]::ParseExact(
            "$(Get-Date -Format yyyy)-$($Matches[1]) $($Matches[2]).$($Matches[3])",
            "yyyy-MM-dd HH:mm:ss.fff",
            $null
        )
    }
    return $null
}

function Get-MetaTime {
    param([string]$MetaPath, [string]$Prefix)
    $line = Get-Content $MetaPath -ErrorAction SilentlyContinue |
        Where-Object { $_ -match "^$([regex]::Escape($Prefix))" } | Select-Object -First 1
    if ($line -match "$([regex]::Escape($Prefix))(.+)") {
        return [datetimeoffset]::Parse($Matches[1].Trim()).LocalDateTime
    }
    return $null
}

function Count-LogInWindow {
    param(
        [string]$Path,
        [string]$Pattern,
        [Parameter(Mandatory = $true)]
        [datetime]$From,
        [datetime]$Before
    )
    $hasBefore = $PSBoundParameters.ContainsKey('Before')
    if (-not (Test-Path $Path)) { return 0 }
    @(Select-String -Path $Path -Pattern $Pattern | Where-Object {
            $t = Parse-LogLineTime $_.Line
            if (-not $t) { return $false }
            if ($t -lt $From.AddSeconds(-1)) { return $false }
            if ($hasBefore -and $t -ge $Before) { return $false }
            return $true
        }).Count
}

function Find-FirstLogLineTime {
    param(
        [string]$Path,
        [string]$Pattern,
        [datetime]$NotBefore = [datetime]::MinValue
    )
    if (-not (Test-Path $Path)) { return $null }
    foreach ($hit in Select-String -Path $Path -Pattern $Pattern) {
        $t = Parse-LogLineTime $hit.Line
        if ($t -and $t -ge $NotBefore.AddSeconds(-1)) { return $t }
    }
    return $null
}

$m01 = Resolve-LogPath -Dir $LogDir -Module "M01"
$m02 = Resolve-LogPath -Dir $LogDir -Module "M02"
$m03 = Resolve-LogPath -Dir $LogDir -Module "M03"
$meta = Join-Path $LogDir "RUN_META.txt"

$tJoinM03 = Get-MetaTime $meta "t_join_m03="
if (-not $tJoinM03) {
    Write-Error "RUN_META.txt missing t_join_m03="
}

$tEdgeReady = Find-FirstLogLineTime -Path $m01 -Pattern "PEER_EDGE_READY remote=M03" -NotBefore $tJoinM03.AddSeconds(-10)
$rosterNotBefore = if ($tEdgeReady) { $tEdgeReady } else { $tJoinM03 }
$tRosterM01 = Find-FirstLogLineTime -Path $m01 -Pattern "ADMISSION_HARNESS_MEMBERSHIP_INJECT.*rosterOnly=true" -NotBefore $rosterNotBefore
$syncNotBefore = if ($tRosterM01) { $tRosterM01 } else { $tJoinM03 }
$tSyncM03 = Find-FirstLogLineTime -Path $m03 -Pattern "ADMISSION_HARNESS_MEMBERSHIP_SYNC.*LOCAL_NO_PLANNER" -NotBefore $syncNotBefore
$harnessNotBefore = if ($tSyncM03) { $tSyncM03 } elseif ($tRosterM01) { $tRosterM01 } else { $tJoinM03 }
$tHarnessM01 = Find-FirstLogLineTime -Path $m01 -Pattern "ADMISSION_HARNESS_MEMBERSHIP_INJECT.*rosterOnly=false" -NotBefore $harnessNotBefore
$activateNotBefore = if ($tHarnessM01) { $tHarnessM01 } else { $tJoinM03 }
$tActivateM01 = Find-FirstLogLineTime -Path $m01 -Pattern "ADMISSION_HARNESS_ACTIVATE ch=" -NotBefore $activateNotBefore

if (-not $tEdgeReady -or -not $tRosterM01 -or -not $tHarnessM01 -or -not $tActivateM01) {
    Write-Error "Missing log anchors (edge=$tEdgeReady roster=$tRosterM01 harness=$tHarnessM01 activate=$tActivateM01)"
}

Write-Host "LogDir=$LogDir"
Write-Host "  edge=$($tEdgeReady.ToString('HH:mm:ss')) roster=$($tRosterM01.ToString('HH:mm:ss')) sync=$(if ($tSyncM03) { $tSyncM03.ToString('HH:mm:ss') } else { 'n/a' }) activate=$($tActivateM01.ToString('HH:mm:ss'))"
Write-Host ""
Write-Host "=== #180 MEMBERSHIP-FIRST DOMAIN CHAIN ==="

$plannerArm = Count-LogInWindow -Path $m01 -Pattern "ADMISSION_HARNESS_PLANNER_SUPPRESS armed=true" -From $tJoinM03 -Before $tRosterM01
$peerEdgeReady = if ($tEdgeReady) { 1 } else { 0 }
$m03Sync = if ($tSyncM03) {
    Count-LogInWindow -Path $m03 -Pattern "ADMISSION_HARNESS_MEMBERSHIP_SYNC.*LOCAL_NO_PLANNER" -From $tSyncM03
} else { 0 }
$rosterInject = Count-LogInWindow -Path $m01 -Pattern "ADMISSION_HARNESS_MEMBERSHIP_INJECT.*rosterOnly=true" -From $tRosterM01
$harnessInject = Count-LogInWindow -Path $m01 -Pattern "ADMISSION_HARNESS_MEMBERSHIP_INJECT.*rosterOnly=false" -From $tHarnessM01 -Before $tActivateM01
$harnessClass = Count-LogInWindow -Path $m01 -Pattern "ADMISSION_HARNESS_CLASSIFICATION.*domain=PAIRWISE_MESH" -From $tHarnessM01
$harnessObl = Count-LogInWindow -Path $m01 -Pattern "ADMISSION_HARNESS_OBLIGATION.*signalingSatisfied=false" -From $tHarnessM01 -Before $tActivateM01
$bootstrapPre = Count-LogInWindow -Path $m01 -Pattern "GROUP_BOOTSTRAP_INTENT_CREATED.*peer=M03" -From $tJoinM03 -Before $tHarnessM01
$bootstrapPost = Count-LogInWindow -Path $m01 -Pattern "GROUP_BOOTSTRAP_INTENT_CREATED.*peer=M03" -From $tActivateM01
$pairInvite = Count-LogInWindow -Path $m01 -Pattern "PAIRWISE_MESH_ADMISSION_INVITE_ISSUED.*peer=M03" -From $tActivateM01
$sdpInvite = Count-LogInWindow -Path $m01 -Pattern "Group invite sent ->.*M03" -From $tActivateM01
$acceptM03 = (Count-LogInWindow -Path $m03 -Pattern "invite reconnect accepted from M01" -From $tActivateM01) +
    (Count-LogInWindow -Path $m03 -Pattern "GROUP_ACCEPT_HANDOFF.*remote=M01.*SUCCESS" -From $tActivateM01)
$signalingSat = Count-LogInWindow -Path $m01 -Pattern "ADMISSION_HARNESS_OBLIGATION_PROBE.*signalingSatisfied=true" -From $tActivateM01
$resyncMismatch = Count-LogInWindow -Path $m03 -Pattern "GROUP_RESYNC_REQUEST \(invite mismatch\)" -From $tActivateM01
$pairwiseReinvited = Count-LogInWindow -Path $m01 -Pattern "Pairwise re-invited.*M03" -From $tActivateM01
$meshJoinOffered = Count-LogInWindow -Path $m01 -Pattern "Group mesh join offered.*M03" -From $tActivateM01
$plannerInvite = $pairwiseReinvited + $meshJoinOffered
$groupJoin = (Count-LogInWindow -Path $m01 -Pattern "GROUP_JOIN" -From $tActivateM01) +
    (Count-LogInWindow -Path $m02 -Pattern "GROUP_JOIN" -From $tActivateM01) +
    (Count-LogInWindow -Path $m03 -Pattern "GROUP_JOIN" -From $tActivateM01)

Write-Host ("  PLANNER_SUPPRESS armed (pre-roster)       {0,4}  require>=1" -f $plannerArm)
Write-Host ("  PEER_EDGE_READY M03 (pre-roster)          {0,4}  require>=1" -f $peerEdgeReady)
Write-Host ("  M01 ROSTER_ONLY inject                    {0,4}  require>=1" -f $rosterInject)
Write-Host ("  M03 MEMBERSHIP_SYNC                       {0,4}  require>=1" -f $m03Sync)
Write-Host ("  HARNESS classify+obligation               {0,4}  require>=1" -f $harnessInject)
Write-Host ("  CLASSIFICATION PAIRWISE_MESH              {0,4}  require>=1" -f $harnessClass)
Write-Host ("  OBLIGATION unsatisfied                    {0,4}  require>=1" -f $harnessObl)
Write-Host ("  BOOTSTRAP_INTENT pre-harness              {0,4}  require=0" -f $bootstrapPre)
Write-Host ("  BOOTSTRAP_INTENT post-activate            {0,4}  require=0" -f $bootstrapPost)
Write-Host ("  NORMAL_MESH_PLANNER_INVITE                  {0,4}  require=0" -f $plannerInvite)
Write-Host ("  PAIRWISE_MESH_INVITE_ISSUED M03           {0,4}  require>=1" -f $pairInvite)
Write-Host ("  SDP Group invite sent M03                 {0,4}  require>=1" -f $sdpInvite)
Write-Host ("  GROUP_ACCEPT / accept path                {0,4}  require>=1" -f $acceptM03)
Write-Host ("  signalingSatisfied=true                   {0,4}  require>=1" -f $signalingSat)
Write-Host ("  invite mismatch resync                    {0,4}  require=0" -f $resyncMismatch)
Write-Host ("  GROUP_JOIN post-activate                  {0,4}  require=0" -f $groupJoin)

$g = @{
    arm = $plannerArm -ge 1
    edge = $peerEdgeReady -ge 1
    roster = $rosterInject -ge 1
    sync = $m03Sync -ge 1
    inject = $harnessInject -ge 1
    class = $harnessClass -ge 1
    obl = $harnessObl -ge 1
    noBootstrap = ($bootstrapPre + $bootstrapPost) -eq 0
    noPlanner = $plannerInvite -eq 0
    pairInvite = $pairInvite -ge 1
    sdp = $sdpInvite -ge 1
    accept = $acceptM03 -ge 1
    signaling = $signalingSat -ge 1
    noJoin = $groupJoin -eq 0
    noMismatch = $resyncMismatch -eq 0
}

Write-Host ""
if ($g.arm -and $g.edge -and $g.roster -and $g.sync -and $g.inject -and $g.class -and $g.obl -and $g.noBootstrap -and $g.noPlanner -and $g.pairInvite -and $g.sdp -and $g.accept -and $g.signaling -and $g.noJoin -and $g.noMismatch) {
    Write-Host "VERDICT=PASS_180_MEMBERSHIP_FIRST_FIELD"
    Write-Host "NOTE=Run7 chain: edge-ready roster-sync obligation activate accept signalingSatisfied"
    exit 0
}
if ($g.pairInvite -and $g.sdp -and -not $g.accept) {
    Write-Host "VERDICT=FAIL_180_ACCEPT"
    Write-Host "NOTE=Activation issued invite but GROUP_ACCEPT/signalingSatisfied missing"
    exit 1
}
if ($g.inject -and $g.class -and $g.obl -and -not $g.pairInvite) {
    Write-Host "VERDICT=FAIL_180_ACTIVATION"
    Write-Host "NOTE=Preconditions met but PAIRWISE_MESH_ADMISSION_INVITE_ISSUED missing"
    exit 1
}
if (-not $g.edge) {
    Write-Host "VERDICT=FAIL_180_EDGE_READY"
    Write-Host "NOTE=PEER_EDGE_READY not observed between M03 launch and roster inject"
    exit 1
}
if (-not $g.inject) {
    Write-Host "VERDICT=FAIL_180_HARNESS"
    exit 1
}
Write-Host "VERDICT=FAIL"
exit 1
