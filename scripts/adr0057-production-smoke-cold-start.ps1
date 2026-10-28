# P0.2 — sequential cold start M01 → M02 → M03 (Talk page default on launch)
param(
    [string]$LogDir = "",
    [int]$InterDeviceDelaySec = 3
)

$ErrorActionPreference = "Stop"
$devices = [ordered]@{
    "M01" = "HTUBB21B09220661"
    "M02" = "DSJ-2407041"
    "M03" = "MDX0220416001963"
}
$Package = "com.talkback.appprod"
$adb = (Get-Command adb).Source

$t0 = Get-Date -Format o
Write-Host "T0=$t0 sequential cold start M01->M02->M03"
foreach ($name in $devices.Keys) {
    $serial = $devices[$name]
    $state = & $adb -s $serial get-state 2>$null
    if ($state -ne "device") { throw "Device offline: $name ($serial)" }
}

foreach ($name in $devices.Keys) {
    $serial = $devices[$name]
    & $adb -s $serial shell am force-stop $Package | Out-Null
    Write-Host "force-stop $name"
}
Start-Sleep -Seconds 2

foreach ($name in $devices.Keys) {
    $serial = $devices[$name]
    & $adb -s $serial shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
    Write-Host "launch $name @ $(Get-Date -Format o)"
    if ($name -ne "M03") { Start-Sleep -Seconds $InterDeviceDelaySec }
}

if ($LogDir) {
    "t0_cold_start=$t0" | Add-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8
    "cold_start_order=M01,M02,M03" | Add-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8
}
Write-Host "Idle ~5 min on Talk page, then stop collectors."
