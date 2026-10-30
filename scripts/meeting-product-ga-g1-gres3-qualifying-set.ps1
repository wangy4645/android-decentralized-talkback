# G1 Scale — run frozen G-RES-3 qualifying set (3× @ N=9). No benchmark redesign.
param(
    [int]$RunCount = 3,
    [ValidateSet("M01", "M02", "M03")]
    [string]$Sender = "M03",
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [string]$SetLogDir = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$talkbackRoot = Join-Path $repoRoot "talkback"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logDir = if ($SetLogDir) { $SetLogDir } else { Join-Path $talkbackRoot "logs\ga-g1-gres3-qualifying-set-$stamp" }
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

@(
    "gate=G1_SCALE"
    "contract=G-RES-3_C4_QUALIFYING"
    "target=3x_VALID_PASS_N9"
    "sender=$Sender"
    "runCount=$RunCount"
    "semantic=scale_capacity_only"
    "LogDir=$logDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

$qualScript = Join-Path $PSScriptRoot "adr0060-gres3-qualifying-run.ps1"
$skipBuildFlag = if ($SkipBuild) { "-SkipBuild" } else { "" }
$skipInstallFlag = if ($SkipInstall) { "-SkipInstall" } else { "" }

for ($i = 1; $i -le $RunCount; $i++) {
    $runSub = Join-Path $logDir ("run-{0:D2}" -f $i)
    New-Item -ItemType Directory -Force -Path $runSub | Out-Null
    Write-Host "=== G1 qualifying run $i/$RunCount ==="
    $runId = "gres3-qualifying-ga-g1-$stamp-r$i"
    $args = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", $qualScript,
        "-Sender", $Sender,
        "-RunId", $runId
    )
    if ($SkipBuild) { $args += "-SkipBuild" }
    if ($SkipInstall) { $args += "-SkipInstall" }
    if ($i -gt 1) {
        $args += "-SkipSinks"
    }
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & powershell @args 2>&1 | Tee-Object -FilePath (Join-Path $runSub "orchestrator.out.txt")
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $prevEap

    $srcLog = Join-Path $talkbackRoot "logs\$runId"
    if (Test-Path $srcLog) {
        Copy-Item -Path (Join-Path $srcLog "*") -Destination $runSub -Recurse -Force -ErrorAction SilentlyContinue
    }
    $verdict = switch ($exitCode) {
        0 { "PASS" }
        3 { "FAIL" }
        default { "INVALID" }
    }
    @(
        "runIndex=$i"
        "runId=$runId"
        "exitCode=$exitCode"
        "verdict=$verdict"
    ) | Set-Content (Join-Path $runSub "RUN_EXIT.txt") -Encoding UTF8

    if ($exitCode -eq 3) {
        Write-Host "G1 run $i VALID FAIL — continuing set collection per frozen rules"
    } elseif ($exitCode -ne 0) {
        Write-Host "G1 run $i INVALID (exit=$exitCode) — fix harness/env and rerun set"
    }
}

& (Join-Path $PSScriptRoot "meeting-product-ga-g1-gres3-set-adjudicate.ps1") -LogDir $logDir
exit $LASTEXITCODE
