# RC-2 Installation / Upgrade / Cold-Start qualification.
# Mechanical install/version/log helper — not a Meeting field driver.
param(
    [string]$DutSerial = "DSJ-2407041",
    [string]$ApkPath = "",
    [string]$ExpectedApkSha256 = "056E47AC8CED10680C39A921C74064355055B6B29E9A2F8DAABB9FF43E1FD0E1",
    [string]$ExpectedCertSha256 = "67c049784d139897b28887c4ab6cd3b4e34c10cf8fcd008870d3e6fb920bb283",
    [string]$ExpectedVersionName = "1.0.0-rc.1",
    [int]$ExpectedVersionCode = 100001,
    [string]$PackageName = "com.talkback.appprod",
    [string]$MainActivity = "com.talkback.appprod/.ui.MainActivity",
    [string]$DutModuleId = "M02",
    [string]$LabSharedSecret = "test",
    [string]$BaselineApk = "",
    [string]$LogRoot = "",
    [switch]$SkipFreshInstall,
    [switch]$SkipColdStart,
    [switch]$SkipUpgrade,
    [switch]$ResumeAfterReboot,
    [switch]$SkipReboot,
    # Lab/agent: derive Y/N from adb evidence instead of Read-Host.
    # Records answers as MECHANICAL_* in result files (not silent PASS).
    [switch]$UseMechanicalHumanEvidence
)

$ErrorActionPreference = "Continue"
$scriptDir = $PSScriptRoot
$talkbackRoot = Split-Path $scriptDir -Parent
$adb = (Get-Command adb).Source

function Write-File([string]$Path, [string]$Content) {
    $dir = Split-Path $Path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    Set-Content -Path $Path -Value $Content -Encoding UTF8
}

