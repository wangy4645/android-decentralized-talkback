# RC-3 Operational Rollback Check — mechanism audit, source regression, C1 field capture.
# Mechanical helper only — does not inject faults or add product hooks.
param(
    [string]$DutSerial = "DSJ-2407041",
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$ExpectedVersionName = "1.0.0-rc.1",
    [int]$ExpectedVersionCode = 100001,
    [string]$ExpectedSourceSha = "0a74d87142eae6176de3f33a42b306c1aa3b27e8",
    [string]$PackageName = "com.talkback.appprod",
    [string]$AnchorOnlyApk = "",
    [string]$LogRoot = "",
    [string]$PriorLogRoot = "",
    [int]$C1RGateTimeoutSec = 180,
    [int]$MeetingExistsWaitSec = 900,
    [switch]$SkipMechanismAudit,
    [switch]$SkipSourceRegression,
    [switch]$SkipC1,
    [switch]$SkipPackageRollbackProbe,
    # After operator Meeting+End: resume C1 adjudication only against existing LogRoot.
    [switch]$ResumeC1,
    # C1R2: Phase0 MeetingExists → Phase1 shadow/multicast gate → End (manual; no R2 driver).
    [switch]$C1R2,
    [switch]$ResumeC1R2
)

$ErrorActionPreference = "Continue"
$scriptDir = $PSScriptRoot
$talkbackRoot = Split-Path $scriptDir -Parent
$repoRoot = Split-Path $talkbackRoot -Parent
$adb = (Get-Command adb).Source

function Write-File([string]$Path, [string]$Content) {
    $dir = Split-Path $Path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    Set-Content -Path $Path -Value $Content -Encoding UTF8
}

function Append-File([string]$Path, [string]$Line) {
    Add-Content -Path $Path -Value $Line -Encoding UTF8
}

function Invoke-AdbOn([string]$Serial, [Parameter(ValueFromRemainingArguments = $true)][string[]]$Args) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $all = @("-s", $Serial) + $Args
        & $adb @all 2>&1
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
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

function Test-SourceFileContains([string]$RelativePath, [string[]]$Needles) {
    $full = Join-Path $talkbackRoot $RelativePath
    if (-not (Test-Path $full)) {
        return [pscustomobject]@{ Path = $RelativePath; Ok = $false; MissingFile = $true; MissingNeedles = $Needles }
    }
    $text = Get-Content -Path $full -Raw
    $missing = @($Needles | Where-Object { $text -notmatch [regex]::Escape($_) })
    return [pscustomobject]@{
        Path = $RelativePath
        Ok = ($missing.Count -eq 0)
        MissingFile = $false
        MissingNeedles = $missing
    }
}

function Test-TalkbackFatal([string]$LogText) {
    if ([string]::IsNullOrEmpty($LogText)) { return $false }
    if ($LogText -match "FATAL EXCEPTION" -and $LogText -match "com\.talkback\.appprod") { return $true }
    if ($LogText -match "Fatal signal .* com\.talkback\.appprod") { return $true }
    return $false
}

function Save-TalkbackLogcat([string]$OutPath, [int]$TailLines = 8000) {
    $raw = Invoke-Adb logcat -d -t $TailLines 2>&1 | Out-String
    $filtered = ($raw -split "`r?`n") | Where-Object {
        $_ -match "talkback|Talkback|com\.talkback\.appprod|MEETING_MEDIA_SHADOW|REPLACEMENT_CUTOVER_RC1|FATAL EXCEPTION|AndroidRuntime"
    }
    Write-File $OutPath (($filtered -join "`n") + "`n")
    return ($filtered -join "`n")
}

function Start-M02LogCapture([string]$OutPath) {
    Invoke-AdbOn $DutSerial logcat -c | Out-Null
    Invoke-AdbOn $DutSerial logcat -b crash -c | Out-Null
    $logArgs = @(
        "-s", $DutSerial, "logcat", "-v", "threadtime",
        "-s", "Talkback:I", "MEETING_MEDIA_SHADOW:I", "REPLACEMENT_CUTOVER_RC1:I", "*:S"
    )
    $proc = Start-Process -FilePath $adb -ArgumentList $logArgs -RedirectStandardOutput $OutPath -PassThru -NoNewWindow
    return $proc
}

function Stop-M02LogCapture($Proc, [string]$OutPath) {
    if ($Proc -and -not $Proc.HasExited) {
        Stop-Process -Id $Proc.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500
    }
    if (Test-Path $OutPath) { return Get-Content -Path $OutPath -Raw }
    return Save-TalkbackLogcat $OutPath
}

function Test-MeetingExists([string]$LogText) {
    $participantSession =
        ($LogText -match "CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* localRole=PARTICIPANT") -or
        ($LogText -match "CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* creationSource=INVITE_ACCEPT") -or
        ($LogText -match "creationSource=INVITE_ACCEPT")
    $activeParticipant =
        ($LogText -match "CONFERENCE_RUNTIME_PROJECTION .* phase=ACTIVE host=false") -or
        ($LogText -match "CONFERENCE_RUNTIME_DECISION .* phase=ACTIVE host=false accepted=true")
    $admissionReady =
        ($LogText -match "CONFERENCE_ADMISSION_PHASE .* phase=READY")
    $meetingStartReady =
        ($LogText -match "trigger=MEETING_START") -and
        (($LogText -match "channelReadiness=READY") -or ($LogText -match "phase=ACTIVE") -or ($LogText -match "conferenceUiReady=true"))
    $pass = $participantSession -or $activeParticipant -or $admissionReady -or $meetingStartReady
    $reasons = New-Object System.Collections.Generic.List[string]
    if (-not $pass) { $reasons.Add("no_meeting_session_active_on_m02") }
    return [pscustomobject]@{
        Pass = $pass
        ParticipantSession = $participantSession
        ActiveParticipant = $activeParticipant
        AdmissionReady = $admissionReady
        MeetingStartReady = $meetingStartReady
        Reasons = $reasons
    }
}

