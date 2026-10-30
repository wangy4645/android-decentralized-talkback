# G2 Real Deployment — one high-quality 3-node Real Mesh product meeting run.
# No Wi-Fi simulation substitute. ENV_BLOCKED if <3 nodes on same real underlay.
param(
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "MDX0220416001963",
    [string]$M04Serial = "192.168.31.206:5555",
    [ValidateSet("M03", "M04")]
    [string]$ThirdPeer = "M03",
    [string]$Iface = "wlan0",
    [int]$PilotUseSec = 180,
    [switch]$SkipBuildInstall = $true,
    [switch]$SkipServiceColdStart,
    [switch]$PreferHeadsetPlayback,
    [string]$LogDirOverride = "",
    [switch]$SkipFinalAdjudication
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$adb = (Get-Command adb).Source
$thirdSerial = if ($ThirdPeer -eq "M04") { $M04Serial } else { $M03Serial }
$deviceMap = @{
    M01 = $M01Serial
    M02 = $M02Serial
    $ThirdPeer = $thirdSerial
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
        if ($ModuleIds | Where-Object { -not $versions.ContainsKey($_) } | Measure-Object | Select-Object -ExpandProperty Count) { break }
    }
    return $versions
}

function Test-EstablishmentCoverage {
    param(
        [string[]]$ParticipantModuleIds,
        [switch]$PreserveWarmServices
    )
    $packageName = "com.talkback.appprod"
    $failures = @()
    $versions = Get-LatestEstablishmentVersions -ModuleIds $ParticipantModuleIds
    # Warm G3a path: never force-stop M01 — instrumented lookup alone kills FG runtime
    # and then SkipServiceColdStart warm-start fails as harness_aborted_before_t0.
    if (-not $PreserveWarmServices) {
        adb -s $M01Serial shell am force-stop $packageName | Out-Null
    }
    foreach ($peer in $ParticipantModuleIds) {
        if ($peer -eq "M01") { continue }
        if (-not $versions.ContainsKey($peer)) {
            $failures += "lookup_version_$peer"
            continue
        }
        if ($PreserveWarmServices) {
            # Trust-store presence below remains the warm-path establishment gate.
            continue
        }
        adb -s $M01Serial shell am instrument -w -r `
            -e peerModuleId $peer `
            -e establishmentKeyVersion $versions[$peer] `
            -e class "com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#pre4RemoteLookup" `
            "$packageName.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { $failures += "lookup_$peer" }
    }
    foreach ($label in @("M01", "M02", $ThirdPeer)) {
        $serial = $deviceMap[$label]
        $probe = adb -s $serial shell "run-as $packageName sh -c 'test -f files/gbc-trust/accepted-local-establishment-trust-state.bin && echo PRESENT || echo ABSENT'"
        if (($probe | Out-String).Trim() -ne "PRESENT") { $failures += "store_$label" }
    }
    return ,@($failures)
}

