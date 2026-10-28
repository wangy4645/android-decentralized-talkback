# P1-C MEDIA_KEY_PACKAGE / J0 Meeting operational / Phase A Shadow Runtime field driver.
# P1C:          Collect → positive UI → C-F7 broadcast → p1c adjudicate.
# J0:           PRE-G/I/J → wait continuation chain → j0 adjudicate (no C-F7).
# ShadowRuntime: PRE-G/I/J → J0 prerequisite → shadow window → leave/rejoin/end → shadow adjudicate.
# PaSr3: same orchestration as ShadowRuntime → PA-SR3-only adjudicator (PR-PA-SR-B1).
# PaSr4: PRE-G/I/J → J0 → SR3 same-session → PTT stimulus + observation → PA-SR4 adjudicator (SR5/SR6 SKIP).
# PaSr4Tx: same PRE/J0/PTT orchestration as PaSr4 → PA-SR4-TX adjudicator (TX packetsSent gate; SR5/SR6 SKIP).
# PaSr4Rx: same PRE/J0/PTT orchestration as PaSr4Tx → PA-SR4-RX adjudicator (M02 INGRESS gate; SR5/SR6 SKIP).
# PaSr4Play: same orchestration as PaSr4Rx → PA-SR4-PLAY adjudicator (M02 PLAYOUT gate; closes PA-SR4 on PASS).
# PaSr5: SR4-class baseline → M01 SOURCE generation replace (PA_SR5_ROTATE_LOCAL_SOURCE) → PA-SR5 adjudicator (SR6 SKIP).
# PaSr6: SR4-class baseline → M01 host Meeting end teardown → bounded quiescence → PA-SR6 adjudicator.
# PaRc1: SR4-class baseline → M02 audible ownership flip (arm/execute/rollback) → RC1 adjudicator.
# PaPilot1: RC1 cutover (no mandatory rollback) → multicast production soak → Meeting end → pilot adjudicator.
# PaG2: G2 Real Mesh — M01 host + M02/M03 invite → cutover → production soak → Meeting end.
# PaG3: G3 Stability — PaPilot1-class long production multicast soak → Meeting end.
# PaR2: R2 release smoke — 3-node Meeting, automatic cutover (NO adb RC1 broadcast).
param(
    [ValidateSet("P1C", "J0", "ShadowRuntime", "PaSr3", "PaSr4", "PaSr4Tx", "PaSr4Rx", "PaSr4Play", "PaSr5", "PaSr6", "PaRc1", "PaPilot1", "PaG2", "PaG3", "PaR2")]
    [string]$DriverMode = "P1C",
    [long]$EstablishmentKeyVersion = 1,
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "",
    [string]$PackageName = "com.talkback.appprod",
    [string]$Activity = "com.talkback.appprod/.ui.MainActivity",
    [string]$ProvisioningManifest = "",
    [int]$PositiveWaitSec = 120,
    [int]$ShadowObservationSec = 45,
    [int]$ShadowQuiescenceSec = 30,
    [int]$ShadowRejoinWaitSec = 60,
    [int]$Rc1PostCutoverSec = 20,
    [int]$Rc1PostRollbackSec = 20,
    [int]$PilotUseSec = 60,
    [int]$NegativeWaitSec = 20,
    [int]$InviteJoinWaitSec = 30,
    [int]$InviteJoinPollMs = 500,
    [int]$OperatorAssistedJoinWaitSec = 60,
    [switch]$AllowOperatorAssistedM02Join,
    [switch]$SkipBuildInstall,
    [switch]$SkipEpProbe,
    [switch]$SkipServiceColdStart,
    [switch]$PreferHeadsetPlayback
)

$ErrorActionPreference = "Stop"
$adb = (Get-Command adb).Source
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logPrefix = switch ($DriverMode) {
    "J0" { "j0-meeting-operational" }
    "ShadowRuntime" { "phase-a-shadow-runtime" }
    "PaSr3" { "phase-a-sr3-field" }
    "PaSr4" { "phase-a-sr4-field" }
    "PaSr4Tx" { "phase-a-sr4-tx-field" }
    "PaSr4Rx" { "phase-a-sr4-rx-field" }
    "PaSr4Play" { "phase-a-sr4-play-field" }
    "PaSr5" { "phase-a-sr5-field" }
    "PaSr6" { "phase-a-sr6-field" }
    "PaRc1" { "replacement-cutover-rc1-field" }
    "PaPilot1" { "controlled-pilot-run1-field" }
    "PaG2" { "ga-g2-real-mesh-field" }
    "PaG3" { "ga-g3-stability-field" }
    "PaR2" { "ga-r2-release-smoke-field" }
    default { "p1c-package-field" }
}
$logDir = Join-Path $repoRoot "talkback\logs\$logPrefix-$stamp"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$script:IsJ0Family = $DriverMode -in @("J0", "ShadowRuntime", "PaSr3", "PaSr4", "PaSr4Tx", "PaSr4Rx", "PaSr4Play", "PaSr5", "PaSr6", "PaRc1", "PaPilot1", "PaG2", "PaG3", "PaR2")
$script:IsShadowFamily = $DriverMode -in @("ShadowRuntime", "PaSr3", "PaSr4", "PaSr4Tx", "PaSr4Rx", "PaSr4Play", "PaSr5", "PaSr6", "PaRc1", "PaPilot1", "PaG2", "PaG3", "PaR2")
$script:IsPaSr4Family = $DriverMode -in @("PaSr4", "PaSr4Tx", "PaSr4Rx", "PaSr4Play")
$script:IsPaSr5Family = $DriverMode -eq "PaSr5"
$script:IsPaSr6Family = $DriverMode -eq "PaSr6"
$script:IsPaRc1Family = $DriverMode -eq "PaRc1"
$script:IsPaPilot1Family = $DriverMode -in @("PaPilot1", "PaG2", "PaG3", "PaR2")
$script:IsPaG2Family = $DriverMode -in @("PaG2", "PaR2")
$script:IsPaG3Family = $DriverMode -eq "PaG3"
$script:IsPaR2Family = $DriverMode -eq "PaR2"
$script:IsCutoverFamily = $DriverMode -in @("PaRc1", "PaPilot1", "PaG2", "PaG3", "PaR2")
$script:IsManualCutoverFamily = $DriverMode -in @("PaRc1", "PaPilot1", "PaG2", "PaG3")
$script:IsMeetingConferenceField = $DriverMode -in @("PaSr5", "PaSr6", "PaRc1", "PaPilot1", "PaG2", "PaG3", "PaR2")
if ($script:IsPaG2Family -and [string]::IsNullOrWhiteSpace($M03Serial)) {
    $M03Serial = "MDX0220416001963"
}

function Get-AdjudicateScript {
    if ($DriverMode -eq "J0") {
        return Join-Path $PSScriptRoot "j0-meeting-operational-adjudicate.ps1"
    }
    if ($DriverMode -eq "ShadowRuntime") {
        return Join-Path $PSScriptRoot "phase-a-shadow-runtime-adjudicate.ps1"
    }
    if ($DriverMode -eq "PaSr3") {
        return Join-Path $PSScriptRoot "phase-a-sr3-field-adjudicate.ps1"
    }
    if ($DriverMode -eq "PaSr4") {
        return Join-Path $PSScriptRoot "phase-a-sr4-field-adjudicate.ps1"
    }
    if ($DriverMode -eq "PaSr4Tx") {
        return Join-Path $PSScriptRoot "phase-a-sr4-tx-field-adjudicate.ps1"
    }
    if ($DriverMode -eq "PaSr4Rx") {
        return Join-Path $PSScriptRoot "phase-a-sr4-rx-field-adjudicate.ps1"
    }
    if ($DriverMode -eq "PaSr4Play") {
        return Join-Path $PSScriptRoot "phase-a-sr4-play-field-adjudicate.ps1"
    }
    if ($DriverMode -eq "PaSr5") {
        return Join-Path $PSScriptRoot "phase-a-sr5-field-adjudicate.ps1"
    }
    if ($DriverMode -eq "PaSr6") {
        return Join-Path $PSScriptRoot "phase-a-sr6-field-adjudicate.ps1"
    }
    if ($DriverMode -eq "PaRc1") {
        return Join-Path $PSScriptRoot "replacement-cutover-rc1-field-adjudicate.ps1"
    }
    if ($DriverMode -eq "PaPilot1") {
        return Join-Path $PSScriptRoot "controlled-pilot-run1-field-adjudicate.ps1"
    }
    if ($DriverMode -eq "PaG2") {
        return Join-Path $PSScriptRoot "meeting-product-ga-g2-real-mesh-field-adjudicate.ps1"
    }
    if ($DriverMode -eq "PaG3") {
        return Join-Path $PSScriptRoot "meeting-product-ga-g3-stability-field-adjudicate.ps1"
    }
    if ($DriverMode -eq "PaR2") {
        return Join-Path $PSScriptRoot "meeting-product-r2-release-smoke-field-adjudicate.ps1"
    }
    return Join-Path $PSScriptRoot "p1c-package-field-adjudicate.ps1"
}

function Invoke-Rc1Broadcast {
    param(
        [string]$Serial,
        [string]$Action,
        [string]$Remote = "M01",
        [bool]$Enabled = $true
    )
    if ($Action -eq "com.talkback.appprod.debug.RC1_PILOT_ENABLE") {
        & $adb -s $Serial shell am broadcast -a $Action --ez enabled $($Enabled.ToString().ToLower()) -p $PackageName 2>&1 | Out-Null
    } else {
        & $adb -s $Serial shell am broadcast -a $Action --es remote $Remote -p $PackageName 2>&1 | Out-Null
    }
}

function Write-Rc1Gate {
    param([hashtable]$Fields)
    $path = Join-Path $logDir "RC1_GATE.txt"
    $existing = @{}
    if (Test-Path $path) {
        Get-Content $path | ForEach-Object {
            if ($_ -match '^([^=]+)=(.*)$') { $existing[$Matches[1]] = $Matches[2] }
        }
    }
    foreach ($k in $Fields.Keys) { $existing[$k] = [string]$Fields[$k] }
    ($existing.GetEnumerator() | ForEach-Object { "{0}={1}" -f $_.Key, $_.Value }) | Set-Content -Encoding utf8 $path
}

function Get-MaxShadowMetricFromLog {
    param([string]$Path, [string]$Pattern, [string]$FieldName)
    if (-not $Path -or -not (Test-Path $Path)) { return [long]0 }
    $max = [long]0
    Select-String -Path $Path -Pattern $Pattern -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.Line -match "$FieldName=([0-9]+)") {
            $v = [long]$Matches[1]
            if ($v -gt $max) { $max = $v }
        }
    }
    return $max
}

function Get-LatestM01SourceGenerationFromPeerLog {
    param([string]$LogPath)
    if (-not $LogPath -or -not (Test-Path $LogPath)) { return $null }
    $matches = Select-String -Path $LogPath -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=(WIRING_MEMBER_INSTALLED|SHADOW_MEMBER_INSTALLED) .*moduleId=M01 .*sourceGeneration=([0-9]+)" -ErrorAction SilentlyContinue
    if (-not $matches) { return $null }
    return $matches[-1].Matches[0].Groups[2].Value
}

$script:J0ManifestFields = @{}
$script:ShadowManifestFields = @{}

function Write-J0DriverManifest([hashtable]$Fields) {
    if (-not $script:IsJ0Family) { return }
    foreach ($k in $Fields.Keys) {
        if ($Fields[$k]) { $script:J0ManifestFields[$k] = $Fields[$k] }
    }
    $path = if ($script:IsShadowFamily) {
        Join-Path $logDir "SHADOW_RUNTIME_MANIFEST.txt"
    } else {
        Join-Path $logDir "J0_FIRST_RUN_MANIFEST.txt"
    }
    $driverModeLine = switch ($DriverMode) {
        "ShadowRuntime" { "driverMode=SHADOW_RUNTIME_NO_NEGATIVE_FIXTURE" }
        "PaSr3" { "driverMode=PA_SR3_NO_NEGATIVE_FIXTURE" }
        "PaSr4" { "driverMode=PA_SR4_NO_NEGATIVE_FIXTURE" }
        "PaSr4Tx" { "driverMode=PA_SR4_TX_NO_NEGATIVE_FIXTURE" }
        "PaSr4Rx" { "driverMode=PA_SR4_RX_NO_NEGATIVE_FIXTURE" }
        "PaSr4Play" { "driverMode=PA_SR4_PLAY_NO_NEGATIVE_FIXTURE" }
        "PaSr5" { "driverMode=PA_SR5_M01_SOURCE_REPLACE" }
        "PaSr6" { "driverMode=PA_SR6_M01_MEETING_END" }
        "PaRc1" { "driverMode=RC1_M02_AUDIBLE_OWNERSHIP_FLIP" }
        "PaPilot1" { "driverMode=CONTROLLED_PILOT_RUN1" }
        "PaG2" { "driverMode=GA_G2_REAL_MESH_3NODE" }
        "PaG3" { "driverMode=GA_G3_STABILITY_SOAK" }
        "PaR2" { "driverMode=R2_RELEASE_SMOKE_AUTO_CUTOVER" }
        default { "driverMode=J0_NO_NEGATIVE_FIXTURE" }
    }
    $lines = @(
        $driverModeLine,
        "cf7Injection=OFF",
        "p1cRepublishAfterBind=OFF",
        "harnessOnlyPostBind=OFF",
        "manualContinuationDrain=OFF"
    )
    foreach ($k in @($script:J0ManifestFields.Keys | Sort-Object)) {
        $lines += "$k=$($script:J0ManifestFields[$k])"
    }
    ($lines | Select-Object -Unique) | Set-Content -Encoding utf8 $path
}

function Write-ShadowRuntimeGate([hashtable]$Fields) {
    if (-not $script:IsShadowFamily) { return }
    $path = Join-Path $logDir "SHADOW_RUNTIME_GATE.txt"
    $existing = @{}
    if (Test-Path $path) {
        Get-Content $path | ForEach-Object {
            if ($_ -match '^([^=]+)=(.*)$') { $existing[$Matches[1].Trim()] = $Matches[2].Trim() }
        }
    }
    foreach ($k in $Fields.Keys) {
        if ($null -ne $Fields[$k] -and "$($Fields[$k])" -ne "") { $existing[$k] = "$($Fields[$k])" }
    }
    ($existing.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) |
        Set-Content -Encoding utf8 $path
}

function Parse-CreationAnchorFromLogs([string]$M01Path, [string]$HostSessionId) {
    if (-not $M01Path -or -not (Test-Path $M01Path)) { return @{} }
    $line = Select-String -Path $M01Path -Pattern "PROFILE01_ORIGIN_EMIT .*factType=1 .*outcome=EMITTED" |
        Where-Object { $_.Line -match "factType=1(?:\s|[^0-9]|$)" } |
        Select-Object -First 1
    if (-not $line) { return @{} }
    $l = $line.Line
    $map = @{}
    if ($l -match "session=([^ ]+)") { $map["hostSessionId"] = $Matches[1] }
    if ($l -match "conferenceId=([^ ]+)") { $map["conferenceId"] = $Matches[1] }
    if ($l -match "factDigest=([^ ]+)") { $map["creationFactDigest"] = $Matches[1] }
    if ($HostSessionId) { $map["hostSessionId"] = $HostSessionId }
    return $map
}

function Parse-MediaKeyEpochFromLogs([string]$M02Path, [string]$ConferenceId) {
    if (-not $M02Path -or -not (Test-Path $M02Path)) { return $null }
    $line = Select-String -Path $M02Path -Pattern "INCOMPLETE_RETAIN conference=$ConferenceId" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($line -and $line.Line -match "mediaKeyEpoch=([^ ]+)") { return $Matches[1] }
    return $null
}

function Invoke-Adb([string]$Serial, [string[]]$Args) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { return ,(& $adb -s $Serial @Args 2>&1 | ForEach-Object { "$_" }) }
    finally { $ErrorActionPreference = $prev }
}

function Start-DeviceLog([string]$Serial, [string]$Label) {
    $out = Join-Path $logDir "$Label-logcat.txt"
    $crashOut = Join-Path $logDir "$Label-crash-logcat.txt"
    & $adb -s $Serial logcat -c 2>&1 | Out-Null
    & $adb -s $Serial logcat -b crash -c 2>&1 | Out-Null
    $logTags = @("-s", "Talkback:I", "MEETING_MEDIA_SHADOW:I")
    if ($script:IsCutoverFamily) {
        $logTags += "REPLACEMENT_CUTOVER_RC1:I"
    }
    $logTags += "*:S"
    $logArgs = @("-s", $Serial, "logcat", "-v", "threadtime") + $logTags
    $proc = Start-Process -FilePath $adb -ArgumentList $logArgs -RedirectStandardOutput $out -PassThru -NoNewWindow
    $crashArgs = @("-s", $Serial, "logcat", "-b", "crash", "-v", "threadtime")
    $crashProc = Start-Process -FilePath $adb -ArgumentList $crashArgs -RedirectStandardOutput $crashOut -PassThru -NoNewWindow
    return @{ Process = $proc; CrashProcess = $crashProc; Path = $out; CrashPath = $crashOut; Label = $Label; Serial = $Serial }
}

function Stop-DeviceLog($Handle) {
    if ($Handle.Process -and -not $Handle.Process.HasExited) {
        Stop-Process -Id $Handle.Process.Id -Force -ErrorAction SilentlyContinue
    }
    if ($Handle.CrashProcess -and -not $Handle.CrashProcess.HasExited) {
        Stop-Process -Id $Handle.CrashProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($Handle.Serial -and $Handle.CrashPath) {
        $prev = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $adb -s $Handle.Serial logcat -d -b crash -v threadtime 2>&1 |
                Add-Content -Path $Handle.CrashPath -Encoding utf8
        } finally {
            $ErrorActionPreference = $prev
        }
    }
}

function Get-AppPid([string]$Serial) {
    $raw = (& $adb -s $Serial shell pidof $PackageName 2>&1 | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    return ($raw -split '\s+')[0]
}

function Test-HostPidStable([string]$Serial, [int]$StableSec = 10) {
    $first = Get-AppPid $Serial
    if (-not $first) { return $false }
    $deadline = (Get-Date).AddSeconds($StableSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        $current = Get-AppPid $Serial
        if ($current -ne $first) { return $false }
    }
    return $true
}

function Test-G2CutoverReadiness(
    [string]$HostSessionId,
    $M01Handle,
    $M02Handle,
    $M03Handle,
    [string]$M01Serial,
    [int]$StableSec = 10
) {
    $logs = @($M01Handle, $M02Handle, $M03Handle) | Where-Object { $_ -and $_.Path }
    $missing = @()
    foreach ($h in $logs) {
        $ready = Select-String -Path $h.Path -Pattern "PROFILE01_MEDIA_SUPPLEMENT .*state=READY" -Quiet -ErrorAction SilentlyContinue
        $shadow = Select-String -Path $h.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_SESSION_STARTED .*sessionId=$HostSessionId" -Quiet -ErrorAction SilentlyContinue
        if (-not $ready) { $missing += "$($h.Label)_SUPPLEMENT_READY" }
        if (-not $shadow) { $missing += "$($h.Label)_SHADOW_SESSION_STARTED" }
    }
    if (-not (Test-HostPidStable $M01Serial $StableSec)) {
        $missing += "M01_PID_UNSTABLE"
    }
    $crashHit = $false
    foreach ($h in $logs) {
        if ($h.CrashPath -and (Test-Path $h.CrashPath)) {
            if (Select-String -Path $h.CrashPath -Pattern "Fatal signal|SIGABRT|backtrace:" -Quiet -ErrorAction SilentlyContinue) {
                $crashHit = $true
                $missing += "$($h.Label)_CRASH_BUFFER"
            }
        }
    }
    $srdIncomplete = Select-String -Path $M01Handle.Path -Pattern "SRD_NATIVE_CALL_ENTER.*session=$HostSessionId.*\|M02" -Quiet -ErrorAction SilentlyContinue
    $srdComplete = Select-String -Path $M01Handle.Path -Pattern "SRD_NATIVE_CALL_EXIT.*session=$HostSessionId.*\|M02" -Quiet -ErrorAction SilentlyContinue
    if ($srdIncomplete -and -not $srdComplete) {
        $missing += "M02_SRD_INCOMPLETE"
    }
    return @{ Ok = ($missing.Count -eq 0); Missing = $missing; CrashBuffer = $crashHit }
}

function Test-G2M03TxReadiness(
    [string]$HostSessionId,
    $M03Handle
) {
    $path = $M03Handle.Path
    $missing = @()
    if (-not (Test-Path $path)) {
        return @{ Ok = $false; Missing = @("M03_LOG_MISSING"); Details = @{} }
    }
    $gotBinding =
        Select-String -Path $path -Pattern "WIRE_INGRESS session=$HostSessionId factType=9 outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue
    $gotShadow =
        Select-String -Path $path -Pattern "PROFILE01_SHADOW_RUNTIME phase=SHADOW_SESSION_STARTED sessionId=$HostSessionId" -Quiet -ErrorAction SilentlyContinue
    $gotSupplement =
        (Select-String -Path $path -Pattern "WIRE_INGRESS session=$HostSessionId factType=11 outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue) -or
        (Select-String -Path $path -Pattern "PROFILE01_MEDIA_SUPPLEMENT .*state=READY" -Quiet -ErrorAction SilentlyContinue)
    if (-not $gotBinding) { $missing += "M03_MEMBER_BINDING_WIRE_APPLIED" }
    if (-not $gotShadow) { $missing += "M03_SHADOW_SESSION_STARTED" }
    if (-not $gotSupplement) { $missing += "M03_MEDIA_SUPPLEMENT_READY" }

    $txSeamClear = $false
    $m03TxObserved = $false
    if ($gotBinding -and $gotShadow -and $gotSupplement) {
        $bindingMark = "WIRE_INGRESS session=$HostSessionId factType=9 outcome=APPLIED"
        $lines = @(Get-Content $path -ErrorAction SilentlyContinue)
        $bindIdx = -1
        for ($i = $lines.Count - 1; $i -ge 0; $i--) {
            if ($lines[$i] -match $bindingMark) { $bindIdx = $i; break }
        }
        if ($bindIdx -ge 0) {
            $tail = $lines[$bindIdx..([Math]::Min($lines.Count - 1, $bindIdx + 250))]
            $m03TxObserved = [bool](
                $tail | Select-String -Pattern "PROFILE01_SHADOW_RUNTIME phase=SHADOW_TX_ACTIVITY sessionId=$HostSessionId moduleId=M03 .*packetsSent=[1-9]" -Quiet -ErrorAction SilentlyContinue
            )
            $fenceAfterBinding = @(
                $tail | Select-String -Pattern "PROFILE01_SHADOW_RUNTIME phase=SHADOW_TX_FENCED sessionId=$HostSessionId reason=NO_MEMBER_BINDING" -ErrorAction SilentlyContinue
            ).Count
            $captureOnAfterBinding = @(
                $tail | Select-String -Pattern "PTT_TIMING event=captureON session=$HostSessionId" -ErrorAction SilentlyContinue
            ).Count
            $captureOffAfterBinding = @(
                $tail | Select-String -Pattern "PTT_TIMING event=captureOFF session=$HostSessionId" -ErrorAction SilentlyContinue
            ).Count
            $captureActive = ($captureOnAfterBinding -gt $captureOffAfterBinding)
            $txSeamClear = $m03TxObserved -or ((-not $captureActive) -and ($fenceAfterBinding -eq 0))
            if (-not $txSeamClear -and $fenceAfterBinding -gt 0) {
                $missing += "M03_TX_SEAM_NO_MEMBER_BINDING"
            }
        } else {
            $missing += "M03_BINDING_MARK_NOT_FOUND"
        }
    }

    return @{
        Ok = ($missing.Count -eq 0)
        Missing = $missing
        Details = @{
            memberBindingWireApplied = $gotBinding
            shadowSessionStarted = $gotShadow
            mediaSupplementReady = $gotSupplement
            txSeamClear = $txSeamClear
            m03TxObserved = $m03TxObserved
        }
    }
}

function Wait-G2M03TxReadiness(
    [string]$HostSessionId,
    $M03Handle,
    [int]$TimeoutSec = 90
) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        $last = Test-G2M03TxReadiness -HostSessionId $HostSessionId -M03Handle $M03Handle
        if ($last.Ok) { return $last }
        Start-Sleep -Seconds 1
    }
    if ($null -eq $last) {
        $last = Test-G2M03TxReadiness -HostSessionId $HostSessionId -M03Handle $M03Handle
    }
    return $last
}

