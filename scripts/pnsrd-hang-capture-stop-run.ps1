# Stop pnsrd hang capture logcat collectors (after on-hang capture saved)

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Continue"
$pidsFile = Join-Path $LogDir "COLLECTOR_PIDS.txt"
if (-not (Test-Path $pidsFile)) {
    Write-Warning "No COLLECTOR_PIDS.txt in $LogDir"
    exit 0
}
Get-Content $pidsFile | ForEach-Object {
    if ($_ -match "collectorPid=(\d+)") {
        $procId = [int]$Matches[1]
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($proc) {
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            Write-Host "Stopped collector pid=$procId"
        }
    }
}
Add-Content (Join-Path $LogDir "CAPTURE_README.txt") "`nstopped=$(Get-Date -Format o)"
Write-Host "Collectors stopped. LogDir=$LogDir"