function Test-C1RPreconditionGate([string]$LogText) {
    $shadowStarted =
        ($LogText -match "SHADOW hook=SESSION_STARTED session=[^\s]+ outcome=APPLIED") -or
        ($LogText -match "PROFILE01_SHADOW_RUNTIME phase=SHADOW_SESSION_STARTED sessionId=")
    $multicastActive =
        ($LogText -match "AUDIBLE_OWNERSHIP_STATE=.*state=MULTICAST_ACTIVE") -or
        ($LogText -match "AUDIBLE_OWNERSHIP_STATE session=[^\s]+ state=MULTICAST_ACTIVE") -or
        ($LogText -match "RC1_FIELD outcome=CUTOVER_SUCCEEDED") -or
        ($LogText -match "RC1_FIELD outcome=AUTO_CUTOVER_SUCCEEDED")
    $reasons = New-Object System.Collections.Generic.List[string]
    if (-not $shadowStarted) { $reasons.Add("no_shadow_session_started") }
    if (-not $multicastActive) { $reasons.Add("no_multicast_active") }
    return [pscustomobject]@{
        Pass = ($reasons.Count -eq 0)
        ShadowStarted = $shadowStarted
        MulticastActive = $multicastActive
        Reasons = $reasons
    }
}

function Test-C1RTeardownClosure([string]$LogText) {
    $reasons = New-Object System.Collections.Generic.List[string]
    if (Test-TalkbackFatal $LogText) {
        $reasons.Add("talkback_fatal_in_window")
    }

    $teardownObserved =
        ($LogText -match "SESSION_END_OWNERSHIP_RELEASED") -or
        ($LogText -match "SESSION_END_RELEASE_MULTICAST") -or
        ($LogText -match "detail=session_teardown") -or
        ($LogText -match "AUDIBLE_OWNERSHIP_STATE=.*detail=session_teardown")

    if (-not $teardownObserved) {
        $reasons.Add("no_teardown_reversion_observed")
    }

    $teardownIdx = [Math]::Max(
        [Math]::Max(
            ($LogText.LastIndexOf("SESSION_END_OWNERSHIP_RELEASED")),
            ($LogText.LastIndexOf("SESSION_END_RELEASE_MULTICAST"))
        ),
        ($LogText.LastIndexOf("detail=session_teardown"))
    )
    $postTeardown = if ($teardownIdx -ge 0) { $LogText.Substring($teardownIdx) } else { "" }

    $zombieAfterTeardown = $false
    if ($postTeardown -match "OWNER_VERIFY=.*multicastProduction=true") { $zombieAfterTeardown = $true }
    if ($postTeardown -match "MULTICAST_AUDIBLE_PROBE=.*active=true") { $zombieAfterTeardown = $true }
    if ($postTeardown -match "MULTICAST_AUDIBLE_PROBE session=.* active=true") { $zombieAfterTeardown = $true }
    if ($zombieAfterTeardown) {
        $reasons.Add("zombie_multicast_owner_after_teardown")
    }

    $releasedObserved =
        ($postTeardown -match "OWNER_VERIFY=.*multicastProduction=false") -or
        ($postTeardown -match "OWNER_VERIFY session=.* multicastProduction=false") -or
        ($postTeardown -match "MULTICAST_AUDIBLE_PROBE=.*active=false") -or
        ($postTeardown -match "MULTICAST_AUDIBLE_PROBE session=.* active=false") -or
        ($postTeardown -match "SESSION_END_RELEASE_MULTICAST")

    if ($teardownObserved -and -not $releasedObserved) {
        $reasons.Add("multicast_ownership_not_released_post_teardown")
    }

    $terminalValid =
        ($postTeardown -match "state=ANCHOR_ACTIVE") -or
        ($postTeardown -match "SESSION_END_OWNERSHIP_RELEASED")

    if ($teardownObserved -and -not $terminalValid) {
        $reasons.Add("terminal_ownership_state_not_valid")
    }

    return [pscustomobject]@{
        Pass = ($reasons.Count -eq 0)
        Reasons = $reasons
        TeardownObserved = $teardownObserved
        ReleasedObserved = $releasedObserved
    }
}

function Copy-PriorRc3Artifacts([string]$SourceRoot, [string]$DestRoot) {
    foreach ($name in @(
        "RC3A_MECHANISM_AUDIT.txt", "RC3B_SOURCE_REGRESSION.txt",
        "RC3C2_FATAL_ROLLBACK.txt", "RC3D_PACKAGE_ROLLBACK.txt",
        "RC3C1_NORMAL_TEARDOWN.txt"
    )) {
        $src = Join-Path $SourceRoot $name
        if (Test-Path $src) {
            Copy-Item -Path $src -Destination (Join-Path $DestRoot $name) -Force
        }
    }
}

function Read-PriorVerdicts([string]$PriorRoot) {
    $path = Join-Path $PriorRoot "RC3_RESULT.txt"
    $map = @{
        A = "UNKNOWN"; B = "UNKNOWN"; C2 = "UNKNOWN"; D = "UNKNOWN"
    }
    if (-not (Test-Path $path)) { return $map }
    $text = Get-Content $path -Raw
    if ($text -match "MECHANISM_AUDIT=([^\r\n]+)") { $map.A = $Matches[1].Trim() }
    if ($text -match "SOURCE_REGRESSION=([^\r\n]+)") { $map.B = $Matches[1].Trim() }
    if ($text -match "C2_FATAL_ROLLBACK=([^\r\n]+)") { $map.C2 = $Matches[1].Trim() }
    if ($text -match "D_OPERATIONAL_PACKAGE_ROLLBACK=([^\r\n]+)") { $map.D = $Matches[1].Trim() }
    return $map
}

function Write-C1R2Result {
    param(
        [string]$Root,
        [hashtable]$Prior,
        [string]$C1R2Verdict,
        [string]$GateVerdict = "",
        [string]$MeetingExists = "",
        [hashtable]$Extra = @{}
    )
    $combined = switch -Wildcard ($C1R2Verdict) {
        "PASS" { "PASS" }
        "TEST_NOT_STARTED" { "BLOCKED" }
        "BLOCKED*" { "BLOCKED" }
        default { "FAIL" }
    }
    $next = switch ($combined) {
        "PASS" { "RC-4_RELEASE_SIGN_OFF" }
        "BLOCKED" {
            if ($C1R2Verdict -eq "TEST_NOT_STARTED") { "RETRY_PHASE0_MEETING_EXISTS" }
            else { "STOP_RC3_BLOCKED_ON_C1R2" }
        }
        default { "STOP_RC3_FAIL" }
    }
    $lines = @(
        "RC3_OPERATIONAL_ROLLBACK"
        "mode=C1R2_ORCHESTRATION_CORRECTED"
        "MECHANISM_AUDIT=$($Prior.A)"
        "SOURCE_REGRESSION=$($Prior.B)"
        "C1_FIRST_RUN=FAIL / INVALID TEST SETUP (preserved)"
        "C1R=INVALID / PRECONDITION_NOT_ESTABLISHED (orchestration; preserved)"
        "C1R2_NORMAL_TEARDOWN_CLOSURE=$C1R2Verdict"
        "C2_FATAL_ROLLBACK=$($Prior.C2)"
        "D_OPERATIONAL_PACKAGE_ROLLBACK=$($Prior.D)"
        "combinedVerdict=$combined"
        "Next=$next"
    )
    if ($MeetingExists) { $lines += "meetingExists=$MeetingExists" }
    if ($GateVerdict) { $lines += "preconditionGate=$GateVerdict" }
    foreach ($k in $Extra.Keys) { $lines += "$k=$($Extra[$k])" }
    Write-File (Join-Path $Root "RC3_RESULT.txt") ($lines -join "`n")
    return $combined
}

