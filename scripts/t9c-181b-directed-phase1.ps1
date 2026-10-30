# #181-B directed — phase1 M01+M02 stable base
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [int]$SettleSeconds = 60
)

$ErrorActionPreference = "Stop"
$devices = @{ M01 = "HTUBB21B09220661"; M02 = "2d73067a"; M03 = "MDX0220416001963" }
$Package = "com.talkback.appprod"
$adb = (Get-Command adb).Source

$t0 = Get-Date -Format o
Write-Host "Phase1 T0=$t0 force-stop all, start M01+M02"
foreach ($name in @("M01", "M02", "M03")) {
    & $adb -s $devices[$name] shell am force-stop $Package
}
Start-Sleep -Milliseconds 500
foreach ($name in @("M01", "M02")) {
    & $adb -s $devices[$name] shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
    Write-Host "  launch $name"
}
"phase1_t0=$t0" | Add-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8
Write-Host "Settling ${SettleSeconds}s..."
Start-Sleep -Seconds $SettleSeconds
Write-Host "Phase1 done"
