# Joint D1 + PR5-2c-C analyzer (ADR-0022 E.16)
# Domains kept separate. J-F is HARD GATE.
# Emits J-A..J-G GATE FACTS only. Sole final verdict = classify-joint-d1-c.ps1
# Does NOT score UI. Production code unchanged by this script.

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [string]$AuthorityModule = "M02",
    [string]$RemoteModule = "M03"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $LogDir)) { throw "LogDir not found: $LogDir" }

function Load-Log([string]$name) {
    $path = Join-Path $LogDir "$name-talkback.log"
    if (-not (Test-Path $path)) {
        $alt = Get-ChildItem $LogDir -Filter "$name*.log" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch "dump" } |
            Select-Object -First 1
        if ($alt) { $path = $alt.FullName }
    }
    if (Test-Path $path) { return @(Get-Content $path -Encoding UTF8) }
    return @()
}

function Prop([string]$line, [string]$name) {
    if ($line -and $line -match "$name=([^\s]+)") { return $Matches[1] }
    return $null
}

function EdgeMatch([string]$line, [string]$remote) {
    return $line -match "remote=$remote\b" -or
        $line -match "to=$remote\b" -or
        $line -match "edge=$remote\b"
}

function GateLabel([bool]$ok) { if ($ok) { "PASS" } else { "FAIL" } }

$auth = Load-Log $AuthorityModule
$peer = Load-Log $RemoteModule
$all = $auth + $peer

# ---- D1 tokens (authority + peer) ----
$d1LocalAccept = @($auth | Where-Object {
    $_ -match "OFFER_DELIVERY stage=LOCAL_ACCEPT" -and
        (EdgeMatch $_ $RemoteModule) -and
        ($_ -match "pathKind=RECOVERY_REATTACH" -or $_ -match "signalDomain=RECOVERY_REATTACH")
})
$d1LocalAcceptedFact = @($auth | Where-Object {
    $_ -match "RECOVERY_DELIVERY_LOCAL_ACCEPTED" -and (EdgeMatch $_ $RemoteModule)
})
$d1Absent = @($auth | Where-Object {
    $_ -match "RECOVERY_REMOTE_INGRESS_ABSENT" -and (EdgeMatch $_ $RemoteModule)
})
$d1Evaluate = @($auth | Where-Object {
    $_ -match "RECOVERY_DELIVERY_RETRY_EVALUATE" -and
        $_ -match "trigger=REMOTE_INGRESS_ABSENT" -and
        (EdgeMatch $_ $RemoteModule)
})
$d1LegacyEval = @($auth | Where-Object {
    $_ -match "RECOVERY_DELIVERY_RETRY_EVALUATE" -and
        $_ -notmatch "trigger=REMOTE_INGRESS_ABSENT"
})
$d1Admitted = @($auth | Where-Object {
    $_ -match "RECOVERY_DELIVERY_RETRY_ADMITTED" -and (EdgeMatch $_ $RemoteModule)
})
$d1RetryPending = @($auth | Where-Object {
    $_ -match "RECOVERY_DELIVERY_RETRY_PENDING" -and (EdgeMatch $_ $RemoteModule)
})
$d1Exhausted = @($auth | Where-Object {
    $_ -match "RECOVERY_DELIVERY_EXHAUSTED" -and (EdgeMatch $_ $RemoteModule)
})
$d1Confirmed = @($auth | Where-Object {
    $_ -match "RECOVERY_DELIVERY_CONFIRMED" -and (EdgeMatch $_ $RemoteModule)
})
$d1Drop = @($peer | Where-Object { $_ -match "D1_DEBUG_DROP_RECOVERY_INGRESS" })
$d1Observed = @($peer | Where-Object {
    $_ -match "RECOVERY_REMOTE_INGRESS_OBSERVED" -and $_ -match "from=$AuthorityModule\b"
})
$d1RemoteReceive = @($peer | Where-Object {
    $_ -match "OFFER_DELIVERY stage=REMOTE_RECEIVE" -and
        $_ -match "pathKind=RECOVERY_REATTACH" -and
        $_ -match "remote=$AuthorityModule\b"
})

