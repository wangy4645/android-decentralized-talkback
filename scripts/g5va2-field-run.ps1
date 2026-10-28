# G5-VA2 one-shot field runner: clear -> CONFERENCE -> preflight -> obs capture.
param(
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "MDX0220416001963",
    [string]$M04Serial = "192.168.31.206:5555",
    [string]$RunId = "",
    [int]$MembershipWaitSec = 90,
    [int]$PostJoinWaitSec = 45,
    [switch]$SkipClear
)

$ErrorActionPreference = "Stop"
$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
$pkg = "com.talkback.appprod"
$activity = "com.talkback.appprod/.ui.MainActivity"

$devices = [ordered]@{
    M01 = $M01Serial
    M02 = $M02Serial
    M03 = $M03Serial
    M04 = $M04Serial
}

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "g5va2-obs-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}
$logDir = Join-Path $repoRoot "logs\$RunId"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Tap-ByResourceId([string]$Serial, [string]$ResourceId) {
    $dumpPath = "/sdcard/talkback-ui-dump.xml"
    $localDump = Join-Path $env:TEMP "talkback-ui-$($Serial.Replace(':','_')).xml"
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        & $adb -s $Serial shell uiautomator dump $dumpPath 2>&1 | Out-Null
        Start-Sleep -Milliseconds 700
        & $adb -s $Serial pull $dumpPath $localDump 2>&1 | Out-Null
        if (-not (Test-Path $localDump)) { continue }
        $xml = (Get-Content $localDump -Raw) -replace "`r`n", "" -replace "`n", ""
        $shortId = $ResourceId.Split(':')[-1]
        $pat = 'resource-id="' + $ResourceId + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
        $bounds = [regex]::Match($xml, $pat)
        if (-not $bounds.Success) {
            $pat2 = 'resource-id="[^"]*:' + $shortId + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
            $bounds = [regex]::Match($xml, $pat2)
        }
        if (-not $bounds.Success) { continue }
        $cx = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
        $cy = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
        & $adb -s $Serial shell input tap $cx $cy 2>&1 | Out-Null
        $ErrorActionPreference = $prev
        Write-Host "TAP $Serial $ResourceId @ ($cx,$cy)"
        return
    }
    $ErrorActionPreference = $prev
    throw "Resource not found on ${Serial}: $ResourceId"
}

function Tap-IfPresent([string]$Serial, [string]$ResourceId) {
    try { Tap-ByResourceId $Serial $ResourceId; return $true } catch { return $false }
}

function Foreground-App([string]$Serial) {
    & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
    & $adb -s $Serial shell am start -n $activity 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    Tap-IfPresent $Serial "$pkg:id/nav_talk" | Out-Null
    Start-Sleep -Milliseconds 800
}

function Invoke-LeaveUi([string]$Serial, [string]$Label) {
    Foreground-App $Serial
    Tap-IfPresent $Serial "$pkg:id/nav_talk" | Out-Null
    Start-Sleep -Milliseconds 500
    Tap-IfPresent $Serial "$pkg:id/btnModeMeeting" | Out-Null
    Start-Sleep -Seconds 1
    foreach ($rid in @("btnHangup", "btnLeave", "btnMeetingLeave", "btnMeetingLeaveTop")) {
        Tap-IfPresent $Serial "$pkg:id/$rid" | Out-Null
    }
    & $adb -s $Serial shell am broadcast -a com.talkback.appprod.debug.P1C_FORCE_LEAVE_CHANNEL_SESSION -p $pkg 2>&1 | Out-Null
    Write-Host "LEAVE $Label"
}

function Ensure-MeetingTab([string]$Serial, [string]$Label) {
    Foreground-App $Serial
    for ($i = 1; $i -le 8; $i++) {
        if (-not (Tap-IfPresent $Serial "$pkg:id/nav_talk")) {
            & $adb -s $Serial shell input tap 90 1430 2>&1 | Out-Null
        }
        Start-Sleep -Milliseconds 800
        if (Tap-IfPresent $Serial "$pkg:id/btnModeMeeting") {
            Write-Host "MEETING_TAB $Label"
            return
        }
        if ($i -ge 2) {
            & $adb -s $Serial shell input tap 435 470 2>&1 | Out-Null
            Write-Host "MEETING_TAB $Label via fallback tap (435,470)"
            return
        }
        if (Tap-IfPresent $Serial "$pkg:id/channelOnlineArea") {
            Start-Sleep -Seconds 1
            if (Tap-IfPresent $Serial "$pkg:id/btnModeMeeting") {
                Write-Host "MEETING_TAB $Label via channelOnlineArea"
                return
            }
        }
        & $adb -s $Serial shell input swipe 360 500 360 900 200 2>&1 | Out-Null
        Start-Sleep -Seconds 1
    }
    throw "btnModeMeeting not found on $Label"
}