function Get-M02LiveTail {
    return (Invoke-AdbOn $DutSerial logcat -d -t 1200 -s Talkback:I MEETING_MEDIA_SHADOW:I REPLACEMENT_CUTOVER_RC1:I 2>&1 | Out-String)
}

function Get-M02PartialLog([string]$M02LogPath) {
    $live = Get-M02LiveTail
    if (Test-Path $M02LogPath) {
        $file = Get-Content $M02LogPath -Raw -ErrorAction SilentlyContinue
        return ($file + "`n" + $live)
    }
    return $live
}

function Write-C1RResult {
    param(
        [string]$Root,
        [hashtable]$Prior,
        [string]$C1RVerdict,
        [string]$C1RDetail = "",
        [string]$GateVerdict = "",
        [hashtable]$Extra = @{}
    )
    $combined = switch ($C1RVerdict) {
        "PASS" { "PASS" }
        "INVALID / PRECONDITION_NOT_ESTABLISHED" { "BLOCKED" }
        default { "FAIL" }
    }
    $next = switch ($combined) {
        "PASS" { "RC-4_RELEASE_SIGN_OFF" }
        "BLOCKED" { "STOP_RC3_BLOCKED_ON_C1R" }
        default { "STOP_RC3_FAIL" }
    }
    $lines = @(
        "RC3_OPERATIONAL_ROLLBACK"
        "mode=C1R_REPLACEMENT"
        "MECHANISM_AUDIT=$($Prior.A)"
        "SOURCE_REGRESSION=$($Prior.B)"
        "C1_FIRST_RUN=FAIL / INVALID TEST SETUP (preserved; superseded by C1R)"
        "C1R_NORMAL_TEARDOWN_CLOSURE=$C1RVerdict"
        "C2_FATAL_ROLLBACK=$($Prior.C2)"
        "D_OPERATIONAL_PACKAGE_ROLLBACK=$($Prior.D)"
        "combinedVerdict=$combined"
        "Next=$next"
    )
    if ($GateVerdict) { $lines += "preconditionGate=$GateVerdict" }
    if ($C1RDetail) { $lines += "detail=$C1RDetail" }
    foreach ($k in $Extra.Keys) { $lines += "$k=$($Extra[$k])" }
    Write-File (Join-Path $Root "RC3_RESULT.txt") ($lines -join "`n")
    return $combined
}

function Get-PackageVersionInfo {
    $dump = Invoke-AdbShell "dumpsys package $PackageName" 2>&1 | Out-String
    $vn = $null; $vc = $null
    if ($dump -match "versionName=([^\s]+)") { $vn = $Matches[1] }
    if ($dump -match "versionCode=(\d+)") { $vc = [int]$Matches[1] }
    return [pscustomobject]@{ VersionName = $vn; VersionCode = $vc }
}

function Test-C1NormalTeardownClosure([string]$LogText) {
    $reasons = New-Object System.Collections.Generic.List[string]
    if (Test-TalkbackFatal $LogText) {
        $reasons.Add("talkback_fatal_in_window")
    }

    $multicastObserved =
        ($LogText -match "AUDIBLE_OWNERSHIP_STATE=.*state=MULTICAST_ACTIVE") -or
        ($LogText -match "AUDIBLE_OWNERSHIP_STATE session=.* state=MULTICAST_ACTIVE") -or
        ($LogText -match "RC1_FIELD outcome=CUTOVER_SUCCEEDED")

    if (-not $multicastObserved) {
        $reasons.Add("no_multicast_active_observed")
    }

    $teardownObserved =
        ($LogText -match "SESSION_END_OWNERSHIP_RELEASED") -or
        ($LogText -match "SESSION_END_RELEASE_MULTICAST") -or
        ($LogText -match "detail=session_teardown") -or
        ($LogText -match "AUDIBLE_OWNERSHIP_STATE=.*detail=session_teardown")

    if (-not $teardownObserved) {
        $reasons.Add("no_teardown_reversion_observed")
    }

    $teardownIdx = [Math]::Max(
        [Math]::Max(
            ($LogText.LastIndexOf("SESSION_END_OWNERSHIP_RELEASED")),
            ($LogText.LastIndexOf("SESSION_END_RELEASE_MULTICAST"))
        ),
        ($LogText.LastIndexOf("detail=session_teardown"))
    )
    $postTeardown = if ($teardownIdx -ge 0) { $LogText.Substring($teardownIdx) } else { "" }

    $zombieAfterTeardown = $false
    if ($postTeardown -match "OWNER_VERIFY=.*multicastProduction=true") { $zombieAfterTeardown = $true }
    if ($postTeardown -match "MULTICAST_AUDIBLE_PROBE=.*active=true") { $zombieAfterTeardown = $true }
    if ($postTeardown -match "MULTICAST_AUDIBLE_PROBE session=.* active=true") { $zombieAfterTeardown = $true }
    if ($zombieAfterTeardown) {
        $reasons.Add("zombie_multicast_owner_after_teardown")
    }

    $releasedObserved =
        ($postTeardown -match "OWNER_VERIFY=.*multicastProduction=false") -or
        ($postTeardown -match "OWNER_VERIFY session=.* multicastProduction=false") -or
        ($postTeardown -match "MULTICAST_AUDIBLE_PROBE=.*active=false") -or
        ($postTeardown -match "MULTICAST_AUDIBLE_PROBE session=.* active=false") -or
        ($postTeardown -match "SESSION_END_RELEASE_MULTICAST")

    if ($teardownObserved -and -not $releasedObserved) {
        $reasons.Add("multicast_ownership_not_released_post_teardown")
    }

    $terminalValid =
        ($postTeardown -match "state=ANCHOR_ACTIVE") -or
        ($postTeardown -match "SESSION_END_OWNERSHIP_RELEASED")

    if ($teardownObserved -and -not $terminalValid) {
        $reasons.Add("terminal_ownership_state_not_valid")
    }

    return [pscustomobject]@{
        Pass = ($reasons.Count -eq 0)
        Reasons = $reasons
        MulticastObserved = $multicastObserved
        TeardownObserved = $teardownObserved
        ReleasedObserved = $releasedObserved
    }
}

