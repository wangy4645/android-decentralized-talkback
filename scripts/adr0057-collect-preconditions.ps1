# ADR-0057 Track C / FTPH — collect PRECONDITIONS per device
# F2 hard gate: M04 MUST be absent from observed participant set.
# Soft: partial M01/M02/M03 discovery is OK before Case A.
param(
    [string]$OutDir = ""
)

$ErrorActionPreference = "Continue"
$devices = [ordered]@{
    M01 = "HTUBB21B09220661"
    M02 = "DSJ-2407041"
    M03 = "MDX0220416001963"
}
$M04 = "DSJ-2407011"
$pkg = "com.talkback.appprod"
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $OutDir) {
    $OutDir = Join-Path $repoRoot "logs\adr0057-preconditions-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
$lines = @(
    "collected=$(Get-Date -Format o)"
    "build=$head"
    "package=$pkg"
    "f2Gate=M04_ABSENT"
    ""
)

# --- F2: M04 isolation ---
$m04State = (& adb -s $M04 get-state 2>&1 | Out-String).Trim()
if ($m04State -notmatch '^device$') { $m04State = "offline" }
$lines += "======== M04 isolation (F2 hard gate) ========"
$lines += "M04 serial=$M04 adbState=$m04State"
if ($m04State -eq "device") {
    & adb -s $M04 shell am force-stop $pkg 2>&1 | Out-Null
    $pidLine = (& adb -s $M04 shell "pidof $pkg" 2>&1 | Out-String).Trim()
    $lines += "force-stop issued"
    $lines += "pidof=$pidLine"
    if ($pidLine -and ($pidLine -match '^\d')) {
        $lines += "F2_RESULT=FAIL reason=M04_PROCESS_STILL_RUNNING"
        Write-Warning "PRECONDITION FAIL: M04 process still running after force-stop"
    } else {
        $lines += "F2_RESULT=PASS_ADB reason=M04_FORCE_STOPPED"
    }
} else {
    $lines += "F2_RESULT=PASS_ADB reason=M04_NOT_ON_ADB (still verify no HELLO from M04 once apps open)"
}
$lines += ""

$allCaseDevicesOnline = $true
foreach ($name in $devices.Keys) {
    $serial = $devices[$name]
    $state = (& adb -s $serial get-state 2>&1 | Out-String).Trim()
    if ($state -notmatch '^device$') { $state = "offline" }
    if ($state -ne "device") {
        $lines += "[$name] OFFLINE serial=$serial"
        $lines += ""
        $allCaseDevicesOnline = $false
        continue
    }
    $lines += "======== $name ($serial) ========"
    $ver = adb -s $serial shell dumpsys package $pkg 2>$null | Select-String "versionName="
    if ($ver) { $lines += $ver.Line.Trim() }
    $upd = adb -s $serial shell dumpsys package $pkg 2>$null | Select-String "lastUpdateTime="
    if ($upd) { $lines += ($upd | Select-Object -First 1).Line.Trim() }
    $ip = adb -s $serial shell "ip -4 addr show wlan0 2>/dev/null | grep inet | awk '{print `$2}' | cut -d/ -f1" 2>$null
    $lines += "wlan0 IP: $ip"
    $moduleId = adb -s $serial shell "run-as $pkg cat shared_prefs/talkback_prod_config.xml 2>/dev/null" 2>$null | Select-String "moduleId"
    if ($moduleId) { $lines += $moduleId.Line.Trim() }
    $lines += "--- gbc-trust ---"
    $trustLs = adb -s $serial shell "run-as $pkg ls -la files/gbc-trust/ 2>&1"
    $lines += $trustLs
    foreach ($f in @("operational-trust-anchor.bin", "accepted-local-trust-state.bin", "field-established-generation-fact-signer.pkcs8", "FTPH_RESULT.txt")) {
        $st = adb -s $serial shell "run-as $pkg stat -c '%s %y' files/gbc-trust/$f 2>&1"
        $lines += "$f : $st"
    }
    $ftph = adb -s $serial shell "run-as $pkg cat files/gbc-trust/FTPH_RESULT.txt 2>&1"
    if ($ftph -and $ftph -notmatch "No such file") {
        $lines += "--- FTPH_RESULT ---"
        $lines += $ftph
    }
    $lines += ""
}

$lines += "======== gate summary ========"
$lines += "caseDevicesOnline=$allCaseDevicesOnline"
$lines += "hardGate=M04_ABSENT (do not require full {M01,M02,M03} mutual discovery before Case A)"
$lines += "if any HELLO/discovery observes M04 after Case A open -> abort / ENV_INVALID"

$outFile = Join-Path $OutDir "PRECONDITIONS.txt"
$lines | Set-Content $outFile -Encoding UTF8
Write-Host "Wrote $outFile"
if (-not $allCaseDevicesOnline) {
    Write-Warning "Case A devices incomplete — DO NOT START CASE A until M01/M02/M03 are online"
}
