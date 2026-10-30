# EP operator — pull evidence from instrumentation transport into batch dir
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^M\d{2}$')]
    [string]$ModuleLabel,
    [Parameter(Mandatory = $true)]
    [string]$BatchDir,
    [Parameter(Mandatory = $true)]
    [string]$InstrumentOutput,
    [string]$PackageName = "com.talkback.appprod"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "ep-field-test-instrumentation-transport.ps1")

New-Item -ItemType Directory -Force -Path $BatchDir | Out-Null
$out = Join-Path $BatchDir "$ModuleLabel-evidence.txt"

Write-Host "Extract EP_OPERATOR_EVIDENCE transport ($Serial, $ModuleLabel)..."
$transport = Write-FieldTestTransportPayloadFile `
    -InstrumentOutput $InstrumentOutput `
    -ExpectedKind "EP_OPERATOR_EVIDENCE" `
    -OutPath $out

if ($transport.PayloadUtf8 -match "No such file|not debuggable|not found") {
    Write-Error "invalid transported evidence on $Serial ($ModuleLabel)"
}

Write-Host "Wrote $out (transportKind=$($transport.Kind), bytes=$([Text.Encoding]::UTF8.GetByteCount($transport.PayloadUtf8)))"
