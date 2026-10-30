# Case B minimal preflight harness (3 gates only)
# Docs: docs/analysis/l15-prr-case-b-field-preflight-v1.md
#
# Usage:
#   .\scripts\case-b-preflight.ps1 -LogDir .\logs\case-b-preflight-20260804-210000
#   .\scripts\case-b-preflight.ps1 -LogDir $logDir -EmitStimulusArmed -AttemptId witness-1
#
# Output: CASE_B_PREFLIGHT_RESULT.json in LogDir
# Does NOT predict qualification/repair/PRR. Does NOT authorize Attempt #7.

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,

    [string]$DutModule = "M03",
    [string]$DutSerial = "MDX0220416001963",
    [int]$ObserverWindowSec = 60,
    [int]$ObserverSampleSec = 10,
    [switch]$EmitStimulusArmed,
    [string]$AttemptId = "",
    [string]$Stimulus = "S1_LOCAL_QUALIFICATION_REPAIR"
)

$ErrorActionPreference = "Stop"

function Resolve-Adb {
    foreach ($c in @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "C:\Users\happy\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe",
        "adb"
    )) {
        if ($c -eq "adb") {
            $cmd = Get-Command adb -ErrorAction SilentlyContinue
            if ($cmd) { return $cmd.Source }
        }
        elseif (Test-Path $c) { return $c }
    }
    throw "adb not found"
}

function Test-GateObserver {
    param([string]$LogPath, [int]$WindowSec, [int]$SampleSec)

    if (-not (Test-Path $LogPath)) {
        return @{ pass = $false; reason = "DUT_OBSERVER_NOT_LIVE"; detail = "log missing: $LogPath" }
    }

    $startBytes = (Get-Item $LogPath).Length
    $samples = [math]::Max(1, [math]::Floor($WindowSec / $SampleSec))
    $deltas = @()

    for ($i = 0; $i -lt $samples; $i++) {
        Start-Sleep -Seconds $SampleSec
        if (-not (Test-Path $LogPath)) {
            return @{ pass = $false; reason = "DUT_OBSERVER_NOT_LIVE"; detail = "log disappeared during window" }
        }
        $nowBytes = (Get-Item $LogPath).Length
        $deltas += ($nowBytes - $startBytes)
        $startBytes = $nowBytes
    }

    $growing = @($deltas | Where-Object { $_ -gt 0 }).Count
    if ($growing -lt ($samples - 1)) {
        return @{
            pass = $false
            reason = "DUT_OBSERVER_NOT_LIVE"
            detail = "byte growth in window insufficient; deltas=$($deltas -join ',')"
        }
    }

    return @{ pass = $true; reason = ""; detail = "observer growth OK; deltas=$($deltas -join ',')" }
}

function Test-GateBaseline {
    param([string]$LogPath)

    if (-not (Test-Path $LogPath)) {
        return @{ pass = $false; reason = "DUT_BASELINE_NOT_MET"; detail = "log missing" }
    }

    $tail = Get-Content $LogPath -Tail 400 -Encoding UTF8 -ErrorAction SilentlyContinue
    if (-not $tail) {
        return @{ pass = $false; reason = "DUT_BASELINE_NOT_MET"; detail = "log empty" }
    }

    $text = $tail -join "`n"
    $failures = @()

    if ($text -match "NETWORK_LOST") { $failures += "NETWORK_LOST present" }
    if ($text -match "recover_send") { $failures += "recovery active (recover_send)" }
    if ($text -notmatch "BIDIRECTIONAL_READY") { $failures += "no recent BIDIRECTIONAL_READY" }
    if ($text -notmatch "members=M02,M03") { $failures += "topology not M02,M03 in recent tail" }
    if ($text -match "members=M01") { $failures += "M01 still in members" }

    if ($failures.Count -gt 0) {
        return @{
            pass = $false
            reason = "DUT_BASELINE_NOT_MET"
            detail = ($failures -join "; ")
        }
    }

    return @{ pass = $true; reason = ""; detail = "qualified topology baseline OK" }
}

function Test-GateStimulusArmed {
    param([string]$LogPath)

    if (-not (Test-Path $LogPath)) {
        return @{ pass = $false; reason = "STIMULUS_NOT_ARMED"; detail = "log missing" }
    }

    $armed = Select-String -Path $LogPath -Pattern "CASE_B_STIMULUS_ARMED|CASE_B_ARMED" -Quiet
    if (-not $armed) {
        return @{ pass = $false; reason = "STIMULUS_NOT_ARMED"; detail = "no CASE_B_STIMULUS_ARMED marker" }
    }

    return @{ pass = $true; reason = ""; detail = "stimulus armed marker present" }
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$adb = Resolve-Adb
$dutLog = Join-Path $LogDir "$DutModule-talkback.log"

if ($EmitStimulusArmed) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    if (-not $AttemptId) { $AttemptId = "preflight-$stamp" }
    $marker = "CASE_B_STIMULUS_ARMED attemptId=$AttemptId stimulus=$Stimulus stamp=$stamp module=$DutModule"
    & $adb -s $DutSerial shell "log -t Talkback -p i `"$marker`"" | Out-Null
    Add-Content -Path $dutLog -Encoding utf8 -Value "=== $marker (harness) ==="
}

$observer = Test-GateObserver -LogPath $dutLog -WindowSec $ObserverWindowSec -SampleSec $ObserverSampleSec
$baseline = Test-GateBaseline -LogPath $dutLog
$stimulus = Test-GateStimulusArmed -LogPath $dutLog

$eligible = $observer.pass -and $baseline.pass -and $stimulus.pass
$outcome = if ($eligible) { "CASE_B_PREFLIGHT_PASS" } else { "PRE_ATTEMPT_ABORT" }

$result = [ordered]@{
    schema = "CASE_B_PREFLIGHT_RESULT/v1"
    evaluatedAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ss")
    logDir = (Resolve-Path $LogDir).Path
    dutModule = $DutModule
    dutSerial = $DutSerial
    stimulus = $Stimulus
    observer = if ($observer.pass) { "PASS" } else { "FAIL" }
    baseline = if ($baseline.pass) { "PASS" } else { "FAIL" }
    stimulusArmed = if ($stimulus.pass) { "PASS" } else { "FAIL" }
    eligible = $eligible
    outcome = $outcome
    gates = @{
        observer = $observer
        baseline = $baseline
        stimulus = $stimulus
    }
}

$jsonPath = Join-Path $LogDir "CASE_B_PREFLIGHT_RESULT.json"
$result | ConvertTo-Json -Depth 6 | Set-Content -Path $jsonPath -Encoding utf8

Write-Host "CASE_B_PREFLIGHT_RESULT=$outcome"
Write-Host "eligible=$eligible"
Write-Host "json=$jsonPath"
if (-not $eligible) {
    foreach ($name in @("observer", "baseline", "stimulus")) {
        $g = $result.gates[$name]
        if (-not $g.pass) {
            Write-Host "FAIL $name`: $($g.reason)   $($g.detail)"
        }
    }
    exit 2
}
exit 0
