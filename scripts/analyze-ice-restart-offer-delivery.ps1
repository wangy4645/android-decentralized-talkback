# ICE Restart Offer Delivery / Signal Path Classification analyzer
# Does not reopen Drain / UVCP / Qualification / Completion Authority.
#
# SignalPathKey (MUST match all that apply):
#   (source, target, signalDomain, lineageId, attemptId, generation)
# signalDomain = RECOVERY_REATTACH | GROUP_MESH | OTHER
# Never score GROUP_MESH REMOTE_RECEIVE as recovery L* success.
#
# Usage:
#   .\scripts\analyze-ice-restart-offer-delivery.ps1 -LogDir logs\signal-path-<stamp>

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [string]$Edge = "",
    [string]$AttemptId = "",
    [string]$OfferLineageId = ""
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $LogDir)) { throw "LogDir not found: $LogDir" }

$files = Get-ChildItem $LogDir -Filter "*-talkback.log" -ErrorAction SilentlyContinue | Sort-Object Name
if (-not $files) { $files = Get-ChildItem $LogDir -Filter "*.log" | Sort-Object Name }
if (-not $files) { throw "No *.log under $LogDir" }

$all = @()
foreach ($f in $files) {
    $device = if ($f.BaseName -match "^(M\d+)") { $Matches[1] } else { $f.BaseName }
    Get-Content $f.FullName -Encoding UTF8 -ErrorAction SilentlyContinue | ForEach-Object {
        $all += [pscustomobject]@{ Device = $device; Line = $_; File = $f.Name }
    }
}

function Get-Field([string]$line, [string]$name) {
    $pat = "\b" + [regex]::Escape($name) + "=([^\s]+)"
    if ($line -match $pat) { return $Matches[1] }
    return $null
}

function Has-Token([string]$line, [string]$token) {
    return $line -match [regex]::Escape($token)
}

function Mark-Step([bool]$ok, [bool]$applicable) {
    if (-not $applicable) { return "N/A" }
    if ($ok) { return "PASS" }
    return "FAIL"
}

function Resolve-SignalDomain([string]$joinIntent, [string]$pathKind, [string]$signalDomain) {
    if ($signalDomain) { return $signalDomain }
    if ($pathKind) { return $pathKind }
    if ($joinIntent -eq "RECOVERY_REATTACH") { return "RECOVERY_REATTACH" }
    if ($joinIntent) { return "GROUP_MESH" }
    return $null
}

$sent = @($all | Where-Object { Has-Token $_.Line "RECOVERY_OFFER_SENT" })
$recv = @($all | Where-Object { Has-Token $_.Line "RECOVERY_OFFER_RECEIVED" })
$acceptHandoff = @($all | Where-Object {
    (Has-Token $_.Line "GROUP_ACCEPT_HANDOFF") -and (Has-Token $_.Line "result=SUCCESS")
})
$groupAccept = @($all | Where-Object { $_.Line -match "Group accept from\s+(M\d+)" })
$stableAfterAnswer = @($all | Where-Object { Has-Token $_.Line "SIGNALING_STABLE_AFTER_REMOTE_ANSWER" })
$dispatched = @($all | Where-Object { Has-Token $_.Line "RECOVERY_ICE_RESTART_DISPATCHED" })
$delivAll = @($all | Where-Object { Has-Token $_.Line "OFFER_DELIVERY" })

