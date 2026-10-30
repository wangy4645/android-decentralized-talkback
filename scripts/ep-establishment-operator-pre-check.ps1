# EP operator — PRE-1..PRE-5 hard gate before Meeting field / EP Field Run #2

param(
    [Parameter(Mandatory = $true)]
    [string]$BatchDir,
    [hashtable]$DeviceMap = @{},
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "MDX0220416001963",
    [string]$M04Serial = "",
    [string]$ThirdPeerSerial = "",
    [ValidateSet("M03", "M04")]
    [string]$ThirdPeerModule = "M03",
    [string]$HostModule = "M01",
    [switch]$Trio,
    [switch]$Quad,
    [string]$PackageName = "com.talkback.appprod"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "ep-field-test-instrumentation-transport.ps1")

$manifestPath = Join-Path $BatchDir "PROVISIONING_MANIFEST.txt"
if (-not (Test-Path $manifestPath)) {
    Write-Error "missing PROVISIONING_MANIFEST.txt in $BatchDir"
}

function Read-ManifestValue {
    param([string]$Key)
    $line = Select-String -Path $manifestPath -Pattern "^$Key=" | Select-Object -Last 1
    if (-not $line) { return $null }
    return ($line.Line -replace "^$Key=", "")
}

function Get-ManifestParticipantModules {
    $explicit = Read-ManifestValue "participant_modules"
    if (-not [string]::IsNullOrWhiteSpace($explicit)) {
        return $explicit.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    }
    $modules = @("M01", "M02")
    $found = Select-String -Path $manifestPath -Pattern '^(M\d{2})\.establishmentKeyVersion=' |
        ForEach-Object { $_.Matches[0].Groups[1].Value } | Sort-Object -Unique
    foreach ($candidate in $found) {
        if ($candidate -notin $modules) { $modules += $candidate }
    }
    return $modules
}

function Resolve-DeviceSerial {
    param([string]$ModuleLabel)
    if ($DeviceMap.Count -gt 0) {
        if ($DeviceMap.ContainsKey($ModuleLabel)) { return [string]$DeviceMap[$ModuleLabel] }
        return $null
    }
    switch ($ModuleLabel) {
        "M01" { return $M01Serial }
        "M02" { return $M02Serial }
        "M03" {
            if (-not [string]::IsNullOrWhiteSpace($M03Serial)) { return $M03Serial }
            if ($ThirdPeerModule -eq "M03" -and -not [string]::IsNullOrWhiteSpace($ThirdPeerSerial)) { return $ThirdPeerSerial }
            return $null
        }
        "M04" {
            if (-not [string]::IsNullOrWhiteSpace($M04Serial)) { return $M04Serial }
            if ($ThirdPeerModule -eq "M04" -and -not [string]::IsNullOrWhiteSpace($ThirdPeerSerial)) { return $ThirdPeerSerial }
            return $null
        }
        default { return $null }
    }
}

if ($DeviceMap.Count -gt 0) {
    if ($DeviceMap.ContainsKey("M01")) { $M01Serial = [string]$DeviceMap["M01"] }
    if ($DeviceMap.ContainsKey("M02")) { $M02Serial = [string]$DeviceMap["M02"] }
}

$topology = Read-ManifestValue "provisioning_topology"
$participantModules = @(Get-ManifestParticipantModules)
$checkQuad = $Quad -or ($topology -eq "quad")
$checkTrio = -not $checkQuad -and ($Trio -or ($topology -eq "trio"))

$m01Version = [long](Read-ManifestValue "M01.establishmentKeyVersion")
$m02Version = [long](Read-ManifestValue "M02.establishmentKeyVersion")
$m01Fp = Read-ManifestValue "M01.publicKeyFingerprint"
$m02Fp = Read-ManifestValue "M02.publicKeyFingerprint"

$results = @()
$fail = @()

$pre1Devices = @(
    @{ Serial = $M01Serial; Label = "M01" },
    @{ Serial = $M02Serial; Label = "M02" }
)
foreach ($moduleId in $participantModules) {
    if ($moduleId -in @("M01", "M02")) { continue }
    $serial = Resolve-DeviceSerial $moduleId
    if ([string]::IsNullOrWhiteSpace($serial)) {
        $results += "PRE-1 $moduleId serial=MISSING"
        $fail += "PRE-1_$moduleId"
        continue
    }
    $pre1Devices += @{ Serial = $serial; Label = $moduleId }
}

function Test-EstablishmentStorePresentViaRunAs {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial
    )
    $path = "files/gbc-trust/accepted-local-establishment-trust-state.bin"
    $out = adb -s $Serial shell "run-as com.talkback.appprod test -f $path && echo present || echo absent" 2>&1
    return ($out -match "present")
}

function Test-EstablishmentStorePresent {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,
        [Parameter(Mandatory = $true)]
        [string]$PackageName
    )
    adb -s $Serial shell am force-stop $PackageName | Out-Null
    $pre1Out = adb -s $Serial shell am instrument -w -r `
        -e class "com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#probeEstablishmentStorePresent" `
        "$PackageName.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) {
        try {
            $store = Test-FieldTestStoreSanityFromInstrumentOutput `
                -InstrumentOutput $pre1Out `
                -ExpectedArtifact "accepted-local-establishment-trust-state.bin"
            return $store.Present
        } catch {
            # Network-adb devices may omit instrumentation transport payloads.
        }
    }
    return Test-EstablishmentStorePresentViaRunAs -Serial $Serial
}

