# Late-peer phase 2: join M03 only
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
$devices = @{ M03 = "MDX0220416001963" }
$Package = "com.talkback.appprod"
$adb = (Get-Command adb).Source

$tJoin = Get-Date -Format o
Write-Host "Phase2 T_join_M03=$tJoin launch M03 only"
& $adb -s $devices.M03 shell am force-stop $Package
Start-Sleep -Milliseconds 300
& $adb -s $devices.M03 shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null

"t_join_m03=$tJoin" | Add-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8
Write-Host "Observe 120s then:"
Write-Host "  .\scripts\t9c-180-late-peer-field-stop-run.ps1 -LogDir `"$LogDir`""