function Invoke-G2DeterministicMultiSourceStimulus(
    [string]$HostSessionId,
    [string]$M01Serial,
    [string]$M03Serial,
    $M01Handle,
    $M03Handle,
    [int]$GapSec = 2
) {
    $result = [ordered]@{
        m01PttBurstOk = $false
        m03PttBurstOk = $false
        m03PttSecondBurstOk = $false
        m03CaptureOnObserved = $false
        m03TxObserved = $false
        m01IngressFromM03 = $false
        m03IngressFromM01 = $false
        m03TxFencedNoMemberBinding = $false
    }
    if (-not (Ensure-HostPttStimulusReady $M03Serial "M03")) {
        return $result
    }
    $result.m01PttBurstOk = Invoke-HostPttStimulusBurst $M01Serial "M01" 1 2 $GapSec
    Start-Sleep -Seconds $GapSec
    $result.m03PttBurstOk = Invoke-HostPttStimulusBurst $M03Serial "M03" 1 2 $GapSec
    Start-Sleep -Seconds $GapSec
    $result.m03PttSecondBurstOk = Invoke-HostPttStimulusBurst $M03Serial "M03" 1 2 $GapSec
    Start-Sleep -Seconds $GapSec

    $obsDeadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $obsDeadline) {
        if ($M03Handle -and (Test-Path $M03Handle.Path)) {
            $result.m03CaptureOnObserved = Select-String -Path $M03Handle.Path -Pattern "PTT_TIMING event=captureON session=$HostSessionId" -Quiet -ErrorAction SilentlyContinue
            $result.m03TxObserved = Select-String -Path $M03Handle.Path -Pattern "PROFILE01_SHADOW_RUNTIME phase=SHADOW_TX_ACTIVITY sessionId=$HostSessionId moduleId=M03 .*packetsSent=[1-9]" -Quiet -ErrorAction SilentlyContinue
            $result.m03TxFencedNoMemberBinding = Select-String -Path $M03Handle.Path -Pattern "PROFILE01_SHADOW_RUNTIME phase=SHADOW_TX_FENCED sessionId=$HostSessionId reason=NO_MEMBER_BINDING" -Quiet -ErrorAction SilentlyContinue
        }
        if ($M01Handle -and (Test-Path $M01Handle.Path)) {
            $result.m01IngressFromM03 = Select-String -Path $M01Handle.Path -Pattern "PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY sessionId=$HostSessionId moduleId=M03 .*ingressAccepted=[1-9]" -Quiet -ErrorAction SilentlyContinue
        }
        if ($M03Handle -and (Test-Path $M03Handle.Path)) {
            $result.m03IngressFromM01 = Select-String -Path $M03Handle.Path -Pattern "PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY sessionId=$HostSessionId moduleId=M01 .*ingressAccepted=[1-9]" -Quiet -ErrorAction SilentlyContinue
        }
        if ($result.m03TxObserved -and $result.m01IngressFromM03 -and $result.m03IngressFromM01) { break }
        Start-Sleep -Seconds 1
    }
    return $result
}

function Write-G2MultiSourceStimulusGate(
    [string]$Dir,
    [string]$HostSessionId,
    $Readiness,
    $Stimulus
) {
    $lines = @(
        "gate=G2_MULTI_SOURCE_STIMULUS"
        "hostSessionId=$HostSessionId"
        "m03TxReadinessOk=$($Readiness.Ok)"
        "m03TxReadinessMissing=$($Readiness.Missing -join ',')"
        "memberBindingWireApplied=$($Readiness.Details.memberBindingWireApplied)"
        "shadowSessionStarted=$($Readiness.Details.shadowSessionStarted)"
        "mediaSupplementReady=$($Readiness.Details.mediaSupplementReady)"
        "txSeamClear=$($Readiness.Details.txSeamClear)"
        "m01PttBurstOk=$($Stimulus.m01PttBurstOk)"
        "m03PttBurstOk=$($Stimulus.m03PttBurstOk)"
        "m03PttSecondBurstOk=$($Stimulus.m03PttSecondBurstOk)"
        "m03CaptureOnObserved=$($Stimulus.m03CaptureOnObserved)"
        "m03TxObserved=$($Stimulus.m03TxObserved)"
        "m03TxFencedNoMemberBinding=$($Stimulus.m03TxFencedNoMemberBinding)"
        "m01IngressFromM03=$($Stimulus.m01IngressFromM03)"
        "m03IngressFromM01=$($Stimulus.m03IngressFromM01)"
    )
    $lines | Set-Content (Join-Path $Dir "G2_MULTI_SOURCE_STIMULUS.txt") -Encoding UTF8
}

function Tap-ByResourceId([string]$Serial, [string]$ResourceId) {
    $dumpPath = "/sdcard/talkback-ui-dump.xml"
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb -s $Serial shell uiautomator dump $dumpPath 2>&1 | Out-Null
        Start-Sleep -Milliseconds 500
        $localDump = Join-Path $env:TEMP "talkback-ui-$($Serial.Replace(':','_')).xml"
        & $adb -s $Serial pull $dumpPath $localDump 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $prev
    }
    if (-not (Test-Path $localDump)) { throw "UI dump missing for $Serial" }
    $xml = Get-Content $localDump -Raw
    $pattern = "resource-id=`"$ResourceId`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`""
    if ($xml -notmatch $pattern) { throw "Resource not found on ${Serial}: $ResourceId" }
    $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    $ErrorActionPreference = "Continue"
    & $adb -s $Serial shell input tap $cx $cy 2>&1 | Out-Null
    $ErrorActionPreference = $prev
    Write-Host "TAP $Serial $ResourceId @ ($cx,$cy)"
}

function Tap-IfPresent([string]$Serial, [string]$ResourceId) {
    try { Tap-ByResourceId $Serial $ResourceId; return $true } catch { return $false }
}

function Get-UiDumpXml([string]$Serial) {
    $dumpPath = "/sdcard/talkback-ui-dump.xml"
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb -s $Serial shell uiautomator dump $dumpPath 2>&1 | Out-Null
        Start-Sleep -Milliseconds 400
        $localDump = Join-Path $env:TEMP "talkback-ui-mute-$($Serial.Replace(':','_')).xml"
        & $adb -s $Serial pull $dumpPath $localDump 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $prev
    }
    if (-not (Test-Path $localDump)) { return $null }
    return (Get-Content $localDump -Raw)
}

function Test-MeetingMicMuted([string]$Serial) {
    # Mute control label flips: unmuted → "Mute"; muted → "Unmute".
    $xml = Get-UiDumpXml $Serial
    if (-not $xml) { return $false }
    if ($xml -notmatch "resource-id=`"${PackageName}:id/btnMeetingMute`"") { return $false }
    # Prefer explicit unmute affordance (already muted).
    if ($xml -match 'text="Unmute"') { return $true }
    if ($xml -match 'text="Mute"') { return $false }
    return $false
}

function Ensure-MeetingMicMuted([string]$Serial) {
    if (-not (Find-ResourcePresent $Serial "${PackageName}:id/btnMeetingMute")) {
        return $false
    }
    if (Test-MeetingMicMuted $Serial) {
        Write-Host "AUDIO: meeting mic already muted on $Serial"
        return $true
    }
    Write-Host "AUDIO: mute meeting mic on $Serial (tap btnMeetingMute)"
    if (-not (Tap-IfPresent $Serial "${PackageName}:id/btnMeetingMute")) { return $false }
    Start-Sleep -Milliseconds 500
    return (Test-MeetingMicMuted $Serial)
}

function Ensure-MeetingMicUnmuted([string]$Serial) {
    if (-not (Find-ResourcePresent $Serial "${PackageName}:id/btnMeetingMute")) {
        return $false
    }
    if (-not (Test-MeetingMicMuted $Serial)) {
        Write-Host "AUDIO: meeting mic already unmuted on $Serial"
        return $true
    }
    Write-Host "AUDIO: unmute meeting mic on $Serial for PTT stimulus"
    if (-not (Tap-IfPresent $Serial "${PackageName}:id/btnMeetingMute")) { return $false }
    Start-Sleep -Milliseconds 500
    return -not (Test-MeetingMicMuted $Serial)
}

function Apply-HeadsetPlaybackRoute([string[]]$Serials) {
    foreach ($s in $Serials) {
        if ([string]::IsNullOrWhiteSpace($s)) { continue }
        Write-Host "AUDIO: prefer headset/earpiece playback on $s (tap btnMeetingHeadset)"
        Tap-IfPresent $s "${PackageName}:id/btnMeetingHeadset" | Out-Null
        Start-Sleep -Milliseconds 300
    }
}

function Apply-MeetingMuteAll([string[]]$Serials) {
    # Field convention: all meeting endpoints stay mic-muted to avoid acoustic howl.
    # PTT stimulus paths temporarily unmute the talker, then remute.
    if (-not $script:IsMeetingConferenceField) { return }
    foreach ($s in $Serials) {
        if ([string]::IsNullOrWhiteSpace($s)) { continue }
        Ensure-MeetingMicMuted $s | Out-Null
    }
}

function Get-MeetingPlaybackSerials {
    $serials = @($M01Serial, $M02Serial)
    if ($script:IsPaG2Family -and -not [string]::IsNullOrWhiteSpace($M03Serial)) {
        $serials += $M03Serial
    }
    return ,$serials
}

function Get-ServiceStartWaitSec([string]$Serial) {
    if ($Serial -eq "MDX0220416001963") { return 20 }
    return 15
}

function Return-ToTalkTab([string]$Serial) {
    if (Tap-IfPresent $Serial "${PackageName}:id/nav_talk") { return }
    # Service sub-page on tall portrait (e.g. M03) can hide bottom nav — back out first.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $adb -s $Serial shell input keyevent KEYCODE_BACK 2>&1 | Out-Null
    Start-Sleep -Seconds 1
    if (Tap-IfPresent $Serial "${PackageName}:id/nav_talk") { return }
    # Reveal bottom navigation if still off-screen.
    & $adb -s $Serial shell input swipe 600 2400 600 2000 250 2>&1 | Out-Null
    & $adb -s $Serial shell input swipe 360 1100 360 500 250 2>&1 | Out-Null
    Start-Sleep -Milliseconds 500
    $ErrorActionPreference = $prev
    if (Tap-IfPresent $Serial "${PackageName}:id/nav_talk") { return }
    Tap-ByResourceId $Serial "${PackageName}:id/nav_talk"
}

function Start-App([string]$Serial, [switch]$ForceStop) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($ForceStop) {
            & $adb -s $Serial shell am force-stop $PackageName 2>&1 | Out-Null
            Start-Sleep -Seconds 1
        }
        & $adb -s $Serial shell am start -n $Activity 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $prev
    }
    Start-Sleep -Seconds 4
}

function Get-TalkbackProbeLines([string]$Serial, [int]$TailLines = 800) {
    # Filter on-device so chatty M02 does not drown DEBUG_DISPATCH in host-side -t windows.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $script = "logcat -d -t $TailLines | grep -E 'DEBUG_DISPATCH|Coordinator started|Coordinator stopped|runtime_unavailable' || true"
        return (& $adb -s $Serial shell $script 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Test-ForegroundRuntimeLive([string]$Serial) {
    # Operator-owned service path: accept live FG service + recent Talkback logs
    # without requiring DEBUG_DISPATCH (some OEM builds drop exported debug receivers).
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $svc = (& $adb -s $Serial shell dumpsys activity services com.talkback.appprod 2>&1 | Out-String)
        if ($svc -notmatch "TalkbackForegroundService") { return $false }
        if ($svc -notmatch "isForeground=true") { return $false }
        $logs = (& $adb -s $Serial logcat -d -t 300 -s Talkback:I 2>&1 | Out-String)
        return ($logs -match "Talkback:")
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Test-RuntimeAttached([string]$Serial) {
    # Live probe for FG service + getRuntime()!=null.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($script:IsPaR2Family) {
            if (Test-ForegroundRuntimeLive $Serial) { return $true }
            $coordText = Get-TalkbackProbeLines $Serial 1500
            $lastStarted = $coordText.LastIndexOf("Coordinator started")
            $lastStopped = $coordText.LastIndexOf("Coordinator stopped")
            if (($lastStarted -ge 0) -and ($lastStarted -gt $lastStopped)) {
                Write-Host "SERVICE runtime fallback: Coordinator started after last stop on $Serial"
                return $true
            }
            return $false
        }
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            & $adb -s $Serial shell am broadcast -a com.talkback.appprod.debug.P1C_FORCE_LEAVE_CHANNEL_SESSION -p $PackageName 2>&1 | Out-Null
            Start-Sleep -Seconds 2
            $probe = Get-TalkbackProbeLines $Serial 1000
            if ($probe -match "DEBUG_DISPATCH_DELIVERED") {
                if ($probe -match "runtime_unavailable") {
                    Start-Sleep -Seconds 2
                    continue
                }
                return $true
            }
            if ($probe -match "DEBUG_DISPATCH_COMPLETED") {
                return $true
            }
            Start-Sleep -Seconds 1
        }

        $coordText = Get-TalkbackProbeLines $Serial 1500
        $lastStarted = $coordText.LastIndexOf("Coordinator started")
        $lastStopped = $coordText.LastIndexOf("Coordinator stopped")
        if (($lastStarted -ge 0) -and ($lastStarted -gt $lastStopped)) {
            Write-Host "SERVICE runtime fallback: Coordinator started after last stop on $Serial"
            return $true
        }
        return $false
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Ensure-ServiceStarted([string]$Serial, [switch]$SkipHardReset) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
    & $adb -s $Serial shell wm dismiss-keyguard 2>&1 | Out-Null
    $ErrorActionPreference = $prev
    Start-Sleep -Seconds 1

    if ($SkipHardReset) {
        # Operator-owned: never force-stop. Prefer FG-live first — avoids am start + FORCE_LEAVE
        # probe (DEBUG_DISPATCH) which is slow and flaky on some OEMs when service is already up.
        Write-Host "SERVICE warm-start probe on $Serial (skip hard-reset)"
        if (Test-ForegroundRuntimeLive $Serial) {
            Write-Host "SERVICE runtime live on $Serial (FG service + Talkback logs)"
            Return-ToTalkTab $Serial
            Start-Sleep -Seconds 1
            return
        }
        if (Test-RuntimeAttached $Serial) {
            Write-Host "SERVICE runtime already attached on $Serial"
            Return-ToTalkTab $Serial
            Start-Sleep -Seconds 1
            return
        }
        # Bring UI forward once, then re-check FG (do not force-stop / cold-start).
        Start-App $Serial
        Start-Sleep -Seconds 2
        if (Test-ForegroundRuntimeLive $Serial) {
            Write-Host "SERVICE runtime live on $Serial after foreground"
            Return-ToTalkTab $Serial
            Start-Sleep -Seconds 1
            return
        }
        if (Test-RuntimeAttached $Serial) {
            Write-Host "SERVICE runtime already attached on $Serial after foreground"
            Return-ToTalkTab $Serial
            Start-Sleep -Seconds 1
            return
        }
        throw ("SERVICE warm-start miss on $Serial — runtime not confirmed. " +
            "Start Talkback service on-device, then re-run with -SkipServiceColdStart -SkipEpProbe.")
    }

    # Hard reset avoids FG ServiceRecord + runtime_unavailable zombies from prior stop/start races.
    Write-Host "SERVICE hard-reset process on $Serial"
    Start-App $Serial -ForceStop
    Start-Sleep -Seconds 2

    Tap-ByResourceId $Serial "${PackageName}:id/nav_settings"
    Start-Sleep -Seconds 1
    Tap-ByResourceId $Serial "${PackageName}:id/rowService"
    Start-Sleep -Seconds 1
    $started = $false
    foreach ($i in 1..5) {
        # Prefer Start; if only Stop is visible, hard-reset (never UI stop+restart).
        $hasStart = $false
        $dumpPath = "/sdcard/talkback-ui-dump.xml"
        $ErrorActionPreference = "Continue"
        & $adb -s $Serial shell uiautomator dump $dumpPath 2>&1 | Out-Null
        Start-Sleep -Milliseconds 400
        $localDump = Join-Path $env:TEMP "talkback-ui-svc-$($Serial.Replace(':','_')).xml"
        & $adb -s $Serial pull $dumpPath $localDump 2>&1 | Out-Null
        $ErrorActionPreference = $prev
        if (Test-Path $localDump) {
            $xml = Get-Content $localDump -Raw
            $hasStart = ($xml -match "resource-id=`"${PackageName}:id/btnStartService`"")
        }
        if ($hasStart) {
            Write-Host "SERVICE start on $Serial (attempt $i)"
            $ErrorActionPreference = "Continue"
            & $adb -s $Serial logcat -c 2>&1 | Out-Null
            $ErrorActionPreference = $prev
            Tap-ByResourceId $Serial "${PackageName}:id/btnStartService"
            $startWaitSec = Get-ServiceStartWaitSec $Serial
            Start-Sleep -Seconds $startWaitSec
            if (Test-RuntimeAttached $Serial) {
                Write-Host "SERVICE runtime attached on $Serial"
                $started = $true
                break
            }
            Write-Host "SERVICE start probe miss on $Serial — wait and re-probe before hard-reset"
            Start-Sleep -Seconds 10
            if (Test-RuntimeAttached $Serial) {
                Write-Host "SERVICE runtime attached on $Serial (re-probe)"
                $started = $true
                break
            }
            Write-Host "SERVICE start did not attach runtime on $Serial — hard-reset and retry"
            Start-App $Serial -ForceStop
            Start-Sleep -Seconds 2
            Tap-ByResourceId $Serial "${PackageName}:id/nav_settings"
            Start-Sleep -Seconds 1
            Tap-ByResourceId $Serial "${PackageName}:id/rowService"
            Start-Sleep -Seconds 1
            continue
        }
        if (Tap-IfPresent $Serial "${PackageName}:id/btnStopService") {
            Write-Host "SERVICE Stop visible on $Serial (attempt $i) — probe runtime before hard-reset"
            Start-Sleep -Seconds (Get-ServiceStartWaitSec $Serial)
            if (Test-RuntimeAttached $Serial) {
                Write-Host "SERVICE runtime attached on $Serial (Stop visible)"
                $started = $true
                break
            }
            # Do NOT UI stop+restart (race leaves FG service without runtime). Hard-reset instead.
            Write-Host "SERVICE Stop visible but runtime missing on $Serial — hard-reset (no UI stop+restart)"
            Start-App $Serial -ForceStop
            Start-Sleep -Seconds 2
            Tap-ByResourceId $Serial "${PackageName}:id/nav_settings"
            Start-Sleep -Seconds 1
            Tap-ByResourceId $Serial "${PackageName}:id/rowService"
            Start-Sleep -Seconds 1
            continue
        }
        Write-Host "Scroll service page on $Serial (attempt $i)..."
        $ErrorActionPreference = "Continue"
        # Portrait swipes
        & $adb -s $Serial shell input swipe 240 700 240 250 250 2>&1 | Out-Null
        & $adb -s $Serial shell input swipe 360 1100 360 500 250 2>&1 | Out-Null
        # Landscape swipes (M02 480x800 rotated → 800x480; Start Service can sit below fold)
        & $adb -s $Serial shell input swipe 400 350 400 80 250 2>&1 | Out-Null
        & $adb -s $Serial shell input swipe 200 350 200 80 250 2>&1 | Out-Null
        $ErrorActionPreference = $prev
        Start-Sleep -Seconds 1
    }
    if (-not $started) {
        throw "Could not start/confirm Talkback runtime on $Serial (FG service alone is not enough)"
    }
    Return-ToTalkTab $Serial
    Start-Sleep -Seconds 2
}