$txns = @()
foreach ($s in $sent) {
    $remote = Get-Field $s.Line "remote"
    $attempt = Get-Field $s.Line "restartAttemptId"
    if (-not $attempt) { $attempt = Get-Field $s.Line "attempt" }
    $gen = Get-Field $s.Line "gen"
    if (-not $gen) { $gen = Get-Field $s.Line "transportGeneration" }
    $lineage = Get-Field $s.Line "offerLineageId"
    $session = Get-Field $s.Line "session"
    $joinIntent = Get-Field $s.Line "joinIntent"
    $signalDomain = Resolve-SignalDomain $joinIntent (Get-Field $s.Line "pathKind") (Get-Field $s.Line "signalDomain")
    if (-not $signalDomain) { $signalDomain = "RECOVERY_REATTACH" }
    $sender = $s.Device
    if (-not $remote) { continue }
    $edgeKey = "$sender->$remote"
    if ($Edge -and $edgeKey -ne $Edge) { continue }
    if ($AttemptId -and $attempt -and $attempt -ne $AttemptId) { continue }
    if ($OfferLineageId -and $lineage -and $lineage -ne $OfferLineageId) { continue }

    # Peer receive MUST match SignalPathKey: remote=sender + domain + lineage (+ session)
    $peerRecv = @($recv | Where-Object {
        $_.Device -eq $remote -and
        (Get-Field $_.Line "remote") -eq $sender -and (
            -not $lineage -or $lineage -eq "NONE" -or (Get-Field $_.Line "offerLineageId") -eq $lineage
        ) -and (
            $signalDomain -ne "RECOVERY_REATTACH" -or
            (Get-Field $_.Line "joinIntent") -eq "RECOVERY_REATTACH" -or
            (Get-Field $_.Line "pathKind") -eq "RECOVERY_REATTACH" -or
            (Get-Field $_.Line "signalDomain") -eq "RECOVERY_REATTACH"
        ) -and (
            -not $session -or (Get-Field $_.Line "session") -eq $session
        )
    })

    $answerSent = @($acceptHandoff | Where-Object {
        $_.Device -eq $remote -and (Get-Field $_.Line "remote") -eq $sender -and (
            -not $session -or (Get-Field $_.Line "session") -eq $session
        )
    })

    $answerApplied = @()
    foreach ($g in $groupAccept) {
        if ($g.Device -ne $sender) { continue }
        if ($g.Line -match ("Group accept from\s+" + [regex]::Escape($remote) + "\b")) {
            if (-not $session -or $g.Line -match [regex]::Escape($session)) {
                $answerApplied += $g
            }
        }
    }
    if (-not $answerApplied) {
        $answerApplied = @($stableAfterAnswer | Where-Object {
            $_.Device -eq $sender -and (
                $_.Line -match [regex]::Escape($remote) -or
                (Get-Field $_.Line "remote") -eq $remote
            )
        })
    }

    $offerRecvPass = $peerRecv.Count -gt 0
    $answerSentPass = $answerSent.Count -gt 0
    $answerAppliedPass = $answerApplied.Count -gt 0

    $classification = "UNKNOWN"
    if (-not $offerRecvPass) {
        $classification = "CASE_1_OFFER_DELIVERY"
    } elseif (-not $answerSentPass) {
        $classification = "CASE_2_ANSWER_GENERATION"
    } elseif (-not $answerAppliedPass) {
        $classification = "CASE_3_ANSWER_RETURN"
    } else {
        $classification = "PASS_FIVE_BOX"
    }

    $txns += [pscustomobject]@{
        Edge = $edgeKey
        Sender = $sender
        Peer = $remote
        AttemptId = $attempt
        OfferLineageId = $lineage
        Gen = $gen
        Session = $session
        JoinIntent = $joinIntent
        SignalDomain = $signalDomain
        OfferSent = (Mark-Step $true $true)
        OfferReceived = (Mark-Step $offerRecvPass $true)
        AnswerCreated = "N/A"
        AnswerSent = (Mark-Step $answerSentPass $offerRecvPass)
        AnswerApplied = (Mark-Step $answerAppliedPass ($offerRecvPass -and $answerSentPass))
        Classification = $classification
        RecvCount = $peerRecv.Count
        AnswerSentCount = $answerSent.Count
        AnswerAppliedCount = $answerApplied.Count
    }
}

if ($txns.Count -eq 0 -and $dispatched.Count -gt 0) {
    Write-Host "NOTE: RECOVERY_ICE_RESTART_DISPATCHED=$($dispatched.Count) but RECOVERY_OFFER_SENT=0"
}

Write-Host "=== ICE Restart Offer Delivery Analyzer ==="
Write-Host "LogDir=$LogDir"
Write-Host "files=$($files.Count) lines=$($all.Count)"
Write-Host "OFFER_SENT=$($sent.Count) OFFER_RECEIVED=$($recv.Count) ACCEPT_HANDOFF_OK=$($acceptHandoff.Count)"
Write-Host "SignalPathKey=(source,target,signalDomain,lineageId,attemptId,generation)"
Write-Host ""

