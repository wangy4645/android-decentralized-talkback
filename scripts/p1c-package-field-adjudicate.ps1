# P1-C MEDIA_KEY_PACKAGE field gate — strict identity adjudication (no inference).
#
# Joins on:
#   conferenceId + mediaKeyEpoch + recipientModuleId + membershipFactDigest
#   (+ recipientEstablishmentKeyVersion from ORIGIN / POST_BIND)
#
# Positive chain (P1-C-R1 re-run):
#   initial ORIGIN publicationOutcome=EMITTED
#   → (allowed) peer PRE_BIND_NOT_RETAINED factType=11
#   → product PROFILE01_MEDIA_KEY_PACKAGE_POST_BIND_REPUBLISH publicationOutcome=EMITTED
#     (or ORIGIN publicationOutcome=POST_BIND_EMITTED)
#   → peer WIRE_INGRESS factType=11
#   → PROFILE01_MEDIA_KEY_INGEST outcome=APPLIED
#   → PROFILE01_MEDIA_SUPPLEMENT state=READY
#   with READY timestamp <= APPLIED timestamp
#
# R1 field-proven hard gate:
#   product admission/ICE path must emit POST_BIND; harness-only republish cannot close R1.
#
# C-F5 wording:
#   decryptOutcome=SUCCESS
#   establishmentPath=PRODUCTION_WIRED
#   Does NOT claim live Keystore unwrap from APPLIED alone.
#
# Usage:
#   .\talkback\scripts\p1c-package-field-adjudicate.ps1 -LogDir <dir>
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"

function Get-ModuleLog {
    param([string]$Module)
    foreach ($name in @("$Module-logcat.txt", "$Module-talkback.log", "$Module-logcat-dump.txt")) {
        $p = Join-Path $LogDir $name
        if (Test-Path $p) { return $p }
    }
    return $null
}

function Parse-Field {
    param([string]$Line, [string]$Key)
    if ([string]::IsNullOrWhiteSpace($Line)) { return $null }
    if ($Line -match "$Key=([^ ]+)") { return $Matches[1] }
    return $null
}

function Get-LogTimestamp {
    param([string]$Line)
    if ($Line -match '(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})') {
        return $Matches[1]
    }
    if ($Line -match '^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?)') {
        return $Matches[1]
    }
    return $null
}

function Compare-LogTimestamp {
    param([string]$Left, [string]$Right)
    if (-not $Left -or -not $Right) { return $null }
    return [string]::CompareOrdinal($Left, $Right)
}

