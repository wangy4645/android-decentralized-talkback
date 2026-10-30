# R2 Release Smoke — release build, automatic GA default cutover (NO adb RC1 broadcast).
param(
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "MDX0220416001963",
    [ValidateSet("M03", "M04")]
    [string]$ThirdPeer = "M03",
    [string]$M04Serial = "192.168.31.206:5555",
    [string]$Iface = "wlan0",
    [int]$SoakSec = 60,
    [int]$MinSoakSec = 45,
    [switch]$SkipBuildInstall,
    [switch]$SkipServiceColdStart,
    [switch]$PreferHeadsetPlayback,
    # Lean field re-run: no install, no R2-B gradle. Still cold-starts services (force-stop)
    # so Meeting CREATE/invite is not blocked by residual session UI state.
    [switch]$Lean,
    [switch]$SkipR2BUnit
)

if ($Lean) {
    $SkipBuildInstall = $true
    $SkipR2BUnit = $true
    # Do NOT set SkipServiceColdStart — force-stop + service restart required for clean Meeting start.
}

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$adb = (Get-Command adb).Source
$thirdSerial = if ($ThirdPeer -eq "M04") { $M04Serial } else { $M03Serial }
$packageName = "com.talkback.appprod"
function Resolve-ReleaseApk {
    $dir = Join-Path $repoRoot "talkback\talkback-app\build\outputs\apk\release"
    $apk = Get-ChildItem -Path $dir -Filter "talkback-app-release.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $apk) {
        $apk = Get-ChildItem -Path $dir -Filter "*.apk" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch "unsigned" } |
            Select-Object -First 1
    }
    if (-not $apk) { throw "signed release APK missing under $dir (run assembleRelease)" }
    return $apk.FullName
}

function Get-LatestEstablishmentVersions {
    param([string[]]$ModuleIds)
    $versions = @{}
    $batchDirs = Get-ChildItem -Path (Join-Path $repoRoot "talkback\logs") -Filter "ep-establishment-operator-*" -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending
    foreach ($dir in $batchDirs) {
        $manifest = Join-Path $dir.FullName "PROVISIONING_MANIFEST.txt"
        if (-not (Test-Path $manifest)) { continue }
        foreach ($moduleId in $ModuleIds) {
            if ($versions.ContainsKey($moduleId)) { continue }
            $line = Select-String -Path $manifest -Pattern "^$moduleId\.establishmentKeyVersion=" | Select-Object -Last 1
            if ($line) { $versions[$moduleId] = [long]($line.Line -replace "^$moduleId\.establishmentKeyVersion=", "") }
        }
        if (-not ($ModuleIds | Where-Object { -not $versions.ContainsKey($_) })) { break }
    }
    return $versions
}

function Test-EstablishmentCoverage {
    param(
        [string[]]$ParticipantModuleIds,
        [switch]$PreserveWarmServices
    )
    $failures = @()
    $versions = Get-LatestEstablishmentVersions -ModuleIds $ParticipantModuleIds
    if (-not $PreserveWarmServices) {
        adb -s $M01Serial shell am force-stop $packageName | Out-Null
    }
    foreach ($peer in $ParticipantModuleIds) {
        if ($peer -eq "M01") { continue }
        if (-not $versions.ContainsKey($peer)) { $failures += "lookup_version_$peer"; continue }
        if ($PreserveWarmServices) { continue }
        adb -s $M01Serial shell am instrument -w -r `
            -e peerModuleId $peer `
            -e establishmentKeyVersion $versions[$peer] `
            -e class "com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#pre4RemoteLookup" `
            "$packageName.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { $failures += "lookup_$peer" }
    }
    # Release APK is not debuggable — run-as store probe unavailable. Warm-path assumes
    # prior field establishment (G2/G3); missing trust surfaces as harness join failure.
    foreach ($pair in @(@($M01Serial, "M01"), @($M02Serial, "M02"), @($thirdSerial, $ThirdPeer))) {
        if (-not $versions.ContainsKey($pair[1])) { $failures += "manifest_version_$($pair[1])" }
    }
    return ,@($failures)
}