function Test-Preflight {
    $summary = @()
    foreach ($name in @("M01", "M02", $ThirdPeer)) {
        $serial = $deviceMap[$name]
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $state = (& $adb -s $serial get-state 2>&1 | Out-String).Trim()
        $ErrorActionPreference = $prevEap
        if ($state -ne "device") {
            $summary += [pscustomobject]@{ Device = $name; Ok = $false; Reason = "OFFLINE"; Ip = "" }
            continue
        }
        $ErrorActionPreference = "Continue"
        $addr = & $adb -s $serial shell "ip -o -4 addr show $Iface" 2>&1 | Out-String
        $ErrorActionPreference = $prevEap
        $ip = ""
        if ($addr -match "inet\s+(\d+\.\d+\.\d+\.\d+)") { $ip = $Matches[1] }
        $ok = $ip -ne ""
        $summary += [pscustomobject]@{ Device = $name; Ok = $ok; Reason = $(if ($ok) { "IFACE_UP" } else { "IFACE_NO_IPV4" }); Ip = $ip }
    }
    return ,$summary
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logDir = if ($LogDirOverride) {
    $LogDirOverride
} else {
    Join-Path $repoRoot "talkback\logs\ga-g2-real-mesh-field-$stamp"
}
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$pf = Test-Preflight
$pf | ConvertTo-Json | Set-Content (Join-Path $logDir "preflight.json") -Encoding utf8
$up = @($pf | Where-Object { $_.Ok })
$prefixes = @($up | ForEach-Object { ($_.Ip -split '\.')[0..2] -join '.' } | Select-Object -Unique)

@(
    "gate=G2_REAL_DEPLOYMENT"
    "subgate=G2_RCA2_FIELD_REVERIFY"
    "operatorAssistedJoin=permitted"
    "productKotlin=frozen"
    "iface=$Iface"
    "thirdPeer=$ThirdPeer"
    "thirdSerial=$thirdSerial"
    "nodesOnline=$($up.Count)"
    "l3Prefixes=$($prefixes -join ',')"
    "semantic=real_underlay_3node_only"
    "LogDir=$logDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

if ($up.Count -lt 3 -or $prefixes.Count -ne 1) {
    $reason = if ($up.Count -lt 3) { "fewer_than_3_real_nodes_online_or_iface_up" } else { "divergent_real_underlay_l3" }
    @(
        "verdict=ENV_BLOCKED"
        "reason=$reason"
        "nodesOnline=$($up.Count)"
        "required=3"
        "sameUnderlay=true"
        "thirdPeer=$ThirdPeer"
    ) | Set-Content (Join-Path $logDir "G2_GATE.txt") -Encoding UTF8
    Write-Host "G2 ENV_BLOCKED: $reason"
    & (Join-Path $PSScriptRoot "meeting-product-ga-g2-real-mesh-field-adjudicate.ps1") -LogDir $logDir
    exit $LASTEXITCODE
}

$estFailures = Test-EstablishmentCoverage -ParticipantModuleIds @("M01", "M02", $ThirdPeer) `
    -PreserveWarmServices:$SkipServiceColdStart
$estFailures | ConvertTo-Json | Set-Content (Join-Path $logDir "establishment_preflight.json") -Encoding utf8
if ($estFailures.Count -gt 0) {
    @(
        "verdict=ENV_BLOCKED"
        "reason=establishment_coverage_incomplete"
        "failed=$($estFailures -join ',')"
        "thirdPeer=$ThirdPeer"
        "thirdSerial=$thirdSerial"
        "action=run ep-establishment-operator-provision-all.ps1 -Trio -ThirdPeerModule $ThirdPeer"
    ) | Set-Content (Join-Path $logDir "G2_GATE.txt") -Encoding UTF8
    Write-Host "G2 ENV_BLOCKED: establishment coverage incomplete for $($estFailures -join ',')"
    & (Join-Path $PSScriptRoot "meeting-product-ga-g2-real-mesh-field-adjudicate.ps1") -LogDir $logDir
    exit 1
}

@(
    "verdict=PREFLIGHT_PASS"
    "reason=same_real_underlay_3node"
    "underlayPrefix=$($prefixes[0])"
    "thirdPeer=$ThirdPeer"
    "thirdSerial=$thirdSerial"
    "establishmentCoverage=PASS"
) | Set-Content (Join-Path $logDir "G2_GATE.txt") -Encoding UTF8

Write-Host "G2 preflight PASS — 3 nodes on $($prefixes[0]).x third=$ThirdPeer ($thirdSerial) establishment=PASS; starting PaG2 product run"

$driverScript = Join-Path $PSScriptRoot "p1c-package-field-first-run.ps1"
$driverArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", $driverScript,
    "-DriverMode", "PaG2",
    "-M01Serial", $M01Serial,
    "-M02Serial", $M02Serial,
    "-M03Serial", $thirdSerial,
    "-PilotUseSec", "$PilotUseSec",
    # CREATE-time invite TTL ~30s; keep join window tight but not shorter than product expiry.
    "-InviteJoinWaitSec", "45",
    "-OperatorAssistedJoinWaitSec", "90",
    "-AllowOperatorAssistedM02Join"
)
if ($SkipBuildInstall) { $driverArgs += "-SkipBuildInstall" }
if ($SkipServiceColdStart) {
    $driverArgs += "-SkipServiceColdStart"
    # EP instrumented probe also tears down FG runtime; warm path must skip it.
    $driverArgs += "-SkipEpProbe"
}
if ($PreferHeadsetPlayback) { $driverArgs += "-PreferHeadsetPlayback" }

$driverExit = 0
try {
    & powershell @driverArgs
    $driverExit = $LASTEXITCODE
} catch {
    $driverExit = 1
    Write-Host "DRIVER_EXCEPTION: $($_.Exception.Message)"
} finally {
    $driverLog = Get-ChildItem -Path (Join-Path $repoRoot "talkback\logs") -Filter "ga-g2-real-mesh-field-*" -Directory |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($driverLog -and $driverLog.FullName -ne $logDir) {
        Copy-Item -Path (Join-Path $driverLog.FullName "*") -Destination $logDir -Recurse -Force -ErrorAction SilentlyContinue
        if (-not $LogDirOverride) {
            Copy-Item -Force (Join-Path $logDir "preflight.json") (Join-Path $driverLog.FullName "preflight.json") -ErrorAction SilentlyContinue
            Copy-Item -Force (Join-Path $logDir "G2_GATE.txt") (Join-Path $driverLog.FullName "G2_GATE.txt") -ErrorAction SilentlyContinue
            $logDir = $driverLog.FullName
        }
    }
}

if (-not $SkipFinalAdjudication) {
    & (Join-Path $PSScriptRoot "meeting-product-ga-g2-real-mesh-field-adjudicate.ps1") -LogDir $logDir
}
exit $LASTEXITCODE