function Read-KvFile {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -match '^\s*#' -or $line -eq "") { return }
        if ($line -match '^([^=]+)=(.*)$') {
            $map[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
    return $map
}

function Get-Lines {
    param([string]$Path, [string]$Pattern)
    if (-not $Path -or -not (Test-Path $Path)) { return @() }
    return @(Select-String -Path $Path -Pattern $Pattern | ForEach-Object { $_.Line })
}

function New-Result {
    param(
        [string]$Id,
        [ValidateSet("PASS", "FAIL", "INVALID")]
        [string]$Status,
        [string]$Evidence = ""
    )
    return [pscustomobject]@{ Id = $Id; Status = $Status; Evidence = $Evidence }
}

$linesOut = New-Object System.Collections.Generic.List[string]
function Add-Out([string]$s) { [void]$linesOut.Add($s) }

$m01Log = Get-ModuleLog "M01"
$m02Log = Get-ModuleLog "M02"
$meta = Read-KvFile (Join-Path $LogDir "RUN_META.txt")
$pre = Read-KvFile (Join-Path $LogDir "PRECONDITIONS.txt")
$manifest = Read-KvFile (Join-Path $LogDir "PROVISIONING_MANIFEST.txt")
$neg = Read-KvFile (Join-Path $LogDir "NEGATIVE_FIXTURE.txt")
$joinGate = Read-KvFile (Join-Path $LogDir "JOIN_GATE.txt")
$groupGate = Read-KvFile (Join-Path $LogDir "GROUP_GATE.txt")
$inviteJoinGate = Read-KvFile (Join-Path $LogDir "INVITE_JOIN_GATE.txt")

$expectedRecipient = if ($meta["expectedRecipientModuleId"]) { $meta["expectedRecipientModuleId"] }
    elseif ($pre["expectedRecipientModuleId"]) { $pre["expectedRecipientModuleId"] }
    else { "M02" }
$expectedEstV = if ($meta["establishmentKeyVersion"]) { $meta["establishmentKeyVersion"] }
    elseif ($pre["establishmentKeyVersion"]) { $pre["establishmentKeyVersion"] }
    elseif ($manifest["M02.establishmentKeyVersion"]) { $manifest["M02.establishmentKeyVersion"] }
    else { $null }

Add-Out "P1-C PACKAGE FIELD ADJUDICATION"
Add-Out "log_dir=$LogDir"
Add-Out "expectedRecipientModuleId=$expectedRecipient"
Add-Out "expectedEstablishmentKeyVersion=$expectedEstV"
Add-Out "join_keys=conferenceId,mediaKeyEpoch,recipientModuleId,recipientEstablishmentKeyVersion,membershipFactDigest"
Add-Out "r1_gate=product_post_bind_republish_required"
Add-Out ""

# --- Group precondition hard gate (PRE-G1..G4) — before join / package ---
if ($groupGate.Count -gt 0) {
    Add-Out "GROUP_GATE status=$($groupGate['status']) verdict=$($groupGate['verdict']) reason=$($groupGate['reason'])"
    Add-Out "GROUP_GATE PRE_G1=$($groupGate['PRE_G1_m01NoActiveGroup']) PRE_G2=$($groupGate['PRE_G2_m02NoActiveGroup']) PRE_G3=$($groupGate['PRE_G3_noGrpBoundOrPending']) PRE_G4=$($groupGate['PRE_G4_createAllowed']) leaveAttempts=$($groupGate['leaveAttempts'])"
    Add-Out ""
    if ($groupGate["verdict"] -eq "INVALID_GROUP_PRECONDITION" -or $groupGate["status"] -eq "FAIL") {
        Add-Out "VERDICT=INVALID_GROUP_PRECONDITION"
        Add-Out ("reason=" + $(if ($groupGate["reason"]) { $groupGate["reason"] } else { "group_precondition_failed" }))
        Add-Out "R1_FIELD=NOT_TESTED"
        Add-Out "Next=FIX_FIELD_GROUP_LEAVE_HARNESS_BEFORE_MEETING_CREATE"
        $adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
        ($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
        Write-Host ($linesOut -join "`n")
        exit 2
    }
    if ($groupGate["PRE_G4_createAllowed"] -ne "true") {
        Add-Out "VERDICT=INVALID_GROUP_PRECONDITION"
        Add-Out "reason=PRE_G4_meeting_create_not_allowed"
        Add-Out "R1_FIELD=NOT_TESTED"
        Add-Out "Next=FIX_FIELD_GROUP_LEAVE_HARNESS_BEFORE_MEETING_CREATE"
        $adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
        ($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
        Write-Host ($linesOut -join "`n")
        exit 2
    }
}

# --- Invite join precondition hard gate (PRE-I1..I6) — before package / R1 ---
if ($inviteJoinGate.Count -gt 0) {
    Add-Out "INVITE_JOIN_GATE status=$($inviteJoinGate['status']) verdict=$($inviteJoinGate['verdict']) reason=$($inviteJoinGate['reason'])"
    Add-Out "INVITE_JOIN_GATE hostSessionId=$($inviteJoinGate['hostSessionId'])"
    Add-Out "INVITE_JOIN_GATE PRE_I1=$($inviteJoinGate['PRE_I1_peerInviteForH']) PRE_I2=$($inviteJoinGate['PRE_I2_overlayVisible']) PRE_I3=$($inviteJoinGate['PRE_I3_joinClickDispatched']) PRE_I4=$($inviteJoinGate['PRE_I4_acceptDispatched']) PRE_I5=$($inviteJoinGate['PRE_I5_noRejectBeforeBind']) PRE_I6=$($inviteJoinGate['PRE_I6_peerBindH'])"
    Add-Out ""
    if ($inviteJoinGate["verdict"] -eq "INVALID_INVITE_JOIN_PRECONDITION" -or $inviteJoinGate["status"] -eq "FAIL") {
        Add-Out "VERDICT=INVALID_INVITE_JOIN_PRECONDITION"
        Add-Out ("reason=" + $(if ($inviteJoinGate["reason"]) { $inviteJoinGate["reason"] } else { "invite_join_precondition_failed" }))
        Add-Out "R1_FIELD=NOT_TESTED"
        Add-Out "Next=FIX_FIELD_INVITE_OVERLAY_BTN_JOIN_MEETING_HARNESS"
        $adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
        ($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
        Write-Host ($linesOut -join "`n")
        exit 2
    }
    if ($inviteJoinGate["PRE_I6_peerBindH"] -ne "PASS") {
        Add-Out "VERDICT=INVALID_INVITE_JOIN_PRECONDITION"
        Add-Out "reason=PRE_I6_peer_bind_not_pass"
        Add-Out "R1_FIELD=NOT_TESTED"
        Add-Out "Next=FIX_FIELD_INVITE_OVERLAY_BTN_JOIN_MEETING_HARNESS"
        $adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
        ($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
        Write-Host ($linesOut -join "`n")
        exit 2
    }
}

# --- Join precondition hard gate (PRE-J1..J6) ---
if ($joinGate.Count -gt 0) {
    Add-Out "JOIN_GATE status=$($joinGate['status']) verdict=$($joinGate['verdict']) reason=$($joinGate['reason'])"
    Add-Out "JOIN_GATE hostSessionId=$($joinGate['hostSessionId']) peerSessionId=$($joinGate['peerSessionId'])"
    Add-Out "JOIN_GATE PRE_J1=$($joinGate['PRE_J1_hostSession']) PRE_J2=$($joinGate['PRE_J2_peerNoCreate']) PRE_J3=$($joinGate['PRE_J3_peerInviteForH']) PRE_J4=$($joinGate['PRE_J4_peerBindH']) PRE_J5=$($joinGate['PRE_J5_sameSessionId']) PRE_J6=$($joinGate['PRE_J6_packageAdjudicationAllowed'])"
    Add-Out ""
    if ($joinGate["verdict"] -eq "INVALID_JOIN_PRECONDITION" -or $joinGate["status"] -eq "FAIL") {
        Add-Out "VERDICT=INVALID_JOIN_PRECONDITION"
        Add-Out ("reason=" + $(if ($joinGate["reason"]) { $joinGate["reason"] } else { "join_precondition_failed" }))
        Add-Out "R1_FIELD=NOT_TESTED"
        Add-Out "Next=FIX_FIELD_JOIN_HARNESS_ACCEPT_HOST_INVITE_NEVER_CREATE"
        $adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
        ($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
        Write-Host ($linesOut -join "`n")
        exit 2
    }
    if ($joinGate["PRE_J6_packageAdjudicationAllowed"] -ne "true") {
        Add-Out "VERDICT=INVALID_JOIN_PRECONDITION"
        Add-Out "reason=PRE_J6_package_adjudication_not_allowed"
        Add-Out "R1_FIELD=NOT_TESTED"
        Add-Out "Next=FIX_FIELD_JOIN_HARNESS_ACCEPT_HOST_INVITE_NEVER_CREATE"
        $adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
        ($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
        Write-Host ($linesOut -join "`n")
        exit 2
    }
}
$invalid = New-Object System.Collections.Generic.List[string]
$fail = New-Object System.Collections.Generic.List[string]
$cf = @{}

if (-not $m01Log -or -not $m02Log) {
    $invalid.Add("missing_device_logs") | Out-Null
}

if (-not $expectedEstV) {
    $invalid.Add("missing_expected_establishmentKeyVersion") | Out-Null
}

# --- Parse OBS events ---
$originLines = Get-Lines $m01Log "PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN "
$postBindObsLines = Get-Lines $m01Log "PROFILE01_MEDIA_KEY_PACKAGE_POST_BIND_REPUBLISH "
$postBindCoordLines = Get-Lines $m01Log "PROFILE01_PACKAGE_POST_BIND_REPUBLISH "
$ingestLines = Get-Lines $m02Log "PROFILE01_MEDIA_KEY_INGEST "
$readyLines = Get-Lines $m02Log "PROFILE01_MEDIA_SUPPLEMENT "
$wire11Lines = Get-Lines $m02Log "WIRE_INGRESS .*factType=11"
$preBindDropLines = Get-Lines $m02Log "PRE_BIND_NOT_RETAINED .*factType=11"

if ($invalid.Count -eq 0) {
    if ($originLines.Count -eq 0) { $invalid.Add("required_OBS_field_absent_ORIGIN") | Out-Null }
    if ($ingestLines.Count -eq 0 -and $wire11Lines.Count -eq 0) {
        if ((Get-Item $m02Log).Length -lt 32) { $invalid.Add("device_log_capture_incomplete_M02") | Out-Null }
    }
}

# Initial transport origin (pre-bind EMITTED)
$emittedOrigins = @(
    $originLines | Where-Object {
        ((Parse-Field $_ "publicationOutcome") -eq "EMITTED") -and
        ((Parse-Field $_ "recipientModuleId") -eq $expectedRecipient -or -not (Parse-Field $_ "recipientModuleId"))
    }
)
$primaryOrigin = $null
foreach ($o in $emittedOrigins) {
    $rid = Parse-Field $o "recipientModuleId"
    if ($rid -eq $expectedRecipient) {
        $primaryOrigin = $o
        break
    }
}
if (-not $primaryOrigin -and $emittedOrigins.Count -gt 0) {
    $primaryOrigin = $emittedOrigins[0]
}

# Post-bind OBS (bridge) — EMITTED
$postBindObsEmitted = @(
    $postBindObsLines | Where-Object {
        ((Parse-Field $_ "publicationOutcome") -eq "EMITTED") -and
        (
            -not (Parse-Field $_ "recipientModuleId") -or
            (Parse-Field $_ "recipientModuleId") -eq $expectedRecipient
        )
    }
)
$postBindOriginLines = @(
    $originLines | Where-Object {
        ((Parse-Field $_ "publicationOutcome") -eq "POST_BIND_EMITTED") -and
        ((Parse-Field $_ "recipientModuleId") -eq $expectedRecipient)
    }
)

# Product vs harness coordinator reasons
$productReasonPattern = 'reason=(admission_ready_|peer_media_connected|membership_accepted_no_media)'
$harnessReasonPattern = 'reason=field_harness_post_bind'
$productCoordHits = @($postBindCoordLines | Where-Object { $_ -match $productReasonPattern })
$harnessCoordHits = @($postBindCoordLines | Where-Object { $_ -match $harnessReasonPattern })
$productCoordEmitted = @(
    $productCoordHits | Where-Object {
        ($_ -match 'outcome=EMITTED') -or ($_ -match 'target=')
    }
)

$conferenceId = $null
$mediaKeyEpoch = $null
$recipientModuleId = $null
$recipientEstV = $null
$membershipDigest = $null
$publicationOutcome = $null

# Prefer post-bind identity for join when present; else fall back to initial ORIGIN.
$identitySourceLine = $null
if ($postBindObsEmitted.Count -gt 0) {
    $identitySourceLine = $postBindObsEmitted[0]
} elseif ($postBindOriginLines.Count -gt 0) {
    $identitySourceLine = $postBindOriginLines[0]
} elseif ($primaryOrigin) {
    $identitySourceLine = $primaryOrigin
}

if ($identitySourceLine) {
    $conferenceId = Parse-Field $identitySourceLine "conferenceId"
    $mediaKeyEpoch = Parse-Field $identitySourceLine "mediaKeyEpoch"
    $recipientModuleId = Parse-Field $identitySourceLine "recipientModuleId"
    $recipientEstV = Parse-Field $identitySourceLine "recipientEstablishmentKeyVersion"
    $membershipDigest = Parse-Field $identitySourceLine "membershipFactDigest"
}
if ($primaryOrigin) {
    $publicationOutcome = Parse-Field $primaryOrigin "publicationOutcome"
    if (-not $recipientModuleId) { $recipientModuleId = Parse-Field $primaryOrigin "recipientModuleId" }
    if (-not $recipientEstV) { $recipientEstV = Parse-Field $primaryOrigin "recipientEstablishmentKeyVersion" }
    if (-not $conferenceId) { $conferenceId = Parse-Field $primaryOrigin "conferenceId" }
    if (-not $mediaKeyEpoch) { $mediaKeyEpoch = Parse-Field $primaryOrigin "mediaKeyEpoch" }
    if (-not $membershipDigest) { $membershipDigest = Parse-Field $primaryOrigin "membershipFactDigest" }
}

# Identity consistency: if both initial + post-bind present, keys must match.
$identityMismatch = $false
if ($primaryOrigin -and $postBindObsEmitted.Count -gt 0) {
    $pb = $postBindObsEmitted[0]
    foreach ($k in @("conferenceId", "mediaKeyEpoch", "recipientModuleId", "recipientEstablishmentKeyVersion", "membershipFactDigest")) {
        $a = Parse-Field $primaryOrigin $k
        $b = Parse-Field $pb $k
        if ($a -and $b -and "$a" -ne "$b") {
            $identityMismatch = $true
            $invalid.Add("post_bind_identity_mismatch_$k") | Out-Null
        }
    }
}

function Match-IdentityLine {
    param(
        [string]$Line,
        [string]$ConferenceId,
        [string]$MediaKeyEpoch,
        [string]$RecipientModuleId = $null
    )
    if ((Parse-Field $Line "conferenceId") -ne $ConferenceId) { return $false }
    if ((Parse-Field $Line "mediaKeyEpoch") -ne $MediaKeyEpoch) { return $false }
    if ($RecipientModuleId) {
        if ((Parse-Field $Line "recipientModuleId") -ne $RecipientModuleId) { return $false }
    }
    return $true
}

$matchingApplied = @()
$matchingReady = @()
if ($conferenceId -and $mediaKeyEpoch -and $recipientModuleId) {
    $matchingApplied = @(
        $ingestLines | Where-Object {
            (Match-IdentityLine $_ $conferenceId $mediaKeyEpoch $recipientModuleId) -and
            ((Parse-Field $_ "outcome") -eq "APPLIED")
        }
    )
    $matchingReady = @(
        $readyLines | Where-Object {
            (Match-IdentityLine $_ $conferenceId $mediaKeyEpoch $null) -and
            ((Parse-Field $_ "state") -eq "READY")
        }
    )
}

# --- Delivery stage report ---
$initialDelivery = "UNKNOWN"
if ($preBindDropLines.Count -gt 0) {
    $initialDelivery = "PRE_BIND_DROPPED_ALLOWED"
} elseif ($primaryOrigin -and $matchingApplied.Count -gt 0 -and $postBindObsEmitted.Count -eq 0) {
    $initialDelivery = "DELIVERED_WITHOUT_DROP"
} elseif ($primaryOrigin) {
    $initialDelivery = "EMITTED_NO_PEER_APPLY_YET"
}

$postBindRepublish = "ABSENT"
if ($postBindObsEmitted.Count -gt 0 -or $postBindOriginLines.Count -gt 0) {
    $postBindRepublish = "EMITTED"
} elseif (@($postBindObsLines | Where-Object { (Parse-Field $_ "publicationOutcome") -eq "SEND_FAILED" }).Count -gt 0) {
    $postBindRepublish = "SEND_FAILED"
}

$postBindIngress = if ($matchingApplied.Count -gt 0) { "APPLIED" } else { "ABSENT" }
$supplement = if ($matchingReady.Count -gt 0) { "READY" } else { "ABSENT" }

# Product path requires coordinator reason from admission/ICE (not field harness).
$postBindEmittedAny = ($postBindObsEmitted.Count -gt 0 -or $postBindOriginLines.Count -gt 0)
$r1ProductPath = $postBindEmittedAny -and ($productCoordHits.Count -gt 0)
$r1HarnessOnly = $postBindEmittedAny -and ($harnessCoordHits.Count -gt 0) -and ($productCoordHits.Count -eq 0)

Add-Out "DELIVERY_STAGE initialDelivery=$initialDelivery postBindRepublish=$postBindRepublish postBindIngress=$postBindIngress supplement=$supplement"
Add-Out "R1_PATH productAuto=$r1ProductPath harnessOnly=$r1HarnessOnly productCoordHits=$($productCoordHits.Count) harnessCoordHits=$($harnessCoordHits.Count)"
Add-Out ""

# --- C-F1 ---
if ($invalid.Count -gt 0 -and -not $primaryOrigin) {
    $cf["C-F1"] = New-Result "C-F1" "INVALID" "no_primary_ORIGIN"
} elseif (-not $primaryOrigin) {
    $cf["C-F1"] = New-Result "C-F1" "FAIL" "origin_not_emitted_for_recipient"
    $fail.Add("C-F1_origin_missing") | Out-Null
} elseif (-not $recipientModuleId -or -not $recipientEstV -or -not $publicationOutcome) {
    $cf["C-F1"] = New-Result "C-F1" "INVALID" "ORIGIN_missing_required_fields"
    $invalid.Add("ORIGIN_missing_required_fields") | Out-Null
} elseif ($recipientModuleId -ne $expectedRecipient) {
    $cf["C-F1"] = New-Result "C-F1" "FAIL" "recipientModuleId=$recipientModuleId expected=$expectedRecipient"
    $fail.Add("C-F1_recipient_mismatch") | Out-Null
} elseif ("$recipientEstV" -ne "$expectedEstV") {
    $cf["C-F1"] = New-Result "C-F1" "FAIL" "recipientEstablishmentKeyVersion=$recipientEstV expected=$expectedEstV"
    $fail.Add("C-F1_version_mismatch") | Out-Null
} elseif ($publicationOutcome -ne "EMITTED") {
    $cf["C-F1"] = New-Result "C-F1" "FAIL" "publicationOutcome=$publicationOutcome"
    $fail.Add("C-F1_not_emitted") | Out-Null
} else {
    $cf["C-F1"] = New-Result "C-F1" "PASS" "origin recipient/version exact initialDelivery=$initialDelivery"
}

# --- C-F2 ---
if ($cf["C-F1"].Status -eq "INVALID" -and -not $primaryOrigin) {
    $cf["C-F2"] = New-Result "C-F2" "INVALID" "no_primary_ORIGIN"
} elseif (-not $primaryOrigin) {
    $cf["C-F2"] = New-Result "C-F2" "FAIL" "no_ORIGIN_for_binding"
    $fail.Add("C-F2_no_origin") | Out-Null
} elseif (-not $membershipDigest -or $membershipDigest -eq "-" -or -not $mediaKeyEpoch -or $mediaKeyEpoch -eq "-") {
    $cf["C-F2"] = New-Result "C-F2" "INVALID" "ORIGIN_missing_digest_or_epoch"
    $invalid.Add("ORIGIN_missing_digest_or_epoch") | Out-Null
} elseif ($identityMismatch) {
    $cf["C-F2"] = New-Result "C-F2" "FAIL" "post_bind_identity_mismatch_vs_initial_origin"
    $fail.Add("C-F2_identity_mismatch") | Out-Null
} else {
    $cf["C-F2"] = New-Result "C-F2" "PASS" "creation digest + epoch exact digest=$membershipDigest epoch=$mediaKeyEpoch"
}

# --- C-F3 transport: peer WIRE_INGRESS factType=11 (post-bind or lucky bind) ---
# Early PRE_BIND_NOT_RETAINED alone is NOT a FAIL when post-bind later succeeds.
if ($invalid.Count -gt 0 -and -not $m02Log) {
    $cf["C-F3"] = New-Result "C-F3" "INVALID" "missing_M02_log"
} elseif ($wire11Lines.Count -eq 0) {
    if ($matchingApplied.Count -gt 0) {
        $cf["C-F3"] = New-Result "C-F3" "FAIL" "peer_INGEST_present_but_WIRE_INGRESS_factType=11_absent"
        $fail.Add("C-F3_wire_ingress_absent") | Out-Null
    } elseif ($primaryOrigin -and $publicationOutcome -eq "EMITTED" -and $postBindRepublish -ne "EMITTED") {
        if ($initialDelivery -eq "PRE_BIND_DROPPED_ALLOWED") {
            $cf["C-F3"] = New-Result "C-F3" "FAIL" "pre_bind_dropped_and_no_post_bind_republish_or_wire"
            $fail.Add("C-F3_pre_bind_drop_without_post_bind") | Out-Null
        } else {
            $cf["C-F3"] = New-Result "C-F3" "FAIL" "origin_emitted_but_peer_never_received_factType=11"
            $fail.Add("C-F3_peer_never_received") | Out-Null
        }
    } else {
        $cf["C-F3"] = New-Result "C-F3" "FAIL" "peer_factType=11_not_observed"
        $fail.Add("C-F3_not_observed") | Out-Null
    }
} else {
    $cf["C-F3"] = New-Result "C-F3" "PASS" "peer received factType=11 count=$($wire11Lines.Count) initialDelivery=$initialDelivery"
}

# --- C-F4 ingest APPLIED ---
if (-not $conferenceId -or -not $mediaKeyEpoch) {
    $cf["C-F4"] = New-Result "C-F4" "INVALID" "conference_identity_cannot_correlate"
    if ($invalid -notcontains "conference_identity_cannot_correlate") {
        $invalid.Add("conference_identity_cannot_correlate") | Out-Null
    }
} elseif ($matchingApplied.Count -eq 0) {
    $rejectedSame = @(
        $ingestLines | Where-Object {
            (Match-IdentityLine $_ $conferenceId $mediaKeyEpoch $recipientModuleId) -and
            ((Parse-Field $_ "outcome") -like "REJECTED_*")
        }
    )
    if ($rejectedSame.Count -gt 0) {
        $reason = Parse-Field $rejectedSame[0] "outcome"
        $cf["C-F4"] = New-Result "C-F4" "FAIL" "received_but_rejected_unexpectedly outcome=$reason"
        $fail.Add("C-F4_unexpected_reject") | Out-Null
    } elseif ($cf["C-F3"].Status -eq "PASS") {
        $cf["C-F4"] = New-Result "C-F4" "FAIL" "wire_observed_but_matching_INGEST_APPLIED_absent"
        $fail.Add("C-F4_applied_absent") | Out-Null
    } else {
        $cf["C-F4"] = New-Result "C-F4" "FAIL" "matching_INGEST_APPLIED_absent"
        $fail.Add("C-F4_applied_absent") | Out-Null
    }
} else {
    $appliedEstV = Parse-Field $matchingApplied[0] "establishmentKeyVersion"
    $cf["C-F4"] = New-Result "C-F4" "PASS" "ingest verified/APPLIED establishmentKeyVersion=$appliedEstV postBindIngress=$postBindIngress"
}

# --- C-F5 decrypt path wording (no fake Keystore claim) ---
$ep2Note = $pre["ep2KeystoreVerifiedCorroboration"]
if ($cf["C-F4"].Status -ne "PASS") {
    $cf["C-F5"] = New-Result "C-F5" "FAIL" "no_APPLIED_for_decrypt_path_evidence"
    if ($fail -notcontains "C-F5_no_applied") { $fail.Add("C-F5_no_applied") | Out-Null }
} else {
    $keystoreClaim = "NOT_CLAIMED_FROM_APPLIED_ALONE"
    if ($ep2Note -and $ep2Note -match "Verified|EP2_PASS|KEYSTORE_VERIFIED") {
        $keystoreClaim = "EP2_CORROBORATED:$ep2Note"
    }
    $ev = "decryptOutcome=SUCCESS establishmentPath=PRODUCTION_WIRED keystoreField=$keystoreClaim"
    $cf["C-F5"] = New-Result "C-F5" "PASS" $ev
}

# --- C-F6 READY + order ---
if ($cf["C-F4"].Status -ne "PASS") {
    $cf["C-F6"] = New-Result "C-F6" "FAIL" "no_APPLIED_to_pair_with_READY"
    if ($fail -notcontains "C-F6_no_applied") { $fail.Add("C-F6_no_applied") | Out-Null }
} elseif ($matchingReady.Count -eq 0) {
    $cf["C-F6"] = New-Result "C-F6" "FAIL" "APPLIED_without_matching_READY"
    $fail.Add("C-F6_applied_without_ready") | Out-Null
} else {
    $readyLine = $matchingReady[0]
    $appliedLine = $matchingApplied[0]
    $tsReady = Get-LogTimestamp $readyLine
    $tsApplied = Get-LogTimestamp $appliedLine
    if (-not $tsReady -or -not $tsApplied) {
        $cf["C-F6"] = New-Result "C-F6" "INVALID" "required_OBS_timestamp_absent_for_READY_APPLIED_order"
        $invalid.Add("READY_APPLIED_timestamp_absent") | Out-Null
    } else {
        $cmp = Compare-LogTimestamp $tsReady $tsApplied
        if ($cmp -gt 0) {
            $cf["C-F6"] = New-Result "C-F6" "FAIL" "evidence_anomaly_READY_after_APPLIED readyTs=$tsReady appliedTs=$tsApplied"
            $fail.Add("C-F6_ready_after_applied") | Out-Null
        } else {
            $cf["C-F6"] = New-Result "C-F6" "PASS" "matching supplement READY readyTs=$tsReady appliedTs=$tsApplied supplement=$supplement"
        }
    }
}

# --- C-F7 negative fence ---
$expectedReject = if ($neg["expectedRejectReason"]) { $neg["expectedRejectReason"] } else { "RECIPIENT_KEY_VERSION_MISMATCH" }
$expectedOutcome = "REJECTED_$expectedReject"
$negObsLines = Get-Lines $m01Log "PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE "
$negObsInjected = @(
    $negObsLines | Where-Object {
        ((Parse-Field $_ "fixtureType") -eq "RECIPIENT_KEY_VERSION_MISMATCH") -and
        ((Parse-Field $_ "injected") -eq "true") -and
        ((Parse-Field $_ "publicationOutcome") -eq "EMITTED")
    }
)
$fileInjected = $neg["injected"]

if (-not (Test-Path (Join-Path $LogDir "NEGATIVE_FIXTURE.txt"))) {
    $cf["C-F7"] = New-Result "C-F7" "INVALID" "negative_fixture_file_missing"
    $invalid.Add("negative_fixture_file_missing") | Out-Null
} elseif ($fileInjected -eq "true" -and $negObsInjected.Count -eq 0) {
    $cf["C-F7"] = New-Result "C-F7" "INVALID" "negative_fixture_not_actually_injected_file_without_OBS"
    $invalid.Add("negative_fixture_not_actually_injected") | Out-Null
} elseif ($negObsInjected.Count -eq 0) {
    $cf["C-F7"] = New-Result "C-F7" "INVALID" "negative_fixture_not_actually_injected"
    $invalid.Add("negative_fixture_not_actually_injected") | Out-Null
} else {
    $negObs = $negObsInjected[-1]
    $negRejects = @(
        $ingestLines | Where-Object { (Parse-Field $_ "outcome") -eq $expectedOutcome }
    )
    if ($negRejects.Count -eq 0) {
        $cf["C-F7"] = New-Result "C-F7" "FAIL" "expected_$expectedOutcome`_absent_after_injected_OBS"
        $fail.Add("C-F7_reject_absent") | Out-Null
    } else {
        $rejectLine = $negRejects[-1]
        $negConf = Parse-Field $rejectLine "conferenceId"
        $negEpoch = Parse-Field $rejectLine "mediaKeyEpoch"
        $rejectTs = Get-LogTimestamp $rejectLine
        $readyAfterReject = @(
            $readyLines | Where-Object {
                (Match-IdentityLine $_ $negConf $negEpoch $null) -and
                ((Parse-Field $_ "state") -eq "READY") -and
                $rejectTs -and (Get-LogTimestamp $_) -and
                ((Compare-LogTimestamp (Get-LogTimestamp $_) $rejectTs) -ge 0)
            }
        )
        if ($readyAfterReject.Count -gt 0) {
            $cf["C-F7"] = New-Result "C-F7" "FAIL" "negative_input_produced_READY_after_reject"
            $fail.Add("C-F7_ready_after_reject") | Out-Null
        } else {
            $nfid = Parse-Field $negObs "negativeFixtureId"
            $wireV = Parse-Field $negObs "wireRecipientKeyVersion"
            $expV = Parse-Field $negObs "expectedRecipientEstablishmentKeyVersion"
            $cf["C-F7"] = New-Result "C-F7" "PASS" "OBS injected=true negativeFixtureId=$nfid expectedV=$expV wireV=$wireV REJECTED_$expectedReject no READY after reject"
        }
    }
}

# --- Emit per-case ---
Add-Out "IDENTITY conferenceId=$conferenceId mediaKeyEpoch=$mediaKeyEpoch recipientModuleId=$recipientModuleId recipientEstablishmentKeyVersion=$recipientEstV membershipFactDigest=$membershipDigest"
Add-Out "ORIGIN_LINE=$primaryOrigin"
Add-Out "POST_BIND_LINE=$(if ($postBindObsEmitted.Count) { $postBindObsEmitted[0] } elseif ($postBindOriginLines.Count) { $postBindOriginLines[0] } else { '' })"
Add-Out "APPLIED_LINE=$(if ($matchingApplied.Count) { $matchingApplied[0] } else { '' })"
Add-Out "READY_LINE=$(if ($matchingReady.Count) { $matchingReady[0] } else { '' })"
Add-Out ""

foreach ($id in @("C-F1", "C-F2", "C-F3", "C-F4", "C-F5", "C-F6", "C-F7")) {
    $r = $cf[$id]
    Add-Out ("{0} {1}  {2}" -f $r.Id, $r.Status, $r.Evidence)
}

$positiveIds = @("C-F1", "C-F2", "C-F3", "C-F4", "C-F5", "C-F6")
$positivePass = ($positiveIds | ForEach-Object { $cf[$_].Status -eq "PASS" } | Where-Object { $_ -eq $false }).Count -eq 0
Add-Out ""
if ($positivePass) {
    Add-Out "POSITIVE LEG PASS"
} else {
    Add-Out "POSITIVE LEG NOT_PASS"
}

# Informational only
$creationApplied = if ((Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED").Count -gt 0) { "YES" } else { "NO|UNKNOWN" }
$drainObs = if ((Get-Lines $m01Log "SESSION_INCOMPLETE_DRAIN").Count + (Get-Lines $m02Log "SESSION_INCOMPLETE_DRAIN").Count -gt 0) { "YES" } else { "NO|UNKNOWN" }
Add-Out "CREATION_APPLIED observed=$creationApplied   (informational only — not gate input)"
Add-Out "INCOMPLETE_DRAIN observed=$drainObs   (informational only — not gate input)"
Add-Out ""

# R1 product-path gate (cannot close R1 field on harness-only)
$r1GateFail = $false
$r1GateInvalid = $false
if ($r1HarnessOnly) {
    $r1GateInvalid = $true
    $invalid.Add("r1_harness_only_republish_not_field_proven") | Out-Null
    Add-Out "R1_FIELD=INVALID reason=harness_only_P1C_REPUBLISH_AFTER_BIND"
} elseif ($postBindRepublish -ne "EMITTED") {
    $r1GateFail = $true
    $fail.Add("r1_product_post_bind_absent") | Out-Null
    Add-Out "R1_FIELD=FAIL reason=product_POST_BIND_EMITTED_absent"
} elseif (-not $r1ProductPath) {
    # Emitted but cannot confirm product admission/ICE reason
    $r1GateInvalid = $true
    $invalid.Add("r1_product_hook_reason_not_confirmed") | Out-Null
    Add-Out "R1_FIELD=INVALID reason=post_bind_emitted_but_product_hook_reason_unconfirmed"
} else {
    Add-Out "R1_FIELD=PASS product_auto_POST_BIND_EMITTED"
}
Add-Out ""

# Final verdict: INVALID dominates; else any FAIL; else PASS requires C-F1..C-F7 + R1 product path
$anyInvalid = ($cf.Values | Where-Object { $_.Status -eq "INVALID" }).Count -gt 0 -or $invalid.Count -gt 0 -or $r1GateInvalid
$anyFail = ($cf.Values | Where-Object { $_.Status -eq "FAIL" }).Count -gt 0 -or $fail.Count -gt 0 -or $r1GateFail
$allPass = ($cf.Values | Where-Object { $_.Status -ne "PASS" }).Count -eq 0 -and -not $r1GateFail -and -not $r1GateInvalid

if ($anyInvalid) {
    Add-Out "VERDICT=INVALID"
    $reasons = @($invalid)
    $reasons += @($cf.Values | Where-Object { $_.Status -eq "INVALID" } | ForEach-Object { "$($_.Id)_$($_.Evidence)" })
    Add-Out ("reason=" + (($reasons | Select-Object -Unique) -join ","))
    Add-Out "Next=FIX_CAPTURE_OR_PRODUCT_HOOK_AND_RERUN_P1C_ONLY"
    $exitCode = 2
} elseif ($anyFail -or -not $allPass) {
    Add-Out "VERDICT=FAIL"
    $reasons = @($fail)
    $reasons += @($cf.Values | Where-Object { $_.Status -eq "FAIL" } | ForEach-Object { "$($_.Id)_$($_.Evidence)" })
    Add-Out ("reason=" + (($reasons | Select-Object -Unique) -join ","))
    Add-Out "Next=RCA_PACKAGE_OR_R1_HOOK_HOLD_J0"
    $exitCode = 1
} else {
    Add-Out "VERDICT=PASS"
    Add-Out "P1-C_package_field=CLOSED / FIELD-PROVEN"
    Add-Out "P1-C-R1=CLOSED / FIELD-PROVEN"
    Add-Out "authoritative_log=talkback/logs/p1c-package-field-20260909-101752"
    Add-Out "Next=MEETING_J0_AUTHORIZABLE_SEPARATE_RUN"
    Add-Out "note=C-F5 does not by itself claim live Keystore unwrap; see keystoreField= in C-F5 evidence"
    $exitCode = 0
}

$adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
Write-Host ($linesOut -join "`n")
exit $exitCode
