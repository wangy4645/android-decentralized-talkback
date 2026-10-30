# ADR-0058 — stop underlay multicast probe log collectors
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
$collectorsFile = Join-Path $LogDir "COLLECTORS.txt"
if (Test-Path $collectorsFile) {
    Get-Content $collectorsFile | ForEach-Object {
        if ($_ -match "collectorPid=(\d+)") {
            $collectorPid = [int]$Matches[1]
            $proc = Get-Process -Id $collectorPid -ErrorAction SilentlyContinue
            if ($proc) {
                Stop-Process -Id $collectorPid -Force
                Write-Host "Stopped collector pid=$collectorPid"
            }
        }
    }
}

Add-Content (Join-Path $LogDir "RUN_META.txt") -Value "stopped=$(Get-Date -Format o)"
Write-Host "Collectors stopped."
