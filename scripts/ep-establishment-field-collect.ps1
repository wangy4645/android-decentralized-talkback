# EP1-EP5 establishment-only field gate — log collect + probe runner
param(
    [Parameter(Mandatory = $true)]
    [long]$EstablishmentKeyVersion,
    [string]$Ep3Mode = "skip",
    [string]$PackageName = "com.talkback.appprod",
    [string]$TestClass = "com.talkback.appprod.fieldtest.EpEstablishmentFieldGateInstrumentedTest",
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041"
)

$ErrorActionPreference = "Stop"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$logDir = Join-Path $repoRoot "talkback\logs\ep-establishment-field-$stamp"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Start-DeviceLog {
    param([string]$Serial, [string]$Label)
    $out = Join-Path $logDir "$Label-logcat.txt"
    Start-Process -FilePath "adb" -ArgumentList @("-s", $Serial, "logcat", "-c") -NoNewWindow -Wait
    $proc = Start-Process -FilePath "adb" -ArgumentList @("-s", $Serial, "logcat", "-s", "Talkback:I", "*:S") -RedirectStandardOutput $out -PassThru -NoNewWindow
    return @{ Process = $proc; Path = $out }
}

function Run-Probe {
    param([string]$Serial, [string]$PeerModuleId)
    adb -s $Serial shell am force-stop $PackageName
    Start-Sleep -Seconds 1
    adb -s $Serial shell am start -n "$PackageName/.MainActivity" | Out-Null
    Start-Sleep -Seconds 3
    adb -s $Serial shell am instrument -w -r `
        -e class "$TestClass#emitEpEstablishmentFieldProbes" `
        -e establishmentKeyVersion $EstablishmentKeyVersion `
        -e peerModuleId $PeerModuleId `
        -e ep3Mode $Ep3Mode `
        "$PackageName.test/androidx.test.runner.AndroidJUnitRunner"
}

Write-Host "Log directory: $logDir"
$m01Log = Start-DeviceLog -Serial $M01Serial -Label "M01"
$m02Log = Start-DeviceLog -Serial $M02Serial -Label "M02"

@"
run_stamp=$stamp
establishmentKeyVersion=$EstablishmentKeyVersion
ep3Mode=$Ep3Mode
m01_serial=$M01Serial
m02_serial=$M02Serial
"@ | Set-Content (Join-Path $logDir "RUN_META.txt")

Write-Host "Running EP probe on M01..."
Run-Probe -Serial $M01Serial -PeerModuleId "M02"
Start-Sleep -Seconds 2
Write-Host "Running EP probe on M02..."
Run-Probe -Serial $M02Serial -PeerModuleId "M01"
Start-Sleep -Seconds 2

Stop-Process -Id $m01Log.Process.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $m02Log.Process.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

Write-Host "Done. Adjudicate with:"
Write-Host "  .\talkback\scripts\ep-establishment-field-adjudicate.ps1 -LogDir `"$logDir`""