function Test-Preflight {
    $summary = @()
    foreach ($pair in @(@($M01Serial, "M01"), @($M02Serial, "M02"), @($thirdSerial, $ThirdPeer))) {
        $serial = $pair[0]
        $name = $pair[1]
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $state = (& $adb -s $serial get-state 2>&1 | Out-String).Trim()
        $ErrorActionPreference = $prevEap
        if ($state -ne "device") {
            $summary += [pscustomobject]@{ Device = $name; Ok = $false; Reason = "OFFLINE"; Ip = "" }
            continue
        }
        $addr = (& $adb -s $serial shell "ip -o -4 addr show $Iface" 2>&1 | Out-String)
        $ip = ""
        if ($addr -match "inet\s+(\d+\.\d+\.\d+\.\d+)") { $ip = $Matches[1] }
        $ok = $ip -ne ""
        $summary += [pscustomobject]@{ Device = $name; Ok = $ok; Reason = $(if ($ok) { "IFACE_UP" } else { "IFACE_NO_IPV4" }); Ip = $ip }
    }
    return ,$summary
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logDir = Join-Path $repoRoot "talkback\logs\ga-r2-release-smoke-field-$stamp"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

@(
    "gate=R2_RELEASE_SMOKE"
    "case=R2-A"
    "buildVariant=release"
    "noAdbCutoverBroadcast=true"
    "executionPath=PaR2_3node_real_mesh_auto_cutover"
    "driverMode=PaR2"
    "topology=3node_real_mesh"
    "soakSec=$SoakSec"
    "minSoakSec=$MinSoakSec"
    "thirdPeer=$ThirdPeer"
    "LogDir=$logDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

$pf = Test-Preflight
$pf | ConvertTo-Json | Set-Content (Join-Path $logDir "preflight.json") -Encoding utf8
$up = @($pf | Where-Object { $_.Ok })
$prefixes = @($up | ForEach-Object { ($_.Ip -split '\.')[0..2] -join '.' } | Select-Object -Unique)
if ($up.Count -lt 3 -or $prefixes.Count -ne 1) {
    $reason = if ($up.Count -lt 3) { "fewer_than_3_real_nodes_online_or_iface_up" } else { "divergent_real_underlay_l3" }
    @(
        "R2_RELEASE_SMOKE"
        "case=R2-A"
        "verdict=ENV_BLOCKED"
        "reason=$reason"
        "R2A_RESULT=INVALID"
        "Next=CONNECT_THIRD_PEER_AND_RERUN_R2A"
    ) | Set-Content (Join-Path $logDir "R2_GATE.txt") -Encoding UTF8
    Write-Host "R2-A ENV_BLOCKED: $reason (logDir=$logDir)"
    exit 2
}

if (-not $SkipBuildInstall) {
    Write-Host "R2: assembleRelease + install on M01/M02/$ThirdPeer..."
    Push-Location (Join-Path $repoRoot "talkback")
    & .\gradlew.bat :talkback-app:assembleRelease
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw "assembleRelease failed" }
    Pop-Location
    $releaseApk = Resolve-ReleaseApk
    foreach ($serial in @($M01Serial, $M02Serial, $thirdSerial)) {
        Write-Host "INSTALL release $serial ($releaseApk)"
        & $adb -s $serial install -r $releaseApk
        if ($LASTEXITCODE -ne 0) { throw "release install failed on $serial" }
    }
} else {
    Write-Host "R2: SkipBuildInstall — using existing release APK on devices"
}

$estFailures = Test-EstablishmentCoverage -ParticipantModuleIds @("M01", "M02", $ThirdPeer) `
    -PreserveWarmServices:$SkipServiceColdStart
$estFailures | ConvertTo-Json | Set-Content (Join-Path $logDir "establishment_preflight.json") -Encoding utf8
if ($estFailures.Count -gt 0) {
    @(
        "R2_RELEASE_SMOKE"
        "case=R2-A"
        "verdict=ENV_BLOCKED"
        "reason=establishment_coverage_incomplete"
        "failed=$($estFailures -join ',')"
        "R2A_RESULT=INVALID"
        "Next=FIX_ESTABLISHMENT_AND_RERUN_R2A"
    ) | Set-Content (Join-Path $logDir "R2_GATE.txt") -Encoding UTF8
    Write-Host "R2-A ENV_BLOCKED: establishment_coverage_incomplete (logDir=$logDir)"
    exit 2
}

Write-Host "R2-A: ${SoakSec}s soak on PaR2 3-node (automatic cutover; release build)"

$driverArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $PSScriptRoot "p1c-package-field-first-run.ps1"),
    "-DriverMode", "PaR2",
    "-M01Serial", $M01Serial,
    "-M02Serial", $M02Serial,
    "-M03Serial", $thirdSerial,
    "-PilotUseSec", "$SoakSec",
    "-InviteJoinWaitSec", "25",
    "-OperatorAssistedJoinWaitSec", "90",
    "-AllowOperatorAssistedM02Join",
    "-SkipBuildInstall",
    "-SkipEpProbe"
)
if ($SkipServiceColdStart) { $driverArgs += "-SkipServiceColdStart" }
if ($PreferHeadsetPlayback) { $driverArgs += "-PreferHeadsetPlayback" }

& powershell @driverArgs
$driverExit = $LASTEXITCODE

$driverLog = Get-ChildItem -Path (Join-Path $repoRoot "talkback\logs") -Filter "ga-r2-release-smoke-field-*" -Directory |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($driverLog -and $driverLog.FullName -ne $logDir) {
    Copy-Item -Path (Join-Path $driverLog.FullName "*") -Destination $logDir -Recurse -Force -ErrorAction SilentlyContinue
}

& (Join-Path $PSScriptRoot "meeting-product-r2-release-smoke-field-adjudicate.ps1") -LogDir $logDir -MinSoakSec $MinSoakSec
$r2aExit = $LASTEXITCODE

if ($SkipR2BUnit) {
    Write-Host "R2-B: SKIPPED (prior unit PASS / -SkipR2BUnit)"
    $r2bExit = 0
} else {
    Write-Host "R2-B: kill-switch unit evidence (multicastAudibleEnabled=false)..."
    Push-Location (Join-Path $repoRoot "talkback")
    & .\gradlew.bat :android-board-talkback:testDebugUnitTest --tests "com.talkback.core.conference.session.integration.cutover.MulticastAudibleAutomaticCutoverTest"
    $r2bExit = $LASTEXITCODE
    & .\gradlew.bat :android-board-talkback:testDebugUnitTest --tests "com.talkback.core.conference.session.integration.cutover.AudibleOwnershipControllerTest.cutover_rejectsWhenMulticastAudibleDisabled"
    if ($LASTEXITCODE -ne 0) { $r2bExit = $LASTEXITCODE }
    Pop-Location
}

$r2bVerdict = if ($r2bExit -eq 0) { "PASS" } else { "VALID_FAIL" }
@(
    "R2_RELEASE_SMOKE"
    "case=R2-B"
    "evidence=unit_debug_focused"
    "note=release_build_has_no_debug_receiver_for_kill_switch"
    "verdict=$r2bVerdict"
    "R2B_RESULT=$r2bVerdict"
) | Set-Content -Encoding utf8 (Join-Path $logDir "R2B_RESULT.txt")

$combined = if ($r2aExit -eq 0 -and $r2bExit -eq 0) { "PASS" } elseif ($r2aExit -eq 2) { "INVALID" } else { "VALID_FAIL" }
@(
    "R2_COMBINED"
    "R2A_RESULT=$(if ($r2aExit -eq 0) { 'PASS' } elseif ($r2aExit -eq 2) { 'INVALID' } else { 'VALID_FAIL' })"
    "R2B_RESULT=$r2bVerdict"
    "combinedVerdict=$combined"
    $(if ($combined -eq "PASS") { "Next=RELEASE_CANDIDATE" } else { "Next=RCA_R1_R2" })
) | Set-Content -Encoding utf8 (Join-Path $logDir "R2_RESULT.txt")

Write-Host "R2 combined=$combined logDir=$logDir"
if ($combined -eq "PASS") { exit 0 }
if ($combined -eq "INVALID") { exit 2 }
exit 1
