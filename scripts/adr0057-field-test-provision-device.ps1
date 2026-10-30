# ADR-0057 FTPH — run instrumented provisioning on one device (launcher only)
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$FtphOutPath = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "ep-field-test-instrumentation-transport.ps1")
$pkg = "com.talkback.appprod"
$testPkg = "com.talkback.appprod.test"
$class = "com.talkback.appprod.fieldtest.Adr0057FieldTestProvisioningInstrumentedTest#provisionTrustPreconditions"

Write-Host "FORCE-STOP $pkg on $Serial"
adb -s $Serial shell am force-stop $pkg | Out-Null

Write-Host "FTPH instrumented test..."
$instrumentOut = adb -s $Serial shell am instrument -w -r -e class $class "$testPkg/androidx.test.runner.AndroidJUnitRunner" 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Host $instrumentOut
    exit $LASTEXITCODE
}

Write-Host "`n--- transported FTPH_RESULT ---"
try {
    $transport = Get-FieldTestTransportFromInstrumentOutput `
        -InstrumentOutput $instrumentOut `
        -ExpectedKind "FTPH_RESULT"
    Write-Host $transport.PayloadUtf8
    if (-not [string]::IsNullOrWhiteSpace($FtphOutPath)) {
        $dir = Split-Path $FtphOutPath -Parent
        if ($dir -and -not (Test-Path $dir)) {
            New-Item -ItemType Directory -Force -Path $dir | Out-Null
        }
        [System.IO.File]::WriteAllText($FtphOutPath, $transport.PayloadUtf8 + "`n")
        Write-Host "Wrote $FtphOutPath"
    }
} catch {
    Write-Error "FTPH_RESULT transport missing: $($_.Exception.Message)"
}