function Get-M01LogTail() {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $text = & $adb -s $M01Serial logcat -d -t 400 -v threadtime -s Talkback:I 2>&1 | Out-String
    $ErrorActionPreference = $prev
    return $text
}

function Test-FourWayMembership([string]$Text) {
    $m = [regex]::Match($Text, 'joinedMembers=M01,M02,M03,M04')
    return $m.Success
}

function Try-PeerJoin([string]$Serial, [string]$Label, [string]$HostSessionId) {
    $btn = "$pkg:id/btnJoinMeeting"
    $deadline = (Get-Date).AddSeconds(35)
    while ((Get-Date) -lt $deadline) {
        if (Tap-IfPresent $Serial $btn) {
            Write-Host "JOIN_CLICK $Label"
            Start-Sleep -Seconds 2
        }
        $peerLog = & $adb -s $Serial logcat -d -t 200 -v threadtime -s Talkback:I 2>&1 | Out-String
        if ($peerLog -match "session=$HostSessionId") { return $true }
        if ($peerLog -match "INVITE_ACCEPT|SESSION_BIND_DRAIN session=$HostSessionId") { return $true }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

if ($M04Serial -match '^\d+\.\d+\.\d+\.\d+:') {
    & $adb connect $M04Serial 2>&1 | Out-Null
}

Write-Host "RUN_ID=$RunId LOG_DIR=$logDir"

if (-not $SkipClear) {
    foreach ($label in $devices.Keys) {
        & $adb -s $devices[$label] logcat -c 2>&1 | Out-Null
        Invoke-LeaveUi $devices[$label] $label
    }
    Write-Host "Waiting 20s after clear..."
    Start-Sleep -Seconds 20
}

foreach ($label in $devices.Keys) {
    Ensure-MeetingTab $devices[$label] $label
}

Write-Host "Waiting up to ${MembershipWaitSec}s for 4-way joinedMembers on M01..."
$membershipOk = $false
$deadline = (Get-Date).AddSeconds($MembershipWaitSec)
while ((Get-Date) -lt $deadline) {
    $tail = Get-M01LogTail
    if (Test-FourWayMembership $tail) {
        $membershipOk = $true
        Write-Host "MEMBERSHIP_4WAY observed on M01"
        break
    }
    Start-Sleep -Seconds 2
}
if (-not $membershipOk) {
    Write-Warning "4-way membership not confirmed before CREATE — proceeding anyway"
}

Write-Host "Host CREATE on M01..."
Foreground-App $M01Serial
Tap-ByResourceId $M01Serial "$pkg:id/btnModeMeeting"
Start-Sleep -Seconds 1
Tap-ByResourceId $M01Serial "$pkg:id/btnPtt"

$hostSessionId = $null
$createDeadline = (Get-Date).AddSeconds(35)
while ((Get-Date) -lt $createDeadline -and -not $hostSessionId) {
    $tail = Get-M01LogTail
    $m = [regex]::Match($tail, 'CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=([0-9a-fA-F-]{36})')
    if ($m.Success) { $hostSessionId = $m.Groups[1].Value; break }
    $m2 = [regex]::Match($tail, 'ADMISSION_DECISION action=CREATE .* incomingSessionId=([0-9a-fA-F-]{36})')
    if ($m2.Success) { $hostSessionId = $m2.Groups[1].Value; break }
    Start-Sleep -Milliseconds 500
}
if (-not $hostSessionId) { throw "Host CONFERENCE session not observed on M01" }
Write-Host "HOST_SESSION=$hostSessionId"

foreach ($label in @("M03", "M04", "M02")) {
    Ensure-MeetingTab $devices[$label] $label
    $ok = Try-PeerJoin $devices[$label] $label $hostSessionId
    if (-not $ok) { Write-Warning "Peer join not confirmed for $label" }
}

Write-Host "Waiting ${PostJoinWaitSec}s post-join..."
Start-Sleep -Seconds $PostJoinWaitSec

& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "g5va2-preflight.ps1") `
    -M01Serial $M01Serial -M02Serial $M02Serial -M03Serial $M03Serial -M04Serial $M04Serial `
    -LogDir $logDir -CaptureSeconds 10 -RunId $RunId
$preflightExit = $LASTEXITCODE

if ($preflightExit -ne 0) {
    Write-Host "PREFLIGHT NO-GO — skipping G5-VA2 observation capture"
    Write-Host "LOG_DIR=$logDir"
    exit $preflightExit
}

Write-Host "PREFLIGHT GO — starting G5-VA2 observation capture"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "g5va2-obs-field-start.ps1") `
    -M01Serial $M01Serial -M02Serial $M02Serial -M03Serial $M03Serial -M04Serial $M04Serial `
    -RunId $RunId

Write-Host "FIELD_CAPTURE_STARTED run=$RunId"
Write-Host "Phase A: M02 idle 15s | Phase B: M03 PTT speech 15s | Phase C: all silent 10s"
Write-Host "Then stop logcat and run g5va2-obs-adjudicate.ps1 -LogDir $logDir"
