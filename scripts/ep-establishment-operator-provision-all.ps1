# EP operator — full provisioning orchestrator (Steps 1-4 + PRE), N devices
#
# Usage:
#   .\ep-establishment-operator-provision-all.ps1 `
#       -DeviceMap @{ M01='192.168.31.46:5555'; M02='192.168.31.115:5555'; M05='3L1G1EE75NNDF8S5' } `
#       -TaskProfileRevision 30
#
# Legacy -M0xSerial / -Trio / -Quad parameters are still accepted and folded into DeviceMap.
param(
    [long]$EstablishmentKeyVersion = 1,
    [long]$TaskProfileRevision = 24,
    [hashtable]$DeviceMap = @{},
    [string]$M01Serial = "",
    [string]$M02Serial = "",
    [string]$M03Serial = "",
    [string]$M04Serial = "",
    [ValidateSet("M03", "M04")]
    [string]$ThirdPeerModule = "M03",
    [switch]$Trio,
    [switch]$Quad,
    [string]$HostModule = "M01"
)

$ErrorActionPreference = "Stop"
$scripts = $PSScriptRoot
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$repoRoot = Split-Path (Split-Path $scripts -Parent) -Parent
$batchDir = Join-Path $repoRoot "talkback\logs\ep-establishment-operator-$stamp"
New-Item -ItemType Directory -Force -Path $batchDir | Out-Null

# --- Build participant map (label -> serial) -------------------------------------------------
$devices = [ordered]@{}
if ($DeviceMap.Count -gt 0) {
    foreach ($label in ($DeviceMap.Keys | Sort-Object)) {
        if ($label -notmatch '^M\d{2}$') { Write-Error "DeviceMap key '$label' must look like M01" }
        $serial = [string]$DeviceMap[$label]
        if ([string]::IsNullOrWhiteSpace($serial)) { Write-Error "DeviceMap[$label] serial empty" }
        $devices[$label] = $serial
    }
} else {
    # Legacy parameter folding
    if ([string]::IsNullOrWhiteSpace($M01Serial)) { $M01Serial = "HTUBB21B09220661" }
    if ([string]::IsNullOrWhiteSpace($M02Serial)) { $M02Serial = "DSJ-2407041" }
    $devices["M01"] = $M01Serial
    $devices["M02"] = $M02Serial
    if ($Quad) {
        if ([string]::IsNullOrWhiteSpace($M03Serial) -or [string]::IsNullOrWhiteSpace($M04Serial)) {
            Write-Error "Quad provisioning requires -M03Serial and -M04Serial"
        }
        $devices["M03"] = $M03Serial
        $devices["M04"] = $M04Serial
    } else {
        $thirdSerial = if ($ThirdPeerModule -eq "M03") { $M03Serial } else { $M04Serial }
        if ($Trio -or -not [string]::IsNullOrWhiteSpace($thirdSerial)) {
            if ([string]::IsNullOrWhiteSpace($thirdSerial)) {
                Write-Error "Trio provisioning requires serial for ThirdPeerModule=$ThirdPeerModule"
            }
            $devices[$ThirdPeerModule] = $thirdSerial
        }
    }
}

$participantModules = @($devices.Keys)
if ($participantModules -notcontains "M01" -or $participantModules -notcontains "M02") {
    Write-Error "Participant set must include M01 and M02 (authority harness anchors on them). Got: $($participantModules -join ',')"
}
if ($participantModules -notcontains $HostModule) {
    Write-Error "HostModule=$HostModule not in participant set"
}
$n = $participantModules.Count
if ($TaskProfileRevision -le ($n - 1)) {
    Write-Error "$n-member provisioning requires -TaskProfileRevision > $($n - 1) (current=$TaskProfileRevision)"
}

Write-Host "Batch directory: $batchDir"
Write-Host "Operator-assigned Keystore version: $EstablishmentKeyVersion"
Write-Host "Task profile revision: $TaskProfileRevision"
Write-Host "Participants ($n):"
foreach ($label in $participantModules) { Write-Host "  $label = $($devices[$label])" }
Write-Host ""

# --- Step 1/2: Keystore identity + evidence on every device ------------------------------------
$step = 1
foreach ($label in $participantModules) {
    Write-Host "`n=== Step $step`: $label Keystore + evidence ($($devices[$label])) ==="
    & (Join-Path $scripts "ep-establishment-operator-provision-device.ps1") `
        -Serial $devices[$label] `
        -EstablishmentKeyVersion $EstablishmentKeyVersion `
        -BatchDir $batchDir `
        -ModuleLabel $label | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Error "STOP at Step $step ($label provision)"; exit 1 }
    $evidence = Join-Path $batchDir "$label-evidence.txt"
    if (-not (Test-Path $evidence)) { Write-Error "STOP at Step $step ($label evidence missing)"; exit 1 }
    $deviceModuleId = (Select-String -Path $evidence -Pattern '^moduleId=' | Select-Object -First 1).Line -replace '^moduleId=', ''
    if ($deviceModuleId -ne $label) {
        Write-Error "STOP at Step $step`: device $($devices[$label]) reports moduleId=$deviceModuleId but was labelled $label"
        exit 1
    }
    $step++
}

# --- Step 3: authority batch sign -------------------------------------------------------------
Write-Host "`n=== Step 3: Authority batch sign ($n members) ==="
& (Join-Path $scripts "ep-establishment-operator-authority-batch.ps1") -BatchDir $batchDir -TaskProfileRevision $TaskProfileRevision
if ($LASTEXITCODE -ne 0) { Write-Error "STOP at Step 3 (authority batch)"; exit 1 }
foreach ($label in $participantModules) {
    if (-not (Test-Path (Join-Path $batchDir "establishment-signed-$label.bin"))) {
        Write-Error "STOP at Step 3: signed delivery for $label missing"; exit 1
    }
}

# --- Step 3x: PAP accept on every device ------------------------------------------------------
foreach ($label in $participantModules) {
    Write-Host "`n=== Step 3-accept: $label ($($devices[$label])) ==="
    & (Join-Path $scripts "ep-establishment-operator-accept-device.ps1") -Serial $devices[$label] -ModuleLabel $label -BatchDir $batchDir
    if ($LASTEXITCODE -ne 0) { Write-Error "STOP at Step 3-accept ($label)"; exit 1 }
}

# --- Step 4: PRE-1..PRE-5 ---------------------------------------------------------------------
Write-Host "`n=== Step 4: PRE-1..PRE-5 ==="
& (Join-Path $scripts "ep-establishment-operator-pre-check.ps1") -BatchDir $batchDir -DeviceMap $devices -HostModule $HostModule
if ($LASTEXITCODE -ne 0) { Write-Error "STOP at Step 4 (PRE check)"; exit 1 }

Write-Host "`nOperator provisioning COMPLETE."
Write-Host "Meeting ENV gate: run ep-establishment-meeting-env-ready.ps1 -BatchDir $batchDir before field."
Write-Host "$HostModule must Found-lookup $(($participantModules | Where-Object { $_ -ne $HostModule }) -join ',') (see PRE_CHECK.txt PRE-5)."
