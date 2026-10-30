# G5-VA2 field ENV preflight — pure observation, GO/NO-GO only.
param(
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "MDX0220416001963",
    [string]$M04Serial = "192.168.31.206:5555",
    [string]$LogDir = "",
    [int]$CaptureSeconds = 10,
    [int]$DumpTailLines = 5000,
    [string]$RunId = "",
    [string]$ChannelId = "CH-01"
)

$ErrorActionPreference = "Stop"
$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
$PackageName = "com.talkback.appprod"

$ExpectedMembers = @("M01", "M02", "M03", "M04")
$Devices = [ordered]@{
    M01 = $M01Serial
    M02 = $M02Serial
    M03 = $M03Serial
    M04 = $M04Serial
}

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "g5va2-preflight-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

if ([string]::IsNullOrWhiteSpace($LogDir)) {
    $LogDir = Join-Path $repoRoot "logs\$RunId"
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
}

function Connect-WirelessIfNeeded([string]$Serial) {
    if ($Serial -match '^\d+\.\d+\.\d+\.\d+:') {
        $prev = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & $adb connect $Serial 2>&1 | Out-Null
        $ErrorActionPreference = $prev
    }
}

function Test-DeviceOnline([string]$Serial) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $state = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
    $ErrorActionPreference = $prev
    return ($state -eq "device")
}