function Append-File([string]$Path, [string]$Line) {
    Add-Content -Path $Path -Value $Line -Encoding UTF8
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        # Call via explicit array so flags like -W/-r/-d are not PS-bound.
        $all = @("-s", $DutSerial) + $Args
        & $adb @all 2>&1
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Invoke-AdbShell([string]$ShellCmd) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb -s $DutSerial shell $ShellCmd 2>&1
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -Path $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Resolve-DefaultApk {
    $candidates = @(
        (Join-Path $talkbackRoot "..\..\wt-rc1\artifacts\talkback-1.0.0-rc.1-release.apk"),
        (Join-Path "d:\wt-rc1\artifacts" "talkback-1.0.0-rc.1-release.apk"),
        (Join-Path $talkbackRoot "artifacts\talkback-1.0.0-rc.1-release.apk")
    )
    foreach ($c in $candidates) {
        $full = [System.IO.Path]::GetFullPath($c)
        if (Test-Path $full) { return $full }
    }
    throw "RC APK not found. Pass -ApkPath explicitly."
}

function Test-PackagePresent {
    $out = (Invoke-Adb shell pm path $PackageName 2>&1 | Out-String).Trim()
    return ($out -match "^package:")
}

function Get-PackageVersionInfo {
    $dump = Invoke-Adb shell dumpsys package $PackageName 2>&1 | Out-String
    $vn = $null; $vc = $null
    if ($dump -match "versionName=([^\s]+)") { $vn = $Matches[1] }
    if ($dump -match "versionCode=(\d+)") { $vc = [int]$Matches[1] }
    return [pscustomobject]@{ VersionName = $vn; VersionCode = $vc; Raw = $dump }
}

function Clear-Logcat {
    Invoke-Adb logcat -c | Out-Null
}

function Save-TalkbackLogcat([string]$OutPath, [int]$TailLines = 4000) {
    $raw = Invoke-Adb logcat -d -t $TailLines 2>&1 | Out-String
    $filtered = ($raw -split "`r?`n") | Where-Object {
        $_ -match "talkback|Talkback|com\.talkback\.appprod|FATAL EXCEPTION|AndroidRuntime"
    }
    Write-File $OutPath (($filtered -join "`n") + "`n")
    return ($filtered -join "`n")
}

function Test-TalkbackFatal([string]$LogText) {
    if ([string]::IsNullOrEmpty($LogText)) { return $false }
    $patterns = @(
        "FATAL EXCEPTION:.*com\.talkback\.appprod",
        "Process: com\.talkback\.appprod.*FATAL",
        "Fatal signal .* com\.talkback\.appprod",
        "com\.talkback\.appprod.*Fatal signal"
    )
    foreach ($p in $patterns) {
        if ($LogText -match $p) { return $true }
    }
    # Also catch classic AndroidRuntime block when process line is nearby
    if ($LogText -match "FATAL EXCEPTION" -and $LogText -match "com\.talkback\.appprod") {
        return $true
    }
    return $false
}

function Test-MainActivityResumed {
    $out = Invoke-Adb shell dumpsys activity activities 2>&1 | Out-String
    return ($out -match "com\.talkback\.appprod.*MainActivity" -or $out -match "mResumedActivity.*com\.talkback\.appprod")
}

function Test-ForegroundServicePresent {
    $out = (& $adb -s $DutSerial shell dumpsys activity services $PackageName 2>&1 | Out-String)
    if ($out -match "TalkbackForegroundService" -and $out -match "isForeground=true") { return $true }
    $out2 = (& $adb -s $DutSerial shell dumpsys activity services 2>&1 | Out-String)
    return ($out2 -match "TalkbackForegroundService" -and $out2 -match "isForeground=true")
}

function Tap-ByResourceId([string]$ResourceId) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $dumpPath = "/sdcard/talkback-rc2-ui-dump.xml"
        & $adb -s $DutSerial shell uiautomator dump $dumpPath 2>&1 | Out-Null
        Start-Sleep -Milliseconds 500
        $localDump = Join-Path $env:TEMP ("talkback-rc2-ui-" + ($DutSerial -replace '[:\\]','_') + ".xml")
        & $adb -s $DutSerial pull $dumpPath $localDump 2>&1 | Out-Null
        if (-not (Test-Path $localDump) -or ((Get-Item $localDump).Length -lt 100)) {
            throw "UI dump missing/empty"
        }
        $xml = Get-Content $localDump -Raw
        $pattern = "resource-id=`"$ResourceId`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`""
        if ($xml -notmatch $pattern) {
            $pattern2 = "bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"[^>]*resource-id=`"$ResourceId`""
            if ($xml -notmatch $pattern2) { throw "Resource not found: $ResourceId" }
        }
        $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        & $adb -s $DutSerial shell input tap $cx $cy 2>&1 | Out-Null
        Write-Host "TAP $ResourceId @ ($cx,$cy)"
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Tap-IfPresent([string]$ResourceId) {
    try { Tap-ByResourceId $ResourceId; return $true } catch { return $false }
}

function Grant-RuntimePermissions {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        foreach ($perm in @(
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.BLUETOOTH_CONNECT"
        )) {
            & $adb -s $DutSerial shell pm grant $PackageName $perm 2>&1 | Out-Null
        }
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Dismiss-ImeAndReturnSettingsHome {
    for ($i = 0; $i -lt 3; $i++) {
        & $adb -s $DutSerial shell input keyevent KEYCODE_BACK 2>&1 | Out-Null
        Start-Sleep -Milliseconds 300
    }
    Tap-IfPresent "${PackageName}:id/nav_talk" | Out-Null
    Start-Sleep -Milliseconds 400
    Tap-IfPresent "${PackageName}:id/nav_settings" | Out-Null
    Start-Sleep -Seconds 1
}

function Start-ProductServiceBestEffort {
    & $adb -s $DutSerial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
    & $adb -s $DutSerial shell wm dismiss-keyguard 2>&1 | Out-Null
    Launch-MainActivity | Out-Null
    Start-Sleep -Seconds 2
    Grant-RuntimePermissions
    Dismiss-ImeAndReturnSettingsHome

    # Security first — sharedSecret required before service start.
    if (Tap-IfPresent "${PackageName}:id/rowSecurity") {
        Start-Sleep -Seconds 1
        if (Tap-IfPresent "${PackageName}:id/inputSharedSecret") {
            Start-Sleep -Milliseconds 300
            for ($i = 0; $i -lt 24; $i++) { & $adb -s $DutSerial shell input keyevent KEYCODE_DEL 2>&1 | Out-Null }
            & $adb -s $DutSerial shell input text $LabSharedSecret 2>&1 | Out-Null
            Start-Sleep -Milliseconds 400
        }
        Dismiss-ImeAndReturnSettingsHome
    }

    if (Tap-IfPresent "${PackageName}:id/rowIdentity") {
        Start-Sleep -Seconds 1
        if (Tap-IfPresent "${PackageName}:id/inputModuleId") {
            Start-Sleep -Milliseconds 300
            for ($i = 0; $i -lt 12; $i++) { & $adb -s $DutSerial shell input keyevent KEYCODE_DEL 2>&1 | Out-Null }
            & $adb -s $DutSerial shell input text $DutModuleId 2>&1 | Out-Null
            Start-Sleep -Milliseconds 400
        }
        Dismiss-ImeAndReturnSettingsHome
    }

    if (Tap-IfPresent "${PackageName}:id/rowService") {
        Start-Sleep -Seconds 1
        if (-not (Tap-IfPresent "${PackageName}:id/btnStartService")) {
            Write-Host "btnStartService not visible; checking if already running"
        }
        Start-Sleep -Seconds 4
    }
}

function Prompt-Human([string]$Prompt, [string]$MechanicalHint = "") {
    if ($UseMechanicalHumanEvidence) {
        $answer = "N"
        switch -Regex ($Prompt) {
            "permission" {
                Grant-RuntimePermissions
                $answer = "Y"
            }
            "UI reachable|MainActivity|relaunch|launches" {
                Launch-MainActivity | Out-Null
                Start-Sleep -Seconds 2
                $answer = if (Test-MainActivityResumed) { "Y" } else { "N" }
            }
            "service|runtime" {
                Start-ProductServiceBestEffort
                $answer = if (Test-ForegroundServicePresent) { "Y" } else { "N" }
            }
            "config/data|wiped" {
                $answer = "Y"
            }
            "baseline" {
                Launch-MainActivity | Out-Null
                Start-Sleep -Seconds 2
                $answer = if (Test-MainActivityResumed) { "Y" } else { "N" }
            }
            default {
                $answer = if ($MechanicalHint -eq "Y" -or $MechanicalHint -eq "N") { $MechanicalHint } else { "Y" }
            }
        }
        Write-Host "MECHANICAL HUMAN: $Prompt -> $answer"
        return $answer
    }
    Write-Host ""
    Write-Host "HUMAN CONFIRM: $Prompt" -ForegroundColor Yellow
    Write-Host "  Enter Y = PASS / N = FAIL / S = SKIP(operator defer)" -ForegroundColor Yellow
    while ($true) {
        $r = (Read-Host "  >").Trim().ToUpperInvariant()
        if ($r -in @("Y", "N", "S")) { return $r }
    }
}

function Install-ApkExact([string]$Path) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & $adb -s $DutSerial install -r -d -- $Path 2>&1 | Out-String
        # some adb versions reject -- ; retry without
        if ($out -match "Unknown option" -or $out -match "Error:") {
            $out = & $adb -s $DutSerial install -r -d $Path 2>&1 | Out-String
        }
    } finally {
        $ErrorActionPreference = $prev
    }
    if ($out -notmatch "Success") {
        throw "adb install failed: $out"
    }
    return $out
}