foreach ($pair in $pre1Devices) {
    $ok = Test-EstablishmentStorePresent -Serial $pair.Serial -PackageName $PackageName
    $results += "PRE-1 $($pair.Label) establishment_store=$ok"
    if (-not $ok) { $fail += "PRE-1_$($pair.Label)" }
}

$pre2Devices = @(
    @{ Serial = $M01Serial; Label = "M01"; Version = $m01Version; Fp = $m01Fp },
    @{ Serial = $M02Serial; Label = "M02"; Version = $m02Version; Fp = $m02Fp }
)
foreach ($moduleId in $participantModules) {
    if ($moduleId -in @("M01", "M02")) { continue }
    $serial = Resolve-DeviceSerial $moduleId
    $version = [long](Read-ManifestValue "$moduleId.establishmentKeyVersion")
    $fp = Read-ManifestValue "$moduleId.publicKeyFingerprint"
    if ([string]::IsNullOrWhiteSpace($serial)) { continue }
    $pre2Devices += @{ Serial = $serial; Label = $moduleId; Version = $version; Fp = $fp }
}

foreach ($pair in $pre2Devices) {
    adb -s $pair.Serial shell am force-stop $PackageName | Out-Null
    adb -s $pair.Serial shell am instrument -w -r -e establishmentKeyVersion $pair.Version -e expectedFingerprint $pair.Fp -e class "com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#verifyLocalKeystoreIdentity" "$PackageName.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | Out-Null
    $ok = $LASTEXITCODE -eq 0
    $results += "PRE-2 $($pair.Label) keystore=$ok version=$($pair.Version)"
    if (-not $ok) { $fail += "PRE-2_$($pair.Label)" }
}

$required = @(
    "deploymentTrustDomainId",
    "taskProfileRevision",
    "signerKeyVersion",
    "M01.establishmentKeyVersion",
    "M01.publicKeyFingerprint",
    "M02.establishmentKeyVersion",
    "M02.publicKeyFingerprint",
    "participant_modules"
)
foreach ($moduleId in $participantModules) {
    if ($moduleId -in @("M01", "M02")) { continue }
    $required += @("$moduleId.establishmentKeyVersion", "$moduleId.publicKeyFingerprint")
}
$pre3Ok = $true
foreach ($key in $required) {
    $val = Read-ManifestValue $key
    if ([string]::IsNullOrWhiteSpace($val)) { $pre3Ok = $false; $fail += "PRE-3_$key" }
}
$results += "PRE-3 manifest_complete=$pre3Ok topology=$topology participants=$($participantModules -join ',')"

$hostSerial = Resolve-DeviceSerial $HostModule
if ([string]::IsNullOrWhiteSpace($hostSerial)) {
    Write-Error "HostModule=$HostModule serial not configured"
}

$requiredHostLookups =
    $participantModules | Where-Object { $_ -ne $HostModule }

foreach ($peer in $requiredHostLookups) {
    $version = [long](Read-ManifestValue "$peer.establishmentKeyVersion")
    adb -s $hostSerial shell am force-stop $PackageName | Out-Null
    adb -s $hostSerial shell am instrument -w -r -e establishmentKeyVersion $version -e peerModuleId $peer -e class "com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#pre4RemoteLookup" "$PackageName.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | Out-Null
    $pre4Ok = $LASTEXITCODE -eq 0
    $results += "PRE-4 ${HostModule}_lookup_$peer=$pre4Ok version=$version"
    if (-not $pre4Ok) { $fail += "PRE-4_${HostModule}_$peer" }
}

$results += "PRE-5 required_host_lookups=$($requiredHostLookups -join ',')"
foreach ($peer in $requiredHostLookups) {
    $line = $results | Where-Object { $_ -like "PRE-4 ${HostModule}_lookup_${peer}=*" } | Select-Object -Last 1
    $present = $false
    if ($line -match '=True') { $present = $true }
    $requiredFlag = "YES"
    $results += "PRE-5 binding ${HostModule}->${peer} required=$requiredFlag present=$present"
    if (-not $present) { $fail += "PRE-5_${HostModule}_$peer" }
}

$nonHost = @($participantModules | Where-Object { $_ -ne $HostModule } | Sort-Object)
for ($i = 0; $i -lt $nonHost.Count; $i++) {
    for ($j = $i + 1; $j -lt $nonHost.Count; $j++) {
        $results += "PRE-5 binding $($nonHost[$i])->$($nonHost[$j]) required=NO present=N/A (non-host package origin)"
    }
}

$report = @()
$report += "EP OPERATOR PRE-CHECK"
$report += "batch_dir=$BatchDir"
$report += "host_module=$HostModule"
$report += "participant_modules=$($participantModules -join ',')"
$report += ""
$report += $results
$report += ""

if ($fail.Count -eq 0) {
    $report += "VERDICT=PASS"
    $report += "meeting_env_ready=YES"
    $report += "next=issue-a1-final-closure-field (ENV gate)"
} else {
    $report += "VERDICT=FAIL"
    $report += "meeting_env_ready=NO"
    $report += ("failed=" + ($fail -join ","))
    $report += "action=STOP — fix provisioning scope before field"
}

$outPath = Join-Path $BatchDir "PRE_CHECK.txt"
$report -join "`n" | Set-Content $outPath
Write-Host ($report -join "`n")
if ($fail.Count -gt 0) { exit 1 }
