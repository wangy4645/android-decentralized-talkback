# Slice 1 late-join field verify — START (M03 sender + M02 receiver).
# Filter: Talkback:I + MEETING_MEDIA_SHADOW:I
# Goal: M03 late join after session playout advanced → M02 audible
param(
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "MDX0220416001963",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = (Get-Command adb).Source

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "slice1-latejoin-field-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

$logDir = Join-Path $repoRoot "logs\$RunId"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Test-DeviceReady([string]$Serial) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $state = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
    $ErrorActionPreference = $prev
    return $state -eq "device"
}

function Start-ShadowLog([string]$Serial, [string]$Label) {
    & $adb -s $Serial logcat -c 2>&1 | Out-Null
    $out = Join-Path $logDir "$Label-logcat.txt"
    $args = @(
        "-s", $Serial, "logcat", "-v", "threadtime",
        "-s", "Talkback:I", "MEETING_MEDIA_SHADOW:I", "*:S"
    )
    $proc = Start-Process -FilePath $adb -ArgumentList $args `
        -RedirectStandardOutput $out -PassThru -NoNewWindow
    return @{
        Label = $Label
        Serial = $Serial
        Path = $out
        Process = $proc
    }
}

$handles = @()
foreach ($entry in @(
    @{ Label = "M02"; Serial = $M02Serial },
    @{ Label = "M03"; Serial = $M03Serial }
)) {
    if (-not (Test-DeviceReady $entry.Serial)) {
        Write-Host "PREFLIGHT_FAIL: $($entry.Label) offline serial=$($entry.Serial)"
        exit 2
    }
    $handles += Start-ShadowLog $entry.Serial $entry.Label
    Write-Host "logcat started $($entry.Label) serial=$($entry.Serial) -> $($handles[-1].Path)"
}

@(
    "run=slice1-latejoin-field"
    "started=$(Get-Date -Format o)"
    "capture=Talkback:I + MEETING_MEDIA_SHADOW:I"
    "devices=M02=$M02Serial,M03=$M03Serial"
    "scope=M01+M02 steady then M03 late join ~5-6s; M03 auto capture; M03->M02 audible"
    "pass=QUEUED + contribution>0 + M02 hears M03 (epoch2 before epoch3)"
    "logDir=$logDir"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

@{
    runId = $RunId
    logDir = $logDir
    handles = @($handles | ForEach-Object {
        @{
            label = $_.Label
            serial = $_.Serial
            path = $_.Path
            pid = $_.Process.Id
        }
    })
} | ConvertTo-Json | Set-Content (Join-Path $logDir "collector-state.json") -Encoding UTF8

Write-Host "RunId=$RunId"
Write-Host "LogDir=$logDir"
Write-Host ""
Write-Host "OPERATOR:"
Write-Host "  1. Meeting: M01+M02 join first, wait ~5-6s (playout advancing)"
Write-Host "  2. M03 join / cutover (late, independent seq)"
Write-Host "  3. M03 auto-capture / speak window"
Write-Host "  4. Confirm: M02 hears M03? (yes/no)"
Write-Host "  5. Stop: scripts/slice1-latejoin-field-stop.ps1 -RunId $RunId"
Write-Host ""
Write-Host "STOP if epoch3 rotation starts after audible verdict, or on persistent LATE."
exit 0
