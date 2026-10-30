# X7 data-plane observation — START (M03 sender + M02 receiver only).
# Filter: Talkback:I + MEETING_MEDIA_SHADOW:I
param(
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "MDX0220416001963",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = (Get-Command adb).Source

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "x7-dataplane-obs-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
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
$preflight = @()
foreach ($entry in @(
    @{ Label = "M02"; Serial = $M02Serial },
    @{ Label = "M03"; Serial = $M03Serial }
)) {
    $ok = Test-DeviceReady $entry.Serial
    $preflight += [pscustomobject]@{
        Device = $entry.Label
        Serial = $entry.Serial
        Online = $ok
    }
    if ($ok) {
        $handles += Start-ShadowLog $entry.Serial $entry.Label
        Write-Host "logcat started $($entry.Label) serial=$($entry.Serial) -> $($handles[-1].Path)"
    } else {
        Write-Warning "OFFLINE $($entry.Label) serial=$($entry.Serial) — collector NOT started"
    }
}

@(
    "run=x7-dataplane-obs"
    "started=$(Get-Date -Format o)"
    "capture=Talkback:I + MEETING_MEDIA_SHADOW:I"
    "devices=M02=$M02Serial,M03=$M03Serial"
    "scope=M03->M02 single PTT 3-5s"
    "logDir=$logDir"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

$preflight | ConvertTo-Json | Set-Content (Join-Path $logDir "preflight.json") -Encoding UTF8

$statePath = Join-Path $logDir "collector-state.json"
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
} | ConvertTo-Json | Set-Content $statePath -Encoding UTF8

Write-Host "RunId=$RunId"
Write-Host "LogDir=$logDir"
Write-Host "Operator: Meeting M01+M02+M03 joined -> M03 PTT 3-5s -> stop script"
Write-Host "Stop: scripts/x7-dataplane-obs-stop-adjudicate.ps1 -RunId $RunId"

if (-not (Test-DeviceReady $M03Serial)) {
    Write-Host "PREFLIGHT_FAIL: M03 offline — connect M03 then re-run start OR run start again after USB attach"
    exit 2
}
if (-not (Test-DeviceReady $M02Serial)) {
    Write-Host "PREFLIGHT_FAIL: M02 offline"
    exit 2
}
exit 0