# ---- C tokens (authority) ----
$cCreated = @($auth | Where-Object {
    $_ -match "DEFERRED_INTENT_CREATED" -and (EdgeMatch $_ $RemoteModule)
})
$cHeldDispatch = @($auth | Where-Object {
    $_ -match "DEFERRED_INTENT_HELD" -and
        ($_ -match "hold=DISPATCH" -or $_ -match "reason=dispatch_not_ready") -and
        (EdgeMatch $_ $RemoteModule)
})
$cCanExecute = @($auth | Where-Object {
    $_ -match "NEGOTIATION_CAN_EXECUTE" -and (EdgeMatch $_ $RemoteModule)
})
$cDrainRetry = @($auth | Where-Object {
    $_ -match "DEFERRED_INTENT_DRAIN_RETRY" -and (EdgeMatch $_ $RemoteModule)
})
$cDrainAttemptRetry = @($auth | Where-Object {
    $_ -match "DEFERRED_INTENT_DRAIN_ATTEMPT" -and
        $_ -match "trigger=DISPATCH_READINESS_RETRY" -and
        (EdgeMatch $_ $RemoteModule)
})
$cReprobe = @($auth | Where-Object {
    $_ -match "DEFERRED_INTENT_REPROBE_RESULT" -and (EdgeMatch $_ $RemoteModule)
})
$cReprobePass = @($cReprobe | Where-Object {
    $_ -match "negotiationExecutable=true" -and $_ -match "dispatchReady=true"
})
$cExecuted = @($auth | Where-Object {
    $_ -match "DEFERRED_INTENT_EXECUTED" -and (EdgeMatch $_ $RemoteModule)
})
$cDispatched = @($auth | Where-Object {
    $_ -match "RECOVERY_ICE_RESTART_DISPATCHED" -and (EdgeMatch $_ $RemoteModule)
})

# ---- Completion / episode ----
$recoveredDecision = @($auth | Where-Object {
    $_ -match "RECOVERY_COMPLETION_DECISION" -and
        $_ -match "candidate=RECOVERED" -and
        (EdgeMatch $_ $RemoteModule)
})
$obligationClosedRecovered = @($auth | Where-Object {
    $_ -match "RECOVERY_OBLIGATION_CLOSED" -and
        $_ -match "reason=RECOVERED" -and
        (EdgeMatch $_ $RemoteModule)
})
$completionClose = @($auth | Where-Object {
    ($_ -match "RECOVERY_OBLIGATION_CLOSED" -or $_ -match "closeObligation") -and
        (EdgeMatch $_ $RemoteModule)
})

# ---- Gate J-A: D1 chain ----
$hasD1Local = ($d1LocalAccept.Count -ge 1 -or $d1LocalAcceptedFact.Count -ge 1)
$gateJA = $hasD1Local -and
    $d1Absent.Count -ge 1 -and
    $d1Evaluate.Count -ge 1 -and
    $d1Admitted.Count -ge 1 -and
    ($d1RetryPending.Count -ge 1 -or $d1LocalAccept.Count -ge 2)

# ---- Gate J-B: C chain (STRICT — DISPATCHED does NOT substitute EXECUTED) ----
# Require: CREATED -> CAN_EXECUTE -> HELD(dispatch) -> DRAIN_RETRY ->
#          REPROBE(pass) -> DISPATCHED(same intent) -> EXECUTED(same intent)
$cIntentForJb = $null
if ($cCreated.Count -ge 1) { $cIntentForJb = Prop $cCreated[0] "intentId" }
$cExecutedSameIntent = @($cExecuted | Where-Object {
    (-not $cIntentForJb) -or ((Prop $_ "intentId") -eq $cIntentForJb)
})
$cDispatchedSameIntent = @($cDispatched | Where-Object {
    (-not $cIntentForJb) -or ((Prop $_ "intentId") -eq $cIntentForJb) -or $_ -match "remote=$RemoteModule\b"
})
$jbDispatchedOnly = ($cDispatched.Count -ge 1 -or $cDispatchedSameIntent.Count -ge 1) -and
    $cExecutedSameIntent.Count -eq 0
