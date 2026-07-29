# R28-N Case B full-chain soak (ADR-0032 dispatch + ADR-0033 gate semantics)
# Gate matrix frozen in docs/adr/0033 section 6. Owner-scoped G-PRR / G-L4-2;
# G-R28-COMPLETION is end-to-end only (WARN allowed). Not ADR-0032/0033 acceptance.
param(
    [switch]$ClearOnly,
    [switch]$CollectOnly,
    [switch]$AnalyzeOnly,
    [string]$LogDir = "",
    [string]$RecoveryPeer = "M03",
    [string]$HostModule = "M02"
)
$devices = @{ M01 = "HTUBB21B09220661"; M02 = "2d73067a"; M03 = "MDX0220416001963" }
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$logsRoot = Join-Path $repoRoot "logs"
function Resolve-Adb {
    foreach ($c in @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe","$env:ANDROID_HOME\platform-tools\adb.exe","adb")) {
        if ($c -eq "adb") { $cmd = Get-Command adb -ErrorAction SilentlyContinue; if ($cmd) { return $cmd.Source } }
        elseif (Test-Path $c) { return $c }
    }
    throw "adb not found"
}
$adb = Resolve-Adb
if (-not $LogDir) { $LogDir = Join-Path $logsRoot ("obs-r28n-caseb-" + (Get-Date -Format "yyyyMMdd-HHmmss")) }
if ($ClearOnly -or (-not $CollectOnly -and -not $AnalyzeOnly)) {
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
    foreach ($name in $devices.Keys) { & $adb -s $devices[$name] logcat -G 16M | Out-Null; & $adb -s $devices[$name] logcat -c | Out-Null }
    @("R28-N Case B soak","host=$HostModule recoveryPeer=$RecoveryPeer","started=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')") | Set-Content (Join-Path $LogDir "meta.txt") -Encoding UTF8
    Write-Host "LogDir=$LogDir"
}
if ($ClearOnly) { exit 0 }
if ($CollectOnly -or (-not $AnalyzeOnly)) {
    foreach ($name in $devices.Keys) {
        $out = Join-Path $LogDir "$name-talkback.log"
        & $adb -s $devices[$name] logcat -d -v time Talkback:I *:S 2>&1 | Set-Content $out -Encoding UTF8
    }
    if (-not $AnalyzeOnly) { exit 0 }
}
function C($lines,$pat){ @($lines|?{$_ -match $pat}).Count }
function Load-Lines($module) {
    $path = Join-Path $LogDir "$module-talkback.log"
    if (Test-Path $path) { Get-Content $path -Encoding UTF8 } else { @() }
}
$report = @("R28-N Case B analysis","logDir=$LogDir")
foreach ($name in @("M01","M02","M03")) {
    $path = Join-Path $LogDir "$name-talkback.log"
    if (-not (Test-Path $path)) { $report += "MISSING $name"; continue }
    $lines = Get-Content $path -Encoding UTF8
    $report += "=== $name ==="
    $report += "PRR_EPISODE_STARTED=$(C $lines 'PRR_EPISODE_STARTED')"
    $report += "PRR_FACT_OBSERVED=$(C $lines 'PRR_FACT_OBSERVED')"
    $report += "peerSignalingReachable_true=$(C $lines 'peerSignalingReachable=true')"
    $report += "WAITING_FOR_PEER_SIGNALING=$(C $lines 'WAITING_FOR_PEER_SIGNALING')"
    $report += "authorityReachable_true=$(C $lines 'authorityReachable=true')"
    $report += "RECOVERY_ICE_RESTART_DISPATCHED=$(C $lines 'RECOVERY_ICE_RESTART_DISPATCHED')"
    $report += "RECOVERY_REATTACH accepted=$(C $lines 'RECOVERY_REATTACH accepted')"
    $report += "RECOVERY_REATTACH_INBOUND_DEFERRED=$(C $lines 'RECOVERY_REATTACH_INBOUND_DEFERRED')"
    $routeFail = @($lines|?{$_ -match 'RECOVERY_REATTACH_DEFERRED' -and $_ -match 'reason=WAITING_FOR_ROUTE'}).Count -gt 0
    $report += "FORBIDDEN_ROUTE_DEFER=$(if($routeFail){'FAIL'}else{'PASS'})"
    $report += "BIDIRECTIONAL_READY=$(C $lines 'BIDIRECTIONAL_READY')"
    $report += "EDGE_RECOVERED=$(C $lines 'EDGE_RECOVERED')"
    $report += "MEMBERSHIP_LEFT=$(C $lines 'MEMBERSHIP_LEFT')"
}
$hostLines = Load-Lines $HostModule
$ownerLines = Load-Lines $RecoveryPeer
$ownerEpisode = (C $ownerLines 'PRR_EPISODE_STARTED') -gt 0
$hostObservesOwner = @($hostLines | Where-Object {
    $_ -match 'PRR_FACT_OBSERVED' -and $_ -match "remoteModuleId=$RecoveryPeer"
}).Count -gt 0
$hostEpisode = (C $hostLines 'PRR_EPISODE_STARTED') -gt 0
$gPrr = $ownerEpisode -and $hostObservesOwner
$ownerQualReady = (C $ownerLines 'BIDIRECTIONAL_READY') -gt 0
$dispatch = (C $hostLines 'RECOVERY_ICE_RESTART_DISPATCHED') -gt 0 -or (C $hostLines 'RECOVERY_REATTACH accepted') -gt 0
$edge = (C $hostLines 'EDGE_RECOVERED') -gt 0
$routeFailGlobal = $false
foreach ($name in @("M01", "M02", "M03")) {
    $lines = Load-Lines $name
    if (@($lines | Where-Object { $_ -match 'RECOVERY_REATTACH_DEFERRED' -and $_ -match 'reason=WAITING_FOR_ROUTE' }).Count -gt 0) {
        $routeFailGlobal = $true
    }
}
$report += "=== Gates Case B host=$HostModule owner=$RecoveryPeer ==="
$report += "G-PRR=$(if($gPrr){'PASS*'}else{'FAIL'}) owner=$RecoveryPeer owner_episode=$(if($ownerEpisode){'yes'}else{'no'}) host_episode=$(if($hostEpisode){'yes'}else{'no'}) host_observes_owner=$(if($hostObservesOwner){'yes'}else{'no'})"
$report += "G-L4-2=$(if($ownerQualReady){'PASS*'}else{'FAIL'}) owner=$RecoveryPeer BIDIRECTIONAL_READY=$(if($ownerQualReady){'yes'}else{'no'}) host_requal_expected=no"
$report += "G-R28-N_DISPATCH=$(if($dispatch){'PASS'}else{'FAIL'}) edge=$HostModule->$RecoveryPeer"
$report += "FORBIDDEN_ROUTE_DEFER=$(if($routeFailGlobal){'FAIL'}else{'PASS'})"
$report += "G-R28-COMPLETION=$(if($edge){'PASS'}else{'WARN'}) end_to_end_only"
$rp = Join-Path $LogDir "r28n-caseb-analysis.txt"
$report | Set-Content $rp -Encoding UTF8
$report | ForEach-Object { Write-Host $_ }