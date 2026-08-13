# #180 membership-first directed field — phase2: edge-ready, roster sync, accept chain
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [string]$ChannelId = "CH-01",
    [string]$PeerModule = "M03",
    [string]$CanonicalMembers = "M01,M02,M03",
    [int]$LaunchM03DelaySec = 5,
    [int]$MinEdgeWaitSec = 25,
    [int]$EdgeReadyTimeoutSec = 45,
    [int]$ObserveSeconds = 90
)

$ErrorActionPreference = "Stop"
$devices = @{
    M01 = "HTUBB21B09220661"
    M02 = "2d73067a"
    M03 = "MDX0220416001963"
}
$Package = "com.talkback.appprod"
$ArmAction = "com.talkback.appprod.debug.P180_ARM_PLANNER_SUPPRESS"
$SyncAction = "com.talkback.appprod.debug.P180_SYNC_MEMBERSHIP_VIEW"
$InjectAction = "com.talkback.appprod.debug.P180_MEMBERSHIP_FIRST"
$ActivateAction = "com.talkback.appprod.debug.P180_PAIRWISE_ACTIVATE"
$ProbeAction = "com.talkback.appprod.debug.P180_PROBE_OBLIGATION"
$ReleaseAction = "com.talkback.appprod.debug.P180_HARNESS_RELEASE"
$adb = (Get-Command adb).Source
$m01Log = Join-Path $LogDir "M01-talkback.log"
$meta = Join-Path $LogDir "RUN_META.txt"

$tArm = Get-Date -Format o
Write-Host "T+0 arm planner suppress on M01"
& $adb -s $devices.M01 shell am broadcast -p $Package -a $ArmAction | Out-Null
"t_arm_m01=$tArm" | Add-Content $meta -Encoding UTF8

Write-Host "T+${LaunchM03DelaySec}s launch M03 (pre-canonical discovery only)"
Start-Sleep -Seconds $LaunchM03DelaySec
$tJoin = Get-Date -Format o
& $adb -s $devices.M03 shell am force-stop $Package
Start-Sleep -Milliseconds 300
& $adb -s $devices.M03 shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
"t_join_m03=$tJoin" | Add-Content $meta -Encoding UTF8
$m03After = ([datetimeoffset]::Parse($tJoin)).LocalDateTime

function Test-LogAfter {
    param([string]$Line, [datetime]$NotBefore)
    if ($Line -match '(\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})\.(\d{3})') {
        $lineAt = [datetime]::ParseExact(
            "$(Get-Date -Format yyyy)-$($Matches[1]) $($Matches[2]).$($Matches[3])",
            "yyyy-MM-dd HH:mm:ss.fff",
            $null
        )
        return $lineAt -ge $NotBefore.AddSeconds(-1)
    }
    return $false
}

Write-Host "Wait PEER_EDGE_READY $PeerModule after launch (min ${MinEdgeWaitSec}s, max ${EdgeReadyTimeoutSec}s)..."
$edgeDeadline = (Get-Date).AddSeconds($EdgeReadyTimeoutSec)
$minEdgeAt = (Get-Date).AddSeconds($MinEdgeWaitSec)
$ready = $false
while ((Get-Date) -lt $edgeDeadline) {
    if ((Get-Date) -ge $minEdgeAt -and (Test-Path $m01Log)) {
        $hit = Select-String -Path $m01Log -Pattern "PEER_EDGE_READY remote=$PeerModule" |
            Where-Object { Test-LogAfter $_.Line $m03After } |
            Select-Object -First 1
        if ($hit) {
            $ready = $true
            Write-Host "PEER_EDGE_READY seen: $($hit.Line)"
            if ($hit.Line -match '(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})') {
                "t_peer_edge_ready_log=$($Matches[1])" | Add-Content $meta -Encoding UTF8
            }
            break
        }
    }
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    Write-Error "PEER_EDGE_READY not observed for $PeerModule after launch; aborting inject"
    & $adb -s $devices.M01 shell am broadcast -p $Package -a $ReleaseAction | Out-Null
    exit 2
}
"t_peer_edge_ready=$(([datetimeoffset]::UtcNow).ToString('o'))" | Add-Content $meta -Encoding UTF8

Write-Host "Arm planner suppress on M03 (answerer)"
& $adb -s $devices.M03 shell am broadcast -p $Package -a $ArmAction | Out-Null
Start-Sleep -Milliseconds 500

$tRoster = Get-Date -Format o
Write-Host "T_roster=$tRoster M01 roster-only inject (no obligation yet)"
& $adb -s $devices.M01 shell am broadcast -p $Package -a $InjectAction `
    --es remote $PeerModule --es channel $ChannelId --ez trigger false --ez rosterOnly true | Out-Null
"t_roster_m01=$tRoster" | Add-Content $meta -Encoding UTF8
Start-Sleep -Seconds 2

$tSync = Get-Date -Format o
Write-Host "T_sync=$tSync sync M03 membership view members=$CanonicalMembers unsatisfied=M01"
& $adb -s $devices.M03 shell am broadcast -p $Package -a $SyncAction `
    --es channel $ChannelId --es members $CanonicalMembers --es unsatisfiedPeer M01 | Out-Null
"t_sync_m03=$tSync" | Add-Content $meta -Encoding UTF8
Start-Sleep -Seconds 2

$tInject = Get-Date -Format o
Write-Host "T_inject=$tInject classify+obligation (M03 roster already aligned)"
& $adb -s $devices.M01 shell am broadcast -p $Package -a $InjectAction `
    --es remote $PeerModule --es channel $ChannelId --ez trigger false --ez rosterOnly false | Out-Null
"t_harness_m01=$tInject" | Add-Content $meta -Encoding UTF8
Start-Sleep -Seconds 1

$tActivate = Get-Date -Format o
Write-Host "T_activate=$tActivate pairwise activation"
& $adb -s $devices.M01 shell am broadcast -p $Package -a $ActivateAction `
    --es remote $PeerModule --es channel $ChannelId | Out-Null
"t_activate_m01=$tActivate" | Add-Content $meta -Encoding UTF8

Write-Host "Observe ${ObserveSeconds}s..."
$probeAt = [Math]::Max(5, $ObserveSeconds - 5)
Start-Sleep -Seconds $probeAt
$tProbe = Get-Date -Format o
Write-Host "T_probe=$tProbe obligation probe on M01"
& $adb -s $devices.M01 shell am broadcast -p $Package -a $ProbeAction `
    --es remote $PeerModule --es channel $ChannelId | Out-Null
"t_probe_m01=$tProbe" | Add-Content $meta -Encoding UTF8
Start-Sleep -Seconds ($ObserveSeconds - $probeAt)
& $adb -s $devices.M01 shell am broadcast -p $Package -a $ReleaseAction | Out-Null
& $adb -s $devices.M03 shell am broadcast -p $Package -a $ReleaseAction | Out-Null