$jbHasDispatched = $cDispatchedSameIntent.Count -ge 1 -or $cDispatched.Count -ge 1
$gateJB = $cCreated.Count -ge 1 -and
    $cCanExecute.Count -ge 1 -and
    $cHeldDispatch.Count -ge 1 -and
    $cDrainRetry.Count -ge 1 -and
    $cReprobe.Count -ge 1 -and
    $cReprobePass.Count -ge 1 -and
    $jbHasDispatched -and
    $cExecutedSameIntent.Count -ge 1

# ---- Gate J-C: D1 must not forge C wakeup ----
$d1EvalMentionsNeg = @($d1Evaluate | Where-Object { $_ -match "NEGOTIATION_CAN_EXECUTE" }).Count
$absentMentionsNeg = @($d1Absent | Where-Object { $_ -match "NEGOTIATION_CAN_EXECUTE" }).Count
$cCreatedFromAbsent = @($cCreated | Where-Object {
    $_ -match "REMOTE_INGRESS_ABSENT" -or $_ -match "trigger=REMOTE_INGRESS_ABSENT"
}).Count
# If D1 chain ran but C never injected (no CREATED) yet CAN_EXECUTE appears after ABSENT → contamination
$d1OnlyForgedCanExec = ($d1Absent.Count -ge 1 -and $cCreated.Count -eq 0 -and $cCanExecute.Count -ge 1)
$gateJC = ($d1EvalMentionsNeg -eq 0) -and ($absentMentionsNeg -eq 0) -and
    ($cCreatedFromAbsent -eq 0) -and (-not $d1OnlyForgedCanExec)

# ---- Gate J-D: C must not impersonate D1 ABSENT trigger ----
$drainUsesAbsent = @($cDrainRetry | Where-Object {
    $_ -match "REMOTE_INGRESS_ABSENT" -or $_ -match "trigger=REMOTE_INGRESS_ABSENT"
}).Count
$drainAttemptUsesAbsent = @($auth | Where-Object {
    $_ -match "DEFERRED_INTENT_DRAIN_ATTEMPT" -and
        (EdgeMatch $_ $RemoteModule) -and
        ($_ -match "trigger=REMOTE_INGRESS_ABSENT")
}).Count
$d1EvalUsesDispatchReady = @($d1LegacyEval | Where-Object {
    $_ -match "DISPATCH_READINESS" -or $_ -match "NEGOTIATION_CAN_EXECUTE"
}).Count
$gateJD = ($drainUsesAbsent -eq 0) -and ($drainAttemptUsesAbsent -eq 0) -and
    ($d1EvalUsesDispatchReady -eq 0) -and ($d1LegacyEval.Count -eq 0)

# ---- Gate J-E: fence correlation stability ----
$d1FenceOk = $true
$d1Lineage = $null
if ($d1Absent.Count -ge 1) {
    $d1Lineage = Prop $d1Absent[0] "offerLineageId"
    # Lineage stability across D1 ownership tokens (chronological in auth log)
    $d1TokenLines = @($auth | Where-Object {
        ($_ -match "RECOVERY_REMOTE_INGRESS_ABSENT" -or
            $_ -match "RECOVERY_DELIVERY_RETRY_EVALUATE" -or
            $_ -match "RECOVERY_DELIVERY_RETRY_ADMITTED" -or
            $_ -match "RECOVERY_DELIVERY_RETRY_PENDING" -or
            $_ -match "RECOVERY_DELIVERY_LOCAL_ACCEPTED") -and
            (EdgeMatch $_ $RemoteModule)
    })
    foreach ($line in $d1TokenLines) {
        $lin = Prop $line "offerLineageId"
        if ($lin -and $d1Lineage -and $lin -ne $d1Lineage -and $lin -ne "NONE") {
            $d1FenceOk = $false
            break
        }
    }
    # ABSENT deliveryAttemptId sequence must be non-decreasing in log order
    $absentAttempts = @()
    foreach ($line in $d1Absent) {
        $att = Prop $line "deliveryAttemptId"
        if ($att) { $absentAttempts += [int64]$att }
    }
    for ($i = 1; $i -lt $absentAttempts.Count; $i++) {
        if ($absentAttempts[$i] -lt $absentAttempts[$i - 1]) { $d1FenceOk = $false; break }
    }
}
# Producer fence: same (L,N) must not have ABSENT + REMOTE_RECEIVE
foreach ($a in $d1Absent) {
    $lin = Prop $a "offerLineageId"
    $att = Prop $a "deliveryAttemptId"
    if (-not $lin -or -not $att) { continue }
    $hit = @($d1RemoteReceive | Where-Object {
        (Prop $_ "offerLineageId") -eq $lin -and (Prop $_ "deliveryAttemptId") -eq $att
    })
    if ($hit.Count -ge 1) { $d1FenceOk = $false; break }
}