if ($txns.Count -eq 0) {
    Write-Host "No RECOVERY_OFFER_SENT transactions found."
    $verdict = "NO_TXN"
} else {
    foreach ($t in $txns) {
        Write-Host "ICE_RESTART_TRANSACTION_TRACE"
        Write-Host ""
        Write-Host "edge=$($t.Edge)"
        Write-Host "signalDomain=$($t.SignalDomain)"
        Write-Host "attemptId=$($t.AttemptId)"
        Write-Host "offerLineageId=$($t.OfferLineageId)"
        Write-Host "generation=$($t.Gen)"
        Write-Host "session=$($t.Session)"
        Write-Host "joinIntent=$($t.JoinIntent)"
        Write-Host ""

        $lineageKey = $t.OfferLineageId
        $domain = $t.SignalDomain
        $sender = $t.Sender
        $peer = $t.Peer
        $session = $t.Session

        function Stage-Pass([string]$stage) {
            $hits = @($delivAll | Where-Object {
                (Has-Token $_.Line ("stage=" + $stage)) -and
                $_.Device -eq $peer -and
                (Get-Field $_.Line "remote") -eq $sender -and (
                    -not $lineageKey -or $lineageKey -eq "NONE" -or
                    (Get-Field $_.Line "offerLineageId") -eq $lineageKey
                ) -and (
                    $domain -ne "RECOVERY_REATTACH" -or
                    (Get-Field $_.Line "pathKind") -eq "RECOVERY_REATTACH" -or
                    (Get-Field $_.Line "signalDomain") -eq "RECOVERY_REATTACH" -or
                    (Get-Field $_.Line "joinIntent") -eq "RECOVERY_REATTACH"
                ) -and (
                    -not $session -or (Get-Field $_.Line "session") -eq $session
                )
            })
            # SEND/LOCAL are on sender device
            if ($stage -eq "SEND_REQUEST" -or $stage -eq "LOCAL_ACCEPT") {
                $hits = @($delivAll | Where-Object {
                    (Has-Token $_.Line ("stage=" + $stage)) -and
                    $_.Device -eq $sender -and
                    (Get-Field $_.Line "remote") -eq $peer -and (
                        -not $lineageKey -or $lineageKey -eq "NONE" -or
                        (Get-Field $_.Line "offerLineageId") -eq $lineageKey
                    ) -and (
                        $domain -ne "RECOVERY_REATTACH" -or
                        (Get-Field $_.Line "pathKind") -eq "RECOVERY_REATTACH" -or
                        (Get-Field $_.Line "signalDomain") -eq "RECOVERY_REATTACH" -or
                        (Get-Field $_.Line "joinIntent") -eq "RECOVERY_REATTACH"
                    )
                })
            }
            if ($hits.Count -gt 0) { return "PASS" }
            return "FAIL"
        }

        # Ingress R1/R2/R3 (peer): time-correlated loosely by lineage on decoded stages;
        # UDP_DATAGRAM_RECEIVED has remote=UNKNOWN — report count near session if D1.
        $udpAny = @($delivAll | Where-Object {
            $_.Device -eq $peer -and (Has-Token $_.Line "stage=UDP_DATAGRAM_RECEIVED")
        }).Count
        $decodedHits = Stage-Pass "SIGNAL_ENVELOPE_DECODED"
        $reattachClass = Stage-Pass "RECOVERY_REATTACH_CLASSIFIED"

        Write-Host ("SEND_REQUEST                 {0}" -f (Stage-Pass "SEND_REQUEST"))
        Write-Host ("LOCAL_ACCEPT                 {0}" -f (Stage-Pass "LOCAL_ACCEPT"))
        Write-Host ("UDP_DATAGRAM_RECEIVED(any)   count=$udpAny")
        Write-Host ("SIGNAL_ENVELOPE_DECODED      {0}" -f $decodedHits)
        Write-Host ("RECOVERY_REATTACH_CLASSIFIED {0}" -f $reattachClass)
        Write-Host ("REMOTE_RECEIVE               {0}" -f (Stage-Pass "REMOTE_RECEIVE"))
        Write-Host ("HANDLER_ACCEPT               {0}" -f (Stage-Pass "HANDLER_ACCEPT"))
        Write-Host ("ANSWER_RETURN                N/A")
        Write-Host ""
        Write-Host ("OFFER_SENT              {0}" -f $t.OfferSent)
        Write-Host ("OFFER_RECEIVED          {0}" -f $t.OfferReceived)
        Write-Host ("ANSWER_CREATED          {0}" -f $t.AnswerCreated)
        Write-Host ("ANSWER_SENT             {0}" -f $t.AnswerSent)
        Write-Host ("ANSWER_APPLIED          {0}" -f $t.AnswerApplied)
        Write-Host ""

        $sendP = (Stage-Pass "SEND_REQUEST")
        $localP = (Stage-Pass "LOCAL_ACCEPT")
        $remoteP = (Stage-Pass "REMOTE_RECEIVE")
        $handlerP = (Stage-Pass "HANDLER_ACCEPT")
        $pathClass = "UNKNOWN"
        if ($remoteP -eq "FAIL" -and ($sendP -eq "PASS" -or $localP -eq "PASS" -or $t.OfferSent -eq "PASS")) {
            $pathClass = "D1_NO_REMOTE_RECEIVE"
        } elseif ($remoteP -eq "PASS" -and $handlerP -eq "FAIL") {
            $pathClass = "D2_HANDLER_REJECT"
        } elseif ($handlerP -eq "PASS" -and $t.AnswerSent -eq "FAIL") {
            $pathClass = "D3_NO_ANSWER"
        } elseif ($t.AnswerSent -eq "PASS" -and $t.AnswerApplied -eq "FAIL") {
            $pathClass = "D4_NO_APPLY"
        } elseif ($t.Classification -eq "PASS_FIVE_BOX" -or ($handlerP -eq "PASS" -and $t.AnswerApplied -eq "PASS")) {
            $pathClass = "D5_FULL_SUCCESS"
        } elseif ($t.Classification -eq "CASE_1_OFFER_DELIVERY") {
            $pathClass = "D1_NO_REMOTE_RECEIVE"
        } elseif ($t.Classification -eq "CASE_2_ANSWER_GENERATION") {
            $pathClass = "D3_NO_ANSWER"
        } elseif ($t.Classification -eq "CASE_3_ANSWER_RETURN") {
            $pathClass = "D4_NO_APPLY"
        }

        $ingressClass = "N/A"
        if ($pathClass -eq "D1_NO_REMOTE_RECEIVE") {
            if ($reattachClass -eq "PASS") {
                $ingressClass = "R3_CLASSIFIED_BUT_NO_HANDLER_CHAIN"
            } elseif ($decodedHits -eq "PASS") {
                $ingressClass = "R3_DECODE_OK_CLASSIFY_MISS"
            } elseif ($udpAny -gt 0) {
                $ingressClass = "R2_OR_R1_NEED_SRC_CORRELATION"
            } else {
                $ingressClass = "R1_NO_UDP_ON_PEER"
            }
        }

        Write-Host "classification=$($t.Classification)"
        Write-Host "pathClass=$pathClass"
        Write-Host "ingressClass=$ingressClass"
        Write-Host "---"
    }
    $case1 = @($txns | Where-Object { $_.Classification -eq "CASE_1_OFFER_DELIVERY" }).Count
    $case2 = @($txns | Where-Object { $_.Classification -eq "CASE_2_ANSWER_GENERATION" }).Count
    $case3 = @($txns | Where-Object { $_.Classification -eq "CASE_3_ANSWER_RETURN" }).Count
    $pass = @($txns | Where-Object { $_.Classification -eq "PASS_FIVE_BOX" }).Count
    Write-Host "SUMMARY case1=$case1 case2=$case2 case3=$case3 pass=$pass total=$($txns.Count)"
    if ($case1 -gt 0) { $verdict = "CASE_1_OFFER_DELIVERY" }
    elseif ($case2 -gt 0) { $verdict = "CASE_2_ANSWER_GENERATION" }
    elseif ($case3 -gt 0) { $verdict = "CASE_3_ANSWER_RETURN" }
    elseif ($pass -eq $txns.Count) { $verdict = "PASS_FIVE_BOX" }
    else { $verdict = "MIXED" }
}

$out = Join-Path $LogDir "ice-restart-offer-delivery-verdict.txt"
@(
    "verdict=$verdict"
    "offer_sent=$($sent.Count)"
    "offer_received=$($recv.Count)"
    "transactions=$($txns.Count)"
) | Set-Content -Path $out -Encoding UTF8
Write-Host "wrote $out"
Write-Host "VERDICT=$verdict"

Write-Host ""
Write-Host "STATUS: Appendix D / Deferred Drain CLOSED | UVCP CLOSED | Recovery Signaling Path INVESTIGATION OPEN"
Write-Host "NON_GOAL: do not reopen peer readiness / qualification / PRR; do not conflate GROUP_MESH with RECOVERY_REATTACH"

if ($verdict -eq "CASE_1_OFFER_DELIVERY" -or $verdict -eq "CASE_2_ANSWER_GENERATION" -or $verdict -eq "CASE_3_ANSWER_RETURN") {
    exit 2
}
if ($verdict -eq "NO_TXN") { exit 3 }
exit 0