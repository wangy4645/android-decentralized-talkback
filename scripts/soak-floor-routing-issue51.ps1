# Issue #51 — Floor routing evidence loop (P0)
# Usage:
#   1. Install diagnostic APK on M01/M02/M03
#   2. .\scripts\soak-floor-routing-issue51.ps1 -ClearOnly
#   3. Repro: M03 Group PTT (before Meeting if possible)
#   4. .\scripts\soak-floor-routing-issue51.ps1 -CollectOnly

param(
    [switch]$ClearOnly,
    [switch]$CollectOnly,
    [string]$OutDir = ""
)

$devices = @{
    M01 = "HTUBB21B09220661"
    M02 = "2d73067a"
    M03 = "MDX0220416001963"
}

$filter = "FLOOR_REQUEST_CALLSITE|FLOOR_REQUEST_SEND|FLOOR_REQUEST_RECV|PEER_UNREACHABLE|REQUEST_OBSERVED|FLOOR_DROP|PTT_DOWN|PTT_UP|IDENTITY_REBOUND|MEETING_RECOVERY|CHANNEL_MODE"

if (-not $OutDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path "logs-issue51-$stamp"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

foreach ($name in $devices.Keys) {
    $id = $devices[$name]
    if ($ClearOnly -or -not $CollectOnly) {
        Write-Host "[$name] logcat -c"
        adb -s $id logcat -c 2>&1 | Out-Null
    }
}

if ($ClearOnly) {
    Write-Host "Cleared. Repro now, then run with -CollectOnly"
    exit 0
}

foreach ($name in $devices.Keys) {
    $id = $devices[$name]
    $out = Join-Path $OutDir "$name.txt"
    Write-Host "[$name] collecting -> $out"
    adb -s $id logcat -d -v time -s Talkback:I 2>&1 |
        Select-String -Pattern $filter |
        Set-Content -Path $out -Encoding utf8
    $lines = (Get-Content $out -ErrorAction SilentlyContinue | Measure-Object -Line).Lines
    Write-Host "  $lines lines"
}

Write-Host ""
Write-Host "OUT=$OutDir"
Write-Host "Check:"
Write-Host "  M03: FLOOR_REQUEST_CALLSITE sendTarget= / FLOOR_REQUEST_SEND"
Write-Host "  M01: FLOOR_REQUEST_RECV / REQUEST_OBSERVED from=M03"
Write-Host "  M02: FLOOR_REQUEST_RECV vs FLOOR_DROP NOT_AUTHORITY"