$cFenceOk = $true
$cIntentId = $null
if ($cCreated.Count -ge 1) {
    $cIntentId = Prop $cCreated[0] "intentId"
    $cAttempt = Prop $cCreated[0] "attemptId"
    $cObl = Prop $cCreated[0] "obligationGen"
    foreach ($line in ($cHeldDispatch + $cDrainRetry + $cExecuted)) {
        $iid = Prop $line "intentId"
        if ($cIntentId -and $iid -and $iid -ne $cIntentId) { $cFenceOk = $false }
    }
    $retryFence = @($cDrainRetry | Where-Object {
        $_ -match "intentId=" -and $_ -match "attemptId=" -and
            $_ -match "obligationGen=" -and $_ -match "admissionSeq="
    })
    if ($cDrainRetry.Count -ge 1 -and $retryFence.Count -lt 1) { $cFenceOk = $false }
    if ($cAttempt -and $cDrainRetry.Count -ge 1) {
        $retryAtt = Prop $cDrainRetry[0] "attemptId"
        if ($retryAtt -and $cAttempt -and $retryAtt -ne $cAttempt) {
            # attemptId drift on same intent is contamination unless supersede logged
            $supersede = @($auth | Where-Object {
                $_ -match "DEFERRED_INTENT_SUPERSEDED" -and (EdgeMatch $_ $RemoteModule)
            }).Count
            if ($supersede -eq 0) { $cFenceOk = $false }
        }
    }
    if ($null -ne $cObl) { } # kept for report
}
$gateJE = $d1FenceOk -and $cFenceOk

# ---- Gate J-F HARD: no premature RECOVERED / completion close ----
function Index-OfFirst($lines, $haystack) {
    if (-not $lines -or $lines.Count -eq 0) { return -1 }
    $target = $lines[0]
    for ($i = 0; $i -lt $haystack.Count; $i++) {
        if ($haystack[$i] -eq $target) { return $i }
    }
    return -1
}

$idxRecovered = Index-OfFirst $recoveredDecision $auth
if ($idxRecovered -lt 0) { $idxRecovered = Index-OfFirst $obligationClosedRecovered $auth }
$idxExecuted = Index-OfFirst $cExecuted $auth
if ($idxExecuted -lt 0) { $idxExecuted = Index-OfFirst $cDispatched $auth }
$idxD1Terminal = Index-OfFirst $d1Confirmed $auth
if ($idxD1Terminal -lt 0) { $idxD1Terminal = Index-OfFirst $d1Exhausted $auth }