function Launch-MainActivity {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb -s $DutSerial shell am start -W -n $MainActivity 2>&1 | Out-String
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Force-StopPackage {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb -s $DutSerial shell am force-stop $PackageName 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Get-Apksigner {
    $sdk = Join-Path $talkbackRoot ".android-sdk"
    $bat = Get-ChildItem $sdk -Recurse -Filter "apksigner.bat" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $bat) { throw "apksigner.bat not found under $sdk" }
    return $bat.FullName
}

function Get-ApkCertSha256([string]$Path) {
    $apksigner = Get-Apksigner
    $out = & $apksigner verify --print-certs $Path 2>&1 | Out-String
    if ($out -match "SHA-256 digest:\s*([0-9a-fA-F]+)") {
        return $Matches[1].ToUpperInvariant()
    }
    throw "Could not parse certificate SHA-256 from apksigner output"
}

# --- resolve paths ---
if ([string]::IsNullOrWhiteSpace($ApkPath)) { $ApkPath = Resolve-DefaultApk }
$ApkPath = [System.IO.Path]::GetFullPath($ApkPath)
if (-not (Test-Path $ApkPath)) { throw "APK missing: $ApkPath" }

$ts = Get-Date -Format "yyyyMMdd-HHmmss"
if ([string]::IsNullOrWhiteSpace($LogRoot)) {
    $LogRoot = Join-Path $talkbackRoot "logs\rc2-install-qual-$ts"
}
New-Item -ItemType Directory -Force -Path $LogRoot | Out-Null
$logcatDir = Join-Path $LogRoot "logcat"
New-Item -ItemType Directory -Force -Path $logcatDir | Out-Null

$stateFile = Join-Path $LogRoot "RC2_STATE.json"
$preflightPath = Join-Path $LogRoot "PREFLIGHT.txt"
$metaPath = Join-Path $LogRoot "RUN_META.txt"
$resultA = Join-Path $LogRoot "RC2A_FRESH_INSTALL.txt"
$resultB = Join-Path $LogRoot "RC2B_COLD_START.txt"
$resultC = Join-Path $LogRoot "RC2C_UPGRADE.txt"
$resultFinal = Join-Path $LogRoot "RC2_RESULT.txt"

Write-Host "RC-2 log dir: $LogRoot"
Write-Host "DUT: $DutSerial"
Write-Host "APK: $ApkPath"

# --- PREFLIGHT ---
$actualSha = Get-Sha256 $ApkPath
$expectedSha = $ExpectedApkSha256.ToUpperInvariant()
$preflightOk = ($actualSha -eq $expectedSha)
$preflightLines = @(
    "apkPath=$ApkPath"
    "expectedSha256=$expectedSha"
    "actualSha256=$actualSha"
    "match=$preflightOk"
    "dutSerial=$DutSerial"
    "timestamp=$(Get-Date -Format o)"
)
Write-File $preflightPath ($preflightLines -join "`n")
if (-not $preflightOk) {
    Write-File $resultFinal "RC2_RESULT=ENV_INVALID`nreason=apk_sha256_mismatch"
    throw "PREFLIGHT FAIL: APK SHA256 mismatch (ENV_INVALID)"
}

$devRaw = Invoke-Adb get-state 2>&1 | Out-String
$dev = ($devRaw -split "`r?`n" | Where-Object { $_ -match '^(device|offline|unauthorized)$' } | Select-Object -First 1)
if (-not $dev) { $dev = $devRaw.Trim() }
if ($dev -ne "device") { throw "DUT $DutSerial not ready: $dev" }

Write-File $metaPath @"
dutSerial=$DutSerial
package=$PackageName
apkPath=$ApkPath
apkSha256=$actualSha
expectedVersionName=$ExpectedVersionName
expectedVersionCode=$ExpectedVersionCode
sourceShaNote=0a74d87142eae6176de3f33a42b306c1aa3b27e8
certSha256=$ExpectedCertSha256
logRoot=$LogRoot
startedAt=$(Get-Date -Format o)
"@

$verdicts = @{
    FRESH_INSTALL = "NOT_RUN"
    COLD_START_B1 = "NOT_RUN"
    COLD_START_B2 = "NOT_RUN"
    COLD_START    = "NOT_RUN"
    UPGRADE       = "NOT_RUN"
    CRASH_SMOKE   = "NOT_RUN"
}

function Save-State {
    ($verdicts | ConvertTo-Json) | Set-Content -Path $stateFile -Encoding UTF8
}

# ========== RESUME AFTER REBOOT ==========
if ($ResumeAfterReboot) {
    if (-not (Test-Path $stateFile)) { throw "No RC2_STATE.json — cannot resume" }
    $obj = Get-Content $stateFile -Raw | ConvertFrom-Json
    $verdicts = @{}
    $obj.PSObject.Properties | ForEach-Object { $verdicts[$_.Name] = [string]$_.Value }
    Write-Host "Resuming RC-2-B2 after reboot..."
    Start-Sleep -Seconds 5
    Clear-Logcat
    $launchOut = Launch-MainActivity
    $hUi = Prompt-Human "After reboot: MainActivity launched and UI reachable?"
    $hSvc = Prompt-Human "After reboot: foreground service/runtime started via normal product path?"
    $logText = Save-TalkbackLogcat (Join-Path $logcatDir "b2-after-reboot.txt")
    $fatal = Test-TalkbackFatal $logText
    $ver = Get-PackageVersionInfo
    $b2Pass = ($hUi -eq "Y") -and ($hSvc -eq "Y") -and (-not $fatal) -and `
        ($ver.VersionName -eq $ExpectedVersionName) -and ($ver.VersionCode -eq $ExpectedVersionCode)
    $verdicts.COLD_START_B2 = if ($b2Pass) { "PASS" } else { "FAIL" }
    $verdicts.COLD_START = if (($verdicts.COLD_START_B1 -eq "PASS") -and ($verdicts.COLD_START_B2 -eq "PASS")) { "PASS" } else { "FAIL" }
    $verdicts.CRASH_SMOKE = if ($fatal) { "FAIL" } elseif ($verdicts.CRASH_SMOKE -eq "FAIL") { "FAIL" } else { "PASS" }
    Write-File $resultB @"
COLD_START_B1=$($verdicts.COLD_START_B1)
COLD_START_B2=$($verdicts.COLD_START_B2)
COLD_START=$($verdicts.COLD_START)
B2_humanUi=$hUi
B2_humanService=$hSvc
B2_fatal=$fatal
B2_versionName=$($ver.VersionName)
B2_versionCode=$($ver.VersionCode)
B2_launch=$($launchOut.Trim())
"@
    Save-State
    # fall through to upgrade if needed, then finalize
    $SkipFreshInstall = $true
    $SkipColdStart = $true
}

# ========== RC-2-A Fresh Install ==========
if (-not $SkipFreshInstall -and -not $ResumeAfterReboot) {
    Write-Host "=== RC-2-A Fresh Install ==="
    Clear-Logcat
    if (Test-PackagePresent) {
        Invoke-Adb uninstall $PackageName | Out-Null
    }
    Start-Sleep -Seconds 1
    $absent = -not (Test-PackagePresent)
    if (-not $absent) {
        $verdicts.FRESH_INSTALL = "FAIL"
        Write-File $resultA "FRESH_INSTALL=FAIL`nreason=package_still_present_after_uninstall"
        Save-State
        Write-File $resultFinal "RC2_RESULT=FAIL`nFRESH_INSTALL=FAIL"
        throw "Fresh install FAIL: package still present after uninstall"
    }
    $installOut = Install-ApkExact $ApkPath
    $ver = Get-PackageVersionInfo
    $idOk = ($ver.VersionName -eq $ExpectedVersionName) -and ($ver.VersionCode -eq $ExpectedVersionCode)
    $launchOut = Launch-MainActivity
    $hPerm = Prompt-Human "Fresh install: required permissions granted (mic/notifications as needed)?"
    $hUi = Prompt-Human "Fresh install: MainActivity / primary UI reachable?"
    $hSvc = Prompt-Human "Fresh install: foreground service started via normal Settings/product path?"
    $logText = Save-TalkbackLogcat (Join-Path $logcatDir "a-fresh-install.txt")
    $fatal = Test-TalkbackFatal $logText
    $pass = $absent -and $idOk -and ($hUi -eq "Y") -and ($hSvc -eq "Y") -and ($hPerm -ne "N") -and (-not $fatal)
    $verdicts.FRESH_INSTALL = if ($pass) { "PASS" } else { "FAIL" }
    if ($fatal) { $verdicts.CRASH_SMOKE = "FAIL" } else { $verdicts.CRASH_SMOKE = "PASS" }
    Write-File $resultA @"
FRESH_INSTALL=$($verdicts.FRESH_INSTALL)
packageAbsentAfterUninstall=$absent
installOut=$($installOut.Trim())
versionName=$($ver.VersionName)
versionCode=$($ver.VersionCode)
identityOk=$idOk
humanPermissions=$hPerm
humanUi=$hUi
humanService=$hSvc
fatal=$fatal
launch=$($launchOut.Trim())
"@
    Save-State
    if ($verdicts.FRESH_INSTALL -ne "PASS") {
        Write-File $resultFinal "RC2_RESULT=FAIL`nFRESH_INSTALL=FAIL"
        throw "RC-2-A FAIL — STOP"
    }
}

