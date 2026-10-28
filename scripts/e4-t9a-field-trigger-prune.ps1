# E4 T9-A — force-stop M02, wait for M01 prune, restart M02
param(
    [string]$LogDir = "",
    [int]$PruneWaitSec = 38
)

$ErrorActionPreference = "Stop"
$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "2d73067a"
    "M03" = "MDX0220416001963"
}
$Package = "com.talkback.appprod"
$adb = (Get-Command adb).Source
$m02 = $devices["M02"]
$m01 = $devices["M01"]

$stamp = Get-Date -Format o
if ($LogDir) {
    "prune_trigger_started=$stamp" | Add-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8
}

Write-Host "[$stamp] force-stop M02 ($m02)"
& $adb -s $m02 shell am force-stop $Package

Write-Host "Waiting ${PruneWaitSec}s for M01 membership prune (EVICTED member M02)..."
Start-Sleep -Seconds $PruneWaitSec

Write-Host "Launch M02 Talkback"
& $adb -s $m02 shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null

$done = Get-Date -Format o
Write-Host "[$done] M02 restarted — watch M01 for E4 markers (60–120s)"
if ($LogDir) {
    "prune_trigger_done=$done waitSec=$PruneWaitSec" | Add-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8
}