$prematureRecovered = $false
$jfNotes = @()
if ($idxRecovered -ge 0) {
    # Recovered while C created/held but never executed
    if ($cCreated.Count -ge 1 -and $cExecuted.Count -eq 0 -and $cDispatched.Count -eq 0) {
        $prematureRecovered = $true
        $jfNotes += "RECOVERED while C intent never EXECUTED/DISPATCHED"
    }
    # Recovered before C EXECUTED in log order
    if ($idxExecuted -ge 0 -and $idxRecovered -lt $idxExecuted) {
        $prematureRecovered = $true
        $jfNotes += "RECOVERED before C EXECUTED/DISPATCHED"
    }
    # Recovered while D1 mid-retry (ABSENT seen, no terminal CONFIRMED/EXHAUSTED yet at recover time)
    if ($d1Absent.Count -ge 1 -and $idxD1Terminal -lt 0) {
        $prematureRecovered = $true
        $jfNotes += "RECOVERED while D1 delivery never CONFIRMED/EXHAUSTED"
    }
    if ($idxD1Terminal -ge 0 -and $idxRecovered -lt $idxD1Terminal) {
        $prematureRecovered = $true
        $jfNotes += "RECOVERED before D1 delivery terminal"
    }
    # D1 recovered + C executed does not auto-equal episode recovered — flag if recovered
    # appears with only those two and without broader completion evidence markers.
    $hasRichCompletion = @($auth | Where-Object {
        $_ -match "RECOVERY_COMPLETION_DECISION" -and
            $_ -match "candidate=RECOVERED" -and
            ($_ -match "evidence=" -or $_ -match "domains=" -or $_ -match "media=" -or $_ -match "signaling=")
    }).Count -ge 1
    if (-not $hasRichCompletion -and $recoveredDecision.Count -ge 1) {
        # still allow if obligation closed with RECOVERED after both terminals — note only
        $jfNotes += "RECOVERED present; verify CompletionPolicy evidence (not auto-fail alone)"
    }
} else {
    $jfNotes += "no RECOVERED/OBLIGATION_CLOSED(RECOVERED) observed (safe for premature-close check)"
}

# Also fail if closeObligation while HELD(dispatch) is last C state without EXECUTED
$heldWithoutExec = $cHeldDispatch.Count -ge 1 -and $cExecuted.Count -eq 0 -and $cDispatched.Count -eq 0
if ($heldWithoutExec -and ($obligationClosedRecovered.Count -ge 1 -or $recoveredDecision.Count -ge 1)) {
    $prematureRecovered = $true
    $jfNotes += "completion close while C still HELD without EXECUTED"
}

$gateJF = -not $prematureRecovered

# ---- Gate J-G: final consistency ----
$openHeld = $cHeldDispatch.Count -ge 1 -and $cExecuted.Count -eq 0 -and $cDispatched.Count -eq 0
$syncing = @($all | Where-Object { $_ -match "finalPresence=SYNCING" -or $_ -match "presence=SYNCING" }).Count
$offerAwaiting = @($all | Where-Object { $_ -match "OFFER_AWAITING_ANSWER" }).Count
$reconnecting = @($all | Where-Object { $_ -match "RECONNECTING" }).Count
# Residual OK only if chains not exercised; else require C closed and D1 terminal when both exercised
$gateJG = $true
$jgNotes = @()
if ($gateJA -and $gateJB) {
    if ($openHeld) {
        $gateJG = $false
        $jgNotes += "C HELD remains without EXECUTED after joint exercise"
    }
    if ($d1Absent.Count -ge 1 -and $d1Confirmed.Count -eq 0 -and $d1Exhausted.Count -eq 0) {
        $gateJG = $false
        $jgNotes += "D1 ABSENT path has no CONFIRMED/EXHAUSTED terminal"
    }
} else {
    $jgNotes += "partial exercise; J-G soft - residuals may be expected"
}
if ($syncing -ge 1) { $jgNotes += "SYNCING_hits=$syncing (diagnostic)" }
if ($offerAwaiting -ge 1) { $jgNotes += "OFFER_AWAITING_ANSWER_hits=$offerAwaiting (diagnostic)" }
if ($reconnecting -ge 1) { $jgNotes += "RECONNECTING_hits=$reconnecting (diagnostic)" }

# ---- Classification (priority) ----
$d1Exercised = $hasD1Local -or $d1Absent.Count -ge 1
$cExercised = $cCreated.Count -ge 1 -or $cHeldDispatch.Count -ge 1
$classification = "NOT_EXERCISED"
$reason = "neither D1 nor C chain exercised"