function Wait-LogPattern([string]$Path, [string]$Pattern, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $Path) -and (Select-String -Path $Path -Pattern $Pattern -Quiet)) {
            return $true
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Wait-ResourceId([string]$Serial, [string]$ResourceId, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            Tap-ByResourceId $Serial $ResourceId
            return $true
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    return $false
}

function Find-ResourcePresent([string]$Serial, [string]$ResourceId) {
    $dumpPath = "/sdcard/talkback-ui-dump.xml"
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb -s $Serial shell uiautomator dump $dumpPath 2>&1 | Out-Null
        Start-Sleep -Milliseconds 400
        $localDump = Join-Path $env:TEMP "talkback-ui-check-$($Serial.Replace(':','_')).xml"
        & $adb -s $Serial pull $dumpPath $localDump 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $prev
    }
    if (-not (Test-Path $localDump)) { return $false }
    $xml = Get-Content $localDump -Raw
    return ($xml -match "resource-id=`"$ResourceId`"")
}

function Test-ResourceClickable([string]$Serial, [string]$ResourceId) {
    $dumpPath = "/sdcard/talkback-ui-dump.xml"
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb -s $Serial shell uiautomator dump $dumpPath 2>&1 | Out-Null
        Start-Sleep -Milliseconds 400
        $localDump = Join-Path $env:TEMP "talkback-ui-click-$($Serial.Replace(':','_')).xml"
        & $adb -s $Serial pull $dumpPath $localDump 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $prev
    }
    if (-not (Test-Path $localDump)) { return $false }
    $xml = Get-Content $localDump -Raw
    if ($xml -notmatch "resource-id=`"$ResourceId`"") { return $false }
    if ($xml -match "resource-id=`"$ResourceId`"[^>]*enabled=`"false`"") { return $false }
    if ($xml -match "resource-id=`"$ResourceId`"[^>]*clickable=`"false`"") { return $false }
    return $true
}

function Get-UiResourceOpeningTag([string]$Serial, [string]$ResourceId) {
    $dumpPath = "/sdcard/talkback-ui-dump.xml"
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb -s $Serial shell uiautomator dump $dumpPath 2>&1 | Out-Null
        Start-Sleep -Milliseconds 400
        $localDump = Join-Path $env:TEMP "talkback-ui-node-$($Serial.Replace(':','_')).xml"
        & $adb -s $Serial pull $dumpPath $localDump 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $prev
    }
    if (-not (Test-Path $localDump)) { return $null }
    $xml = Get-Content $localDump -Raw
    if ($xml -match "<node[^>]*resource-id=`"$ResourceId`"[^>]*>") {
        return $Matches[0]
    }
    return $null
}

function Test-UiResourceEnabled([string]$Serial, [string]$ResourceId) {
    $tag = Get-UiResourceOpeningTag $Serial $ResourceId
    if (-not $tag) { return $false }
    return -not ($tag -match '\benabled="false"')
}

function Test-TalkPagePttReady([string]$Serial) {
    $ridPtt = "${PackageName}:id/btnPtt"
    if (-not (Find-ResourcePresent $Serial $ridPtt)) { return $false }
    return (Test-UiResourceEnabled $Serial $ridPtt)
}

function Prepare-HostPttStimulusSurface([string]$Serial, [string]$Label) {
    if ($script:IsMeetingConferenceField) {
        if (Tap-IfPresent $Serial "${PackageName}:id/btnModeMeeting") {
            Write-Host "UI: $Label select Meeting mode tab for hold-to-talk stimulus (session preserved)"
            Start-Sleep -Milliseconds 500
            return
        }
    }
    $ridModePtt = "${PackageName}:id/btnModePtt"
    if (Tap-IfPresent $Serial $ridModePtt) {
        Write-Host "UI: $Label select PTT mode tab for hold-to-talk stimulus (session preserved)"
        Start-Sleep -Milliseconds 500
    }
}

function Ensure-MeetingPagePttReady([string]$Serial, [string]$Label = "host") {
    if (Test-TalkPagePttReady $Serial) {
        Write-Host "UI: $Label Meeting page ready (btnPtt visible+enabled)"
        return $true
    }
    Write-Host "UI: $Label Meeting btnPtt not ready — bounded Meeting-tab restore (no service restart)..."
    for ($round = 1; $round -le 2; $round++) {
        $prev = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
            & $adb -s $Serial shell wm dismiss-keyguard 2>&1 | Out-Null
            & $adb -s $Serial shell am start -n $Activity 2>&1 | Out-Null
        } finally {
            $ErrorActionPreference = $prev
        }
        Start-Sleep -Seconds 1
        Tap-IfPresent $Serial "${PackageName}:id/nav_talk" | Out-Null
        Start-Sleep -Milliseconds 500
        Prepare-HostPttStimulusSurface $Serial $Label
        for ($i = 1; $i -le 12; $i++) {
            if (Test-TalkPagePttReady $Serial) {
                Write-Host "UI: $Label Meeting page ready after restore (round=$round poll=$i)"
                return $true
            }
            if (Find-ResourcePresent $Serial "${PackageName}:id/btnPtt") {
                Write-Host "UI: $Label btnPtt present but not enabled yet (round=$round poll=$i)"
            }
            Start-Sleep -Milliseconds 500
        }
        if ($round -eq 1) {
            Write-Host "UI: $Label Meeting btnPtt still not ready — retry Meeting-tab restore once..."
        }
    }
    Write-Host "UI: $Label Meeting btnPtt still missing/disabled after bounded Meeting-tab restore"
    return $false
}

function Ensure-HostPttStimulusReady([string]$Serial, [string]$Label = "host") {
    if ($script:IsMeetingConferenceField) {
        return Ensure-MeetingPagePttReady $Serial $Label
    }
    return Ensure-TalkPagePttReady $Serial $Label
}

function Test-MeetingEndControlPresent([string]$Serial) {
    foreach ($suffix in @("btnMeetingLeaveTop", "btnMeetingLeave", "btnMeetingCancel")) {
        if (Find-ResourcePresent $Serial "${PackageName}:id/$suffix") { return $true }
    }
    return $false
}

function Test-MeetingOverlayShellPresent([string]$Serial) {
    return (Find-ResourcePresent $Serial "${PackageName}:id/btnMeetingBack") -or
        (Find-ResourcePresent $Serial "${PackageName}:id/panelMeetingMain")
}

function Foreground-TalkbackApp([string]$Serial) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
        & $adb -s $Serial shell wm dismiss-keyguard 2>&1 | Out-Null
        & $adb -s $Serial shell am start -n $Activity 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $prev
    }
    Start-Sleep -Seconds 1
}

function Dismiss-MeetingSubPages([string]$Serial, [string]$Label, [int]$MaxBack = 4) {
    for ($i = 1; $i -le $MaxBack; $i++) {
        if (Test-MeetingEndControlPresent $Serial) { return $true }
        if (-not (Find-ResourcePresent $Serial "${PackageName}:id/meetingSubContainer")) { return $false }
        Write-Host "UI: $Label dismiss Meeting sub-page via BACK (step=$i)"
        $prev = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $adb -s $Serial shell input keyevent KEYCODE_BACK 2>&1 | Out-Null
        } finally {
            $ErrorActionPreference = $prev
        }
        Start-Sleep -Milliseconds 700
    }
    return (Test-MeetingEndControlPresent $Serial)
}

function Open-MeetingOverlayFromTalkShell([string]$Serial, [string]$Label) {
    # Leave controls live on MeetingFragment overlay, not Talk-tab PTT surface.
    # btnModeMeeting + channelOnlineArea both route to requestMeetingScreen/openMeetingEvents.
    $opened = $false
    if (Find-ResourcePresent $Serial "${PackageName}:id/nav_talk") {
        Tap-IfPresent $Serial "${PackageName}:id/nav_talk" | Out-Null
        Start-Sleep -Milliseconds 500
    }
    if (Tap-IfPresent $Serial "${PackageName}:id/btnModeMeeting") {
        Write-Host "UI: $Label open Meeting overlay via btnModeMeeting"
        $opened = $true
        Start-Sleep -Seconds 1
    }
    if (-not (Test-MeetingEndControlPresent $Serial) -and
        (Tap-IfPresent $Serial "${PackageName}:id/channelOnlineArea")) {
        Write-Host "UI: $Label open Meeting overlay via channelOnlineArea"
        $opened = $true
        Start-Sleep -Seconds 1
    }
    return $opened
}

function Ensure-MeetingOverlayVisible([string]$Serial, [string]$Label) {
    if (Test-MeetingEndControlPresent $Serial) { return $true }
    Foreground-TalkbackApp $Serial
    for ($round = 1; $round -le 4; $round++) {
        if (Test-MeetingEndControlPresent $Serial) {
            Write-Host "UI: $Label Meeting overlay end controls visible (round=$round)"
            return $true
        }
        if (Test-MeetingOverlayShellPresent $Serial) {
            Write-Host "UI: $Label Meeting overlay shell visible — dismiss sub-pages if any (round=$round)"
            Dismiss-MeetingSubPages $Serial $Label | Out-Null
            if (Test-MeetingEndControlPresent $Serial) { return $true }
        }
        if (Find-ResourcePresent $Serial "${PackageName}:id/nav_talk") {
            Open-MeetingOverlayFromTalkShell $Serial $Label | Out-Null
        } elseif (-not (Test-MeetingOverlayShellPresent $Serial)) {
            Write-Host "UI: $Label neither Talk shell nor Meeting overlay visible — BACK once (round=$round)"
            $prev = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            try {
                & $adb -s $Serial shell input keyevent KEYCODE_BACK 2>&1 | Out-Null
            } finally {
                $ErrorActionPreference = $prev
            }
            Start-Sleep -Milliseconds 700
            Open-MeetingOverlayFromTalkShell $Serial $Label | Out-Null
        }
        for ($poll = 1; $poll -le 12; $poll++) {
            if (Test-MeetingEndControlPresent $Serial) {
                Write-Host "UI: $Label Meeting overlay end controls ready (round=$round poll=$poll)"
                return $true
            }
            Start-Sleep -Milliseconds 500
        }
    }
    return $false
}

function Ensure-HostMeetingEndReady([string]$Serial, [string]$Label) {
    if (Test-MeetingEndControlPresent $Serial) {
        Write-Host "UI: $Label Meeting end controls visible"
        return $true
    }
    Write-Host "UI: $Label Meeting end controls missing — restore Meeting overlay (session preserved; no force-stop)..."
    $ok = Ensure-MeetingOverlayVisible $Serial $Label
    if ($ok) {
        Write-Host "UI: $Label Meeting end controls ready after overlay restore"
    } else {
        Write-Host "UI: $Label Meeting end controls still missing after overlay restore"
    }
    return $ok
}

function Invoke-HostMeetingEndUi([string]$Serial) {
    foreach ($suffix in @("btnMeetingLeaveTop", "btnMeetingLeave", "btnMeetingCancel")) {
        $rid = "${PackageName}:id/$suffix"
        if (Tap-IfPresent $Serial $rid) {
            Write-Host "UI: host Meeting end tapped $suffix"
            return $true
        }
    }
    return $false
}

function Restore-TalkPageFromMeetingOverlay([string]$Serial, [string]$Label) {
    $ridMeetingBack = "${PackageName}:id/btnMeetingBack"
    $ridTalk = "${PackageName}:id/nav_talk"
    $minimized = $false
    if (Find-ResourcePresent $Serial $ridMeetingBack) {
        Write-Host "UI: $Label meeting overlay visible — minimize via btnMeetingBack (session preserved)"
        Tap-IfPresent $Serial $ridMeetingBack | Out-Null
        Start-Sleep -Milliseconds 800
        $minimized = $true
    } elseif (-not (Find-ResourcePresent $Serial $ridTalk)) {
        Write-Host "UI: $Label bottom nav hidden — BACK once to restore Talk shell (no service restart)"
        $prev = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $adb -s $Serial shell input keyevent KEYCODE_BACK 2>&1 | Out-Null
        } finally {
            $ErrorActionPreference = $prev
        }
        Start-Sleep -Milliseconds 800
        $minimized = $true
    }
    return $minimized
}

function Ensure-TalkPagePttReady([string]$Serial, [string]$Label = "host") {
    $ridTalk = "${PackageName}:id/nav_talk"
    if (Test-TalkPagePttReady $Serial) {
        Write-Host "UI: $Label Talk page ready (btnPtt visible+enabled)"
        return $true
    }
    Write-Host "UI: $Label btnPtt not ready — bounded Talk-page restore (no service restart)..."
    for ($round = 1; $round -le 2; $round++) {
        $prev = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
            & $adb -s $Serial shell wm dismiss-keyguard 2>&1 | Out-Null
            & $adb -s $Serial shell am start -n $Activity 2>&1 | Out-Null
        } finally {
            $ErrorActionPreference = $prev
        }
        Start-Sleep -Seconds 1
        Restore-TalkPageFromMeetingOverlay $Serial $Label | Out-Null
        Tap-IfPresent $Serial $ridTalk | Out-Null
        Start-Sleep -Milliseconds 500
        Prepare-HostPttStimulusSurface $Serial $Label
        for ($i = 1; $i -le 12; $i++) {
            if (Test-TalkPagePttReady $Serial) {
                Write-Host "UI: $Label Talk page ready after restore (round=$round poll=$i)"
                return $true
            }
            if (Find-ResourcePresent $Serial "${PackageName}:id/btnPtt") {
                Write-Host "UI: $Label btnPtt present but not enabled yet (round=$round poll=$i)"
            }
            Start-Sleep -Milliseconds 500
        }
        if ($round -eq 1) {
            Write-Host "UI: $Label btnPtt still not ready — retry Talk restore once..."
        }
    }
    Write-Host "UI: $Label btnPtt still missing/disabled after bounded Talk-page restore"
    return $false
}

function Invoke-HostPttStimulusBurst(
    [string]$Serial,
    [string]$Label,
    [int]$Bursts,
    [int]$HoldSec = 2,
    [int]$GapSec = 3
) {
    $ridPtt = "${PackageName}:id/btnPtt"
    $muteManaged = $false
    if ($script:IsMeetingConferenceField -and (Find-ResourcePresent $Serial "${PackageName}:id/btnMeetingMute")) {
        # Temporary unmute for TX stimulus; always remute afterward to prevent howl.
        Ensure-MeetingMicUnmuted $Serial | Out-Null
        $muteManaged = $true
    }
    try {
        for ($ptt = 1; $ptt -le $Bursts; $ptt++) {
            if (-not (Ensure-HostPttStimulusReady $Serial $Label)) { return $false }
            if (-not (Tap-IfPresent $Serial $ridPtt)) {
                Write-Host "UI: $Label btnPtt tap failed on burst $ptt"
                return $false
            }
            Start-Sleep -Seconds $HoldSec
            if (-not (Ensure-HostPttStimulusReady $Serial $Label)) { return $false }
            if (-not (Tap-IfPresent $Serial $ridPtt)) {
                Write-Host "UI: $Label btnPtt release tap failed on burst $ptt"
                return $false
            }
            Start-Sleep -Seconds $GapSec
        }
        return $true
    } finally {
        if ($muteManaged) {
            Ensure-MeetingMicMuted $Serial | Out-Null
        }
    }
}

function Get-FirstMatch([string]$Path, [string]$Pattern) {
    if (-not (Test-Path $Path)) { return $null }
    $m = Select-String -Path $Path -Pattern $Pattern | Select-Object -First 1
    if (-not $m) { return $null }
    if ($m.Line -match $Pattern) { return $Matches[1] }
    return $null
}

function Test-LogQuiet([string]$Path, [string]$Pattern) {
    if (-not (Test-Path $Path)) { return $false }
    return [bool](Select-String -Path $Path -Pattern $Pattern -Quiet -ErrorAction SilentlyContinue)
}

function Write-JoinGate($Map) {
    $lines = @("gate=P1C_JOIN_PRECONDITION")
    foreach ($k in @($Map.Keys)) {
        $lines += "$k=$($Map[$k])"
    }
    ($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $logDir "JOIN_GATE.txt")
}

function Write-GroupGate($Map) {
    $lines = @("gate=P1C_GROUP_PRECONDITION")
    foreach ($k in @($Map.Keys)) {
        $lines += "$k=$($Map[$k])"
    }
    ($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $logDir "GROUP_GATE.txt")
}

function Write-InviteJoinGate($Map) {
    $lines = @("gate=P1C_INVITE_JOIN_PRECONDITION")
    foreach ($k in @($Map.Keys)) {
        $lines += "$k=$($Map[$k])"
    }
    ($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $logDir "INVITE_JOIN_GATE.txt")
}

function Write-UiGate($Map) {
    $lines = @("gate=HARNESS_UI_PREREQUISITE")
    foreach ($k in @($Map.Keys)) {
        $lines += "$k=$($Map[$k])"
    }
    ($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $logDir "UI_GATE.txt")
}

function Abort-FieldPrecondition([string]$Verdict, [string]$Reason, $M01Handle, $M02Handle, $JoinGate, $GroupGate, $InviteJoinGate) {
    if ($GroupGate) {
        $GroupGate["status"] = "FAIL"
        $GroupGate["verdict"] = $Verdict
        $GroupGate["reason"] = $Reason
        Write-GroupGate $GroupGate
    }
    if ($InviteJoinGate) {
        $InviteJoinGate["status"] = "FAIL"
        $InviteJoinGate["verdict"] = $Verdict
        $InviteJoinGate["reason"] = $Reason
        Write-InviteJoinGate $InviteJoinGate
    }
    if ($JoinGate) {
        $JoinGate["status"] = "FAIL"
        $JoinGate["verdict"] = $Verdict
        $JoinGate["reason"] = $Reason
        Write-JoinGate $JoinGate
    }
    Write-Host "$Verdict reason=$Reason — abort before product chain adjudication"
    if ($script:IsJ0Family) {
        Write-J0DriverManifest @{
            peerModuleId = "M02"
            abortVerdict = $Verdict
            abortReason = $Reason
        }
    } else {
        @"
injected=false
status=skipped_precondition_abort
expectedRejectReason=RECIPIENT_KEY_VERSION_MISMATCH
fixtureType=RECIPIENT_KEY_VERSION_MISMATCH
notes=C-F7 skipped due to $Verdict
"@ | Set-Content -Encoding utf8 (Join-Path $logDir "NEGATIVE_FIXTURE.txt")
    }
    if ($M01Handle) { Stop-DeviceLog $M01Handle }
    if ($M02Handle) { Stop-DeviceLog $M02Handle }
    Start-Sleep -Seconds 1
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Get-AdjudicateScript) -LogDir $logDir
    $code = $LASTEXITCODE
    Write-Host "FIRST_RUN_EXIT=$code logDir=$logDir driverMode=$DriverMode"
    exit $code
}

function Abort-JoinPrecondition([string]$Reason, $M01Handle, $M02Handle, $Gate, $InviteJoinGate) {
    Abort-FieldPrecondition "INVALID_JOIN_PRECONDITION" $Reason $M01Handle $M02Handle $Gate $null $InviteJoinGate
}

function Abort-GroupPrecondition([string]$Reason, $M01Handle, $M02Handle, $Gate) {
    Abort-FieldPrecondition "INVALID_GROUP_PRECONDITION" $Reason $M01Handle $M02Handle $null $Gate $null
}

function Abort-InviteJoinPrecondition([string]$Reason, $M01Handle, $M02Handle, $InviteJoinGate, $JoinGate) {
    Abort-FieldPrecondition "INVALID_INVITE_JOIN_PRECONDITION" $Reason $M01Handle $M02Handle $JoinGate $null $InviteJoinGate
}

function Abort-HarnessUi(
    [string]$Reason,
    [string]$Phase,
    $M01Handle,
    $M02Handle,
    $JoinGate,
    $GroupGate,
    $InviteJoinGate
) {
    Write-UiGate @{
        status = "FAIL"
        verdict = "INVALID_UI_PREREQUISITE"
        harnessAbort = "true"
        phase = $Phase
        reason = $Reason
        resource = "${PackageName}:id/btnPtt"
    }
    if ($script:IsShadowFamily) {
        Write-ShadowRuntimeGate @{
            driverObservationPhase = "HARNESS_ABORT"
            harnessAbort = "true"
            harnessAbortReason = $Reason
            harnessAbortPhase = $Phase
        }
    }
    Abort-FieldPrecondition "INVALID_UI_PREREQUISITE" $Reason $M01Handle $M02Handle $JoinGate $GroupGate $InviteJoinGate
}

function Ensure-AppForegroundTalkTab([string]$Serial) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
    & $adb -s $Serial shell wm dismiss-keyguard 2>&1 | Out-Null
    & $adb -s $Serial shell am start -n $Activity 2>&1 | Out-Null
    $ErrorActionPreference = $prev
    Start-Sleep -Seconds 1
    if (-not (Tap-IfPresent $Serial "${PackageName}:id/nav_talk")) {
        # First dump can race cold resume; one more launch+tap.
        $ErrorActionPreference = "Continue"
        & $adb -s $Serial shell am start -n $Activity 2>&1 | Out-Null
        $ErrorActionPreference = $prev
        Start-Sleep -Seconds 1
        Tap-ByResourceId $Serial "${PackageName}:id/nav_talk"
    }
}

function Ensure-PeerForegroundMeetingTab([string]$Serial) {
    Write-Host "Peer foreground + Meeting tab (no CREATE) before host invite..."
    Ensure-AppForegroundTalkTab $Serial
    Start-Sleep -Milliseconds 400
    Tap-ByResourceId $Serial "${PackageName}:id/btnModeMeeting"
    Start-Sleep -Milliseconds 400
}

function Test-PeerInviteObservedForH([string]$Path, [string]$HostSessionId) {
    if (Test-LogQuiet $Path "CONFERENCE_ADMISSION_PHASE session=$HostSessionId .* reason=INVITE_RECEIVED") { return $true }
    if (Test-LogQuiet $Path "GROUP_INVITE session=$HostSessionId") { return $true }
    if (Test-LogQuiet $Path "MEETING_INVITE_UI sessionId=$HostSessionId state=OVERLAY_VISIBLE") { return $true }
    return $false
}

function Test-PeerInviteOverlayForH([string]$Path, [string]$Serial, [string]$HostSessionId) {
    if (Test-LogQuiet $Path "MEETING_INVITE_UI sessionId=$HostSessionId state=OVERLAY_VISIBLE") { return $true }
    if (Find-ResourcePresent $Serial "${PackageName}:id/btnJoinMeeting") { return $true }
    return $false
}

function Try-TapInviteJoinButton([string]$Serial, [string]$ResourceId, [int]$MaxAttempts = 8) {
    for ($i = 1; $i -le $MaxAttempts; $i++) {
        if (Tap-IfPresent $Serial $ResourceId) {
            return $true
        }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

function Test-PeerInviteRejectOrExpiryForH([string]$Path, [string]$HostSessionId) {
    if (Test-LogQuiet $Path "Conference invite expired") { return $true }
    if (Test-LogQuiet $Path "CALL_REJECT .* sessionId=$HostSessionId") { return $true }
    if (Test-LogQuiet $Path "signalType=CALL_REJECT .* sessionId=$HostSessionId") { return $true }
    return $false
}

function Test-PeerJoinClickObservedForH([string]$Path, [string]$HostSessionId) {
    if (Test-LogQuiet $Path "MEETING_INVITE_UI sessionId=$HostSessionId state=JOIN_CLICKED") { return $true }
    if (Test-LogQuiet $Path "MEETING_INVITE_UI sessionId=$HostSessionId state=ACCEPT_DISPATCHED") { return $true }
    if (Test-LogQuiet $Path "CONFERENCE_ADMISSION_PHASE session=$HostSessionId .* phase=ACCEPTING") { return $true }
    return $false
}

function Wait-PeerInviteAcceptAfterJoin(
    [string]$PeerLogPath,
    [string]$HostSessionId,
    $InviteJoinGate,
    [int]$PollMs
) {
    if ($InviteJoinGate["PRE_I4_acceptDispatched"] -eq "PASS") { return $true }
    $acceptDeadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $acceptDeadline) {
        if (Test-PeerInviteRejectOrExpiryForH $PeerLogPath $HostSessionId) {
            $InviteJoinGate["PRE_I5_noRejectBeforeBind"] = "FAIL"
            return $false
        }
        if (Test-LogQuiet $PeerLogPath "MEETING_INVITE_UI sessionId=$HostSessionId state=ACCEPT_DISPATCHED") {
            $InviteJoinGate["PRE_I4_acceptDispatched"] = "PASS"
            return $true
        }
        if (Test-LogQuiet $PeerLogPath "CONFERENCE_ADMISSION_PHASE session=$HostSessionId .* phase=ACCEPTING") {
            $InviteJoinGate["PRE_I4_acceptDispatched"] = "PASS"
            return $true
        }
        Start-Sleep -Milliseconds $PollMs
    }
    $InviteJoinGate["PRE_I4_acceptDispatched"] = "FAIL"
    return $false
}

function Invoke-ThirdPeerInviteJoin(
    [string]$PeerSerial,
    $PeerLog,
    [string]$HostSessionId,
    [string]$PeerLabel,
    $M01Handle,
    $M02Handle,
    $JoinGate,
    $InviteJoinGate
) {
    Write-Host "G2: invite join for $PeerLabel on hostSessionId=$HostSessionId"
    # Do NOT re-enter Meeting tab here — nav_talk/btnModeMeeting dismisses an
    # already-visible invite overlay (field: OVERLAY_VISIBLE → then no Join).
    # Caller must Ensure-PeerForegroundMeetingTab before host CREATE.
    $btnJoinRid = "${PackageName}:id/btnJoinMeeting"
    $inviteDeadline = (Get-Date).AddSeconds($InviteJoinWaitSec)
    $gotI1 = $false
    $gotI2 = $false
    $clickedJoin = $false
    while ((Get-Date) -lt $inviteDeadline) {
        if (Test-PeerInviteRejectOrExpiryForH $PeerLog.Path $HostSessionId) {
            throw "G2_${PeerLabel}_invite_reject_or_expiry"
        }
        if (-not $gotI1 -and (Test-PeerInviteObservedForH $PeerLog.Path $HostSessionId)) { $gotI1 = $true }
        if ($gotI1 -and -not $gotI2 -and (Test-PeerInviteOverlayForH $PeerLog.Path $PeerSerial $HostSessionId)) { $gotI2 = $true }
        # Overlay may already be up before I1 log flush — allow Join tap either way.
        if ((-not $clickedJoin) -and (
                $gotI2 -or
                (Find-ResourcePresent $PeerSerial $btnJoinRid) -or
                (Test-LogQuiet $PeerLog.Path "MEETING_INVITE_UI sessionId=$HostSessionId state=OVERLAY_VISIBLE")
            )) {
            if ((Find-ResourcePresent $PeerSerial $btnJoinRid) -or (Try-TapInviteJoinButton $PeerSerial $btnJoinRid)) {
                Tap-ByResourceId $PeerSerial $btnJoinRid
                $clickedJoin = $true
                $gotI2 = $true
            }
        }
        if ($clickedJoin) {
            $peerInviteAccept = Test-LogQuiet $PeerLog.Path "CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=$HostSessionId .* creationSource=INVITE_ACCEPT"
            $peerBindDrain = Test-LogQuiet $PeerLog.Path "SESSION_BIND_DRAIN session=$HostSessionId"
            $peerSessionCreatedH = Test-LogQuiet $PeerLog.Path "CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=$HostSessionId"
            if ($peerInviteAccept -or $peerBindDrain -or $peerSessionCreatedH) {
                Write-Host "G2: $PeerLabel bound to hostSessionId=$HostSessionId"
                return
            }
        }
        Start-Sleep -Milliseconds $InviteJoinPollMs
    }

    if ($AllowOperatorAssistedM02Join -or $script:IsPaR2Family) {
        Write-Host "OPERATOR_ASSIST: tap Join on $PeerLabel ($PeerSerial) within ${OperatorAssistedJoinWaitSec}s — harness watches bind logs only"
        $opDeadline = (Get-Date).AddSeconds($OperatorAssistedJoinWaitSec)
        while ((Get-Date) -lt $opDeadline) {
            if (Find-ResourcePresent $PeerSerial $btnJoinRid) {
                Tap-ByResourceId $PeerSerial $btnJoinRid
                $clickedJoin = $true
            }
            $peerInviteAccept = Test-LogQuiet $PeerLog.Path "CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=$HostSessionId .* creationSource=INVITE_ACCEPT"
            $peerBindDrain = Test-LogQuiet $PeerLog.Path "SESSION_BIND_DRAIN session=$HostSessionId"
            $peerSessionCreatedH = Test-LogQuiet $PeerLog.Path "CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=$HostSessionId"
            if ($peerInviteAccept -or $peerBindDrain -or $peerSessionCreatedH) {
                Write-Host "G2: $PeerLabel bound to hostSessionId=$HostSessionId (operator-assisted)"
                return
            }
            Start-Sleep -Seconds 1
        }
    }
    throw "G2_${PeerLabel}_did_not_bind_host_session_$HostSessionId"
}

function Invoke-ProductGroupLeave([string]$Serial, [string]$Label) {
    Write-Host "GROUP leave via product leaveChannelSession broadcast on $Label ($Serial)..."
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb -s $Serial shell am broadcast -a com.talkback.appprod.debug.P1C_FORCE_LEAVE_CHANNEL_SESSION -p $PackageName 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Get-LastMatchLine([string]$Path, [string]$Pattern) {
    if (-not (Test-Path $Path)) { return $null }
    $m = Select-String -Path $Path -Pattern $Pattern -ErrorAction SilentlyContinue | Select-Object -Last 1
    if ($m) { return $m.Line }
    return $null
}

function Test-LatestLeaveClear([string]$Path) {
    $line = Get-LastMatchLine $Path "P1C_GROUP_PRECONDITION_LEAVE .*outcome="
    if (-not $line) { return $false }
    return ($line -match "outcome=CLEAR")
}

function Test-GroupQuietAfterMark([string]$Path, [string]$MarkPattern, [string]$ChannelId) {
    if (-not (Test-Path $Path)) { return $false }
    $lines = Get-Content $Path -ErrorAction SilentlyContinue
    if (-not $lines) { return $false }
    $markIdx = -1
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        if ($lines[$i] -match $MarkPattern) { $markIdx = $i; break }
    }
    if ($markIdx -lt 0) { return $false }
    if ($markIdx -ge ($lines.Count - 1)) { return $true }
    $tail = $lines[($markIdx + 1)..($lines.Count - 1)]
    $grp = "grp:$ChannelId"
    foreach ($line in $tail) {
        if ($line -match "P1C_GROUP_PRECONDITION_LEAVE") { continue }
        # Teardown / hangup lines for the dying session are OK; reformation is not.
        if ($line -match "IDLE->GROUP_PTT") { return $false }
        if ($line -match "CHANNEL_MODE ch=$ChannelId .*->GROUP_PTT") { return $false }
        if ($line -match "GROUP_INVITE session=$grp") { return $false }
        if ($line -match "acceptGroupInvite" -and $line -match $grp) { return $false }
        if ($line -match "signalType=HEARTBEAT session=$grp") { return $false }
        if ($line -match "signalType=WEBRTC_ICE session=$grp") { return $false }
    }
    return $true
}

$buildSha = (git -C (Join-Path $repoRoot "talkback") rev-parse --short HEAD 2>$null)
if (-not $buildSha) { $buildSha = "unknown" }
$apkPath = Join-Path $repoRoot "talkback\talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
$testApk = Join-Path $repoRoot "talkback\talkback-app\build\outputs\apk\androidTest\debug\talkback-app-debug-androidTest.apk"
if (-not $ProvisioningManifest) {
    $ProvisioningManifest = Join-Path $repoRoot "talkback\logs\ep-establishment-operator-20260908-155431\PROVISIONING_MANIFEST.txt"
}

$runTitle = switch ($DriverMode) {
    "J0" { "J0 FIRST-RUN" }
    "ShadowRuntime" { "PHASE A SHADOW RUNTIME FIRST-RUN" }
    "PaSr3" { "PA-SR3 FIELD FIRST-RUN" }
    "PaSr4" { "PA-SR4 FIELD FIRST-RUN" }
    "PaSr4Tx" { "PA-SR4-TX FIELD FIRST-RUN" }
    "PaSr4Rx" { "PA-SR4-RX FIELD FIRST-RUN" }
    "PaSr4Play" { "PA-SR4-PLAY FIELD FIRST-RUN" }
    "PaSr5" { "PA-SR5 FIELD FIRST-RUN" }
    "PaSr6" { "PA-SR6 FIELD FIRST-RUN" }
    "PaRc1" { "REPLACEMENT CUTOVER RC1 FIELD FIRST-RUN" }
    "PaPilot1" { "CONTROLLED PILOT RUN-1 FIELD" }
    "PaG2" { "GA G2 REAL MESH 3-NODE FIELD" }
    "PaG3" { "GA G3 STABILITY SOAK FIELD" }
    "PaR2" { "R2 RELEASE SMOKE FIELD" }
    default { "P1-C FIRST-RUN" }
}
Write-Host $runTitle
Write-Host "logDir=$logDir"
Write-Host "buildSha=$buildSha"
Write-Host "driverMode=$DriverMode"

if ($script:IsJ0Family) {
    Write-J0DriverManifest @{ peerModuleId = "M02" }
}

if (-not $SkipBuildInstall) {
    Push-Location (Join-Path $repoRoot "talkback")
    & .\gradlew.bat :talkback-app:assembleDebug :talkback-app:assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw "assembleDebug failed" }
    Pop-Location
    foreach ($s in @($M01Serial, $M02Serial)) {
        Write-Host "INSTALL $s"
        & $adb -s $s install -r $apkPath
        if (Test-Path $testApk) { & $adb -s $s install -r $testApk }
    }
}

# PRE-1 trust presence (debuggable builds only; release PaR2 uses warm establishment from prior field runs)
if ($script:IsPaR2Family) {
    Write-Host "PRE-1 SKIP: PaR2 release build — run-as unavailable; warm establishment assumed"
} else {
    foreach ($pair in @(@($M01Serial, "M01"), @($M02Serial, "M02"))) {
        $raw = & $adb -s $pair[0] shell "run-as $PackageName ls files/gbc-trust" 2>&1 | Out-String
        Write-Host "PRE-1 $($pair[1]) gbc-trust listing:`n$raw"
        if ($raw -notmatch "accepted-local-establishment-trust-state\.bin") {
            throw "PRE-1 FAIL: missing establishment trust on $($pair[1])"
        }
        if ($raw -notmatch "accepted-local-trust-state\.bin") {
            throw "PRE-1 FAIL: missing signing trust on $($pair[1])"
        }
        if ($raw -notmatch "operational-trust-anchor\.bin") {
            throw "PRE-1 FAIL: missing operational anchor on $($pair[1])"
        }
        Write-Host "PRE-1 $($pair[1]) establishment trust PRESENT"
    }
}

if (-not $SkipEpProbe) {
    Write-Host "PRE-2/PRE-3 EP probe..."
    foreach ($pair in @(@($M01Serial, "M02"), @($M02Serial, "M01"))) {
        $serial = $pair[0]
        & $adb -s $serial shell am force-stop $PackageName 2>&1 | Out-Null
        Start-Sleep -Seconds 1
        & $adb -s $serial shell am start -n $Activity 2>&1 | Out-Null
        Start-Sleep -Seconds 3
        & $adb -s $serial shell am instrument -w -r `
            -e class "com.talkback.appprod.fieldtest.EpEstablishmentFieldGateInstrumentedTest#emitEpEstablishmentFieldProbes" `
            -e establishmentKeyVersion $EstablishmentKeyVersion `
            -e peerModuleId $pair[1] `
            -e ep3Mode skip `
            "$PackageName.test/androidx.test.runner.AndroidJUnitRunner"
    }
}

$gateName = switch ($DriverMode) {
    "J0" { "J0_MEETING_OPERATIONAL_FIRST_RUN" }
    "ShadowRuntime" { "PHASE_A_SHADOW_RUNTIME_FIRST_RUN" }
    "PaSr3" { "PA_SR3_FIELD_FIRST_RUN" }
    "PaSr4" { "PA_SR4_FIELD_FIRST_RUN" }
    "PaSr4Tx" { "PA_SR4_TX_FIELD_FIRST_RUN" }
    "PaSr4Rx" { "PA_SR4_RX_FIELD_FIRST_RUN" }
    "PaSr4Play" { "PA_SR4_PLAY_FIELD_FIRST_RUN" }
    "PaSr5" { "PA_SR5_FIELD_FIRST_RUN" }
    "PaSr6" { "PA_SR6_FIELD_FIRST_RUN" }
    "PaRc1" { "RC1_FIELD_FIRST_RUN" }
    "PaPilot1" { "CONTROLLED_PILOT_RUN1" }
    "PaG2" { "GA_G2_REAL_MESH_FIELD" }
    "PaG3" { "GA_G3_STABILITY_FIELD" }
    "PaR2" { "R2_RELEASE_SMOKE_FIELD" }
    default { "P1-C_PACKAGE_FIELD_FIRST_RUN" }
}
$driverModeMeta = switch ($DriverMode) {
    "J0" { "J0_NO_NEGATIVE_FIXTURE" }
    "ShadowRuntime" { "SHADOW_RUNTIME_NO_NEGATIVE_FIXTURE" }
    "PaSr3" { "PA_SR3_NO_NEGATIVE_FIXTURE" }
    "PaSr4" { "PA_SR4_NO_NEGATIVE_FIXTURE" }
    "PaSr4Tx" { "PA_SR4_TX_NO_NEGATIVE_FIXTURE" }
    "PaSr4Rx" { "PA_SR4_RX_NO_NEGATIVE_FIXTURE" }
    "PaSr4Play" { "PA_SR4_PLAY_NO_NEGATIVE_FIXTURE" }
    "PaSr5" { "PA_SR5_M01_SOURCE_REPLACE" }
    "PaSr6" { "PA_SR6_M01_MEETING_END" }
    "PaRc1" { "RC1_M02_AUDIBLE_OWNERSHIP_FLIP" }
    "PaPilot1" { "CONTROLLED_PILOT_RUN1" }
    "PaG2" { "GA_G2_REAL_MESH_3NODE" }
    "PaG3" { "GA_G3_STABILITY_SOAK" }
    "PaR2" { "R2_RELEASE_SMOKE_AUTO_CUTOVER" }
    default { "P1C_WITH_CF7" }
}
$cf7Injection = if ($DriverMode -eq "P1C") { "ON" } else { "OFF" }
$preTitle = switch ($DriverMode) {
    "J0" { "J0 Meeting operational PRECONDITIONS" }
    "ShadowRuntime" { "Phase A Shadow Runtime PRECONDITIONS" }
    "PaSr3" { "PA-SR3 field PRECONDITIONS" }
    "PaSr4" { "PA-SR4 field PRECONDITIONS" }
    "PaSr4Tx" { "PA-SR4-TX field PRECONDITIONS" }
    "PaSr4Rx" { "PA-SR4-RX field PRECONDITIONS" }
    "PaSr4Play" { "PA-SR4-PLAY field PRECONDITIONS" }
    "PaSr5" { "PA-SR5 field PRECONDITIONS" }
    "PaSr6" { "PA-SR6 field PRECONDITIONS" }
    "PaRc1" { "RC1 field PRECONDITIONS" }
    "PaPilot1" { "Controlled Pilot Run-1 PRECONDITIONS" }
    "PaG2" { "GA G2 Real Mesh 3-node PRECONDITIONS" }
    "PaR2" { "R2 Release smoke PRECONDITIONS" }
    "PaG3" { "GA G3 Stability soak PRECONDITIONS" }
    default { "P1-C package field PRECONDITIONS" }
}
@"
run_stamp=$stamp
gate=$gateName
driverMode=$driverModeMeta
hostModuleId=M01
expectedRecipientModuleId=M02
peerModuleId=M02
establishmentKeyVersion=$EstablishmentKeyVersion
m01_serial=$M01Serial
m02_serial=$M02Serial
m03_serial=$M03Serial
packageName=$PackageName
buildSha=$buildSha
versionName=1.0.0
collect_only=false
first_run=true
cf7Injection=$cf7Injection
p1cRepublishAfterBind=OFF
harnessOnlyPostBind=OFF
manualContinuationDrain=OFF
shadowObservationSec=$ShadowObservationSec
shadowQuiescenceSec=$ShadowQuiescenceSec
shadowRejoinWaitSec=$ShadowRejoinWaitSec
"@ | Set-Content -Encoding utf8 (Join-Path $logDir "RUN_META.txt")

$preLines = @(
    $preTitle
    "buildSha=$buildSha"
    "versionName=1.0.0"
    "devices=M01=$M01Serial M02=$M02Serial"
    "hostModuleId=M01"
    "expectedRecipientModuleId=M02"
    "peerModuleId=M02"
    "establishmentKeyVersion=$EstablishmentKeyVersion"
    "sameCandidateApk=YES"
    "productionGbcTrustWiring=YES"
    "productionEstablishmentWiring=YES"
    "p1cOriginBridge=YES"
    "p1cFieldObs=YES"
    "epProvisioningValid=YES"
    "ep2KeystoreVerifiedCorroboration=EP2_PASS_KEYSTORE_VERIFIED"
)
if ($script:IsJ0Family) {
    $preLines += @(
        "doNotRequireCreationApplied=false"
        "doNotAdjudicateIncompleteDrain=false"
        "doNotAdjudicateMeetingOperational=false"
        "cf7Injection=OFF"
        "j0ContinuationAutoOnly=YES"
    )
    if ($script:IsShadowFamily) {
        $shadowGateLabel = switch ($DriverMode) {
            "PaSr3" { "PA-SR3_ONLY" }
            "PaSr4" { "PA-SR4_ONLY" }
            "PaSr4Tx" { "PA-SR4-TX_ONLY" }
            "PaSr4Rx" { "PA-SR4-RX_ONLY" }
            "PaSr4Play" { "PA-SR4-PLAY_CLOSES_PA_SR4_ON_PASS" }
            "PaSr5" { "PA-SR5_M01_SOURCE_REPLACE" }
            "PaSr6" { "PA_SR6_M01_MEETING_END" }
            "PaRc1" { "RC1_M02_AUDIBLE_OWNERSHIP_FLIP" }
            "PaPilot1" { "CONTROLLED_PILOT_RUN1" }
            default { "PA-SR1..SR6" }
        }
        $preLines += @(
            "shadowRuntimeGate=$shadowGateLabel"
            "shadowObservationSec=$ShadowObservationSec"
            "shadowRejoinWaitSec=$ShadowRejoinWaitSec"
            "audioTrackFence=NO_PRODUCTION_OWNER"
        )
        if ($script:IsPaSr4Family) {
            $preLines += @(
                "driverLeavePhase=SKIPPED"
                "driverRejoinPhase=SKIPPED"
                "driverEndPhase=SKIPPED"
                "pttStimulus=3x_M01_STIMULUS_NOT_PASS_CRITERION"
            )
            if ($DriverMode -eq "PaSr4Tx") {
                $preLines += @(
                    "txGate=SHADOW_TX_ACTIVITY_packetsSent_gt0"
                    "paSr4FullGate=NOT_CLOSED_BY_TX_PASS"
                )
            }
            if ($DriverMode -eq "PaSr4Rx") {
                $preLines += @(
                    "txPrerequisite=SHADOW_TX_ACTIVITY_packetsSent_gt0_M01"
                    "rxGate=INGRESS_ACTIVITY_packetsReceived_gt0_M02"
                    "playoutGate=OBSERVE_ONLY_NOT_PASS"
                    "paSr4FullGate=NOT_CLOSED_BY_RX_PASS"
                )
            }
            if ($DriverMode -eq "PaSr4Play") {
                $preLines += @(
                    "txPrerequisite=SHADOW_TX_ACTIVITY_packetsSent_gt0_M01"
                    "ingressPrerequisite=INGRESS_ACTIVITY_packetsReceived_gt0_M02"
                    "playGate=PLAYOUT_ACTIVITY_successfulPlayoutWrites_gt0_M02"
                    "paSr4FullGate=CLOSED_ON_PLAY_PASS"
                )
            }
        }
        if ($script:IsPaSr5Family) {
            $preLines += @(
                "driverLeavePhase=SKIPPED"
                "driverRejoinPhase=SKIPPED"
                "driverEndPhase=SKIPPED"
                "sr5Stimulus=PA_SR5_ROTATE_LOCAL_SOURCE_M01"
                "sr5SubjectModule=M01"
                "sr5AdjudicationPlane=M02"
                "pttStimulus=3x_M01_STIMULUS_NOT_PASS_CRITERION"
            )
        }
        if ($script:IsPaSr6Family) {
            $preLines += @(
                "driverLeavePhase=SKIPPED"
                "driverRejoinPhase=SKIPPED"
                "driverRotatePhase=SKIPPED"
                "sr6Stimulus=M01_host_Meeting_end_product_path"
                "sr6SubjectModule=M01"
                "sr6AdjudicationPlane=M02"
                "shadowQuiescenceSec=$ShadowQuiescenceSec"
                "pttStimulus=3x_M01_STIMULUS_NOT_PASS_CRITERION"
            )
        }
        if ($script:IsPaRc1Family) {
            $preLines += @(
                "driverLeavePhase=SKIPPED"
                "driverRejoinPhase=SKIPPED"
                "driverEndPhase=SKIPPED"
                "driverRotatePhase=SKIPPED"
                "rc1Stimulus=M02_audible_ownership_flip"
                "rc1SubjectModule=M02"
                "rc1AdjudicationPlane=M02"
                "rc1PostCutoverSec=$Rc1PostCutoverSec"
                "rc1PostRollbackSec=$Rc1PostRollbackSec"
                "pttStimulus=3x_M01_STIMULUS_NOT_PASS_CRITERION"
            )
        }
        if ($script:IsPaPilot1Family) {
            $preLines += @(
                "driverLeavePhase=SKIPPED"
                "driverRejoinPhase=SKIPPED"
                "driverRotatePhase=SKIPPED"
                "pilotStimulus=M02_cutover_then_meeting_use_then_end"
                "pilotSubjectModule=M02"
                "pilotAdjudicationPlane=M02"
                "pilotUseSec=$PilotUseSec"
                "rc1PostCutoverSec=$Rc1PostCutoverSec"
                "mandatoryRollback=false"
                "pttStimulus=M01_STIMULUS_DURING_PILOT_USE"
            )
            if ($script:IsPaG2Family) {
                $preLines += @(
                    "g2ThirdPeerModule=M03"
                    "g2ThirdPeerSerial=$M03Serial"
                    "g2Semantic=real_underlay_3node_only"
                    "pttStimulus=M01_and_M03_multi_source"
                )
            }
            if ($script:IsPaG3Family) {
                $preLines += @(
                    "g3Semantic=duration_stability_only"
                    "g3MinSoakSec=$PilotUseSec"
                )
            }
        }
    }
} else {
    $preLines += @(
        "doNotRequireCreationApplied=true"
        "doNotAdjudicateIncompleteDrain=true"
        "doNotAdjudicateMeetingOperational=true"
    )
}
($preLines -join "`n") | Set-Content -Encoding utf8 (Join-Path $logDir "PRECONDITIONS.txt")

if (Test-Path $ProvisioningManifest) {
    Copy-Item -Force $ProvisioningManifest (Join-Path $logDir "PROVISIONING_MANIFEST.txt")
}

Write-Host "PRE-6 start shared collector window"
$m01Log = Start-DeviceLog $M01Serial "M01"
$m02Log = Start-DeviceLog $M02Serial "M02"
$m03Log = $null
if ($script:IsPaG2Family) {
    $m03Log = Start-DeviceLog $M03Serial "M03"
}
Start-Sleep -Seconds 2

try {
    $serviceStartLabel = if ($SkipServiceColdStart) { "warm start services" } else { "cold start services" }
    Write-Host "=== Positive leg: $serviceStartLabel + host CREATE + peer JOIN (not CREATE) ==="
    if (-not $SkipServiceColdStart) {
        Start-App $M01Serial -ForceStop
        Start-App $M02Serial -ForceStop
        if ($script:IsPaG2Family) {
            Start-App $M03Serial -ForceStop
        }
    }
    $skipHardReset = [bool]$SkipServiceColdStart
    Ensure-ServiceStarted $M01Serial -SkipHardReset:$skipHardReset
    # Cold-start needs settle time between devices; warm/operator path does not.
    if (-not $SkipServiceColdStart) {
        Start-Sleep -Seconds 10
    }
    Ensure-ServiceStarted $M02Serial -SkipHardReset:$skipHardReset
    if ($script:IsPaG2Family) {
        Ensure-ServiceStarted $M03Serial -SkipHardReset:$skipHardReset
    }
    if ($SkipServiceColdStart) {
        Write-Host "Warm path: skip 15s discovery settle (services already running)"
        Start-Sleep -Seconds 2
    } else {
        Write-Host "Waiting 15s for discovery..."
        Start-Sleep -Seconds 15
    }

    $ErrorActionPreference = "Continue"
    & $adb -s $M01Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
    & $adb -s $M02Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
    if ($script:IsPaG2Family) {
        & $adb -s $M03Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
    }
    $ErrorActionPreference = "Stop"

    $groupChannelId = "CH-01"
    $groupGate = [ordered]@{
        PRE_G1_m01NoActiveGroup = "PENDING"
        PRE_G2_m02NoActiveGroup = "PENDING"
        PRE_G3_noGrpBoundOrPending = "PENDING"
        PRE_G4_createAllowed = "false"
        channelId = $groupChannelId
        leaveAttempts = "0"
        status = "PENDING"
    }

    Write-Host "PRE-G: Meeting tab + product leaveChannelSession on both (no CREATE, no clear-data)..."
    $preGSerials = @($M01Serial, $M02Serial)
    if ($script:IsPaG2Family) { $preGSerials += $M03Serial }
    foreach ($s in $preGSerials) {
        # Discovery wait / M02 ensure can leave M01 on launcher; always re-foreground first.
        Ensure-AppForegroundTalkTab $s
        Start-Sleep -Seconds 1
        $modeOk = $false
        for ($try = 1; $try -le 4; $try++) {
            if (Tap-IfPresent $s "${PackageName}:id/btnModeMeeting") {
                $modeOk = $true
                break
            }
            Write-Host "PRE-G: btnModeMeeting not ready on $s (try $try) — re-foreground Talk"
            Ensure-AppForegroundTalkTab $s
            Start-Sleep -Seconds 1
        }
        if (-not $modeOk) {
            throw "Resource not found on ${s}: ${PackageName}:id/btnModeMeeting"
        }
        Start-Sleep -Seconds 1
        Tap-IfPresent $s "${PackageName}:id/btnHangup" | Out-Null
        Tap-IfPresent $s "${PackageName}:id/btnLeave" | Out-Null
        Tap-IfPresent $s "${PackageName}:id/btnMeetingLeave" | Out-Null
        Tap-IfPresent $s "${PackageName}:id/btnMeetingLeaveTop" | Out-Null
        # Never tap channelOnlineArea / btnPtt here — that can CREATE Meeting or GROUP
    }

    if ($script:IsPaR2Family) {
        # Release APK has no debug leave broadcast; operator owns clean session state.
        Write-Host "PRE-G SKIP leave-CLEAR gate (PaR2 release): UI hangup taps only; operator owns residual group"
        $groupGate["PRE_G1_m01NoActiveGroup"] = "SKIP_RELEASE"
        $groupGate["PRE_G2_m02NoActiveGroup"] = "SKIP_RELEASE"
        $groupGate["PRE_G3_noGrpBoundOrPending"] = "SKIP_RELEASE"
        $groupGate["PRE_G4_createAllowed"] = "true"
        $groupGate["status"] = "PASS"
        $groupGate["leaveAttempts"] = "0"
        Write-GroupGate $groupGate
        Write-Host "PRE-G1..G4 PASS (PaR2 release skip) — host Meeting CREATE now allowed"
    } else {
    # Bounded product-leave rounds; observe teardown — no blind long sleep.
    $leaveRoundMax = 3
    $teardownWaitSec = 20
    $quietConfirmSec = 6
    $groupClear = $false
    for ($round = 1; $round -le $leaveRoundMax; $round++) {
        $groupGate["leaveAttempts"] = "$round"
        Write-Host ("PRE-G leave round {0}/{1}" -f $round, $leaveRoundMax)
        Invoke-ProductGroupLeave $M01Serial "M01"
        Invoke-ProductGroupLeave $M02Serial "M02"
        if ($script:IsPaG2Family -and $m03Log) {
            Invoke-ProductGroupLeave $M03Serial "M03"
        }

        # Re-assert Meeting tab preference after leave (product may clear channel preferred).
        $reassertSerials = @($M01Serial, $M02Serial)
        if ($script:IsPaG2Family -and $m03Log) { $reassertSerials += $M03Serial }
        foreach ($s in $reassertSerials) {
            Tap-IfPresent $s "${PackageName}:id/btnModeMeeting" | Out-Null
        }

        $m01Clear = $false
        $m02Clear = $false
        $m03Clear = $true
        $deadlineLeave = (Get-Date).AddSeconds($teardownWaitSec)
        while ((Get-Date) -lt $deadlineLeave) {
            $m01Clear = Test-LatestLeaveClear $m01Log.Path
            $m02Clear = Test-LatestLeaveClear $m02Log.Path
            if ($script:IsPaG2Family -and $m03Log) {
                $m03Clear = Test-LatestLeaveClear $m03Log.Path
            }
            if ($m01Clear -and $m02Clear -and $m03Clear) { break }
            Start-Sleep -Seconds 1
        }
        if (-not $m01Clear) {
            Write-Host ("PRE-G round {0}: M01 latest leave not CLEAR" -f $round)
        }
        if (-not $m02Clear) {
            Write-Host ("PRE-G round {0}: M02 latest leave not CLEAR" -f $round)
        }

        # Quiet window: no grp:CH-01 reformation after latest CLEAR marks.
        $quietDeadline = (Get-Date).AddSeconds($quietConfirmSec)
        $quietOk = $false
        while ((Get-Date) -lt $quietDeadline) {
            $q1 = Test-GroupQuietAfterMark $m01Log.Path "P1C_GROUP_PRECONDITION_LEAVE .*outcome=CLEAR" $groupChannelId
            $q2 = Test-GroupQuietAfterMark $m02Log.Path "P1C_GROUP_PRECONDITION_LEAVE .*outcome=CLEAR" $groupChannelId
            $q3 = $true
            if ($script:IsPaG2Family -and $m03Log) {
                $q3 = Test-GroupQuietAfterMark $m03Log.Path "P1C_GROUP_PRECONDITION_LEAVE .*outcome=CLEAR" $groupChannelId
            }
            if ($q1 -and $q2 -and $q3) {
                $quietOk = $true
                break
            }
            Write-Host ("PRE-G round {0}: waiting quiet (m01={1} m02={2} m03={3})" -f $round, $q1, $q2, $q3)
            Start-Sleep -Seconds 1
        }

        if ($m01Clear -and $m02Clear -and $m03Clear -and $quietOk) {
            $groupClear = $true
            break
        }
        Write-Host ("PRE-G round {0} incomplete — re-leave if residual GROUP" -f $round)
    }

    $groupGate["PRE_G1_m01NoActiveGroup"] = $(if (Test-LatestLeaveClear $m01Log.Path) { "PASS" } else { "FAIL" })
    $groupGate["PRE_G2_m02NoActiveGroup"] = $(if (Test-LatestLeaveClear $m02Log.Path) { "PASS" } else { "FAIL" })

    $g3Ok =
        $groupClear -and
        (Test-GroupQuietAfterMark $m01Log.Path "P1C_GROUP_PRECONDITION_LEAVE .*outcome=CLEAR" $groupChannelId) -and
        (Test-GroupQuietAfterMark $m02Log.Path "P1C_GROUP_PRECONDITION_LEAVE .*outcome=CLEAR" $groupChannelId) -and
        ($(if ($script:IsPaG2Family -and $m03Log) {
            Test-GroupQuietAfterMark $m03Log.Path "P1C_GROUP_PRECONDITION_LEAVE .*outcome=CLEAR" $groupChannelId
        } else { $true }))
    $groupGate["PRE_G3_noGrpBoundOrPending"] = $(if ($g3Ok) { "PASS" } else { "FAIL" })

    if (-not $groupClear -or $groupGate["PRE_G1_m01NoActiveGroup"] -ne "PASS" -or $groupGate["PRE_G2_m02NoActiveGroup"] -ne "PASS" -or $groupGate["PRE_G3_noGrpBoundOrPending"] -ne "PASS") {
        $groupGate["PRE_G4_createAllowed"] = "false"
        Abort-GroupPrecondition "PRE_G_group_teardown_incomplete_or_residual_grp_$groupChannelId" $m01Log $m02Log $groupGate
    }

    $groupGate["PRE_G4_createAllowed"] = "true"
    $groupGate["status"] = "PASS"
    Write-GroupGate $groupGate
    Write-Host "PRE-G1..G4 PASS — host Meeting CREATE now allowed"

    $groupGate["PRE_G4_createAllowed"] = "true"
    $groupGate["status"] = "PASS"
    Write-GroupGate $groupGate
    Write-Host "PRE-G1..G4 PASS — host Meeting CREATE now allowed"
    }

    Ensure-PeerForegroundMeetingTab $M02Serial
    if ($script:IsPaG2Family -and -not [string]::IsNullOrWhiteSpace($M03Serial)) {
        # Third peer must be on Meeting tab before CREATE — invite TTL ~30s is shared.
        Ensure-PeerForegroundMeetingTab $M03Serial
    }

    $joinGate = [ordered]@{
        PRE_J1_hostSession = "PENDING"
        PRE_J2_peerNoCreate = "PENDING"
        PRE_J3_peerInviteForH = "PENDING"
        PRE_J4_peerBindH = "PENDING"
        PRE_J5_sameSessionId = "PENDING"
        PRE_J6_packageAdjudicationAllowed = "false"
        hostSessionId = ""
        peerSessionId = ""
        peerJoinPath = "btnJoinMeeting_acceptIncoming"
        status = "PENDING"
    }

    $inviteJoinGate = [ordered]@{
        PRE_I1_peerInviteForH = "PENDING"
        PRE_I2_overlayVisible = "PENDING"
        PRE_I3_joinClickDispatched = "PENDING"
        PRE_I4_acceptDispatched = "PENDING"
        PRE_I5_noRejectBeforeBind = "PENDING"
        PRE_I6_peerBindH = "PENDING"
        hostSessionId = ""
        status = "PENDING"
    }

    Write-Host "Host CREATE (Meeting mode PTT only on M01)..."
    Tap-ByResourceId $M01Serial "${PackageName}:id/btnModeMeeting"
    Start-Sleep -Seconds 1
    Tap-ByResourceId $M01Serial "${PackageName}:id/btnPtt"

    # PRE-J1: capture host sessionId H
    $hostSessionId = $null
    $hostDeadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $hostDeadline -and -not $hostSessionId) {
        $hostSessionId = Get-FirstMatch $m01Log.Path 'CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=([0-9a-fA-F-]{36}) .* localRole=HOST creationSource=USER_CREATE'
        if (-not $hostSessionId) {
            $hostSessionId = Get-FirstMatch $m01Log.Path 'ADMISSION_DECISION action=CREATE .* incomingSessionId=([0-9a-fA-F-]{36}) .* creationSource=USER_CREATE'
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $hostSessionId) {
        Abort-JoinPrecondition "PRE_J1_host_session_not_observed" $m01Log $m02Log $joinGate $inviteJoinGate
    }
    $joinGate["hostSessionId"] = $hostSessionId
    $inviteJoinGate["hostSessionId"] = $hostSessionId
    $joinGate["PRE_J1_hostSession"] = "PASS"
    Write-Host "PRE-J1 PASS hostSessionId=$hostSessionId"

    # PaG2: bind third peer FIRST while CREATE-time invite is still live.
    # Joining M02 first historically burned the ~30s invite TTL; M04 then saw
    # OVERLAY_VISIBLE → Conference invite expired → CALL_REJECT (no bind).
    if ($script:IsPaG2Family -and $m03Log) {
        try {
            Invoke-ThirdPeerInviteJoin $M03Serial $m03Log $hostSessionId "M03" $m01Log $m02Log $joinGate $inviteJoinGate
            Write-J0DriverManifest @{
                hostSessionId = $hostSessionId
                peerModuleId = "M02"
                thirdPeerModuleId = "M03"
            }
        } catch {
            Abort-InviteJoinPrecondition $_.Exception.Message $m01Log $m02Log $inviteJoinGate $joinGate
        }
    }

    Write-Host "PRE-I: peer invite overlay join (btnJoinMeeting only; poll ${InviteJoinPollMs}ms)..."
    $btnJoinRid = "${PackageName}:id/btnJoinMeeting"
    $inviteDeadline = (Get-Date).AddSeconds($InviteJoinWaitSec)
    $gotI1 = $false
    $gotI2 = $false
    $clickedJoin = $false
    $joinFlowStarted = $false
    while ((Get-Date) -lt $inviteDeadline) {
        $peerCreate = Get-FirstMatch $m02Log.Path 'CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=([0-9a-fA-F-]{36}) .* creationSource=USER_CREATE'
        if ($peerCreate -and $peerCreate -ne $hostSessionId) {
            $joinGate["peerSessionId"] = $peerCreate
            $joinGate["PRE_J2_peerNoCreate"] = "FAIL"
            Abort-JoinPrecondition "PRE_J2_peer_created_session_$peerCreate" $m01Log $m02Log $joinGate $inviteJoinGate
        }

        if (Test-PeerInviteRejectOrExpiryForH $m02Log.Path $hostSessionId) {
            $inviteJoinGate["PRE_I5_noRejectBeforeBind"] = "FAIL"
            Abort-InviteJoinPrecondition "PRE_I5_CALL_REJECT_or_invite_expiry_before_bind_$hostSessionId" $m01Log $m02Log $inviteJoinGate $joinGate
        }

        if (-not $gotI1 -and (Test-PeerInviteObservedForH $m02Log.Path $hostSessionId)) {
            $gotI1 = $true
            $inviteJoinGate["PRE_I1_peerInviteForH"] = "PASS"
            Write-Host "PRE-I1 PASS peer invite for H observed"
        }

        if ($gotI1 -and -not $gotI2) {
            if (Test-PeerInviteOverlayForH $m02Log.Path $M02Serial $hostSessionId) {
                $gotI2 = $true
                $inviteJoinGate["PRE_I2_overlayVisible"] = "PASS"
                Write-Host "PRE-I2 PASS invite overlay / btnJoinMeeting available"
            }
        }

        if ($gotI2 -and -not $clickedJoin) {
            $overlayObs = Test-LogQuiet $m02Log.Path "MEETING_INVITE_UI sessionId=$hostSessionId state=OVERLAY_VISIBLE"
            $buttonPresent = (Find-ResourcePresent $M02Serial $btnJoinRid) -or (Test-ResourceClickable $M02Serial $btnJoinRid)
            # Match G2 third-peer join: overlay log may lead UI dump; retry tap aggressively.
            if ($overlayObs -or $buttonPresent -or $gotI2) {
                if (Try-TapInviteJoinButton $M02Serial $btnJoinRid 16) {
                    $clickedJoin = $true
                } elseif (Tap-IfPresent $M02Serial $btnJoinRid) {
                    $clickedJoin = $true
                }
            }
        }

        if ($clickedJoin -and -not $joinFlowStarted) {
            $joinFlowStarted = $true
            Write-Host "PRE-I3 dispatched tap on btnJoinMeeting"
            $clickConfirmDeadline = (Get-Date).AddSeconds(5)
            while ((Get-Date) -lt $clickConfirmDeadline) {
                if (Test-LogQuiet $m02Log.Path "MEETING_INVITE_UI sessionId=$hostSessionId state=JOIN_CLICKED") {
                    $inviteJoinGate["PRE_I3_joinClickDispatched"] = "PASS"
                    break
                }
                Start-Sleep -Milliseconds $InviteJoinPollMs
            }
            if ($inviteJoinGate["PRE_I3_joinClickDispatched"] -ne "PASS") {
                Write-Host "PRE-I3 auto tap unconfirmed (no JOIN_CLICKED in 5s) — defer to operator-assisted window if enabled"
                $clickedJoin = $false
                $joinFlowStarted = $false
                if ($AllowOperatorAssistedM02Join -and $gotI2) { break }
            } else {
                Write-Host "PRE-I3 PASS JOIN_CLICKED OBS"
            }

            if ($inviteJoinGate["PRE_I3_joinClickDispatched"] -eq "PASS") {
                if (-not (Wait-PeerInviteAcceptAfterJoin $m02Log.Path $hostSessionId $inviteJoinGate $InviteJoinPollMs)) {
                    Abort-InviteJoinPrecondition "PRE_I4_ACCEPT_DISPATCHED_not_observed" $m01Log $m02Log $inviteJoinGate $joinGate
                }
                Write-Host "PRE-I4 PASS acceptIncomingMeeting dispatched"
                break
            }
        }

        Start-Sleep -Milliseconds $InviteJoinPollMs
    }

    if (-not $gotI1) {
        $inviteJoinGate["PRE_I1_peerInviteForH"] = "FAIL"
        Abort-InviteJoinPrecondition "PRE_I1_peer_invite_for_H_not_observed" $m01Log $m02Log $inviteJoinGate $joinGate
    }
    if (-not $gotI2) {
        $inviteJoinGate["PRE_I2_overlayVisible"] = "FAIL"
        Abort-InviteJoinPrecondition "PRE_I2_invite_overlay_not_visible" $m01Log $m02Log $inviteJoinGate $joinGate
    }
    if (-not $clickedJoin -and $AllowOperatorAssistedM02Join -and $gotI2) {
        Write-Host "PRE-I3 OPERATOR_ASSISTED: tap Join on M02 ($M02Serial) within ${OperatorAssistedJoinWaitSec}s — harness watches JOIN_CLICKED only (no admission bypass)"
        $inviteJoinGate["PRE_I3_joinMode"] = "OPERATOR_ASSISTED"
        $opDeadline = (Get-Date).AddSeconds($OperatorAssistedJoinWaitSec)
        while ((Get-Date) -lt $opDeadline) {
            if (Test-PeerInviteRejectOrExpiryForH $m02Log.Path $hostSessionId) {
                $inviteJoinGate["PRE_I5_noRejectBeforeBind"] = "FAIL"
                Abort-InviteJoinPrecondition "PRE_I5_CALL_REJECT_during_operator_assisted_join_$hostSessionId" $m01Log $m02Log $inviteJoinGate $joinGate
            }
            if (Test-PeerJoinClickObservedForH $m02Log.Path $hostSessionId) {
                $clickedJoin = $true
                $inviteJoinGate["PRE_I3_joinClickDispatched"] = "OPERATOR_ASSISTED_PASS"
                Write-Host "PRE-I3 OPERATOR_ASSISTED PASS join/accept signal observed"
                if (-not (Wait-PeerInviteAcceptAfterJoin $m02Log.Path $hostSessionId $inviteJoinGate $InviteJoinPollMs)) {
                    Abort-InviteJoinPrecondition "PRE_I4_ACCEPT_DISPATCHED_not_observed_after_operator_assisted_join" $m01Log $m02Log $inviteJoinGate $joinGate
                }
                Write-Host "PRE-I4 PASS acceptIncomingMeeting dispatched (operator-assisted)"
                break
            }
            Start-Sleep -Milliseconds $InviteJoinPollMs
        }
    }
    if (-not $clickedJoin) {
        $inviteJoinGate["PRE_I3_joinClickDispatched"] = "FAIL"
        Abort-InviteJoinPrecondition "PRE_I3_btnJoinMeeting_not_clicked_in_window" $m01Log $m02Log $inviteJoinGate $joinGate
    }

    $inviteJoinGate["PRE_I5_noRejectBeforeBind"] = "PASS"
    $joinGate["PRE_J3_peerInviteForH"] = "PASS"

    # PRE-I6 / PRE-J4/J5: wait for same-session bind
    $bindDeadline = (Get-Date).AddSeconds(60)
    $peerBound = $false
    while ((Get-Date) -lt $bindDeadline) {
        if (Test-PeerInviteRejectOrExpiryForH $m02Log.Path $hostSessionId) {
            $inviteJoinGate["PRE_I5_noRejectBeforeBind"] = "FAIL"
            Abort-InviteJoinPrecondition "PRE_I5_REJECT_before_bind_after_accept_$hostSessionId" $m01Log $m02Log $inviteJoinGate $joinGate
        }
        $peerCreate = Get-FirstMatch $m02Log.Path 'CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=([0-9a-fA-F-]{36}) .* creationSource=USER_CREATE'
        if ($peerCreate -and $peerCreate -ne $hostSessionId) {
            $joinGate["peerSessionId"] = $peerCreate
            $joinGate["PRE_J2_peerNoCreate"] = "FAIL"
            Abort-JoinPrecondition "PRE_J2_peer_created_session_$peerCreate" $m01Log $m02Log $joinGate $inviteJoinGate
        }

        $peerInviteAccept = Test-LogQuiet $m02Log.Path "CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=$hostSessionId .* creationSource=INVITE_ACCEPT"
        $peerBindDrain = Test-LogQuiet $m02Log.Path "SESSION_BIND_DRAIN session=$hostSessionId"
        $peerSessionCreatedH = Test-LogQuiet $m02Log.Path "CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=$hostSessionId"
        if ($peerInviteAccept -or $peerBindDrain -or $peerSessionCreatedH) {
            $peerBound = $true
            $joinGate["peerSessionId"] = $hostSessionId
            break
        }
        Start-Sleep -Milliseconds $InviteJoinPollMs
    }

    if (-not $peerBound) {
        $inviteJoinGate["PRE_I6_peerBindH"] = "FAIL"
        $joinGate["PRE_J2_peerNoCreate"] = "PASS"
        $joinGate["PRE_J4_peerBindH"] = "FAIL"
        Abort-InviteJoinPrecondition "PRE_I6_peer_did_not_bind_host_session_$hostSessionId" $m01Log $m02Log $inviteJoinGate $joinGate
    }

    $inviteJoinGate["PRE_I6_peerBindH"] = "PASS"
    $inviteJoinGate["status"] = "PASS"
    Write-InviteJoinGate $inviteJoinGate
    Write-Host "PRE-I1..I6 PASS peer bound to hostSessionId=$hostSessionId"

    $joinGate["PRE_J2_peerNoCreate"] = "PASS"
    $joinGate["PRE_J4_peerBindH"] = "PASS"
    $joinGate["PRE_J5_sameSessionId"] = "PASS"
    $joinGate["PRE_J6_packageAdjudicationAllowed"] = "true"
    $joinGate["status"] = "PASS"
    Write-JoinGate $joinGate
    if ($script:IsJ0Family) {
        Write-Host "PRE-J1..J6 PASS — J0 prerequisite / shadow runtime chain now allowed"
        Write-J0DriverManifest @{
            hostSessionId = $hostSessionId
            peerModuleId = "M02"
        }
    } else {
        Write-Host "PRE-J1..J6 PASS — package post-bind adjudication now allowed"
    }

    if ($script:IsJ0Family) {
        Write-Host "Waiting for J0 continuation chain up to ${PositiveWaitSec}s (product auto only; no C-F7 / no debug republish)..."
        $deadline = (Get-Date).AddSeconds($PositiveWaitSec)
        $gotRetain = $false
        $gotIncomplete = $false
        $gotPostBind = $false
        $gotReady = $false
        $gotDrain = $false
        $gotCreationApplied = $false
        while ((Get-Date) -lt $deadline) {
            $peerCreate = Get-FirstMatch $m02Log.Path 'CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=([0-9a-fA-F-]{36}) .* creationSource=USER_CREATE'
            if ($peerCreate -and $peerCreate -ne $hostSessionId) {
                Abort-JoinPrecondition "PRE_J2_late_peer_created_session_$peerCreate" $m01Log $m02Log $joinGate $inviteJoinGate
            }
            if (-not $gotRetain) {
                $gotRetain = Select-String -Path $m02Log.Path -Pattern "WIRE_INGRESS .*DEFERRED_NO_SESSION_RETAINED" -Quiet -ErrorAction SilentlyContinue
                if ($gotRetain) { Write-Host "J0 chain: CREATION pre-bind retained" }
            }
            if (-not $gotIncomplete) {
                $gotIncomplete = Select-String -Path $m02Log.Path -Pattern "WIRE_INGRESS .*factType=1.*INCOMPLETE_PENDING.*MISSING_MEDIA_SUPPLEMENT" -Quiet -ErrorAction SilentlyContinue
                if ($gotIncomplete) { Write-Host "J0 chain: INCOMPLETE_PENDING after bind" }
            }
            if (-not $gotPostBind) {
                $gotPostBind = Select-String -Path $m01Log.Path -Pattern "PROFILE01_MEDIA_KEY_PACKAGE_POST_BIND_REPUBLISH .*publicationOutcome=EMITTED" -Quiet -ErrorAction SilentlyContinue
                if (-not $gotPostBind) {
                    $gotPostBind = Select-String -Path $m01Log.Path -Pattern "PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN .*publicationOutcome=POST_BIND_EMITTED" -Quiet -ErrorAction SilentlyContinue
                }
                if ($gotPostBind) { Write-Host "J0 chain: product POST_BIND_EMITTED" }
            }
            if (-not $gotReady) {
                $gotReady = Select-String -Path $m02Log.Path -Pattern "PROFILE01_MEDIA_SUPPLEMENT .*state=READY" -Quiet -ErrorAction SilentlyContinue
                if ($gotReady) { Write-Host "J0 chain: SUPPLEMENT READY" }
            }
            if (-not $gotDrain) {
                $gotDrain = Select-String -Path $m02Log.Path -Pattern "SESSION_INCOMPLETE_DRAIN .*count=1" -Quiet -ErrorAction SilentlyContinue
                if ($gotDrain) { Write-Host "J0 chain: SESSION_INCOMPLETE_DRAIN count=1" }
            }
            if (-not $gotCreationApplied) {
                $gotCreationApplied = Select-String -Path $m02Log.Path -Pattern "WIRE_INGRESS .*factType=1 outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue
                if ($gotCreationApplied) { Write-Host "J0 chain: CREATION APPLIED" }
            }
            if ($gotRetain -and $gotIncomplete -and $gotPostBind -and $gotReady -and $gotDrain -and $gotCreationApplied) { break }
            Start-Sleep -Seconds 2
        }
        Write-Host "J0 fingerprints: RETAIN=$gotRetain INCOMPLETE=$gotIncomplete POST_BIND=$gotPostBind READY=$gotReady DRAIN=$gotDrain CREATION_APPLIED=$gotCreationApplied"
        $anchor = Parse-CreationAnchorFromLogs $m01Log.Path $hostSessionId
        $epoch = $null
        if ($anchor["conferenceId"]) {
            $epoch = Parse-MediaKeyEpochFromLogs $m02Log.Path $anchor["conferenceId"]
        }
        Write-J0DriverManifest @{
            hostSessionId = $hostSessionId
            conferenceId = $anchor["conferenceId"]
            creationFactDigest = $anchor["creationFactDigest"]
            mediaKeyEpoch = $epoch
            peerModuleId = "M02"
            chainRetain = $(if ($gotRetain) { "observed" } else { "missing" })
            chainIncomplete = $(if ($gotIncomplete) { "observed" } else { "missing" })
            chainPostBind = $(if ($gotPostBind) { "observed" } else { "missing" })
            chainReady = $(if ($gotReady) { "observed" } else { "missing" })
            chainDrain = $(if ($gotDrain) { "observed" } else { "missing" })
            chainCreationApplied = $(if ($gotCreationApplied) { "observed" } else { "missing" })
        }

        if ($script:IsShadowFamily) {
            Write-ShadowRuntimeGate @{
                j0Prerequisite = $(if ($gotCreationApplied) { "APPLIED" } else { "MISSING" })
                driverObservationPhase = "STARTING"
            }

            $obsLabel = switch ($DriverMode) {
                "PaSr3" { "PA-SR3" }
                "PaSr4" { "PA-SR4" }
                "PaSr4Tx" { "PA-SR4-TX" }
                "PaSr4Rx" { "PA-SR4-RX" }
                "PaSr4Play" { "PA-SR4-PLAY" }
                "PaSr5" { "PA-SR5" }
                "PaSr6" { "PA-SR6" }
                "PaRc1" { "RC1" }
                "PaPilot1" { "PILOT-1" }
                default { "PA-SR" }
            }
            if ($DriverMode -eq "PaSr4Tx") {
                Write-Host "${obsLabel}: shadow TX observation window ${ShadowObservationSec}s (PTT bursts for SHADOW_TX_ACTIVITY)..."
                Write-Host "NOTE: 3x M01 PTT is stimulus only — PASS requires SHADOW_TX_ACTIVITY packetsSent>0, not tap counts."
            } elseif ($DriverMode -eq "PaSr4Rx") {
                Write-Host "${obsLabel}: shadow RX observation window ${ShadowObservationSec}s (PTT bursts for host TX + peer ingress)..."
                Write-Host "NOTE: 3x M01 PTT is stimulus only — PASS requires M02 INGRESS_ACTIVITY packetsReceived>0; PLAYOUT is observe-only."
            } elseif ($DriverMode -eq "PaSr4Play") {
                Write-Host "${obsLabel}: shadow PLAY observation window ${ShadowObservationSec}s (full TX→RX→ingress→playout chain)..."
                Write-Host "NOTE: 3x M01 PTT is stimulus only — PASS requires M02 PLAYOUT_ACTIVITY successfulPlayoutWrites>0."
            } elseif ($DriverMode -eq "PaSr5") {
                Write-Host "${obsLabel}: Window A baseline G1 — SR4-class TX/RX/INGRESS/PLAY observation ${ShadowObservationSec}s..."
                Write-Host "NOTE: PASS requires M01 SOURCE generation replace on peer (G1 revoke → G2 install); not M02 leave."
                if (-not (Ensure-HostPttStimulusReady $M01Serial "M01")) {
                    Abort-HarnessUi "PA_SR5_window_A_btnPtt_not_ready_after_talk_navigate" "PA_SR5_WINDOW_A" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                }
            } elseif ($DriverMode -eq "PaSr6") {
                Write-Host "${obsLabel}: Window A operational baseline — SR4-class TX/RX/INGRESS/PLAY observation ${ShadowObservationSec}s..."
                Write-Host "NOTE: PASS requires operational media before M01 Meeting end; never-started runtime = INVALID_BASELINE."
                if (-not (Ensure-HostPttStimulusReady $M01Serial "M01")) {
                    Abort-HarnessUi "PA_SR6_window_A_btnPtt_not_ready_after_talk_navigate" "PA_SR6_WINDOW_A" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                }
            } elseif ($DriverMode -eq "PaRc1" -or $DriverMode -eq "PaPilot1") {
                Write-Host "${obsLabel}: Window A ANCHOR_ACTIVE baseline — SR4-class TX/RX/INGRESS/PLAY observation ${ShadowObservationSec}s..."
                Write-Host "NOTE: PASS requires anchor audible owner + shadow media healthy before M02 cutover."
                if (-not (Ensure-HostPttStimulusReady $M01Serial "M01")) {
                    Abort-HarnessUi "PILOT_window_A_btnPtt_not_ready" "PILOT_WINDOW_A" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                }
            } else {
                Write-Host "${obsLabel}: shadow runtime observation window ${ShadowObservationSec}s (PTT bursts for ingress/playout)..."
                Write-Host "NOTE: 3x M01 PTT is stimulus only — PASS requires INGRESS/PLAYOUT OBS, not tap counts."
            }
            $pttUiPhase = switch ($DriverMode) {
                "PaSr5" { "PA_SR5_WINDOW_A" }
                "PaSr6" { "PA_SR6_WINDOW_A" }
                "PaRc1" { "RC1_WINDOW_A" }
                "PaPilot1" { "PILOT_WINDOW_A" }
                "PaSr4Tx" { "PA_SR4_TX_WINDOW" }
                "PaSr4Rx" { "PA_SR4_RX_WINDOW" }
                "PaSr4Play" { "PA_SR4_PLAY_WINDOW" }
                default { "SHADOW_RUNTIME_WINDOW" }
            }
            if ($PreferHeadsetPlayback) {
                Apply-HeadsetPlaybackRoute (Get-MeetingPlaybackSerials)
            }
            Apply-MeetingMuteAll (Get-MeetingPlaybackSerials)
            if (-not (Invoke-HostPttStimulusBurst $M01Serial "M01" 3)) {
                Abort-HarnessUi "host_ptt_stimulus_burst_failed" $pttUiPhase $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
            }

            $obsDeadline = (Get-Date).AddSeconds($ShadowObservationSec)
            $gotShadowStarted = $false
            $gotHostProjected = $false
            $gotMemberReady = $false
            $gotIngress = $false
            $gotPlayout = $false
            $gotTxActivity = $false
            $gotCaptureOn = $false
            $gotPeerWiring = $false
            $gotPeerIngress = $false
            while ((Get-Date) -lt $obsDeadline) {
                if (-not $gotHostProjected) {
                    $gotHostProjected = (Select-String -Path $m01Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=HOST_SESSION_PROJECTED .*sessionId=$hostSessionId .*outcome=(APPLIED|ALREADY_LIVE)" -Quiet -ErrorAction SilentlyContinue)
                }
                if (-not $gotShadowStarted) {
                    $gotShadowStarted = (Select-String -Path $m01Log.Path -Pattern "SHADOW hook=SESSION_STARTED session=$hostSessionId outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue) -or
                        (Select-String -Path $m02Log.Path -Pattern "SHADOW hook=SESSION_STARTED session=$hostSessionId outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue) -or
                        (Select-String -Path $m01Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_SESSION_STARTED .*sessionId=$hostSessionId" -Quiet -ErrorAction SilentlyContinue)
                }
                if (-not $gotMemberReady) {
                    # SR3 primary direction: peer consumes host SOURCE → MEMBER_MEDIA_READY module=M01
                    $gotMemberReady = (Select-String -Path $m01Log.Path -Pattern "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=M01 outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue) -or
                        (Select-String -Path $m02Log.Path -Pattern "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=M01 outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue)
                    if (-not $gotMemberReady -and -not $script:IsPaSr4Family) {
                        # Full shadow / PaSr3 also accept peer-module install fingerprint
                        $gotMemberReady = (Select-String -Path $m01Log.Path -Pattern "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=M02 outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue) -or
                            (Select-String -Path $m02Log.Path -Pattern "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=M02 outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue)
                    }
                }
                if (-not $gotIngress) {
                    $gotIngress = (Select-String -Path $m01Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY .*sessionId=$hostSessionId" -Quiet -ErrorAction SilentlyContinue) -or
                        (Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY .*sessionId=$hostSessionId" -Quiet -ErrorAction SilentlyContinue)
                }
                if (-not $gotPlayout) {
                    $gotPlayout = (Select-String -Path $m01Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId" -Quiet -ErrorAction SilentlyContinue) -or
                        (Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId" -Quiet -ErrorAction SilentlyContinue)
                }
                if (-not $gotTxActivity) {
                    $gotTxActivity = (Select-String -Path $m01Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_TX_ACTIVITY .*sessionId=$hostSessionId .*packetsSent=[1-9]" -Quiet -ErrorAction SilentlyContinue)
                }
                if (-not $gotCaptureOn) {
                    $gotCaptureOn = (Select-String -Path $m01Log.Path -Pattern "PTT_TIMING event=captureON session=$hostSessionId" -Quiet -ErrorAction SilentlyContinue)
                }
                if (-not $gotPeerWiring) {
                    $gotPeerWiring = (Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_SESSION_STARTED .*sessionId=$hostSessionId .*transportScopeActive=true" -Quiet -ErrorAction SilentlyContinue) -or
                        (Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_SESSION_STARTED .*sessionId=$hostSessionId .*transportScopeActive=true" -Quiet -ErrorAction SilentlyContinue)
                }
                if (-not $gotPeerIngress) {
                    $gotPeerIngress = (Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY .*sessionId=$hostSessionId .*packetsReceived=[1-9]" -Quiet -ErrorAction SilentlyContinue)
                }
                if ($DriverMode -eq "PaSr4Tx") {
                    if ($gotHostProjected -and $gotShadowStarted -and $gotTxActivity) { break }
                } elseif ($DriverMode -eq "PaSr4Rx") {
                    if ($gotTxActivity -and $gotPeerWiring -and $gotPeerIngress) { break }
                } elseif ($DriverMode -eq "PaSr4Play") {
                    if ($gotTxActivity -and $gotPeerWiring -and $gotPeerIngress -and $gotPlayout) { break }
                } elseif ($DriverMode -eq "PaSr5" -or $DriverMode -eq "PaSr6" -or $DriverMode -eq "PaRc1" -or $DriverMode -eq "PaPilot1") {
                    if ($gotTxActivity -and $gotPeerWiring -and $gotPeerIngress -and $gotPlayout -and $gotMemberReady) { break }
                } elseif ($DriverMode -eq "PaSr4") {
                    if ($gotMemberReady -and $gotIngress -and $gotPlayout) { break }
                } elseif ($gotShadowStarted -and $gotMemberReady -and $gotIngress -and $gotPlayout) {
                    break
                }
                Start-Sleep -Seconds 2
            }
            if ($DriverMode -eq "PaSr4Tx") {
                Write-Host "Shadow TX fingerprints: HOST_PROJECTED=$gotHostProjected SESSION_STARTED=$gotShadowStarted captureON=$gotCaptureOn TX_ACTIVITY=$gotTxActivity"
                Write-ShadowRuntimeGate @{
                    obsHostSessionProjected = $(if ($gotHostProjected) { "observed" } else { "missing" })
                    obsSessionStarted = $(if ($gotShadowStarted) { "observed" } else { "missing" })
                    obsCaptureOn = $(if ($gotCaptureOn) { "observed" } else { "missing" })
                    obsShadowTxActivity = $(if ($gotTxActivity) { "observed" } else { "missing" })
                    obsMemberReady = $(if ($gotMemberReady) { "observed" } else { "missing" })
                    obsIngressActivity = $(if ($gotIngress) { "observed" } else { "missing" })
                    obsPlayoutActivity = $(if ($gotPlayout) { "observed" } else { "missing" })
                }
            } elseif ($DriverMode -eq "PaSr4Rx") {
                Write-Host "Shadow RX fingerprints: TX_ACTIVITY=$gotTxActivity PEER_WIRING=$gotPeerWiring PEER_INGRESS=$gotPeerIngress captureON=$gotCaptureOn PLAYOUT_OBS=$gotPlayout"
                Write-ShadowRuntimeGate @{
                    obsCaptureOn = $(if ($gotCaptureOn) { "observed" } else { "missing" })
                    obsShadowTxActivity = $(if ($gotTxActivity) { "observed" } else { "missing" })
                    obsPeerWiringSession = $(if ($gotPeerWiring) { "observed" } else { "missing" })
                    obsPeerIngressActivity = $(if ($gotPeerIngress) { "observed" } else { "missing" })
                    obsMemberReady = $(if ($gotMemberReady) { "observed" } else { "missing" })
                    obsIngressActivity = $(if ($gotIngress) { "observed" } else { "missing" })
                    obsPlayoutActivity = $(if ($gotPlayout) { "observed" } else { "missing" })
                }
            } elseif ($DriverMode -eq "PaSr4Play") {
                Write-Host "Shadow PLAY fingerprints: TX_ACTIVITY=$gotTxActivity PEER_WIRING=$gotPeerWiring PEER_INGRESS=$gotPeerIngress PLAYOUT=$gotPlayout captureON=$gotCaptureOn"
                Write-ShadowRuntimeGate @{
                    obsCaptureOn = $(if ($gotCaptureOn) { "observed" } else { "missing" })
                    obsShadowTxActivity = $(if ($gotTxActivity) { "observed" } else { "missing" })
                    obsPeerWiringSession = $(if ($gotPeerWiring) { "observed" } else { "missing" })
                    obsPeerIngressActivity = $(if ($gotPeerIngress) { "observed" } else { "missing" })
                    obsMemberReady = $(if ($gotMemberReady) { "observed" } else { "missing" })
                    obsIngressActivity = $(if ($gotIngress) { "observed" } else { "missing" })
                    obsPlayoutActivity = $(if ($gotPlayout) { "observed" } else { "missing" })
                }
            } elseif ($DriverMode -eq "PaSr5") {
                $captureG1 = Get-LatestM01SourceGenerationFromPeerLog $m02Log.Path
                Write-Host "PA-SR5 Window A: TX=$gotTxActivity PEER_WIRING=$gotPeerWiring INGRESS=$gotPeerIngress PLAYOUT=$gotPlayout MEMBER_READY=$gotMemberReady captureG1=$captureG1"
                Write-ShadowRuntimeGate @{
                    sr5Window = "A"
                    captureG1 = $(if ($captureG1) { $captureG1 } else { "missing" })
                    obsCaptureOn = $(if ($gotCaptureOn) { "observed" } else { "missing" })
                    obsShadowTxActivity = $(if ($gotTxActivity) { "observed" } else { "missing" })
                    obsPeerWiringSession = $(if ($gotPeerWiring) { "observed" } else { "missing" })
                    obsPeerIngressActivity = $(if ($gotPeerIngress) { "observed" } else { "missing" })
                    obsMemberReady = $(if ($gotMemberReady) { "observed" } else { "missing" })
                    obsIngressActivity = $(if ($gotIngress) { "observed" } else { "missing" })
                    obsPlayoutActivity = $(if ($gotPlayout) { "observed" } else { "missing" })
                }
            } elseif ($DriverMode -eq "PaSr6") {
                $confLine = Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*sessionId=$hostSessionId .*conferenceId=" -ErrorAction SilentlyContinue | Select-Object -First 1
                $captureConferenceId = if ($confLine -and $confLine.Line -match "conferenceId=([^ ]+)") { $Matches[1] } else { "" }
                Write-Host "PA-SR6 Window A: TX=$gotTxActivity PEER_WIRING=$gotPeerWiring INGRESS=$gotPeerIngress PLAYOUT=$gotPlayout MEMBER_READY=$gotMemberReady conferenceId=$captureConferenceId"
                Write-ShadowRuntimeGate @{
                    sr6Window = "A"
                    captureConferenceId = $(if ($captureConferenceId) { $captureConferenceId } else { "missing" })
                    obsCaptureOn = $(if ($gotCaptureOn) { "observed" } else { "missing" })
                    obsShadowTxActivity = $(if ($gotTxActivity) { "observed" } else { "missing" })
                    obsPeerWiringSession = $(if ($gotPeerWiring) { "observed" } else { "missing" })
                    obsPeerIngressActivity = $(if ($gotPeerIngress) { "observed" } else { "missing" })
                    obsMemberReady = $(if ($gotMemberReady) { "observed" } else { "missing" })
                    obsIngressActivity = $(if ($gotIngress) { "observed" } else { "missing" })
                    obsPlayoutActivity = $(if ($gotPlayout) { "observed" } else { "missing" })
                }
            } elseif ($DriverMode -eq "PaRc1" -or $DriverMode -eq "PaPilot1") {
                Write-Host "${obsLabel} Window A: TX=$gotTxActivity PEER_WIRING=$gotPeerWiring INGRESS=$gotPeerIngress PLAYOUT=$gotPlayout MEMBER_READY=$gotMemberReady"
                Write-ShadowRuntimeGate @{
                    rc1Window = "A"
                    obsCaptureOn = $(if ($gotCaptureOn) { "observed" } else { "missing" })
                    obsShadowTxActivity = $(if ($gotTxActivity) { "observed" } else { "missing" })
                    obsPeerWiringSession = $(if ($gotPeerWiring) { "observed" } else { "missing" })
                    obsPeerIngressActivity = $(if ($gotPeerIngress) { "observed" } else { "missing" })
                    obsMemberReady = $(if ($gotMemberReady) { "observed" } else { "missing" })
                    obsIngressActivity = $(if ($gotIngress) { "observed" } else { "missing" })
                    obsPlayoutActivity = $(if ($gotPlayout) { "observed" } else { "missing" })
                }
                Write-Rc1Gate @{
                    hostSessionId = $hostSessionId
                    rc1Window = "A"
                    pilotUseSec = "$PilotUseSec"
                    driverObservationPhase = "COMPLETE"
                }
            } else {
                Write-Host "Shadow observation fingerprints: SESSION_STARTED=$gotShadowStarted MEMBER_READY=$gotMemberReady INGRESS=$gotIngress PLAYOUT=$gotPlayout"
                Write-ShadowRuntimeGate @{
                    obsSessionStarted = $(if ($gotShadowStarted) { "observed" } else { "missing" })
                    obsMemberReady = $(if ($gotMemberReady) { "observed" } else { "missing" })
                    obsIngressActivity = $(if ($gotIngress) { "observed" } else { "missing" })
                    obsPlayoutActivity = $(if ($gotPlayout) { "observed" } else { "missing" })
                }
            }

            if ($script:IsPaSr4Family) {
                $srSkipLabel = switch ($DriverMode) {
                    "PaSr4Tx" { "PA-SR5/SR6" }
                    "PaSr4Rx" { "PA-SR5/SR6" }
                    "PaSr4Play" { "PA-SR5/SR6" }
                    default { "PA-SR5/SR6" }
                }
                Write-Host "${srSkipLabel}: SKIPPED ($DriverMode-only run; observe-only if incidental)"
                Write-ShadowRuntimeGate @{
                    driverLeavePhase = "SKIPPED"
                    driverRejoinPhase = "SKIPPED"
                    driverEndPhase = "SKIPPED"
                    driverObservationPhase = "COMPLETE"
                }
            } elseif ($script:IsPaSr5Family) {
                $captureG1 = Get-LatestM01SourceGenerationFromPeerLog $m02Log.Path
                Write-Host "PA-SR5 Window B: M01 SOURCE generation replace (product path)..."
                Write-ShadowRuntimeGate @{
                    sr5Window = "B"
                    driverRotatePhase = "RUNNING"
                    captureG1 = $(if ($captureG1) { $captureG1 } else { "missing" })
                }
                & $adb -s $M01Serial shell am broadcast -a com.talkback.appprod.debug.PA_SR5_ROTATE_LOCAL_SOURCE --es remote M02 -p $PackageName 2>&1 | Out-Null
                $rotateDeadline = (Get-Date).AddSeconds(45)
                $gotMemberReplaced = $false
                $gotRevokedG1 = $false
                while ((Get-Date) -lt $rotateDeadline) {
                    if (-not $gotMemberReplaced) {
                        $gotMemberReplaced = (Select-String -Path $m02Log.Path -Pattern "SHADOW hook=MEMBER_REPLACED session=$hostSessionId module=M01 outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue)
                    }
                    if (-not $gotRevokedG1 -and $captureG1) {
                        $gotRevokedG1 = Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_MEMBER_REVOKED .*moduleId=M01 .*sourceGeneration=$captureG1" -Quiet -ErrorAction SilentlyContinue
                    }
                    if ($gotMemberReplaced -and ($gotRevokedG1 -or -not $captureG1)) { break }
                    Start-Sleep -Seconds 1
                }
                Write-Host "PA-SR5 rotate fingerprints: MEMBER_REPLACED=$gotMemberReplaced REVOKED_G1=$gotRevokedG1 captureG1=$captureG1"
                Write-ShadowRuntimeGate @{
                    driverRotatePhase = $(if ($gotMemberReplaced) { "COMPLETE" } else { "INCOMPLETE" })
                    obsMemberReplaced = $(if ($gotMemberReplaced) { "observed" } else { "missing" })
                    obsRevokedG1 = $(if ($gotRevokedG1) { "observed" } else { "missing" })
                }

                Write-Host "PA-SR5 Window C: poll post-replace media on G2 (${ShadowRejoinWaitSec}s)..."
                Write-ShadowRuntimeGate @{ sr5Window = "C"; driverPostReplacePhase = "RUNNING" }
                $postDeadline = (Get-Date).AddSeconds($ShadowRejoinWaitSec)
                $gotG2Install = $false
                $gotPostIngress = $false
                $gotPostPlayout = $false
                while ((Get-Date) -lt $postDeadline) {
                    if (-not $gotG2Install) {
                        if ($captureG1) {
                            $gotG2Install = Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_MEMBER_INSTALLED .*moduleId=M01 .*sourceGeneration=([0-9]+)" -ErrorAction SilentlyContinue |
                                Where-Object { [long]$_.Matches[0].Groups[1].Value -gt [long]$captureG1 } |
                                Select-Object -First 1
                            $gotG2Install = [bool]$gotG2Install
                        } else {
                            $installCount = @(Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_MEMBER_INSTALLED .*moduleId=M01" -ErrorAction SilentlyContinue).Count
                            $gotG2Install = $installCount -ge 2
                        }
                    }
                    if (-not $gotPostIngress) {
                        $gotPostIngress = Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY .*sessionId=$hostSessionId .*packetsReceived=[1-9]" -Quiet -ErrorAction SilentlyContinue
                    }
                    if (-not $gotPostPlayout) {
                        $gotPostPlayout = Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId .*successfulPlayoutWrites=[1-9]" -Quiet -ErrorAction SilentlyContinue
                    }
                    if ($gotG2Install -and $gotPostIngress -and $gotPostPlayout) { break }
                    Start-Sleep -Seconds 1
                }
                if (-not (Invoke-HostPttStimulusBurst $M01Serial "M01" 2 2 2)) {
                    Abort-HarnessUi "PA_SR5_window_C_ptt_stimulus_burst_failed" "PA_SR5_WINDOW_C" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                }
                Write-Host "PA-SR5 post-replace: G2_INSTALL=$gotG2Install POST_INGRESS=$gotPostIngress POST_PLAYOUT=$gotPostPlayout"
                Write-ShadowRuntimeGate @{
                    driverPostReplacePhase = $(if ($gotG2Install -and $gotPostPlayout) { "COMPLETE" } else { "INCOMPLETE" })
                    obsG2Installed = $(if ($gotG2Install) { "observed" } else { "missing" })
                    obsPostIngress = $(if ($gotPostIngress) { "observed" } else { "missing" })
                    obsPostPlayout = $(if ($gotPostPlayout) { "observed" } else { "missing" })
                    driverLeavePhase = "SKIPPED"
                    driverRejoinPhase = "SKIPPED"
                    driverEndPhase = "SKIPPED"
                    driverObservationPhase = "COMPLETE"
                }
                Write-Host "PA-SR6: SKIPPED (PaSr5-only run)"
            } elseif ($script:IsCutoverFamily) {
                $cutoverLabel = switch ($DriverMode) {
                    "PaG2" { "G2" }
                    "PaG3" { "G3" }
                    "PaR2" { "R2" }
                    "PaPilot1" { "PILOT-1" }
                    default { "RC1" }
                }
                if ($script:IsPaG2Family) {
                    Write-Host "G2/R2: cutover readiness gate (3-node supplement/shadow + host PID stable + SRD complete)..."
                    $g2Ready = Test-G2CutoverReadiness -HostSessionId $hostSessionId -M01Handle $m01Log -M02Handle $m02Log -M03Handle $m03Log -M01Serial $M01Serial -StableSec 10
                    if (-not $g2Ready.Ok) {
                        $reason = "G2_cutover_readiness_" + ($g2Ready.Missing -join "_")
                        Write-Host "G2 cutover readiness FAIL: $($g2Ready.Missing -join ', ')"
                        @(
                            "gate=G2_CUTOVER_READINESS"
                            "verdict=NOT_TESTED_CUTOVER"
                            "reason=$reason"
                            "missing=$($g2Ready.Missing -join ',')"
                            "hostSessionId=$hostSessionId"
                        ) | Set-Content (Join-Path $logDir "CUTOVER_READINESS_GATE.txt") -Encoding UTF8
                        Abort-HarnessUi $reason "CUTOVER_READINESS" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                    }
                    Write-Host "G2 cutover readiness PASS"
                }

                Write-Rc1Gate @{ rc1Window = "B"; driverCutoverPhase = "RUNNING"; hostSessionId = $hostSessionId; cutoverStartedAt = (Get-Date).ToString("o") }
                $cutoverWaitSec = if ($script:IsPaR2Family) { 120 } else { $Rc1PostCutoverSec }
                $gotAutoCutoverSucceeded = $false
                $gotCutoverSucceeded = $false
                $gotMulticastActive = $false
                $gotProductionWrites = $false
                $gotOwnerVerifyB = $false
                $gotDualOwner = $false
                $gotShadowSessionStarted = $false

                if ($script:IsManualCutoverFamily) {
                    Write-Host "${cutoverLabel}: enable pilot on M02..."
                    Invoke-Rc1Broadcast -Serial $M02Serial -Action "com.talkback.appprod.debug.RC1_PILOT_ENABLE" -Enabled $true
                    Start-Sleep -Seconds 2
                    Write-Host "${cutoverLabel} cutover: arm → execute on M02 (remote=M01)..."
                    Invoke-Rc1Broadcast -Serial $M02Serial -Action "com.talkback.appprod.debug.RC1_ARM_CUTOVER"
                    Start-Sleep -Seconds 1
                    Invoke-Rc1Broadcast -Serial $M02Serial -Action "com.talkback.appprod.debug.RC1_EXECUTE_CUTOVER"
                } else {
                    Write-Host "R2: waiting for production automatic cutover (NO adb RC1 broadcast)..."
                }

                if ($PreferHeadsetPlayback) {
                    Apply-HeadsetPlaybackRoute (Get-MeetingPlaybackSerials)
                }
                Apply-MeetingMuteAll (Get-MeetingPlaybackSerials)
                if (-not (Invoke-HostPttStimulusBurst $M01Serial "M01" 3)) {
                    if ($script:IsManualCutoverFamily) {
                        Abort-HarnessUi "${cutoverLabel}_cutover_ptt_stimulus_burst_failed" "CUTOVER" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                    } else {
                        Write-Host "R2: M01 PTT burst failed during auto-cutover wait (continue observing)"
                    }
                }
                $cutoverDeadline = (Get-Date).AddSeconds($cutoverWaitSec)
                while ((Get-Date) -lt $cutoverDeadline) {
                    if ($script:IsPaR2Family -and -not $gotAutoCutoverSucceeded) {
                        $gotAutoCutoverSucceeded = Select-String -Path $m02Log.Path -Pattern "RC1_FIELD outcome=AUTO_CUTOVER_SUCCEEDED session=$hostSessionId" -Quiet -ErrorAction SilentlyContinue
                    }
                    if (-not $gotShadowSessionStarted) {
                        $gotShadowSessionStarted = Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_SESSION_STARTED .*sessionId=$hostSessionId" -Quiet -ErrorAction SilentlyContinue
                    }
                    if (-not $gotCutoverSucceeded) {
                        $gotCutoverSucceeded = Select-String -Path $m02Log.Path -Pattern "RC1_FIELD outcome=CUTOVER_SUCCEEDED session=$hostSessionId" -Quiet -ErrorAction SilentlyContinue
                    }
                    if (-not $gotMulticastActive) {
                        $gotMulticastActive = Select-String -Path $m02Log.Path -Pattern "AUDIBLE_OWNERSHIP_STATE session=$hostSessionId state=MULTICAST_ACTIVE" -Quiet -ErrorAction SilentlyContinue
                    }
                    if (-not $gotProductionWrites) {
                        $gotProductionWrites = Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*audioTrackOwner=MULTICAST_PRODUCTION .*sessionId=$hostSessionId .*successfulPlayoutWrites=[1-9]" -Quiet -ErrorAction SilentlyContinue
                        if (-not $gotProductionWrites) {
                            $gotProductionWrites = Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId .*successfulPlayoutWrites=[1-9]" -Quiet -ErrorAction SilentlyContinue
                        }
                    }
                    if (-not $gotOwnerVerifyB) {
                        $gotOwnerVerifyB = Select-String -Path $m02Log.Path -Pattern "OWNER_VERIFY session=$hostSessionId pass=true .*multicastProduction=true" -Quiet -ErrorAction SilentlyContinue
                    }
                    $gotDualOwner = Select-String -Path $m02Log.Path -Pattern "OWNERSHIP_INVARIANT_VIOLATION session=$hostSessionId" -Quiet -ErrorAction SilentlyContinue
                    if ($gotDualOwner) { break }
                    if ($script:IsPaR2Family) {
                        if ($gotAutoCutoverSucceeded -and $gotMulticastActive -and $gotProductionWrites) { break }
                    } elseif ($gotCutoverSucceeded -and $gotMulticastActive -and $gotProductionWrites -and $gotOwnerVerifyB) {
                        break
                    }
                    Start-Sleep -Seconds 1
                }
                Write-Host "${cutoverLabel} cutover: AUTO_CUTOVER=$gotAutoCutoverSucceeded CUTOVER_SUCCEEDED=$gotCutoverSucceeded MULTICAST_ACTIVE=$gotMulticastActive PRODUCTION_WRITES=$gotProductionWrites OWNER_VERIFY=$gotOwnerVerifyB DUAL_OWNER=$gotDualOwner"
                Write-Rc1Gate @{
                    driverCutoverPhase = $(if (($script:IsPaR2Family -and $gotAutoCutoverSucceeded -and $gotMulticastActive -and -not $gotDualOwner) -or ($script:IsManualCutoverFamily -and $gotCutoverSucceeded -and -not $gotDualOwner)) { "COMPLETE" } else { "INCOMPLETE" })
                    obsAutoCutoverSucceeded = $(if ($gotAutoCutoverSucceeded) { "observed" } else { "missing" })
                    obsShadowSessionStarted = $(if ($gotShadowSessionStarted) { "observed" } else { "missing" })
                    obsCutoverSucceeded = $(if ($gotCutoverSucceeded) { "observed" } else { "missing" })
                    obsMulticastActive = $(if ($gotMulticastActive) { "observed" } else { "missing" })
                    obsProductionWrites = $(if ($gotProductionWrites) { "observed" } else { "missing" })
                    obsOwnerVerifyB = $(if ($gotOwnerVerifyB) { "observed" } else { "missing" })
                    obsDualOwner = $(if ($gotDualOwner) { "observed" } else { "missing" })
                    cutoverMode = $(if ($script:IsPaR2Family) { "AUTO_PRODUCTION" } else { "MANUAL_DEBUG_BROADCAST" })
                }
                if ($script:IsPaR2Family) {
                    if ($gotDualOwner -or -not $gotAutoCutoverSucceeded -or -not $gotMulticastActive) {
                        Abort-HarnessUi "R2_auto_cutover_failed" "CUTOVER" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                    }
                } elseif ($gotDualOwner -or -not $gotCutoverSucceeded) {
                    Abort-HarnessUi "${cutoverLabel}_cutover_failed" "CUTOVER" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                }

                if ($script:IsPaG2Family -and $m03Log) {
                    Write-Host "G2: M03 TX readiness gate (member binding + shadow + TX seam quiescent)..."
                    $m03TxReady = Wait-G2M03TxReadiness -HostSessionId $hostSessionId -M03Handle $m03Log -TimeoutSec 90
                    if (-not $m03TxReady.Ok) {
                        $reason = "G2_m03_tx_readiness_" + ($m03TxReady.Missing -join "_")
                        Write-Host "G2 M03 TX readiness FAIL: $($m03TxReady.Missing -join ', ')"
                        Write-G2MultiSourceStimulusGate $logDir $hostSessionId $m03TxReady @{
                            m01PttBurstOk = $false
                            m03PttBurstOk = $false
                            m03PttSecondBurstOk = $false
                            m03CaptureOnObserved = $false
                            m03TxObserved = $false
                            m01IngressFromM03 = $false
                            m03IngressFromM01 = $false
                            m03TxFencedNoMemberBinding = $false
                        }
                        Abort-HarnessUi $reason "G2_MULTI_SOURCE_READINESS" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                    }
                    Write-Host "G2 M03 TX readiness PASS (binding+shadow+supplement+seam clear)"
                    Write-Host "G2: deterministic multi-source PTT (M01 burst → M03 burst ×2)..."
                    if ($PreferHeadsetPlayback) {
                        Apply-HeadsetPlaybackRoute (Get-MeetingPlaybackSerials)
                    }
                    Apply-MeetingMuteAll (Get-MeetingPlaybackSerials)
                    $multiStimulus = Invoke-G2DeterministicMultiSourceStimulus `
                        -HostSessionId $hostSessionId `
                        -M01Serial $M01Serial `
                        -M03Serial $M03Serial `
                        -M01Handle $m01Log `
                        -M03Handle $m03Log `
                        -GapSec 2
                    Write-G2MultiSourceStimulusGate $logDir $hostSessionId $m03TxReady $multiStimulus
                    Apply-MeetingMuteAll (Get-MeetingPlaybackSerials)
                    Write-Host (
                        "G2 multi-source stimulus: M01_PTT=$($multiStimulus.m01PttBurstOk) " +
                        "M03_PTT=$($multiStimulus.m03PttBurstOk)/$($multiStimulus.m03PttSecondBurstOk) " +
                        "M03_TX=$($multiStimulus.m03TxObserved) M01_INGRESS_M03=$($multiStimulus.m01IngressFromM03) " +
                        "M03_FENCE=$($multiStimulus.m03TxFencedNoMemberBinding)"
                    )
                    if ($multiStimulus.m03PttBurstOk -and $multiStimulus.m03TxFencedNoMemberBinding -and -not $multiStimulus.m03TxObserved) {
                        Write-Host "G2: M03 PTT delivered but TX seam still NO_MEMBER_BINDING — product defect candidate (harness continues soak for adjudication)"
                    }
                }

                if ($script:IsPaPilot1Family) {
                    $multicastStartedAt = Get-Date
                    $soakLabel = switch ($DriverMode) {
                        "PaG2" { "G2" }
                        "PaG3" { "G3" }
                        "PaR2" { "R2" }
                        default { "PILOT-1" }
                    }
                    Write-Host "${soakLabel} use: multicast production audible soak ${PilotUseSec}s (no mandatory rollback)..."
                    if ($PreferHeadsetPlayback) {
                        Apply-HeadsetPlaybackRoute (Get-MeetingPlaybackSerials)
                    }
                    Apply-MeetingMuteAll (Get-MeetingPlaybackSerials)
                    Write-Rc1Gate @{ rc1Window = "USE"; driverPilotPhase = "RUNNING"; multicastProductionStartedAt = $multicastStartedAt.ToString("o") }
                    $useDeadline = $multicastStartedAt.AddSeconds($PilotUseSec)
                    $useBurst = 0
                    while ((Get-Date) -lt $useDeadline) {
                        $useBurst++
                        if (-not (Invoke-HostPttStimulusBurst $M01Serial "M01" 1 2 2)) {
                            Write-Host "${soakLabel} use: M01 PTT burst $useBurst failed (continue observing)"
                        }
                        $gotDualOwner = Select-String -Path $m02Log.Path -Pattern "OWNERSHIP_INVARIANT_VIOLATION session=$hostSessionId" -Quiet -ErrorAction SilentlyContinue
                        $lostMulticast = Select-String -Path $m02Log.Path -Pattern "AUDIBLE_OWNERSHIP_STATE session=$hostSessionId state=ANCHOR_ACTIVE" -Quiet -ErrorAction SilentlyContinue
                        $autoRollback = Select-String -Path $m02Log.Path -Pattern "RC1_FIELD outcome=AUTO_ROLLBACK_SUCCEEDED session=$hostSessionId" -Quiet -ErrorAction SilentlyContinue
                        if ($gotDualOwner) {
                            Write-Rc1Gate @{ obsDualOwner = "observed"; driverUsePhase = "ABORT_DUAL_OWNER" }
                            Abort-HarnessUi "PILOT1_dual_owner_during_use" "PILOT_USE" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                        }
                        if ($autoRollback -or ($lostMulticast -and -not $gotCutoverSucceeded)) {
                            # lostMulticast alone is weak if ANCHOR_ACTIVE appeared pre-cutover; check auto-rollback / post-cutover loss
                        }
                        if ($autoRollback) {
                            Write-Rc1Gate @{ obsUnexpectedAutoRollback = "observed"; driverUsePhase = "ABORT_AUTO_ROLLBACK" }
                            Abort-HarnessUi "PILOT1_unexpected_auto_rollback" "PILOT_USE" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                        }
                        Start-Sleep -Seconds 1
                    }
                    $multicastDurationSec = [int]((Get-Date) - $multicastStartedAt).TotalSeconds
                    Write-Rc1Gate @{
                        driverUsePhase = "COMPLETE"
                        multicastProductionDurationSec = "$multicastDurationSec"
                        pilotUseBursts = "$useBurst"
                    }

                    Write-Host "PILOT-1 end: M01 host Meeting end (product path)..."
                    Write-Rc1Gate @{ rc1Window = "END"; driverEndPhase = "RUNNING" }
                    Write-ShadowRuntimeGate @{
                        driverEndPhase = "RUNNING"
                        driverLeavePhase = "SKIPPED"
                        driverRejoinPhase = "SKIPPED"
                        driverRotatePhase = "SKIPPED"
                    }
                    if (-not (Ensure-HostMeetingEndReady $M01Serial "M01")) {
                        Abort-HarnessUi "PILOT1_meeting_end_controls_not_ready" "PILOT_END" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                    }
                    $endTapped = Invoke-HostMeetingEndUi $M01Serial
                    if (-not $endTapped) {
                        Abort-HarnessUi "PILOT1_meeting_end_tap_failed" "PILOT_END" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                    }
                    $endDeadline = (Get-Date).AddSeconds(45)
                    $gotSessionStopped = $false
                    $gotTeardownEmpty = $false
                    while ((Get-Date) -lt $endDeadline) {
                        if (-not $gotSessionStopped) {
                            $stoppedPattern = "SHADOW hook=SESSION_STOPPED session=$hostSessionId .*outcome=APPLIED"
                            $gotSessionStopped = (Select-String -Path $m01Log.Path -Pattern $stoppedPattern -Quiet -ErrorAction SilentlyContinue) -or
                                (Select-String -Path $m02Log.Path -Pattern $stoppedPattern -Quiet -ErrorAction SilentlyContinue)
                        }
                        if (-not $gotTeardownEmpty) {
                            $teardownCandidates = @(Select-String -Path $m01Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$hostSessionId" -ErrorAction SilentlyContinue) +
                                @(Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$hostSessionId" -ErrorAction SilentlyContinue)
                            foreach ($td in $teardownCandidates) {
                                $line = $td.Line
                                if ($line -match "transportScopeActive=false" -and
                                    $line -match "catalogEntries=0" -and
                                    $line -match "admittedCount=0" -and
                                    $line -match "activeJitterSources=0" -and
                                    $line -match "liveDecoders=0" -and
                                    $line -match "jitterBufferCount=0") {
                                    $gotTeardownEmpty = $true
                                    break
                                }
                            }
                        }
                        if ($gotSessionStopped -and $gotTeardownEmpty) { break }
                        Start-Sleep -Seconds 1
                    }
                    Write-Host "PILOT-1 end: SESSION_STOPPED=$gotSessionStopped TEARDOWN_EMPTY=$gotTeardownEmpty"
                    Write-Rc1Gate @{
                        driverEndPhase = $(if ($gotSessionStopped -and $gotTeardownEmpty) { "COMPLETE" } else { "INCOMPLETE" })
                        obsSessionStopped = $(if ($gotSessionStopped) { "observed" } else { "missing" })
                        obsTeardownEmpty = $(if ($gotTeardownEmpty) { "observed" } else { "missing" })
                        driverObservationPhase = "COMPLETE"
                        mandatoryRollback = "false"
                    }
                    Write-ShadowRuntimeGate @{
                        obsSessionStopped = $(if ($gotSessionStopped) { "observed" } else { "missing" })
                        obsTeardownEmpty = $(if ($gotTeardownEmpty) { "observed" } else { "missing" })
                        driverEndPhase = $(if ($gotSessionStopped -and $gotTeardownEmpty) { "COMPLETE" } else { "INCOMPLETE" })
                        driverObservationPhase = "COMPLETE"
                    }
                } else {
                    Write-Host "RC1 Window C: manual rollback on M02..."
                    Write-Rc1Gate @{ rc1Window = "C"; driverRollbackPhase = "RUNNING" }
                    Invoke-Rc1Broadcast -Serial $M02Serial -Action "com.talkback.appprod.debug.RC1_ROLLBACK"
                    if (-not (Invoke-HostPttStimulusBurst $M01Serial "M01" 2 2 2)) {
                        Abort-HarnessUi "RC1_window_C_ptt_stimulus_burst_failed" "RC1_WINDOW_C" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                    }
                    $rollbackDeadline = (Get-Date).AddSeconds($Rc1PostRollbackSec)
                    $gotRollbackSucceeded = $false
                    $gotAnchorRestored = $false
                    $gotMulticastReleased = $false
                    $gotAnchorActiveState = $false
                    while ((Get-Date) -lt $rollbackDeadline) {
                        if (-not $gotRollbackSucceeded) {
                            $gotRollbackSucceeded = Select-String -Path $m02Log.Path -Pattern "RC1_FIELD outcome=ROLLBACK_SUCCEEDED session=$hostSessionId" -Quiet -ErrorAction SilentlyContinue
                        }
                        if (-not $gotAnchorRestored) {
                            $gotAnchorRestored = Select-String -Path $m02Log.Path -Pattern "ANCHOR_AUDIBLE_PROBE session=$hostSessionId active=true reason=replacement_cutover_anchor_restore" -Quiet -ErrorAction SilentlyContinue
                        }
                        if (-not $gotMulticastReleased) {
                            $gotMulticastReleased = Select-String -Path $m02Log.Path -Pattern "MULTICAST_AUDIBLE_PROBE session=$hostSessionId active=false" -Quiet -ErrorAction SilentlyContinue
                        }
                        if (-not $gotAnchorActiveState) {
                            $gotAnchorActiveState = Select-String -Path $m02Log.Path -Pattern "AUDIBLE_OWNERSHIP_STATE session=$hostSessionId state=ANCHOR_ACTIVE" -Quiet -ErrorAction SilentlyContinue
                        }
                        if ($gotRollbackSucceeded -and $gotAnchorRestored -and $gotMulticastReleased -and $gotAnchorActiveState) { break }
                        Start-Sleep -Seconds 1
                    }
                    Write-Host "RC1 Window C: ROLLBACK_SUCCEEDED=$gotRollbackSucceeded ANCHOR_RESTORED=$gotAnchorRestored MULTICAST_RELEASED=$gotMulticastReleased ANCHOR_STATE=$gotAnchorActiveState"
                    Write-Rc1Gate @{
                        driverRollbackPhase = $(if ($gotRollbackSucceeded) { "COMPLETE" } else { "INCOMPLETE" })
                        obsRollbackSucceeded = $(if ($gotRollbackSucceeded) { "observed" } else { "missing" })
                        obsAnchorRestored = $(if ($gotAnchorRestored) { "observed" } else { "missing" })
                        obsMulticastReleased = $(if ($gotMulticastReleased) { "observed" } else { "missing" })
                        obsAnchorActiveState = $(if ($gotAnchorActiveState) { "observed" } else { "missing" })
                        driverObservationPhase = "COMPLETE"
                    }
                    Write-ShadowRuntimeGate @{
                        driverLeavePhase = "SKIPPED"
                        driverRejoinPhase = "SKIPPED"
                        driverEndPhase = "SKIPPED"
                        driverRotatePhase = "SKIPPED"
                        driverObservationPhase = "COMPLETE"
                    }
                    Write-Host "PA-SR5/SR6: SKIPPED (RC1-only run)"
                }
            } elseif ($script:IsPaSr6Family) {
                $confLine = Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*sessionId=$hostSessionId .*conferenceId=" -ErrorAction SilentlyContinue | Select-Object -First 1
                $captureConferenceId = if ($confLine -and $confLine.Line -match "conferenceId=([^ ]+)") { $Matches[1] } else { "" }
                $baselineIngressMax = Get-MaxShadowMetricFromLog $m02Log.Path "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY .*sessionId=$hostSessionId" "packetsReceived"
                $baselinePlayoutMax = Get-MaxShadowMetricFromLog $m02Log.Path "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId" "successfulPlayoutWrites"

                Write-Host "PA-SR6 Window B: M01 host Meeting end (product path)..."
                Write-ShadowRuntimeGate @{
                    sr6Window = "B"
                    driverEndPhase = "RUNNING"
                    captureConferenceId = $(if ($captureConferenceId) { $captureConferenceId } else { "missing" })
                    baselineIngressMax = $baselineIngressMax
                    baselinePlayoutMax = $baselinePlayoutMax
                    driverLeavePhase = "SKIPPED"
                    driverRejoinPhase = "SKIPPED"
                    driverRotatePhase = "SKIPPED"
                }
                if (-not (Ensure-HostMeetingEndReady $M01Serial "M01")) {
                    Abort-HarnessUi "PA_SR6_window_B_meeting_end_controls_not_ready" "PA_SR6_WINDOW_B" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                }
                $endTapped = Invoke-HostMeetingEndUi $M01Serial
                if (-not $endTapped) {
                    Abort-HarnessUi "PA_SR6_window_B_meeting_end_tap_failed" "PA_SR6_WINDOW_B" $m01Log $m02Log $joinGate $groupGate $inviteJoinGate
                }
                $endDeadline = (Get-Date).AddSeconds(45)
                $gotSessionStopped = $false
                $gotPlayoutDisarmed = $false
                $gotTeardownEmpty = $false
                while ((Get-Date) -lt $endDeadline) {
                    if (-not $gotSessionStopped) {
                        $stoppedPattern = "SHADOW hook=SESSION_STOPPED session=$hostSessionId .*outcome=APPLIED"
                        $gotSessionStopped = (Select-String -Path $m01Log.Path -Pattern $stoppedPattern -Quiet -ErrorAction SilentlyContinue) -or
                            (Select-String -Path $m02Log.Path -Pattern $stoppedPattern -Quiet -ErrorAction SilentlyContinue)
                    }
                    if (-not $gotPlayoutDisarmed) {
                        $gotPlayoutDisarmed = (Select-String -Path $m01Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_PLAYOUT_DISARMED .*sessionId=$hostSessionId" -Quiet -ErrorAction SilentlyContinue) -or
                            (Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_PLAYOUT_DISARMED .*sessionId=$hostSessionId" -Quiet -ErrorAction SilentlyContinue)
                    }
                    if (-not $gotTeardownEmpty) {
                        $teardownCandidates = @(Select-String -Path $m01Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$hostSessionId" -ErrorAction SilentlyContinue) +
                            @(Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$hostSessionId" -ErrorAction SilentlyContinue)
                        foreach ($td in $teardownCandidates) {
                            $line = $td.Line
                            if ($line -match "transportScopeActive=false" -and
                                $line -match "catalogEntries=0" -and
                                $line -match "admittedCount=0" -and
                                $line -match "activeJitterSources=0" -and
                                $line -match "liveDecoders=0" -and
                                $line -match "jitterBufferCount=0") {
                                $gotTeardownEmpty = $true
                                break
                            }
                        }
                    }
                    if ($gotSessionStopped -and $gotPlayoutDisarmed -and $gotTeardownEmpty) { break }
                    Start-Sleep -Seconds 1
                }
                Write-Host "PA-SR6 Window B fingerprints: SESSION_STOPPED=$gotSessionStopped PLAYOUT_DISARMED=$gotPlayoutDisarmed TEARDOWN_EMPTY=$gotTeardownEmpty"
                Write-ShadowRuntimeGate @{
                    obsSessionStopped = $(if ($gotSessionStopped) { "observed" } else { "missing" })
                    obsPlayoutDisarmed = $(if ($gotPlayoutDisarmed) { "observed" } else { "missing" })
                    obsTeardownEmpty = $(if ($gotTeardownEmpty) { "observed" } else { "missing" })
                }

                Write-Host "PA-SR6 Window C: bounded quiescence observation ${ShadowQuiescenceSec}s (no resurrection)..."
                Write-ShadowRuntimeGate @{ sr6Window = "C"; quiescenceSec = $ShadowQuiescenceSec }
                $teardownIngressMax = Get-MaxShadowMetricFromLog $m02Log.Path "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY .*sessionId=$hostSessionId" "packetsReceived"
                $teardownPlayoutMax = Get-MaxShadowMetricFromLog $m02Log.Path "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId" "successfulPlayoutWrites"
                Start-Sleep -Seconds $ShadowQuiescenceSec
                $postIngressMax = Get-MaxShadowMetricFromLog $m02Log.Path "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY .*sessionId=$hostSessionId" "packetsReceived"
                $postPlayoutMax = Get-MaxShadowMetricFromLog $m02Log.Path "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId" "successfulPlayoutWrites"
                $ingressDelta = $postIngressMax - $teardownIngressMax
                $playoutDelta = $postPlayoutMax - $teardownPlayoutMax
                Write-Host "PA-SR6 quiescence: ingressDelta=$ingressDelta playoutDelta=$playoutDelta teardownIngressMax=$teardownIngressMax postIngressMax=$postIngressMax"
                Write-ShadowRuntimeGate @{
                    quiescenceIngressDelta = $ingressDelta
                    quiescencePlayoutDelta = $playoutDelta
                    driverEndPhase = $(if ($gotSessionStopped -and $gotPlayoutDisarmed -and $gotTeardownEmpty) { "COMPLETE" } else { "INCOMPLETE" })
                    driverObservationPhase = "COMPLETE"
                }
            } else {
            Write-Host "PA-SR5 (legacy ShadowRuntime): controlled peer leave on M02 — NOT authorized for frozen SR5 gate..."
            Write-ShadowRuntimeGate @{ driverLeavePhase = "RUNNING" }
            Tap-IfPresent $M02Serial "${PackageName}:id/btnMeetingLeave" | Out-Null
            Tap-IfPresent $M02Serial "${PackageName}:id/btnMeetingLeaveTop" | Out-Null
            Tap-IfPresent $M02Serial "${PackageName}:id/btnHangup" | Out-Null
            $leaveDeadline = (Get-Date).AddSeconds(30)
            $gotMemberRemoved = $false
            while ((Get-Date) -lt $leaveDeadline -and -not $gotMemberRemoved) {
                $gotMemberRemoved = (Select-String -Path $m01Log.Path -Pattern "SHADOW hook=MEMBER_REMOVED session=$hostSessionId module=M02 outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue) -or
                    (Select-String -Path $m02Log.Path -Pattern "SHADOW hook=MEMBER_REMOVED session=$hostSessionId module=M02 outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue)
                Start-Sleep -Seconds 1
            }
            Write-Host "PA-SR5 leave fingerprint: MEMBER_REMOVED=$gotMemberRemoved"
            Write-ShadowRuntimeGate @{
                driverLeavePhase = $(if ($gotMemberRemoved) { "COMPLETE" } else { "INCOMPLETE" })
                obsMemberRemoved = $(if ($gotMemberRemoved) { "observed" } else { "missing" })
            }

            Write-Host "PA-SR5: peer rejoin same Meeting (poll ${ShadowRejoinWaitSec}s)..."
            Write-ShadowRuntimeGate @{ driverRejoinPhase = "RUNNING" }
            $btnJoinRid = "${PackageName}:id/btnJoinMeeting"
            $rejoinDeadline = (Get-Date).AddSeconds($ShadowRejoinWaitSec)
            $rejoinClicked = $false
            $rejoinReady = $false
            while ((Get-Date) -lt $rejoinDeadline) {
                if (Test-PeerInviteObservedForH $m02Log.Path $hostSessionId) {
                    if (-not $rejoinClicked) {
                        if ((Find-ResourcePresent $M02Serial $btnJoinRid) -or (Test-ResourceClickable $M02Serial $btnJoinRid)) {
                            Tap-ByResourceId $M02Serial $btnJoinRid
                            $rejoinClicked = $true
                            Write-Host "PA-SR5: rejoin tap dispatched"
                        }
                    }
                }
                $memberReadyCount = @(Select-String -Path $m01Log.Path -Pattern "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=M02 outcome=APPLIED" -ErrorAction SilentlyContinue).Count +
                    @(Select-String -Path $m02Log.Path -Pattern "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=M02 outcome=APPLIED" -ErrorAction SilentlyContinue).Count
                if ($memberReadyCount -ge 2) {
                    $rejoinReady = $true
                    break
                }
                Start-Sleep -Milliseconds $InviteJoinPollMs
            }
            Write-Host "PA-SR5 rejoin fingerprint: CLICKED=$rejoinClicked MEMBER_READY_COUNT>=2=$rejoinReady"
            Write-ShadowRuntimeGate @{
                driverRejoinPhase = $(if ($rejoinReady) { "COMPLETE" } else { "INCOMPLETE" })
                obsRejoinMemberReady = $(if ($rejoinReady) { "observed" } else { "missing" })
            }

            if ($rejoinReady) {
                for ($ptt = 1; $ptt -le 2; $ptt++) {
                    Tap-ByResourceId $M01Serial "${PackageName}:id/btnPtt"
                    Start-Sleep -Seconds 2
                    Tap-ByResourceId $M01Serial "${PackageName}:id/btnPtt"
                    Start-Sleep -Seconds 2
                }
            }

            Write-Host "PA-SR6: controlled Meeting end on host..."
            Write-ShadowRuntimeGate @{ driverEndPhase = "RUNNING" }
            Tap-IfPresent $M01Serial "${PackageName}:id/btnHangup" | Out-Null
            Tap-IfPresent $M01Serial "${PackageName}:id/btnMeetingLeave" | Out-Null
            Tap-IfPresent $M01Serial "${PackageName}:id/btnMeetingLeaveTop" | Out-Null
            $endDeadline = (Get-Date).AddSeconds(45)
            $gotSessionStopped = $false
            $gotTeardownEmpty = $false
            while ((Get-Date) -lt $endDeadline) {
                if (-not $gotSessionStopped) {
                    $gotSessionStopped = (Select-String -Path $m01Log.Path -Pattern "SHADOW hook=SESSION_STOPPED session=$hostSessionId outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue) -or
                        (Select-String -Path $m02Log.Path -Pattern "SHADOW hook=SESSION_STOPPED session=$hostSessionId outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue)
                }
                if (-not $gotTeardownEmpty) {
                    $gotTeardownEmpty = (Select-String -Path $m01Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$hostSessionId .*transportScopeActive=false" -Quiet -ErrorAction SilentlyContinue) -or
                        (Select-String -Path $m02Log.Path -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$hostSessionId .*transportScopeActive=false" -Quiet -ErrorAction SilentlyContinue)
                }
                if ($gotSessionStopped -and $gotTeardownEmpty) { break }
                Start-Sleep -Seconds 1
            }
            Write-Host "PA-SR6 end fingerprints: SESSION_STOPPED=$gotSessionStopped TEARDOWN_EMPTY=$gotTeardownEmpty"
            Write-ShadowRuntimeGate @{
                driverEndPhase = $(if ($gotSessionStopped -and $gotTeardownEmpty) { "COMPLETE" } else { "INCOMPLETE" })
                obsSessionStopped = $(if ($gotSessionStopped) { "observed" } else { "missing" })
                obsTeardownEmpty = $(if ($gotTeardownEmpty) { "observed" } else { "missing" })
                driverObservationPhase = "COMPLETE"
            }
            } # end non-PaSr4Family leave/rejoin/end
        }
    } else {
    Write-Host "Waiting for positive OBS up to ${PositiveWaitSec}s (product auto post-bind only; no debug republish)..."
    $deadline = (Get-Date).AddSeconds($PositiveWaitSec)
    $gotOrigin = $false; $gotPostBind = $false; $gotWire = $false; $gotReady = $false; $gotApplied = $false
    $gotPreBindDrop = $false; $gotProductReason = $false
    while ((Get-Date) -lt $deadline) {
        # Continuous join pollution guards
        $peerCreate = Get-FirstMatch $m02Log.Path 'CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=([0-9a-fA-F-]{36}) .* creationSource=USER_CREATE'
        if ($peerCreate -and $peerCreate -ne $hostSessionId) {
            Abort-JoinPrecondition "PRE_J2_late_peer_created_session_$peerCreate" $m01Log $m02Log $joinGate $inviteJoinGate
        }

        if (-not $gotOrigin) {
            $gotOrigin = Select-String -Path $m01Log.Path -Pattern "PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN .*publicationOutcome=EMITTED" -Quiet -ErrorAction SilentlyContinue
        }
        if (-not $gotPreBindDrop) {
            $gotPreBindDrop = Select-String -Path $m02Log.Path -Pattern "PRE_BIND_NOT_RETAINED .*factType=11" -Quiet -ErrorAction SilentlyContinue
            if ($gotPreBindDrop) {
                Write-Host "Observed PRE_BIND_NOT_RETAINED factType=11 (allowed) — waiting for product POST_BIND..."
            }
        }
        if (-not $gotPostBind) {
            $gotPostBind = Select-String -Path $m01Log.Path -Pattern "PROFILE01_MEDIA_KEY_PACKAGE_POST_BIND_REPUBLISH .*publicationOutcome=EMITTED" -Quiet -ErrorAction SilentlyContinue
            if (-not $gotPostBind) {
                $gotPostBind = Select-String -Path $m01Log.Path -Pattern "PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN .*publicationOutcome=POST_BIND_EMITTED" -Quiet -ErrorAction SilentlyContinue
            }
        }
        if (-not $gotProductReason) {
            $gotProductReason = Select-String -Path $m01Log.Path -Pattern "PROFILE01_PACKAGE_POST_BIND_REPUBLISH reason=(admission_ready_|peer_media_connected|membership_accepted_no_media)" -Quiet -ErrorAction SilentlyContinue
        }
        if (-not $gotWire) {
            $gotWire = Select-String -Path $m02Log.Path -Pattern "WIRE_INGRESS .*factType=11" -Quiet -ErrorAction SilentlyContinue
        }
        if (-not $gotReady) {
            $gotReady = Select-String -Path $m02Log.Path -Pattern "PROFILE01_MEDIA_SUPPLEMENT .*state=READY" -Quiet -ErrorAction SilentlyContinue
        }
        if (-not $gotApplied) {
            $gotApplied = Select-String -Path $m02Log.Path -Pattern "PROFILE01_MEDIA_KEY_INGEST .*outcome=APPLIED" -Quiet -ErrorAction SilentlyContinue
        }
        if ($gotOrigin -and $gotPostBind -and $gotWire -and $gotReady -and $gotApplied) { break }
        Start-Sleep -Seconds 2
    }
    Write-Host "Positive fingerprints: ORIGIN=$gotOrigin PRE_BIND_DROP=$gotPreBindDrop POST_BIND=$gotPostBind PRODUCT_REASON=$gotProductReason WIRE11=$gotWire READY=$gotReady APPLIED=$gotApplied"

    if (-not ($gotOrigin -and $gotPostBind -and $gotWire -and $gotReady -and $gotApplied)) {
        Write-Host "Positive leg incomplete after same-session bind — still attempting C-F7 while session may be alive"
    } else {
        Write-Host "Positive window closed (product POST_BIND observed) — triggering C-F7 immediately"
    }

    Write-Host "=== Negative leg: C-F7 broadcast on M01 ==="
    & $adb -s $M01Serial shell am broadcast -a com.talkback.appprod.debug.P1C_CF7_RECIPIENT_KEY_VERSION_MISMATCH --es remote M02
    $gotNeg = Wait-LogPattern $m01Log.Path "PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE .*injected=true" $NegativeWaitSec
    $gotReject = Wait-LogPattern $m02Log.Path "PROFILE01_MEDIA_KEY_INGEST .*outcome=REJECTED_RECIPIENT_KEY_VERSION_MISMATCH" $NegativeWaitSec
    Write-Host "Negative fingerprints: INJECTED_OBS=$gotNeg REJECT=$gotReject"
    }
}
finally {
    Stop-DeviceLog $m01Log
    Stop-DeviceLog $m02Log
    Start-Sleep -Seconds 1
}

# Freeze NEGATIVE_FIXTURE from OBS if present (P1-C only)
if ($DriverMode -eq "P1C") {
$negLine = $null
if (Test-Path $m01Log.Path) {
    $negLine = Select-String -Path $m01Log.Path -Pattern "PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE .*injected=true" |
        Select-Object -Last 1 |
        ForEach-Object { $_.Line }
}
$injected = if ($negLine) { "true" } else { "false" }
$nfid = if ($negLine -match "negativeFixtureId=([^ ]+)") { $Matches[1] } else { "" }
$expV = if ($negLine -match "expectedRecipientEstablishmentKeyVersion=([^ ]+)") { $Matches[1] } else { "" }
$wireV = if ($negLine -match "wireRecipientKeyVersion=([^ ]+)") { $Matches[1] } else { "" }
@"
injected=$injected
status=$(if ($injected -eq 'true') { 'injected_from_OBS' } else { 'not_injected' })
expectedRejectReason=RECIPIENT_KEY_VERSION_MISMATCH
fixtureType=RECIPIENT_KEY_VERSION_MISMATCH
negativeFixtureId=$nfid
expectedRecipientEstablishmentKeyVersion=$expV
wireRecipientKeyVersion=$wireV
injectionMethod=adb_broadcast_P1C_CF7_RECIPIENT_KEY_VERSION_MISMATCH
notes=auto-filled from OBS by p1c-package-field-first-run.ps1
"@ | Set-Content -Encoding utf8 (Join-Path $logDir "NEGATIVE_FIXTURE.txt")
}

Write-Host "Adjudicating $logDir (driverMode=$DriverMode) ..."
& powershell -NoProfile -ExecutionPolicy Bypass -File (Get-AdjudicateScript) -LogDir $logDir
$exit = $LASTEXITCODE
Write-Host "FIRST_RUN_EXIT=$exit logDir=$logDir driverMode=$DriverMode"
exit $exit
