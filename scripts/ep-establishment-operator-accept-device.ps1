# EP operator — push signed delivery + accept on one device (base64 instrumentation)
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^M\d{2}$')]
    [string]$ModuleLabel,
    [Parameter(Mandatory = $true)]
    [string]$BatchDir,
    [string]$PackageName = "com.talkback.appprod"
)

$ErrorActionPreference = "Stop"
$signed = Join-Path $BatchDir "establishment-signed-$ModuleLabel.bin"
if (-not (Test-Path $signed)) {
    Write-Error "missing signed delivery: $signed"
}

$b64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($signed))
Write-Host "Writing signed delivery via instrumentation ($Serial, $ModuleLabel, $($b64.Length) b64 chars)..."

adb -s $Serial shell am force-stop $PackageName
adb -s $Serial shell am instrument -w -r `
    -e signedDeliveryBase64 $b64 `
    -e class "com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#writeSignedEstablishmentDeliveryFromBase64" `
    "$PackageName.test/androidx.test.runner.AndroidJUnitRunner"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Accepting..."
adb -s $Serial shell am instrument -w -r `
    -e class "com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#acceptEstablishmentRevision" `
    "$PackageName.test/androidx.test.runner.AndroidJUnitRunner"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Accept complete ($Serial, $ModuleLabel)."