function Capture-DeviceLog([string]$Label, [string]$Serial, [string]$OutPath) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $text = & $adb -s $Serial logcat -d -t $DumpTailLines -v threadtime `
        -s Talkback:I MEETING_MEDIA_SHADOW:I 2>&1 | Out-String
    $ErrorActionPreference = $prev
    Set-Content -Path $OutPath -Value $text -Encoding utf8
}

function Get-LastMatch([string]$Text, [string]$Pattern) {
    $m = [regex]::Matches($Text, $Pattern)
    if ($m.Count -eq 0) { return $null }
    return $m[$m.Count - 1]
}

function Parse-JoinedMembers([string]$Line) {
    if ($Line -match 'joinedMembers=([^ ]*)') {
        $raw = $Matches[1]
        if ([string]::IsNullOrWhiteSpace($raw)) { return @() }
        return $raw.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    }
    return @()
}

function Parse-MembershipEpoch([string]$Line) {
    if ($Line -match 'membershipEpoch=(\d+)') { return [int]$Matches[1] }
    if ($Line -match 'rosterEpoch=(\d+)') { return [int]$Matches[1] }
    return $null
}

function Parse-PeerIceM02([string]$Line) {
    if ($Line -match 'peerIceStates=([^ ]*)') {
        $blob = $Matches[1]
        $hit = [regex]::Match($blob, 'M02=([A-Z_]+)')
        if ($hit.Success) { return $hit.Groups[1].Value }
    }
    return $null
}

function Normalize-MemberSet([string[]]$Members) {
    return ($Members | Sort-Object) -join ','
}

function Evaluate-SessionGate([string]$M01Text) {
    $result = @{
        Pass = $false
        Evidence = @()
    }

    $modeHit = Get-LastMatch $M01Text 'CHANNEL_MODE ch=[^ ]+ [^ ]+->CONFERENCE'
    if ($modeHit) {
        $result.Evidence += "channelMode=$($modeHit.Value)"
    } else {
        $result.Evidence += "channelMode=MISSING (no IDLE->CONFERENCE)"
    }

    $hostSession = $null
    $created = Get-LastMatch $M01Text 'CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=([0-9a-fA-F-]{36})'
    if ($created -and $created.Groups[1].Success) {
        $hostSession = $created.Groups[1].Value
    }
    if (-not $hostSession) {
        $admit = Get-LastMatch $M01Text 'ADMISSION_DECISION action=CREATE .* incomingSessionId=([0-9a-fA-F-]{36})'
        if ($admit -and $admit.Groups[1].Success) {
            $hostSession = $admit.Groups[1].Value
        }
    }
    if ($hostSession) {
        $result.Evidence += "conferenceSessionId=$hostSession"
    } else {
        $result.Evidence += "conferenceSessionId=MISSING"
    }

    $readiness = Get-LastMatch $M01Text 'GROUP_TRANSITION_READINESS_SNAPSHOT.*'
    if ($readiness) {
        if ($readiness.Value -match 'localSessionId=([^ ]+)') {
            $localSid = $Matches[1]
            $result.Evidence += "localSessionId=$localSid"
            if ($localSid -match '^grp:') {
                $result.Evidence += "groupOnlySession=true"
            }
        }
    }

    $groupDigest = Get-LastMatch $M01Text "GROUP_DIGEST sessionId=grp:$ChannelId sessionType=GROUP"
    if ($groupDigest -and -not $hostSession) {
        $result.Evidence += "staleGroupDigest=grp:$ChannelId"
    }

    $result.Pass = ($null -ne $modeHit) -and ($null -ne $hostSession) -and (-not ($hostSession -match '^grp:'))
    if ($readiness -and ($readiness.Value -match "localSessionId=grp:$ChannelId") -and -not $modeHit) {
        $result.Pass = $false
        $result.Evidence += "blockedBy=GROUP_mesh_without_CONFERENCE"
    }
    return $result
}

function Evaluate-Profile01Liveness([string]$Label, [string]$Text) {
    $count = ([regex]::Matches($Text, 'MEETING_MEDIA_SHADOW:')).Count
    return @{
        Pass = ($count -gt 0)
        Count = $count
        Evidence = "${Label}:MEETING_MEDIA_SHADOW=$count"
    }
}

function Evaluate-MembershipGate([string]$Label, [string]$Text) {
    $snap = Get-LastMatch $Text 'GROUP_TRANSITION_READINESS_SNAPSHOT.*'
    if (-not $snap) {
        $snap = Get-LastMatch $Text 'TOPOLOGY_SNAPSHOT.*'
    }
    if (-not $snap) {
        return @{
            Pass = $false
            Evidence = "${Label}:readinessSnapshot=MISSING"
            Joined = @()
            Epoch = $null
        }
    }
    $joined = Parse-JoinedMembers $snap.Value
    $epoch = Parse-MembershipEpoch $snap.Value
    $expected = Normalize-MemberSet $ExpectedMembers
    $actual = Normalize-MemberSet $joined
    return @{
        Pass = ($actual -eq $expected) -and ($null -ne $epoch)
        Evidence = "${Label}:joinedMembers=$actual epoch=$epoch"
        Joined = $joined
        Epoch = $epoch
    }
}

function Evaluate-M02IceGate([string]$M01Text) {
    $snap = Get-LastMatch $M01Text 'GROUP_TRANSITION_READINESS_SNAPSHOT.*'
    if (-not $snap) {
        return @{ Pass = $false; Evidence = "M01:peerIceStates=MISSING" }
    }
    $state = Parse-PeerIceM02 $snap.Value
    return @{
        Pass = ($state -eq "CONNECTED")
        Evidence = "M01:peerIceStates M02=$state"
    }
}

function Get-OptionalTelemetry([hashtable]$LogsByLabel) {
    $info = @()
    foreach ($label in $LogsByLabel.Keys) {
        $text = $LogsByLabel[$label]
        $prod = ([regex]::Matches($text, 'MULTICAST_PRODUCTION')).Count
        $armed = ([regex]::Matches($text, 'SHADOW_PLAYOUT_ARMED')).Count
        $info += "${label}:MULTICAST_PRODUCTION=$prod SHADOW_PLAYOUT_ARMED=$armed"
    }
    return $info
}

# --- device connectivity ---
foreach ($label in $Devices.Keys) {
    Connect-WirelessIfNeeded $Devices[$label]
}
$offline = @()
foreach ($label in $Devices.Keys) {
    if (-not (Test-DeviceOnline $Devices[$label])) {
        $offline += $label
    }
}
if ($offline.Count -gt 0) {
    $msg = "OFFLINE devices: $($offline -join ',')"
    Write-Host "VERDICT=NO-GO"
    Write-Host "FIRST_FAILED_GATE=DEVICE_OFFLINE"
    Write-Host "evidence=$msg"
    @{
        verdict = "NO-GO"
        firstFailedGate = "DEVICE_OFFLINE"
        evidence = $msg
    } | ConvertTo-Json | Set-Content (Join-Path $LogDir "preflight-result.json") -Encoding utf8
    @(
        "VERDICT=NO-GO"
        "FIRST_FAILED_GATE=DEVICE_OFFLINE"
        "evidence=$msg"
        "runId=$RunId"
        "adjudicated=$(Get-Date -Format o)"
    ) | Set-Content (Join-Path $LogDir "preflight-result.txt") -Encoding utf8
    exit 1
}

function Resolve-LogPath([string]$Label) {
    $candidates = @(
        (Join-Path $LogDir "$Label-logcat.txt"),
        (Join-Path $LogDir "$Label-preflight-logcat.txt")
    )
    foreach ($c in $candidates) {
        if ((Test-Path $c) -and ((Get-Item $c).Length -gt 0)) { return $c }
    }
    return $null
}

$existingLogs = @{}
foreach ($label in $Devices.Keys) {
    $hit = Resolve-LogPath $label
    if ($hit) { $existingLogs[$label] = $hit }
}
$useExisting = ($existingLogs.Count -eq $Devices.Count)

if (-not $useExisting) {
    if ($CaptureSeconds -gt 0) {
        Write-Host "Waiting ${CaptureSeconds}s for telemetry convergence..."
        Start-Sleep -Seconds $CaptureSeconds
    }
}

$logsByLabel = @{}
foreach ($label in $Devices.Keys) {
    if ($useExisting) {
        $path = $existingLogs[$label]
        Write-Host "Analyze existing $label -> $path"
    } else {
        $path = Join-Path $LogDir "$label-preflight-logcat.txt"
        Write-Host "Capture $label $($Devices[$label]) -> $path"
        Capture-DeviceLog $label $Devices[$label] $path
    }
    $logsByLabel[$label] = Get-Content $path -Raw
}

$gateResults = [ordered]@{}
$gateResults["SESSION"] = Evaluate-SessionGate $logsByLabel["M01"]

$livenessFails = @()
foreach ($label in $ExpectedMembers) {
    $gateResults["PROFILE01_LIVENESS_$label"] = Evaluate-Profile01Liveness $label $logsByLabel[$label]
    if (-not $gateResults["PROFILE01_LIVENESS_$label"].Pass) {
        $livenessFails += $label
    }
}
$gateResults["PROFILE01_LIVENESS"] = @{
    Pass = ($livenessFails.Count -eq 0)
    Evidence = if ($livenessFails.Count -eq 0) { "all devices MEETING_MEDIA_SHADOW>0" } else { "missing=$($livenessFails -join ',')" }
}

$membershipResults = @{}
$epochs = @()
foreach ($label in $ExpectedMembers) {
    $membershipResults[$label] = Evaluate-MembershipGate $label $logsByLabel[$label]
    if ($null -ne $membershipResults[$label].Epoch) {
        $epochs += $membershipResults[$label].Epoch
    }
}
$epochSet = $epochs | Sort-Object -Unique
$membershipPass = $true
$membershipEvidence = @()
foreach ($label in $ExpectedMembers) {
    if (-not $membershipResults[$label].Pass) { $membershipPass = $false }
    $membershipEvidence += $membershipResults[$label].Evidence
}
if ($epochSet.Count -ne 1) {
    $membershipPass = $false
    $membershipEvidence += "rosterEpochMismatch=[$($epochSet -join '|')]"
} else {
    $membershipEvidence += "rosterEpoch=$($epochSet[0])"
}
$gateResults["CANONICAL_MEMBERSHIP"] = @{
    Pass = $membershipPass
    Evidence = ($membershipEvidence -join '; ')
}

$gateResults["M02_HOST_CONNECTIVITY"] = Evaluate-M02IceGate $logsByLabel["M01"]

$optional = Get-OptionalTelemetry $logsByLabel

$firstFailed = $null
$gateOrder = @("SESSION", "PROFILE01_LIVENESS", "CANONICAL_MEMBERSHIP", "M02_HOST_CONNECTIVITY")
foreach ($name in $gateOrder) {
    if (-not $gateResults[$name].Pass) {
        $firstFailed = $name
        break
    }
}

$verdict = if ($firstFailed) { "NO-GO" } else { "GO" }
$allEvidence = @()
foreach ($name in $gateOrder) {
    $g = $gateResults[$name]
    if ($g.Evidence -is [array]) {
        $allEvidence += "$name=$($g.Evidence -join '; ')"
    } else {
        $allEvidence += "$name=$($g.Evidence)"
    }
}
$allEvidence += "optional=$($optional -join '; ')"

Write-Host "=== G5-VA2 PREFLIGHT ==="
Write-Host "VERDICT=$verdict"
if ($firstFailed) { Write-Host "FIRST_FAILED_GATE=$firstFailed" }
foreach ($line in $allEvidence) { Write-Host "evidence: $line" }
Write-Host "LOG_DIR=$LogDir"

$resultLines = @(
    "VERDICT=$verdict"
    "FIRST_FAILED_GATE=$firstFailed"
    "evidence=$($allEvidence -join ' | ')"
    "optional=$($optional -join ' | ')"
    "runId=$RunId"
    "captureSeconds=$CaptureSeconds"
    "adjudicated=$(Get-Date -Format o)"
)
$resultLines | Set-Content (Join-Path $LogDir "preflight-result.txt") -Encoding utf8
@{
    verdict = $verdict
    firstFailedGate = $firstFailed
    gates = $gateResults
    optional = $optional
    runId = $RunId
    logDir = $LogDir
} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $LogDir "preflight-result.json") -Encoding utf8

if ($verdict -eq "GO") { exit 0 }
exit 1
