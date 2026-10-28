# Slice 1 late-join field verify — STOP collectors (manual adjudication after).
param(
    [Parameter(Mandatory = $true)]
    [string]$RunId
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$logDir = Join-Path $repoRoot "logs\$RunId"
$statePath = Join-Path $logDir "collector-state.json"

if (-not (Test-Path $statePath)) {
    Write-Error "Missing $statePath"
    exit 1
}

$state = Get-Content $statePath -Raw | ConvertFrom-Json
foreach ($h in $state.handles) {
    $procId = [int]$h.pid
    try {
        $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($p) {
            Stop-Process -Id $procId -Force
            Write-Host "stopped $($h.label) pid=$procId"
        } else {
            Write-Host "already stopped $($h.label) pid=$procId"
        }
    } catch {
        Write-Host "stop miss $($h.label) pid=$procId : $_"
    }
}

@(
    "stopped=$(Get-Date -Format o)"
    "note=manual adjudication required; do not use captureON-gated X7 adjudicator"
) | Add-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

Write-Host "LogDir=$logDir"
Write-Host "Next: manual adjudicate M03->M02 epoch2 LATE vs QUEUED + audible"
exit 0