if (-not $gateJF) {
    $classification = "FAIL_COMPLETION_SAFETY"
    $reason = "J-F HARD FAIL: premature RECOVERED/completion close"
} elseif (-not $gateJC -or -not $gateJD) {
    $classification = "FAIL_CROSS_DOMAIN_CONTAMINATION"
    $reason = "J-C/J-D FAIL: cross-domain trigger forgery"
} elseif ($d1Exercised -and -not $gateJA) {
    $classification = "ABORT_INVALID_EXERCISE"
    $reason = "J-A anchor incomplete (post-R2: not FAIL_D1_CHAIN)"
} elseif ($cExercised -and -not $gateJB) {
    if ($jbDispatchedOnly) {
        $classification = "FAIL_C_CHAIN"
        $reason = "J-B CONDITIONAL_FAIL: DISPATCHED without EXECUTED+REPROBE_PASS (DISPATCHED != EXECUTED)"
    } else {
        $classification = "FAIL_C_CHAIN"
        $reason = "C tokens present but J-B chain incomplete (need EXECUTED + REPROBE_PASS)"
    }
} elseif (-not $d1Exercised -and -not $cExercised) {
    $classification = "NOT_EXERCISED"
    $reason = "no D1/C ownership tokens for edge"
} elseif ($gateJA -and $gateJB -and $gateJC -and $gateJD -and $gateJE -and $gateJF -and $gateJG) {
    $classification = "PASS_JOINT_D1_C"
    $reason = "J-A..J-G all PASS (J-B requires EXECUTED+REPROBE_PASS; J-F hard gate)"
} elseif ($gateJA -and $gateJB -and $gateJC -and $gateJD -and $gateJE -and $gateJF -and -not $gateJG) {
    $classification = "FAIL_C_CHAIN"
    $reason = "J-G final consistency FAIL after joint exercise"
    # prefer not to invent new class; keep FAIL_C_CHAIN / or use contamination — use FAIL_C_CHAIN as residual
    $classification = "FAIL_CROSS_DOMAIN_CONTAMINATION"
    $reason = "J-G FAIL: residual open recovery state after joint exercise"
} else {
    $classification = "NOT_EXERCISED"
    $reason = "partial gates; joint episode not fully closed"
}