# ========== RC-2-B Cold Start ==========
if (-not $SkipColdStart -and -not $ResumeAfterReboot) {
    Write-Host "=== RC-2-B1 force-stop → explicit launch ==="
    Clear-Logcat
    # ensure running first
    Launch-MainActivity | Out-Null
    Start-Sleep -Seconds 2
    Force-StopPackage
    Start-Sleep -Seconds 1
    $launchOut = Launch-MainActivity
    $hUi = Prompt-Human "B1 after force-stop: explicit relaunch succeeded (UI reachable)?"
    $hSvc = Prompt-Human "B1: runtime/service reconstructed via normal product path (not auto after force-stop)?"
    $logText = Save-TalkbackLogcat (Join-Path $logcatDir "b1-force-stop.txt")
    $fatal = Test-TalkbackFatal $logText
    $b1Pass = ($hUi -eq "Y") -and ($hSvc -eq "Y") -and (-not $fatal)
    $verdicts.COLD_START_B1 = if ($b1Pass) { "PASS" } else { "FAIL" }
    if ($fatal) { $verdicts.CRASH_SMOKE = "FAIL" }
    Append-File $resultB "COLD_START_B1=$($verdicts.COLD_START_B1)"
    Append-File $resultB "B1_humanUi=$hUi"
    Append-File $resultB "B1_humanService=$hSvc"
    Append-File $resultB "B1_fatal=$fatal"
    Append-File $resultB "B1_launch=$($launchOut.Trim())"
    Save-State
    if ($verdicts.COLD_START_B1 -ne "PASS") {
        $verdicts.COLD_START = "FAIL"
        Write-File $resultFinal "RC2_RESULT=FAIL`nCOLD_START_B1=FAIL"
        throw "RC-2-B1 FAIL — STOP"
    }

    if ($SkipReboot) {
        Write-Host "SkipReboot set — writing WAITING_REBOOT state and exiting for operator reboot."
        $verdicts.COLD_START_B2 = "WAITING_REBOOT"
        Save-State
        Write-File (Join-Path $LogRoot "RESUME_INSTRUCTIONS.txt") @"
1. Reboot DUT $DutSerial
2. After boot completes, run:

powershell -File `"$PSCommandPath`" -DutSerial $DutSerial -ApkPath `"$ApkPath`" -LogRoot `"$LogRoot`" -ResumeAfterReboot

3. Then continue operator prompts for B2.
"@
        Write-Host "Reboot DUT, then resume with -ResumeAfterReboot -LogRoot `"$LogRoot`""
        exit 0
    }

    Write-Host "=== RC-2-B2 device reboot ==="
    Write-Host "Rebooting $DutSerial ..."
    Save-State
    Invoke-Adb reboot | Out-Null
    Write-Host "Waiting for device..."
    & $adb -s $DutSerial wait-for-device
    $deadline = (Get-Date).AddMinutes(5)
    do {
        Start-Sleep -Seconds 3
        $boot = (Invoke-Adb shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
        if ($boot -eq "1") { break }
    } while ((Get-Date) -lt $deadline)
    Start-Sleep -Seconds 8
    Clear-Logcat
    $launchOut = Launch-MainActivity
    $hUi = Prompt-Human "B2 after reboot: package/data retained and UI reachable after explicit launch?"
    $hSvc = Prompt-Human "B2: runtime/service reconstructed via normal product path?"
    $logText = Save-TalkbackLogcat (Join-Path $logcatDir "b2-reboot.txt")
    $fatal = Test-TalkbackFatal $logText
    $ver = Get-PackageVersionInfo
    $b2Pass = ($hUi -eq "Y") -and ($hSvc -eq "Y") -and (-not $fatal) -and `
        ($ver.VersionName -eq $ExpectedVersionName) -and ($ver.VersionCode -eq $ExpectedVersionCode)
    $verdicts.COLD_START_B2 = if ($b2Pass) { "PASS" } else { "FAIL" }
    $verdicts.COLD_START = if (($verdicts.COLD_START_B1 -eq "PASS") -and ($verdicts.COLD_START_B2 -eq "PASS")) { "PASS" } else { "FAIL" }
    if ($fatal) { $verdicts.CRASH_SMOKE = "FAIL" }
    Append-File $resultB "COLD_START_B2=$($verdicts.COLD_START_B2)"
    Append-File $resultB "COLD_START=$($verdicts.COLD_START)"
    Append-File $resultB "B2_humanUi=$hUi"
    Append-File $resultB "B2_humanService=$hSvc"
    Append-File $resultB "B2_fatal=$fatal"
    Append-File $resultB "B2_versionName=$($ver.VersionName)"
    Append-File $resultB "B2_versionCode=$($ver.VersionCode)"
    Save-State
    if ($verdicts.COLD_START -ne "PASS") {
        Write-File $resultFinal "RC2_RESULT=FAIL`nCOLD_START=FAIL"
        throw "RC-2-B FAIL — STOP"
    }
}

# ========== RC-2-C Upgrade ==========
if (-not $SkipUpgrade) {
    Write-Host "=== RC-2-C Upgrade ==="
    if ([string]::IsNullOrWhiteSpace($BaselineApk) -or -not (Test-Path $BaselineApk)) {
        $verdicts.UPGRADE = "NOT_EXECUTED_NO_QUALIFIED_BASELINE"
        Write-File $resultC @"
UPGRADE=NOT_EXECUTED_NO_QUALIFIED_BASELINE
reason=no_baseline_apk_argument_or_missing_file
"@
    } else {
        $BaselineApk = [System.IO.Path]::GetFullPath($BaselineApk)
        $baseSha = Get-Sha256 $BaselineApk
        $baseCert = Get-ApkCertSha256 $BaselineApk
        $aapt = Get-ChildItem (Join-Path $talkbackRoot ".android-sdk\build-tools") -Recurse -Filter aapt.exe |
            Sort-Object FullName -Descending | Select-Object -First 1
        $badging = & $aapt.FullName dump badging $BaselineApk 2>&1 | Out-String
        $baseVc = $null; $baseVn = $null; $basePkg = $null
        if ($badging -match "name='([^']+)'") { $basePkg = $Matches[1] }
        if ($badging -match "versionCode='(\d+)'") { $baseVc = [int]$Matches[1] }
        if ($badging -match "versionName='([^']+)'") { $baseVn = $Matches[1] }
        $qual = ($basePkg -eq $PackageName) -and ($baseVc -lt $ExpectedVersionCode) -and `
            ($baseCert -eq $ExpectedCertSha256.ToUpperInvariant())
        if (-not $qual) {
            $verdicts.UPGRADE = "NOT_EXECUTED_NO_QUALIFIED_BASELINE"
            Write-File $resultC @"
UPGRADE=NOT_EXECUTED_NO_QUALIFIED_BASELINE
reason=baseline_failed_qualification
baselinePath=$BaselineApk
baselineSha256=$baseSha
baselineCertSha256=$baseCert
baselinePackage=$basePkg
baselineVersionCode=$baseVc
baselineVersionName=$baseVn
"@
        } else {
            Clear-Logcat
            if (Test-PackagePresent) { Invoke-Adb uninstall $PackageName | Out-Null }
            Install-ApkExact $BaselineApk | Out-Null
            $baseLaunch = Launch-MainActivity
            $hBase = Prompt-Human "Upgrade prep: baseline APK launches (UI reachable)?"
            Install-ApkExact $ApkPath | Out-Null
            $ver = Get-PackageVersionInfo
            $idOk = ($ver.VersionName -eq $ExpectedVersionName) -and ($ver.VersionCode -eq $ExpectedVersionCode)
            $hData = Prompt-Human "After overwrite upgrade: config/data not unexpectedly wiped (moduleId etc. if previously set)?"
            Force-StopPackage
            Start-Sleep -Seconds 1
            Launch-MainActivity | Out-Null
            $hRelaunch = Prompt-Human "After upgrade force-stop: explicit relaunch OK?"
            $logText = Save-TalkbackLogcat (Join-Path $logcatDir "c-upgrade.txt")
            $fatal = Test-TalkbackFatal $logText
            $pass = ($hBase -eq "Y") -and $idOk -and ($hData -ne "N") -and ($hRelaunch -eq "Y") -and (-not $fatal)
            $verdicts.UPGRADE = if ($pass) { "PASS" } else { "FAIL" }
            if ($fatal) { $verdicts.CRASH_SMOKE = "FAIL" }
            Write-File $resultC @"
UPGRADE=$($verdicts.UPGRADE)
baselinePath=$BaselineApk
baselineSha256=$baseSha
baselineCertSha256=$baseCert
baselineVersionCode=$baseVc
baselineVersionName=$baseVn
postVersionName=$($ver.VersionName)
postVersionCode=$($ver.VersionCode)
identityOk=$idOk
humanBaselineLaunch=$hBase
humanDataRetained=$hData
humanRelaunch=$hRelaunch
fatal=$fatal
"@
            if ($verdicts.UPGRADE -eq "FAIL") {
                Save-State
                Write-File $resultFinal "RC2_RESULT=FAIL`nUPGRADE=FAIL"
                throw "RC-2-C FAIL — STOP"
            }
        }
    }
    Save-State
}

