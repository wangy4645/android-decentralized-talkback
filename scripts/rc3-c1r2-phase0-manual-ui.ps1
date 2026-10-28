# RC-3 C1R2 Phase 0 only: M01 CREATE Meeting, M02 join as participant. NO End.
param(
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$PackageName = "com.talkback.appprod",
    [string]$Activity = "com.talkback.appprod/.ui.MainActivity",
    [int]$InviteJoinWaitSec = 45
)

$ErrorActionPreference = "Continue"
$adb = (Get-Command adb).Source

function Tap-ByResourceId([string]$Serial, [string]$ResourceId) {
    $dumpPath = "/sdcard/talkback-ui-dump.xml"
    & $adb -s $Serial shell uiautomator dump $dumpPath 2>&1 | Out-Null
    Start-Sleep -Milliseconds 500
    $localDump = Join-Path $env:TEMP "c1r2-ui-$($Serial.Replace(':','_')).xml"
    & $adb -s $Serial pull $dumpPath $localDump 2>&1 | Out-Null
    if (-not (Test-Path $localDump)) { throw "UI dump missing for $Serial" }
    $xml = Get-Content $localDump -Raw
    $pattern = "resource-id=`"$ResourceId`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`""
    if ($xml -notmatch $pattern) { throw "Resource not found on ${Serial}: $ResourceId" }
    $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    & $adb -s $Serial shell input tap $cx $cy 2>&1 | Out-Null
    Write-Host "TAP $Serial $ResourceId @ ($cx,$cy)"
}

function Tap-IfPresent([string]$Serial, [string]$ResourceId) {
    try { Tap-ByResourceId $Serial $ResourceId; return $true } catch { return $false }
}

function Find-ResourcePresent([string]$Serial, [string]$ResourceId) {
    $dumpPath = "/sdcard/talkback-ui-dump.xml"
    & $adb -s $Serial shell uiautomator dump $dumpPath 2>&1 | Out-Null
    Start-Sleep -Milliseconds 400
    $localDump = Join-Path $env:TEMP "c1r2-check-$($Serial.Replace(':','_')).xml"
    & $adb -s $Serial pull $dumpPath $localDump 2>&1 | Out-Null
    if (-not (Test-Path $localDump)) { return $false }
    $xml = Get-Content $localDump -Raw
    return ($xml -match "resource-id=`"$ResourceId`"")
}

function Foreground-App([string]$Serial) {
    & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
    & $adb -s $Serial shell wm dismiss-keyguard 2>&1 | Out-Null
    & $adb -s $Serial shell am start -n $Activity 2>&1 | Out-Null
    Start-Sleep -Seconds 1
}

Write-Host "=== C1R2 Phase 0 UI: M01 CREATE + M02 JOIN (no End) ==="

# M02 on Meeting tab first (invite TTL ~30s)
Foreground-App $M02Serial
Tap-IfPresent $M02Serial "${PackageName}:id/nav_talk" | Out-Null
Start-Sleep -Milliseconds 400
Tap-ByResourceId $M02Serial "${PackageName}:id/btnModeMeeting"
Start-Sleep -Milliseconds 500
Write-Host "M02 Meeting tab ready"

# M01 CREATE
Foreground-App $M01Serial
Tap-IfPresent $M01Serial "${PackageName}:id/nav_talk" | Out-Null
Start-Sleep -Milliseconds 400
Tap-ByResourceId $M01Serial "${PackageName}:id/btnModeMeeting"
Start-Sleep -Seconds 1
Tap-ByResourceId $M01Serial "${PackageName}:id/btnPtt"
Write-Host "M01 CREATE dispatched (btnPtt). Waiting invite on M02..."

$btnJoin = "${PackageName}:id/btnJoinMeeting"
$deadline = (Get-Date).AddSeconds($InviteJoinWaitSec)
$joined = $false
while ((Get-Date) -lt $deadline) {
    if (Find-ResourcePresent $M02Serial $btnJoin) {
        if (Tap-IfPresent $M02Serial $btnJoin) {
            Write-Host "M02 JOIN tapped"
            $joined = $true
            break
        }
    }
    # Also try text-based join if resource id dump races
    Start-Sleep -Milliseconds 400
}
if (-not $joined) {
    Write-Host "WARN: btnJoinMeeting not clicked in ${InviteJoinWaitSec}s — check overlay"
    exit 2
}

Write-Host "Phase 0 UI done. Do NOT End. Wait for MeetingExists / Phase1 gates."
exit 0