$report = New-Object System.Collections.Generic.List[string]
$report.Add("JOINT_D1_C_REPORT") | Out-Null
$report.Add("logDir=$LogDir") | Out-Null
$report.Add("edge=${AuthorityModule}->${RemoteModule}") | Out-Null
$report.Add("analyzed=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')") | Out-Null
$report.Add("scope=Joint D1 + PR5-2c-C domain independence / completion safety") | Out-Null
$report.Add("outOfScope=production code change UI UVCP PR5-3") | Out-Null
$report.Add("") | Out-Null
$report.Add("=== Counts (D1) ===") | Out-Null
$report.Add("LOCAL_ACCEPT(recovery)=$($d1LocalAccept.Count)") | Out-Null
$report.Add("REMOTE_INGRESS_ABSENT=$($d1Absent.Count)") | Out-Null
$report.Add("RETRY_EVALUATE(ABSENT)=$($d1Evaluate.Count)") | Out-Null
$report.Add("RETRY_ADMITTED=$($d1Admitted.Count)") | Out-Null
$report.Add("RETRY_PENDING=$($d1RetryPending.Count)") | Out-Null
$report.Add("DELIVERY_EXHAUSTED=$($d1Exhausted.Count)") | Out-Null
$report.Add("DELIVERY_CONFIRMED=$($d1Confirmed.Count)") | Out-Null
$report.Add("D1_DEBUG_DROP=$($d1Drop.Count)") | Out-Null
$report.Add("REMOTE_INGRESS_OBSERVED=$($d1Observed.Count)") | Out-Null
$report.Add("REMOTE_RECEIVE=$($d1RemoteReceive.Count)") | Out-Null
$report.Add("LEGACY_EVAL=$($d1LegacyEval.Count)") | Out-Null
$report.Add("") | Out-Null
$report.Add("=== Counts (C) ===") | Out-Null
$report.Add("DEFERRED_INTENT_CREATED=$($cCreated.Count)") | Out-Null
$report.Add("NEGOTIATION_CAN_EXECUTE=$($cCanExecute.Count)") | Out-Null
$report.Add("HELD(dispatch)=$($cHeldDispatch.Count)") | Out-Null
$report.Add("DRAIN_RETRY=$($cDrainRetry.Count)") | Out-Null
$report.Add("DRAIN_ATTEMPT(DISPATCH_READINESS_RETRY)=$($cDrainAttemptRetry.Count)") | Out-Null
$report.Add("REPROBE=$($cReprobe.Count)") | Out-Null
$report.Add("REPROBE_PASS=$($cReprobePass.Count)") | Out-Null
$report.Add("EXECUTED=$($cExecuted.Count)") | Out-Null
$report.Add("ICE_RESTART_DISPATCHED=$($cDispatched.Count)") | Out-Null
$report.Add("") | Out-Null
$report.Add("=== Completion markers ===") | Out-Null
$report.Add("RECOVERY_COMPLETION_DECISION(RECOVERED)=$($recoveredDecision.Count)") | Out-Null
$report.Add("OBLIGATION_CLOSED(RECOVERED)=$($obligationClosedRecovered.Count)") | Out-Null
$report.Add("completionCloseLines=$($completionClose.Count)") | Out-Null
$report.Add("") | Out-Null
$report.Add("=== Gates ===") | Out-Null
$report.Add("J-A D1 ingress retry chain       $(GateLabel $gateJA)") | Out-Null
$report.Add("J-A require=ABSENT->EVALUATE->ADMITTED->RETRY (EXHAUSTED not required)") | Out-Null
$report.Add("J-B C deferred drain chain       $(GateLabel $gateJB)") | Out-Null
$report.Add("J-B require=EXECUTED+REPROBE_PASS (DISPATCHED alone = FAIL)") | Out-Null
$report.Add("J-B REPROBE_PASS=$($cReprobePass.Count) EXECUTED=$($cExecuted.Count) EXECUTED(sameIntent)=$($cExecutedSameIntent.Count) DISPATCHED=$($cDispatched.Count) dispatchedOnly=$jbDispatchedOnly") | Out-Null
$report.Add("J-C D1->C trigger purity         $(GateLabel $gateJC)") | Out-Null
$report.Add("J-D C->D1 trigger purity         $(GateLabel $gateJD)") | Out-Null
$report.Add("J-E correlation/fence            $(GateLabel $gateJE)") | Out-Null
$report.Add("J-F completion safety            $(GateLabel $gateJF)  <-- HARD GATE") | Out-Null
$report.Add("J-G final recovery consistency   $(GateLabel $gateJG)") | Out-Null
$report.Add("") | Out-Null
$report.Add("=== Fence detail ===") | Out-Null
$report.Add("D1 offerLineageId=$(if ($d1Lineage) { $d1Lineage } else { '-' }) fenceOk=$d1FenceOk") | Out-Null
$report.Add("C intentId=$(if ($cIntentId) { $cIntentId } else { '-' }) fenceOk=$cFenceOk") | Out-Null
$report.Add("") | Out-Null
$report.Add("=== J-F notes ===") | Out-Null
if ($jfNotes.Count -eq 0) { $report.Add("note=(none)") | Out-Null }
else { foreach ($n in $jfNotes) { $report.Add("note=$n") | Out-Null } }
$report.Add("") | Out-Null
$report.Add("=== J-G notes ===") | Out-Null
if ($jgNotes.Count -eq 0) { $report.Add("note=(none)") | Out-Null }
else { foreach ($n in $jgNotes) { $report.Add("note=$n") | Out-Null } }
$report.Add("") | Out-Null
$report.Add("=== Classification (GATE / PROVISIONAL — not final verdict) ===") | Out-Null
$report.Add("result=$classification") | Out-Null
$report.Add("reason=$reason") | Out-Null
$report.Add("pr53Unlock=$(if ($classification -eq 'PASS_JOINT_D1_C') { 'ALLOWED' } else { 'BLOCKED' })") | Out-Null
$report.Add("finalAuthority=JOINT_EVIDENCE_CLASSIFICATION.txt") | Out-Null
$report.Add("note=Analyzer emits J-A..J-G gate facts only; Joint Evidence Classifier owns finalOutcome") | Out-Null
$report.Add("") | Out-Null
$report.Add("STATUS: Joint regression gate facts (not production change; J-F hard gate; NOT sole final verdict)") | Out-Null

$out = Join-Path $LogDir "JOINT_D1_C_REPORT.txt"
$utf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllLines($out, $report.ToArray(), $utf8)
Write-Host ($report -join "`n")
Write-Host ""
Write-Host "Wrote $out"