# T9/C — force-stop all three, relaunch within 2s (T0)
param(
    [string]$LogDir = ""
)

$ErrorActionPreference = "Stop"
$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "2d73067a"
    "M03" = "MDX0220416001963"
}
$Package = "com.talkback.appprod"
$adb = (Get-Command adb).Source

$t0 = Get-Date -Format o
Write-Host "T0=$t0 force-stop all"
foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    & $adb -s $serial shell am force-stop $Package
    Write-Host "  force-stop $name"
}

Start-Sleep -Milliseconds 500

Write-Host "Launch all (±2s window)"
foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    & $adb -s $serial shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
    Write-Host "  launch $name"
}

if ($LogDir) {
    "t0_cold_start=$t0" | Add-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8
}
Write-Host ""
Write-Host "Observe 120s. Then stop:"
Write-Host "  .\scripts\t9c-180-field-stop-run.ps1 -LogDir `"$LogDir`""