function Test-C2FatalRollbackEvidence {
    param(
        [string]$TestFileRelative,
        [bool]$SourceRegressionPass
    )
    $full = Join-Path $talkbackRoot $TestFileRelative
    if (-not (Test-Path $full)) {
        return [pscustomobject]@{ Pass = $false; Mode = "MISSING_TEST_FILE" }
    }
    $text = Get-Content -Path $full -Raw
    $hasFatalCase = $text -match "fatalFailure_triggersRollbackFromMulticast"
    $hasFatalCall = $text -match "onReplacementRuntimeFatal"
    $hasAnchorRestore = $text -match "ANCHOR_ACTIVE"
    if (-not $SourceRegressionPass) {
        return [pscustomobject]@{ Pass = $false; Mode = "SOURCE_REGRESSION_FAIL" }
    }
    if ($hasFatalCase -and $hasFatalCall -and $hasAnchorRestore) {
        return [pscustomobject]@{ Pass = $true; Mode = "SOURCE-PROVEN" }
    }
    return [pscustomobject]@{ Pass = $false; Mode = "EVIDENCE_GAP" }
}

$ts = Get-Date -Format "yyyyMMdd-HHmmss"
if ([string]::IsNullOrWhiteSpace($LogRoot)) {
    if ($C1R2 -or $ResumeC1R2) {
        $LogRoot = Join-Path $talkbackRoot "logs\rc3-operational-rollback-c1r2-$ts"
    } else {
        $LogRoot = Join-Path $talkbackRoot "logs\rc3-operational-rollback-$ts"
    }
}
New-Item -ItemType Directory -Force -Path $LogRoot | Out-Null

