# EP operator — Step 1/2: provision local Keystore identity + emit evidence on one device
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [Parameter(Mandatory = $true)]
    [long]$EstablishmentKeyVersion,
    [string]$BatchDir = "",
    [string]$ModuleLabel = "",
    [string]$PackageName = "com.talkback.appprod",
    [string]$TestClass = "com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#provisionLocalEstablishmentIdentity"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "ep-field-test-instrumentation-transport.ps1")

Write-Host "OP-PROVISION serial=$Serial establishmentKeyVersion=$EstablishmentKeyVersion"
adb -s $Serial shell am force-stop $PackageName | Out-Null

$instrumentOut = adb -s $Serial shell am instrument -w -r `
    -e establishmentKeyVersion $EstablishmentKeyVersion `
    -e class $TestClass `
    "$PackageName.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Host $instrumentOut
    exit $LASTEXITCODE
}

Write-Host "`n--- transported EP_OPERATOR_EVIDENCE ---"
try {
    $transport = Get-FieldTestTransportFromInstrumentOutput `
        -InstrumentOutput $instrumentOut `
        -ExpectedKind "EP_OPERATOR_EVIDENCE"
    Write-Host $transport.PayloadUtf8
} catch {
    Write-Error "EP_OPERATOR_EVIDENCE transport missing: $($_.Exception.Message)"
}

if (-not [string]::IsNullOrWhiteSpace($BatchDir) -and -not [string]::IsNullOrWhiteSpace($ModuleLabel)) {
    & (Join-Path $PSScriptRoot "ep-establishment-operator-pull-evidence.ps1") `
        -Serial $Serial `
        -ModuleLabel $ModuleLabel `
        -BatchDir $BatchDir `
        -InstrumentOutput $instrumentOut
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "`n--- EP_OP_PROBE (last 5) ---"
adb -s $Serial logcat -d -s Talkback:I | Select-String "EP_OP_PROBE" | Select-Object -Last 5

# Propagate instrument output for batch orchestrator
Write-Output $instrumentOut
