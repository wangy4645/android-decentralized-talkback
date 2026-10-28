# G-RES-3 E3b architecture remediation probe — nonblocking sendmmsg (MSG_DONTWAIT).
# Uses H1c short gate (90s) for tail/latency comparison vs E3 blocking baseline.
# NOT qualifying evidence. NOT Run #4.

param(
    [ValidateSet("M01", "M02", "M03")]
    [string]$Sender = "M03",
    [int]$ProbeIndex = 1,
    [string]$RunId = "",
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$SkipSinks
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "gres3-e3b-probe-$ProbeIndex-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

$h1cArgs = @{
    Sender = $Sender
    HarnessPhase = "H1c"
    ExecutionModel = "E3b"
    RunId = $RunId
}
if ($SkipBuild) { $h1cArgs.SkipBuild = $true }
if ($SkipInstall) { $h1cArgs.SkipInstall = $true }
if ($SkipSinks) { $h1cArgs.SkipSinks = $true }

& (Join-Path $PSScriptRoot "adr0060-gres3-h1c-qualification-run.ps1") @h1cArgs
if ($LASTEXITCODE -ne 0) { throw "E3b probe run failed" }

$logDir = Join-Path $repoRoot "logs/$RunId"
& (Join-Path $PSScriptRoot "adr0060-gres3-e3b-nonblocking-probe-adjudicate.ps1") -LogDir $logDir
exit $LASTEXITCODE
