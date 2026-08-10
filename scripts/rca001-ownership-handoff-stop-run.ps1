# RCA-001 — stop collectors + dump
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "2d73067a"
    "M03" = "MDX0220416001963"
}
$adb = (Get-Command adb).Source
if (-not (Test-Path $LogDir)) { throw "LogDir not found: $LogDir" }

$pidsFile = Join-Path $LogDir "COLLECTOR_PIDS.txt"
if (Test-Path $pidsFile) {
    Get-Content $pidsFile | ForEach-Object {
        if ($_ -match "collectorPid=(\d+)") {
            Stop-Process -Id ([int]$Matches[1]) -Force -ErrorAction SilentlyContinue
            Write-Host "STOPPED $($Matches[1])"
        }
    }
}
foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    & $adb -s $serial logcat -d -v time -s Talkback:I *:S |
        Out-File (Join-Path $LogDir "$name-logcat-dump.txt") -Encoding utf8
}
"stopped=$(Get-Date -Format o)" | Add-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8
Write-Host "Done $LogDir"
