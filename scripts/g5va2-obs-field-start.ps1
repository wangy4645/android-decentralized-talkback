# G5-VA2 observation capture — dual-mode VAD evidence on post-APM pcm960.
param(
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "MDX0220416001963",
    [string]$M04Serial = "192.168.31.206:5555",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = (Get-Command adb).Source

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "g5va2-obs-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

$logDir = Join-Path $repoRoot "logs\$RunId"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Start-ObsLog([string]$Serial, [string]$Label) {
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

if ($M04Serial -match '^\d+\.\d+\.\d+\.\d+:') {
    & $adb connect $M04Serial 2>&1 | Out-Null
}

$handles = @()
foreach ($entry in @(
    @{ Label = "M01"; Serial = $M01Serial },
    @{ Label = "M02"; Serial = $M02Serial },
    @{ Label = "M03"; Serial = $M03Serial },
    @{ Label = "M04"; Serial = $M04Serial }
)) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $state = (& $adb -s $entry.Serial get-state 2>&1 | Out-String).Trim()
    $ErrorActionPreference = $prev
    if ($state -ne "device") {
        Write-Warning "OFFLINE $($entry.Label) serial=$($entry.Serial)"
        continue
    }
    $handles += Start-ObsLog $entry.Serial $entry.Label
    Write-Host "logcat $($entry.Label) $($entry.Serial) -> $($handles[-1].Path)"
}

@(
    "run=$RunId"
    "started=$(Get-Date -Format o)"
    "capture=Talkback:I (G5_VA2_ADJ) + MEETING_MEDIA_SHADOW:I"
    "identity=M01=$M01Serial(USB),M02=$M02Serial(USB),M03=$M03Serial(USB),M04=$M04Serial(wireless)"
    "phaseA=M02 idle ambient 15s (no PTT on any device)"
    "phaseB=M03 PTT speech 15s only"
    "phaseC=all silent 10s"
) + ($handles | ForEach-Object { "logcat $($_.Label) pid=$($_.Process.Id) path=$($_.Path)" }) |
    Set-Content -Encoding utf8 (Join-Path $logDir "run-card.txt")

Write-Host "LOG_DIR=$logDir"
if ($handles.Count -gt 0) {
    Write-Host "Stop with: Get-Process -Id $($handles.Process.Id -join ',') | Stop-Process"
}