if ($C1R2 -or $ResumeC1R2) {
    if ([string]::IsNullOrWhiteSpace($PriorLogRoot)) {
        $PriorLogRoot = Join-Path $talkbackRoot "logs\rc3-operational-rollback-20260916-095738"
    }
    $priorVerdicts = Read-PriorVerdicts $PriorLogRoot
    if ($priorVerdicts.A -ne "PASS" -or $priorVerdicts.B -ne "PASS" -or $priorVerdicts.C2 -notmatch "^PASS") {
        Write-Host "C1R2 blocked: prior A/B/C2 not PASS (PriorLogRoot=$PriorLogRoot)"
        exit 1
    }
    Copy-PriorRc3Artifacts $PriorLogRoot $LogRoot | Out-Null

    $c1r2Path = Join-Path $LogRoot "RC3C1R2_NORMAL_TEARDOWN.txt"
    $meetingPath = Join-Path $LogRoot "RC3C1R2_MEETING_EXISTS.txt"
    $gatePath = Join-Path $LogRoot "RC3C1R2_GATE.txt"
    $m02LogPath = Join-Path $LogRoot "M02-logcat.txt"
    $captureMeta = Join-Path $LogRoot "RC3C1R2_CAPTURE.txt"

    if ($ResumeC1R2) {
        $gateText = if (Test-Path $gatePath) { Get-Content $gatePath -Raw } else { "" }
        if ($gateText -notmatch "preconditionGate=PASS") {
            Write-File $c1r2Path ((@(
                "C1R2_NORMAL_TEARDOWN_CLOSURE=BLOCKED / PRODUCT-CONTROL-PATH EVIDENCE REQUIRED"
                "reason=resume_without_gate_pass"
            ) -join "`n"))
            Write-C1R2Result -Root $LogRoot -Prior $priorVerdicts `
                -C1R2Verdict "BLOCKED / PRODUCT-CONTROL-PATH EVIDENCE REQUIRED" -GateVerdict "NOT_PASS" | Out-Null
            Write-Host "C1R2 resume blocked: gate not PASS logDir=$LogRoot"
            exit 1
        }
        $logProc = $null
        if (Test-Path $captureMeta) {
            $capText = Get-Content $captureMeta -Raw
            if ($capText -match "logProcId=(\d+)") {
                $procId = [int]$Matches[1]
                $logProc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            }
        }
        $state = Invoke-Adb get-state 2>&1 | Out-String
        if ($state -notmatch "device") {
            Write-File $c1r2Path (("C1R2_NORMAL_TEARDOWN_CLOSURE=FAIL", "reason=dut_not_ready", "state=$state") -join "`n")
            Write-C1R2Result -Root $LogRoot -Prior $priorVerdicts -C1R2Verdict "FAIL / TEARDOWN_REVERSION_DEFECT" | Out-Null
            exit 1
        }
        $ver = Get-PackageVersionInfo
        $logText = Stop-M02LogCapture $logProc $m02LogPath
        Write-File $captureMeta ((@(
            "captureStopped=$(Get-Date -Format o)"
            "m02LogPath=$m02LogPath"
        ) -join "`n"))
        $adj = Test-C1RTeardownClosure $logText
        $c1r2Verdict = if ($adj.Pass) { "PASS" } else { "FAIL / TEARDOWN_REVERSION_DEFECT" }
        Write-File $c1r2Path ((@(
            "C1R2_NORMAL_TEARDOWN_CLOSURE"
            "mode=ResumeC1R2"
            "topology=M01_host_M02_participant"
            "dutSerial=$DutSerial"
            "m01Serial=$M01Serial"
            "versionName=$($ver.VersionName)"
            "versionCode=$($ver.VersionCode)"
            "meetingExists=PASS"
            "preconditionGate=PASS"
            "teardownObserved=$($adj.TeardownObserved)"
            "releasedObserved=$($adj.ReleasedObserved)"
            $(if ($adj.Reasons.Count -gt 0) { "failReasons=$($adj.Reasons -join ';')" } else { "failReasons=" })
            "humanListening=NOT_ADJUDICATED"
            "C1R2_NORMAL_TEARDOWN_CLOSURE=$c1r2Verdict"
        ) -join "`n"))
        $combined = Write-C1R2Result -Root $LogRoot -Prior $priorVerdicts `
            -C1R2Verdict $c1r2Verdict -GateVerdict "PASS" -MeetingExists "PASS"
        Write-Host "C1R2 resume combined=$combined logDir=$LogRoot"
        if ($combined -eq "PASS") { exit 0 }
        exit 1
    }

    Write-File (Join-Path $LogRoot "RUN_META.txt") ((@(
        "RC3_OPERATIONAL_ROLLBACK"
        "mode=C1R2_ORCHESTRATION_CORRECTED"
        "timestamp=$ts"
        "dutSerial=$DutSerial"
        "m01Serial=$M01Serial"
        "topology=M01_host_M02_participant"
        "priorLogRoot=$PriorLogRoot"
        "expectedVersionName=$ExpectedVersionName"
        "expectedVersionCode=$ExpectedVersionCode"
        "meetingExistsWaitSec=$MeetingExistsWaitSec"
        "phase1GateTimeoutSec=$C1RGateTimeoutSec"
        "collection=Talkback+MEETING_MEDIA_SHADOW+REPLACEMENT_CUTOVER_RC1"
        "noR2LeanDriver=true"
        "supersedes=C1+C1R"
    ) -join "`n"))

    foreach ($serial in @($DutSerial, $M01Serial)) {
        $st = Invoke-AdbOn $serial get-state 2>&1 | Out-String
        if ($st -notmatch "device") {
            Write-File $c1r2Path ((@(
                "C1R2_NORMAL_TEARDOWN_CLOSURE=TEST_NOT_STARTED"
                "reason=device_offline"
                "serial=$serial"
            ) -join "`n"))
            Write-C1R2Result -Root $LogRoot -Prior $priorVerdicts `
                -C1R2Verdict "TEST_NOT_STARTED" -MeetingExists "FAIL" | Out-Null
            exit 3
        }
    }

    $ver = Get-PackageVersionInfo
    if ($ver.VersionName -ne $ExpectedVersionName -or $ver.VersionCode -ne $ExpectedVersionCode) {
        Write-File $c1r2Path ((@(
            "C1R2_NORMAL_TEARDOWN_CLOSURE=TEST_NOT_STARTED"
            "reason=package_identity_drift"
            "versionName=$($ver.VersionName)"
            "versionCode=$($ver.VersionCode)"
        ) -join "`n"))
        Write-C1R2Result -Root $LogRoot -Prior $priorVerdicts `
            -C1R2Verdict "TEST_NOT_STARTED" -MeetingExists "FAIL" | Out-Null
        exit 3
    }

    Write-Host ""
    Write-Host "=== RC-3-C1R2 (orchestration-corrected) ==="
    Write-Host "Topology: M01 host, M02 participant. No R2 lean driver."
    Write-Host "PHASE 0: M01 Start Meeting, M02 join. Then MeetingExists gate."
    Write-Host "Do NOT End until Phase1 gate PASS. Starting collection..."
    $logProc = Start-M02LogCapture $m02LogPath
    Write-File (Join-Path $LogRoot "RC3C1R2_WINDOW_OPEN.txt") ((@(
        "C1R2_WINDOW_OPEN"
        "phase=0_MANUAL_SETUP"
        "topology=M01_host_M02_participant"
        "instruction=M01 Start Meeting; M02 join as participant"
        "meetingExistsGate=SESSION_CREATED PARTICIPANT|ACTIVE host=false|ADMISSION READY|MEETING_START+READY"
        "phase1Gate=SHADOW_SESSION_STARTED AND MULTICAST_ACTIVE"
        "phase1TimerStartsAfter=MeetingExists PASS"
    ) -join "`n"))

    Write-Host ""
    Write-Host "Phase 0: waiting MeetingExists (setup wait=${MeetingExistsWaitSec}s; NOT qualification timer)..."
    $setupDeadline = (Get-Date).AddSeconds($MeetingExistsWaitSec)
    $meeting = $null
    while ((Get-Date) -lt $setupDeadline) {
        Start-Sleep -Seconds 2
        $partial = Get-M02PartialLog $m02LogPath
        $meeting = Test-MeetingExists $partial
        if ($meeting.Pass) { break }
        Write-Host "  MeetingExists pending..."
    }
    if (-not $meeting -or -not $meeting.Pass) {
        Stop-M02LogCapture $logProc $m02LogPath | Out-Null
        Write-File $meetingPath ((@(
            "MEETING_EXISTS_GATE"
            "meetingExists=FAIL"
            "failReasons=$($meeting.Reasons -join ';')"
            "verdict=TEST_NOT_STARTED"
            "note=C1R2_qualification_not_consumed"
        ) -join "`n"))
        Write-File $c1r2Path ((@(
            "C1R2_NORMAL_TEARDOWN_CLOSURE=TEST_NOT_STARTED"
            "meetingExists=FAIL"
            "humanListening=NOT_ADJUDICATED"
        ) -join "`n"))
        Write-C1R2Result -Root $LogRoot -Prior $priorVerdicts `
            -C1R2Verdict "TEST_NOT_STARTED" -MeetingExists "FAIL" | Out-Null
        Write-Host "MeetingExists FAIL -> TEST_NOT_STARTED (C1R2 not consumed). logDir=$LogRoot"
        exit 3
    }

    Write-File $meetingPath ((@(
        "MEETING_EXISTS_GATE"
        "meetingExists=PASS"
        "participantSession=$($meeting.ParticipantSession)"
        "activeParticipant=$($meeting.ActiveParticipant)"
        "admissionReady=$($meeting.AdmissionReady)"
        "meetingStartReady=$($meeting.MeetingStartReady)"
        "observedAt=$(Get-Date -Format o)"
        "phase1TimerStarts=NOW"
    ) -join "`n"))
    Write-Host "MeetingExists PASS. Phase 1 qualification timer started (${C1RGateTimeoutSec}s)..."

    $deadline = (Get-Date).AddSeconds($C1RGateTimeoutSec)
    $gate = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $partial = Get-M02PartialLog $m02LogPath
        $gate = Test-C1RPreconditionGate $partial
        if ($gate.Pass) { break }
        Write-Host ("  Phase1 gate pending shadow=$($gate.ShadowStarted) multicast=$($gate.MulticastActive)")
    }
    if (-not $gate -or -not $gate.Pass) {
        Stop-M02LogCapture $logProc $m02LogPath | Out-Null
        Write-File $gatePath ((@(
            "C1R2_PRECONDITION_GATE"
            "preconditionGate=FAIL"
            "meetingExists=PASS"
            "shadowStarted=$($gate.ShadowStarted)"
            "multicastActive=$($gate.MulticastActive)"
            "failReasons=$($gate.Reasons -join ';')"
            "verdict=BLOCKED / PRODUCT-CONTROL-PATH EVIDENCE REQUIRED"
        ) -join "`n"))
        Write-File $c1r2Path ((@(
            "C1R2_NORMAL_TEARDOWN_CLOSURE=BLOCKED / PRODUCT-CONTROL-PATH EVIDENCE REQUIRED"
            "meetingExists=PASS"
            "preconditionGate=FAIL"
            "failReasons=$($gate.Reasons -join ';')"
            "humanListening=NOT_ADJUDICATED"
        ) -join "`n"))
        Write-C1R2Result -Root $LogRoot -Prior $priorVerdicts `
            -C1R2Verdict "BLOCKED / PRODUCT-CONTROL-PATH EVIDENCE REQUIRED" `
            -GateVerdict "FAIL" -MeetingExists "PASS" | Out-Null
        Write-Host "Phase1 gate FAIL after MeetingExists PASS -> PRODUCT-CONTROL-PATH. STOP. logDir=$LogRoot"
        exit 2
    }

    Write-File $gatePath ((@(
        "C1R2_PRECONDITION_GATE"
        "preconditionGate=PASS"
        "meetingExists=PASS"
        "shadowStarted=true"
        "multicastActive=true"
        "gateObservedAt=$(Get-Date -Format o)"
    ) -join "`n"))
    Write-File $c1r2Path ((@(
        "C1R2_NORMAL_TEARDOWN_CLOSURE=GATE_PASS_PENDING_END"
        "meetingExists=PASS"
        "preconditionGate=PASS"
        "next=operator_normal_end_then_ResumeC1R2"
    ) -join "`n"))
    Write-File $captureMeta ((@(
        "logCaptureActive=true"
        "m02LogPath=$m02LogPath"
        "logProcId=$($logProc.Id)"
        "gatePassAt=$(Get-Date -Format o)"
    ) -join "`n"))
    Write-File (Join-Path $LogRoot "RC3_RESULT.txt") ((@(
        "RC3_OPERATIONAL_ROLLBACK"
        "mode=C1R2_ORCHESTRATION_CORRECTED"
        "MECHANISM_AUDIT=$($priorVerdicts.A)"
        "SOURCE_REGRESSION=$($priorVerdicts.B)"
        "C1_FIRST_RUN=FAIL / INVALID TEST SETUP (preserved)"
        "C1R=INVALID / PRECONDITION_NOT_ESTABLISHED (preserved)"
        "C1R2_NORMAL_TEARDOWN_CLOSURE=GATE_PASS_PENDING_END"
        "C2_FATAL_ROLLBACK=$($priorVerdicts.C2)"
        "D_OPERATIONAL_PACKAGE_ROLLBACK=$($priorVerdicts.D)"
        "meetingExists=PASS"
        "preconditionGate=PASS"
        "combinedVerdict=PENDING_C1R2_END"
        "Next=OPERATOR_NORMAL_END_THEN_ResumeC1R2"
        "logRoot=$LogRoot"
    ) -join "`n"))
    Write-Host ""
    Write-Host "Phase1 gate PASS. Log capture still running. NOW: normal Meeting End (M01/product UI)."
    Write-Host "Then: .\rc3-operational-rollback-field-first-run.ps1 -C1R2 -ResumeC1R2 -LogRoot `"$LogRoot`""
    Write-Host "logDir=$LogRoot"
    exit 4
}

if ($ResumeC1) {
    $c1Path = Join-Path $LogRoot "RC3C1_NORMAL_TEARDOWN.txt"
    $state = Invoke-Adb get-state 2>&1 | Out-String
    if ($state -notmatch "device") {
        Write-File $c1Path (("C1_NORMAL_TEARDOWN_CLOSURE=FAIL", "reason=dut_not_ready", "state=$state") -join "`n")
        Write-File (Join-Path $LogRoot "RC3_RESULT.txt") (("RC3_OPERATIONAL_ROLLBACK", "C1_NORMAL_TEARDOWN_CLOSURE=FAIL", "combinedVerdict=FAIL", "Next=STOP_RC3_FAIL") -join "`n")
        Write-Host "RC-3 C1 resume FAIL (dut not ready) logDir=$LogRoot"
        exit 1
    }
    $ver = Get-PackageVersionInfo
    $logText = Save-TalkbackLogcat (Join-Path $LogRoot "RC3C1_logcat.txt")
    $adj = Test-C1NormalTeardownClosure $logText
    $c1Verdict = if ($adj.Pass) { "PASS" } else { "FAIL" }
    Write-File $c1Path ((@(
        "C1_NORMAL_TEARDOWN_CLOSURE"
        "mode=ResumeC1"
        "dutSerial=$DutSerial"
        "versionName=$($ver.VersionName)"
        "versionCode=$($ver.VersionCode)"
        "multicastObserved=$($adj.MulticastObserved)"
        "teardownObserved=$($adj.TeardownObserved)"
        "releasedObserved=$($adj.ReleasedObserved)"
        $(if ($adj.Reasons.Count -gt 0) { "failReasons=$($adj.Reasons -join ';')" } else { "failReasons=" })
        "humanListening=NOT_ADJUDICATED"
        "C1_NORMAL_TEARDOWN_CLOSURE=$c1Verdict"
    ) -join "`n"))
    $prior = Join-Path $LogRoot "RC3_RESULT.txt"
    $aLine = "MECHANISM_AUDIT=UNKNOWN"
    $bLine = "SOURCE_REGRESSION=UNKNOWN"
    $c2Line = "C2_FATAL_ROLLBACK=UNKNOWN"
    $dLine = "D_OPERATIONAL_PACKAGE_ROLLBACK=UNKNOWN"
    if (Test-Path $prior) {
        $prev = Get-Content $prior -Raw
        if ($prev -match "MECHANISM_AUDIT=([^\r\n]+)") { $aLine = "MECHANISM_AUDIT=$($Matches[1])" }
        if ($prev -match "SOURCE_REGRESSION=([^\r\n]+)") { $bLine = "SOURCE_REGRESSION=$($Matches[1])" }
        if ($prev -match "C2_FATAL_ROLLBACK=([^\r\n]+)") { $c2Line = "C2_FATAL_ROLLBACK=$($Matches[1])" }
        if ($prev -match "D_OPERATIONAL_PACKAGE_ROLLBACK=([^\r\n]+)") { $dLine = "D_OPERATIONAL_PACKAGE_ROLLBACK=$($Matches[1])" }
    }
    $combined = if ($c1Verdict -eq "PASS") { "PASS" } else { "FAIL" }
    Write-File $prior ((@(
        "RC3_OPERATIONAL_ROLLBACK"
        $aLine
        $bLine
        "C1_NORMAL_TEARDOWN_CLOSURE=$c1Verdict"
        $c2Line
        $dLine
        "combinedVerdict=$combined"
        $(if ($combined -eq "PASS") { "Next=RC-4_RELEASE_SIGN_OFF" } else { "Next=STOP_RC3_FAIL" })
    ) -join "`n"))
    Write-Host "RC-3 C1 resume combined=$combined logDir=$LogRoot"
    if ($combined -eq "PASS") { exit 0 }
    exit 1
}

Write-File (Join-Path $LogRoot "RUN_META.txt") ((@(
    "RC3_OPERATIONAL_ROLLBACK"
    "timestamp=$ts"
    "dutSerial=$DutSerial"
    "expectedVersionName=$ExpectedVersionName"
    "expectedVersionCode=$ExpectedVersionCode"
    "expectedSourceSha=$ExpectedSourceSha"
    "skipMechanismAudit=$SkipMechanismAudit"
    "skipSourceRegression=$SkipSourceRegression"
    "skipC1=$SkipC1"
    "logRoot=$LogRoot"
) -join "`n"))

# --- RC-3-A ---
$aVerdict = "PASS"
$aPath = Join-Path $LogRoot "RC3A_MECHANISM_AUDIT.txt"
if ($SkipMechanismAudit) {
    Write-File $aPath "MECHANISM_AUDIT=SKIPPED`n"
    $aVerdict = "SKIPPED"
} else {
    $checks = @(
        (Test-SourceFileContains "android-board-talkback\src\main\java\com\talkback\core\conference\session\ConferenceSessionMediaCoordinatorDelegate.kt" @(
            "ReplacementCutoverRc1.onSessionTeardown"
        )),
        (Test-SourceFileContains "android-board-talkback\src\main\java\com\talkback\core\conference\session\integration\MeetingProductMediaShadow.kt" @(
            "ReplacementCutoverRc1.onReplacementRuntimeFatal"
        )),
        (Test-SourceFileContains "android-board-talkback\src\main\java\com\talkback\app\TalkbackRuntimeFactory.kt" @(
            "ReplacementCutoverRc1.install"
        )),
        (Test-SourceFileContains "android-board-talkback\src\main\java\com\talkback\core\conference\session\integration\cutover\AudibleOwnershipController.kt" @(
            "fun onSessionTeardown",
            "fun onReplacementRuntimeFatal"
        ))
    )
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("MECHANISM_AUDIT")
    $lines.Add("releaseDebugControl=INHERITED_RC1_MANIFEST")
    $lines.Add("rc1Manifest=talkback/artifacts/rc1/talkback-1.0.0-rc.1-release-manifest.txt")
    foreach ($c in $checks) {
        if ($c.MissingFile) {
            $lines.Add("FAIL missing_file=$($c.Path)")
            $aVerdict = "FAIL"
        } elseif (-not $c.Ok) {
            $lines.Add("FAIL path=$($c.Path) missing=$($c.MissingNeedles -join ',')")
            $aVerdict = "FAIL"
        } else {
            $lines.Add("PASS path=$($c.Path)")
        }
    }
    $lines.Add("MECHANISM_AUDIT=$aVerdict")
    Write-File $aPath ($lines -join "`n")
}

# --- RC-3-B ---
$bVerdict = "PASS"
$bPath = Join-Path $LogRoot "RC3B_SOURCE_REGRESSION.txt"
$bLog = Join-Path $LogRoot "RC3B_gradle.log"
if ($SkipSourceRegression) {
    Write-File $bPath "SOURCE_REGRESSION=SKIPPED`n"
    $bVerdict = "SKIPPED"
} else {
    Push-Location $talkbackRoot
    try {
        & .\gradlew.bat :android-board-talkback:testDebugUnitTest `
            --tests "com.talkback.core.conference.session.integration.cutover.AudibleOwnershipControllerTest" `
            --tests "com.talkback.core.conference.session.integration.cutover.MulticastAudibleAutomaticCutoverTest" `
            2>&1 | Tee-Object -FilePath $bLog
        $gradleExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($gradleExit -ne 0) { $bVerdict = "FAIL" }
    Write-File $bPath ((@(
        "SOURCE_REGRESSION"
        "tests=AudibleOwnershipControllerTest,MulticastAudibleAutomaticCutoverTest"
        "gradleExit=$gradleExit"
        "SOURCE_REGRESSION=$bVerdict"
    ) -join "`n"))
}

# --- RC-3-C2 (derived from B + test source) ---
$c2 = Test-C2FatalRollbackEvidence `
    -TestFileRelative "android-board-talkback\src\test\java\com\talkback\core\conference\session\integration\cutover\AudibleOwnershipControllerTest.kt" `
    -SourceRegressionPass ($bVerdict -eq "PASS")
$c2Verdict = if ($c2.Pass) { "PASS / SOURCE-PROVEN" } else { "FAIL" }
Write-File (Join-Path $LogRoot "RC3C2_FATAL_ROLLBACK.txt") ((@(
    "C2_FATAL_ROLLBACK"
    "mode=$($c2.Mode)"
    "evidence=fatalFailure_triggersRollbackFromMulticast"
    "fieldFaultInjection=NO"
    "C2_FATAL_ROLLBACK=$c2Verdict"
) -join "`n"))

# --- RC-3-D ---
$dVerdict = "NOT_EXECUTED / NO_QUALIFIED_ARTIFACT"
$dPath = Join-Path $LogRoot "RC3D_PACKAGE_ROLLBACK.txt"
if ($SkipPackageRollbackProbe) {
    $dVerdict = "SKIPPED"
    Write-File $dPath "D_OPERATIONAL_PACKAGE_ROLLBACK=SKIPPED`n"
} elseif (-not [string]::IsNullOrWhiteSpace($AnchorOnlyApk) -and (Test-Path $AnchorOnlyApk)) {
    $dVerdict = "NOT_EXECUTED / ANCHOR_ARTIFACT_PRESENT_BUT_EXECUTION_NOT_AUTHORIZED"
    Write-File $dPath ((@(
        "D_OPERATIONAL_PACKAGE_ROLLBACK"
        "anchorOnlyApk=$AnchorOnlyApk"
        "note=artifact_present_but_RC3_does_not_auto_install_without_explicit_run_card_authorization"
        "D_OPERATIONAL_PACKAGE_ROLLBACK=$dVerdict"
    ) -join "`n"))
} else {
    Write-File $dPath ((@(
        "D_OPERATIONAL_PACKAGE_ROLLBACK"
        "qualifiedAnchorOnlyArtifact=NONE"
        "rollbackStrategy=operational_redeploy_anchor_only_package_when_available"
        "verifiedCapability=NO"
        "D_OPERATIONAL_PACKAGE_ROLLBACK=$dVerdict"
    ) -join "`n"))
}

# Hard fence: A/B/C2 REQUIRED FAIL → do not enter C1.
$preC1Fail = @()
if ($aVerdict -eq "FAIL") { $preC1Fail += "A" }
if ($bVerdict -eq "FAIL") { $preC1Fail += "B" }
if ($c2Verdict -eq "FAIL") { $preC1Fail += "C2" }
if ($preC1Fail.Count -gt 0) {
    Write-File (Join-Path $LogRoot "RC3C1_NORMAL_TEARDOWN.txt") ((@(
        "C1_NORMAL_TEARDOWN_CLOSURE=NOT_EXECUTED"
        "reason=REQUIRED_PRE_C1_FAIL"
        "requiredFail=$($preC1Fail -join ',')"
    ) -join "`n"))
    Write-File (Join-Path $LogRoot "RC3_RESULT.txt") ((@(
        "RC3_OPERATIONAL_ROLLBACK"
        "MECHANISM_AUDIT=$aVerdict"
        "SOURCE_REGRESSION=$bVerdict"
        "C1_NORMAL_TEARDOWN_CLOSURE=NOT_EXECUTED"
        "C2_FATAL_ROLLBACK=$c2Verdict"
        "D_OPERATIONAL_PACKAGE_ROLLBACK=$dVerdict"
        "requiredFail=$($preC1Fail -join ',')"
        "combinedVerdict=FAIL"
        "Next=STOP_RC3_FAIL"
    ) -join "`n"))
    Write-Host "RC-3 STOP before C1: requiredFail=$($preC1Fail -join ',') logDir=$LogRoot"
    exit 1
}

# --- RC-3-C1 ---
$c1Verdict = "PASS"
$c1Path = Join-Path $LogRoot "RC3C1_NORMAL_TEARDOWN.txt"
if ($SkipC1) {
    Write-File $c1Path "C1_NORMAL_TEARDOWN_CLOSURE=SKIPPED`n"
    $c1Verdict = "SKIPPED"
} else {
    $state = Invoke-Adb get-state 2>&1 | Out-String
    if ($state -notmatch "device") {
        Write-File $c1Path (("C1_NORMAL_TEARDOWN_CLOSURE=FAIL", "reason=dut_not_ready", "state=$state") -join "`n")
        $c1Verdict = "FAIL"
    } else {
        $ver = Get-PackageVersionInfo
        if ($ver.VersionName -ne $ExpectedVersionName -or $ver.VersionCode -ne $ExpectedVersionCode) {
            Write-File $c1Path ((@(
                "C1_NORMAL_TEARDOWN_CLOSURE=FAIL"
                "reason=package_identity_drift"
                "versionName=$($ver.VersionName)"
                "versionCode=$($ver.VersionCode)"
            ) -join "`n"))
            $c1Verdict = "FAIL"
        } else {
            Write-Host ""
            Write-Host "=== RC-3-C1 Normal Teardown Closure (M02) ==="
            Write-Host "DUT already on exact rc.1 ($ExpectedVersionName / $ExpectedVersionCode)."
            Write-Host "Do NOT reinstall."
            Write-Host "Clearing logcat and opening C1 window now..."
            Invoke-Adb logcat -c | Out-Null
            Write-File (Join-Path $LogRoot "RC3C1_WINDOW_OPEN.txt") ((@(
                "C1_WINDOW_OPEN"
                "dutSerial=$DutSerial"
                "versionName=$($ver.VersionName)"
                "versionCode=$($ver.VersionCode)"
                "instruction=Run ONE short Meeting then normal End. Then resume:"
                "resume=.\rc3-operational-rollback-field-first-run.ps1 -ResumeC1 -LogRoot `"$LogRoot`""
            ) -join "`n"))
            Write-File $c1Path ((@(
                "C1_NORMAL_TEARDOWN_CLOSURE=WINDOW_OPEN"
                "dutSerial=$DutSerial"
                "versionName=$($ver.VersionName)"
                "versionCode=$($ver.VersionCode)"
                "humanListening=NOT_ADJUDICATED"
                "next=operator_short_meeting_then_ResumeC1"
            ) -join "`n"))
            Write-File (Join-Path $LogRoot "RC3_RESULT.txt") ((@(
                "RC3_OPERATIONAL_ROLLBACK"
                "MECHANISM_AUDIT=$aVerdict"
                "SOURCE_REGRESSION=$bVerdict"
                "C1_NORMAL_TEARDOWN_CLOSURE=WINDOW_OPEN"
                "C2_FATAL_ROLLBACK=$c2Verdict"
                "D_OPERATIONAL_PACKAGE_ROLLBACK=$dVerdict"
                "combinedVerdict=PENDING_C1"
                "Next=OPERATOR_SHORT_MEETING_THEN_ResumeC1"
                "logRoot=$LogRoot"
            ) -join "`n"))
            Write-Host ""
            Write-Host "A/B/C2/D complete. C1 window OPEN (logcat cleared)."
            Write-Host "Operator: ONE short Meeting on M02 → normal End."
            Write-Host "Then: .\rc3-operational-rollback-field-first-run.ps1 -ResumeC1 -LogRoot `"$LogRoot`""
            Write-Host "Or reply 继续 and agent will resume adjudication."
            Write-Host "logDir=$LogRoot"
            exit 3
        }
    }
}

# --- Combined ---
$requiredFail = @()
if ($aVerdict -eq "FAIL") { $requiredFail += "A" }
if ($bVerdict -eq "FAIL") { $requiredFail += "B" }
if ($c1Verdict -eq "FAIL") { $requiredFail += "C1" }
if ($c2Verdict -eq "FAIL") { $requiredFail += "C2" }

$combined = if ($requiredFail.Count -eq 0) { "PASS" } else { "FAIL" }
Write-File (Join-Path $LogRoot "RC3_RESULT.txt") ((@(
    "RC3_OPERATIONAL_ROLLBACK"
    "MECHANISM_AUDIT=$aVerdict"
    "SOURCE_REGRESSION=$bVerdict"
    "C1_NORMAL_TEARDOWN_CLOSURE=$c1Verdict"
    "C2_FATAL_ROLLBACK=$c2Verdict"
    "D_OPERATIONAL_PACKAGE_ROLLBACK=$dVerdict"
    "requiredFail=$($requiredFail -join ',')"
    "combinedVerdict=$combined"
    $(if ($combined -eq "PASS") { "Next=RC-4_RELEASE_SIGN_OFF" } else { "Next=STOP_RC3_FAIL" })
) -join "`n"))

Write-Host "RC-3 combined=$combined logDir=$LogRoot"
if ($combined -eq "PASS") { exit 0 }
exit 1