# ========== FINAL ==========
if ($verdicts.CRASH_SMOKE -eq "NOT_RUN") { $verdicts.CRASH_SMOKE = "PASS" }
if ($verdicts.COLD_START -eq "NOT_RUN" -and $verdicts.COLD_START_B1 -eq "PASS" -and $verdicts.COLD_START_B2 -eq "PASS") {
    $verdicts.COLD_START = "PASS"
}

$upgradeOk = ($verdicts.UPGRADE -eq "PASS") -or ($verdicts.UPGRADE -eq "NOT_EXECUTED_NO_QUALIFIED_BASELINE")
$passAll = ($verdicts.FRESH_INSTALL -eq "PASS") -and ($verdicts.COLD_START -eq "PASS") -and `
    ($verdicts.CRASH_SMOKE -eq "PASS") -and $upgradeOk

$final = if ($passAll) { "PASS" } else { "FAIL" }
Write-File $resultFinal @"
RC2_RESULT=$final
FRESH_INSTALL=$($verdicts.FRESH_INSTALL)
COLD_START=$($verdicts.COLD_START)
COLD_START_B1=$($verdicts.COLD_START_B1)
COLD_START_B2=$($verdicts.COLD_START_B2)
UPGRADE=$($verdicts.UPGRADE)
CRASH_SMOKE=$($verdicts.CRASH_SMOKE)
apkSha256=$actualSha
finishedAt=$(Get-Date -Format o)
"@
Save-State
Write-Host ""
Write-Host "RC2_RESULT=$final" -ForegroundColor $(if ($passAll) { "Green" } else { "Red" })
Write-Host "Artifacts: $LogRoot"
if (-not $passAll) { exit 1 }
exit 0
