# GA Evidence Set — launch G1/G2/G3 in parallel to independent field verdicts.
param(
    [switch]$SkipG1,
    [switch]$SkipG2,
    [switch]$SkipG3,
    [ValidateSet("G3a", "G3b")]
    [string]$G3Tier = "G3a",
    [int]$G3SoakSec = 0,
    [switch]$SkipBuildInstall = $true
)
if ($G3SoakSec -le 0) {
    $G3SoakSec = if ($G3Tier -eq "G3b") { 7200 } else { 900 }
}

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$talkbackRoot = Join-Path $repoRoot "talkback"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logDir = Join-Path $talkbackRoot "logs\ga-evidence-set-$stamp"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

@(
    "orchestrator=GA_EVIDENCE_SET"
    "G1=G-RES-3_3x_N9"
    "G2=REAL_MESH_3NODE_PRODUCT"
    "G3=$G3Tier`_PRODUCTION_SOAK_${G3SoakSec}s"
    "G3_blocksGa=$(if ($G3Tier -eq 'G3a') { 'true' } else { 'false' })"
    "parallel=true"
    "semantic_independence=true"
    "invalid_rule=fix_harness_rerun"
    "valid_fail_rule=RCA_per_gate"
    "LogDir=$logDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

$jobs = @()
$scriptRoot = $PSScriptRoot

if (-not $SkipG1) {
    $g1Dir = Join-Path $logDir "G1"
    New-Item -ItemType Directory -Force -Path $g1Dir | Out-Null
    $jobs += Start-Job -Name "G1" -ScriptBlock {
        param($root, $out)
        Set-Location (Split-Path (Split-Path $root -Parent) -Parent)
        $args = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $root "meeting-product-ga-g1-gres3-qualifying-set.ps1"), "-SetLogDir", $out, "-SkipBuild", "-SkipInstall")
        & powershell @args
        exit $LASTEXITCODE
    } -ArgumentList $scriptRoot, $g1Dir
}

if (-not $SkipG2) {
    $g2Dir = Join-Path $logDir "G2"
    New-Item -ItemType Directory -Force -Path $g2Dir | Out-Null
    $jobs += Start-Job -Name "G2" -ScriptBlock {
        param($root, $out, $skipBi)
        Set-Location (Split-Path (Split-Path $root -Parent) -Parent)
        $args = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $root "meeting-product-ga-g2-real-mesh-field-first-run.ps1"))
        if ($skipBi) { $args += "-SkipBuildInstall" }
        & powershell @args
        $result = Get-ChildItem -Path (Join-Path (Split-Path (Split-Path $root -Parent) -Parent) "talkback\logs") -Filter "ga-g2-real-mesh-field-*" -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($result) { Copy-Item -Path (Join-Path $result.FullName "*") -Destination $out -Recurse -Force -ErrorAction SilentlyContinue }
        exit $LASTEXITCODE
    } -ArgumentList $scriptRoot, $g2Dir, $SkipBuildInstall.IsPresent
}

if (-not $SkipG3) {
    $g3Dir = Join-Path $logDir "G3"
    New-Item -ItemType Directory -Force -Path $g3Dir | Out-Null
    $jobs += Start-Job -Name "G3" -ScriptBlock {
        param($root, $out, $tier, $soak, $skipBi)
        Set-Location (Split-Path (Split-Path $root -Parent) -Parent)
        $args = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $root "meeting-product-ga-g3-stability-field-first-run.ps1"), "-Tier", $tier, "-SoakSec", "$soak")
        if ($skipBi) { $args += "-SkipBuildInstall" }
        & powershell @args
        $result = Get-ChildItem -Path (Join-Path (Split-Path (Split-Path $root -Parent) -Parent) "talkback\logs") -Filter "ga-g3*-stability-field-*" -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($result) { Copy-Item -Path (Join-Path $result.FullName "*") -Destination $out -Recurse -Force -ErrorAction SilentlyContinue }
        exit $LASTEXITCODE
    } -ArgumentList $scriptRoot, $g3Dir, $G3Tier, $G3SoakSec, $SkipBuildInstall.IsPresent
}

Write-Host "GA orchestrator: waiting for $($jobs.Count) parallel gate job(s)..."
foreach ($job in $jobs) {
    Wait-Job $job | Out-Null
    $state = Receive-Job $job
    $code = if ($null -ne $job.ChildJobs[0].JobStateInfo.Reason) { 2 } else { 0 }
    try {
        $jobOutput = Receive-Job $job -ErrorAction SilentlyContinue
        if ($jobOutput) { $jobOutput | Out-String | Write-Host }
    } catch {}
    Write-Host "$($job.Name) completed"
    Remove-Job $job -Force
}

# Copy gate results to aggregate dir
$g1Result = Join-Path $logDir "G1\G1_RESULT.txt"
$g2Result = Join-Path $logDir "G2\G2_RESULT.txt"
$g3Result = Join-Path $logDir "G3\G3_RESULT.txt"
foreach ($pair in @(
    @{ Src = $g1Result; Dst = (Join-Path $logDir "G1_RESULT.txt") }
    @{ Src = $g2Result; Dst = (Join-Path $logDir "G2_RESULT.txt") }
    @{ Src = $g3Result; Dst = (Join-Path $logDir "G3_RESULT.txt") }
)) {
    if (Test-Path $pair.Src) { Copy-Item -Force $pair.Src $pair.Dst }
}

& (Join-Path $PSScriptRoot "meeting-product-ga-evidence-set-adjudicate.ps1") -LogDir $logDir
Write-Host "GA_EVIDENCE_SET LogDir=$logDir"
exit $LASTEXITCODE
