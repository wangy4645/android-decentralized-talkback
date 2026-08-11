# ADR-0050 R2a Directed Ingress Soak — thin adjudicate.
# PASS focus: LEASE → READY → DISPATCH → ANSWER (bounded). Scores T1/T2/T3.
# Does NOT score EDGE_RECOVERED / UVCP / DEGRADED / completion.
#
# FROZEN (domain boundary):
#   RECOVERY_REMOTE_INGRESS_ABSENT is not a negotiation readiness signal.
#   It must not be used as R2a admission evidence.
#   Recovery delivery observation marker 不参与 NegotiationIngressGate 判定。
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [string]$FlapTarget = "M02",
    [int]$T3FailMs = 15000
)

$ErrorActionPreference = "Stop"

function Resolve-LogPath {
    param([string]$Dir, [string]$Primary, [string]$Fallback)
    $p = Join-Path $Dir $Primary
    if (Test-Path $p) { return $p }
    $f = Join-Path $Dir $Fallback
    if (Test-Path $f) { return $f }
    return $p
}

function Get-Hits {
    param([string]$Path, [string]$Pattern)
    if (-not (Test-Path $Path)) { return @() }
    @(Select-String -Path $Path -Pattern $Pattern)
}

function Edge-Hits {
    param([string]$Path, [string]$Token, [string]$Remote)
    @(Get-Hits -Path $Path -Pattern $Token | Where-Object {
        $_.Line -match "remote=$Remote\b" -or $_.Line -match "edge=$Remote\b"
    })
}

function Get-LineTimeMs {
    param([string]$Line)
    # logcat: "08-11 15:41:08.593 ..."
    if ($Line -match "(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})") {
        $mm = [int]$Matches[1]; $dd = [int]$Matches[2]
        $hh = [int]$Matches[3]; $mi = [int]$Matches[4]
        $ss = [int]$Matches[5]; $ms = [int]$Matches[6]
        # year-agnostic ordinal within day is enough for deltas
        return ((((($mm * 31L + $dd) * 24 + $hh) * 60 + $mi) * 60 + $ss) * 1000 + $ms)
    }
    return $null
}

function First-After {
    param($Hits, [int]$AfterLine)
    @($Hits | Where-Object { $_.LineNumber -gt $AfterLine } | Select-Object -First 1)
}

function Summarize-Observer {
    param(
        [string]$Observer,
        [string]$LogPath,
        [string]$Remote,
        [int]$T3FailMs
    )

    $blocked = @(Edge-Hits -Path $LogPath -Token "NEGOTIATION_NON_OWNER_BLOCKED" -Remote $Remote)
    $admitted = @(Edge-Hits -Path $LogPath -Token "NEGOTIATION_LEASE_ADMITTED" -Remote $Remote)
    $pending = @(Edge-Hits -Path $LogPath -Token "NEGOTIATION_INGRESS_PENDING" -Remote $Remote)
    $ready = @(Edge-Hits -Path $LogPath -Token "REMOTE_NEGOTIATION_READY" -Remote $Remote)
    $deadline = @(Edge-Hits -Path $LogPath -Token "NEGOTIATION_INGRESS_DEADLINE" -Remote $Remote)
    $dispatched = @(Edge-Hits -Path $LogPath -Token "RECOVERY_ICE_RESTART_DISPATCHED" -Remote $Remote)
    # Informational only — delivery observation window; NOT R2a negotiation readiness.
    $deliveryIngressAbsent = @(Get-Hits -Path $LogPath -Pattern "RECOVERY_REMOTE_INGRESS_ABSENT" | Where-Object {
        $_.Line -match "to=$Remote\b" -or $_.Line -match "remote=$Remote\b"
    })
    # Answer evidence on observer: remote applies our offer (ANSWERER / SLD ANSWER) after dispatch
    $answerHits = @(Get-Hits -Path $LogPath -Pattern "op=SLD type=ANSWER|ANSWERER_TRANSACTION_COMMIT|remoteDesc=ANSWER" | Where-Object {
        $_.Line -match $Remote -or $_.Line -match "tag=.*\|$Remote\b"
    })

    $t1 = $null; $t2 = $null; $t3 = $null
    $admitLine = if ($admitted.Count -gt 0) { $admitted[0] } else { $null }
    $readyLine = $null
    $dispatchLine = $null
    $answerLine = $null

    if ($null -ne $admitLine) {
        $readyLine = (First-After -Hits $ready -AfterLine $admitLine.LineNumber | Select-Object -First 1)
        if ($null -eq $readyLine -and $ready.Count -gt 0) {
            # IMMEDIATE ready may log just before admit in rare races; take first ready near admit
            $readyLine = $ready[0]
        }
        $dispatchLine = if ($null -ne $readyLine) {
            (First-After -Hits $dispatched -AfterLine $readyLine.LineNumber | Select-Object -First 1)
        } else {
            (First-After -Hits $dispatched -AfterLine $admitLine.LineNumber | Select-Object -First 1)
        }
        if ($null -ne $dispatchLine) {
            $answerLine = (First-After -Hits $answerHits -AfterLine $dispatchLine.LineNumber | Select-Object -First 1)
        }

        $ta = Get-LineTimeMs $admitLine.Line
        $tr = if ($readyLine) { Get-LineTimeMs $readyLine.Line } else { $null }
        $td = if ($dispatchLine) { Get-LineTimeMs $dispatchLine.Line } else { $null }
        $tn = if ($answerLine) { Get-LineTimeMs $answerLine.Line } else { $null }
        if ($null -ne $ta -and $null -ne $tr) { $t1 = [int]($tr - $ta) }
        if ($null -ne $tr -and $null -ne $td) { $t2 = [int]($td - $tr) }
        if ($null -ne $td -and $null -ne $tn) { $t3 = [int]($tn - $td) }
    }

    $passLease = ($admitted.Count -ge 1)
    $passBlocked = ($blocked.Count -eq 0)
    $passReady = ($ready.Count -ge 1)
    $passDispatch = ($dispatched.Count -ge 1)
    $passAnswer = ($null -ne $answerLine)
    $passT3 = ($null -ne $t3 -and $t3 -ge 0 -and $t3 -le $T3FailMs)

    $case = "UNKNOWN"
    if ($deadline.Count -gt 0 -and -not $passDispatch) {
        # R2a correctly refused dispatch when no negotiation ingress confidence.
        $case = "CORRECT_REFUSE_NO_DISPATCH"
    }
    elseif ($passReady -and $passDispatch -and -not $passAnswer) {
        $case = "B_READY_OFFER_NO_ANSWER"
    }
    elseif ($passLease -and $passReady -and $passDispatch -and $passAnswer -and $passBlocked) {
        if ($passT3) { $case = "A_FIELD_SUPPORTED" }
        else { $case = "A_PARTIAL_SLOW_ANSWER" }
    }
    elseif ($passLease -and $passDispatch -and -not $passReady) {
        $case = "REGRESSION_DISPATCH_WITHOUT_READY"
    }
    else {
        $case = "FAIL_OR_INCOMPLETE"
    }

    [pscustomobject]@{
        Observer                      = $Observer
        Remote                        = $Remote
        LeaseAdmitted                 = $admitted.Count
        IngressPending                = $pending.Count
        RemoteNegotiationReady        = $ready.Count
        IngressDeadline               = $deadline.Count
        IceRestartDispatched          = $dispatched.Count
        DeliveryIngressAbsentInfoOnly = $deliveryIngressAbsent.Count
        NonOwnerBlocked               = $blocked.Count
        AnswerAfterDispatch           = $passAnswer
        T1_LeaseToReadyMs             = $t1
        T2_ReadyToDispatchMs          = $t2
        T3_DispatchToAnswerMs         = $t3
        PassLease                     = $passLease
        PassBlockedZero               = $passBlocked
        PassReady                     = $passReady
        PassDispatch                  = $passDispatch
        PassAnswer                    = $passAnswer
        PassT3Bounded                 = $passT3
        Case                          = $case
    }
}

$m01 = Resolve-LogPath $LogDir "M01-talkback.log" "M01.log"
$m03 = Resolve-LogPath $LogDir "M03-talkback.log" "M03.log"

$s01 = Summarize-Observer -Observer "M01" -LogPath $m01 -Remote $FlapTarget -T3FailMs $T3FailMs
$s03 = Summarize-Observer -Observer "M03" -LogPath $m03 -Remote $FlapTarget -T3FailMs $T3FailMs

Write-Host "=== ADR-0050 R2a Directed Ingress Adjudication ==="
Write-Host "LogDir=$LogDir FlapTarget=$FlapTarget T3FailMs=$T3FailMs"
Write-Host "NOT scored: EDGE_RECOVERED | DEGRADED | UVCP"
Write-Host "NOT scored: RECOVERY_REMOTE_INGRESS_ABSENT (delivery observation; not R2a readiness)"
Write-Host ""
$s01, $s03 | Format-List Observer, Remote, LeaseAdmitted, IngressPending, RemoteNegotiationReady, `
    IngressDeadline, IceRestartDispatched, DeliveryIngressAbsentInfoOnly, NonOwnerBlocked, AnswerAfterDispatch, `
    T1_LeaseToReadyMs, T2_ReadyToDispatchMs, T3_DispatchToAnswerMs, `
    PassLease, PassBlockedZero, PassReady, PassDispatch, PassAnswer, PassT3Bounded, Case

$cases = @($s01.Case, $s03.Case)
$anySupported = $cases | Where-Object { $_ -match "^A_" }
$anyB = $cases | Where-Object { $_ -eq "B_READY_OFFER_NO_ANSWER" }
$anyRefuse = $cases | Where-Object { $_ -eq "CORRECT_REFUSE_NO_DISPATCH" }
$anyRegression = $cases | Where-Object { $_ -eq "REGRESSION_DISPATCH_WITHOUT_READY" }

Write-Host ""
if ($anyRegression.Count -ge 1) {
    Write-Host "VERDICT: REGRESSION — dispatch without REMOTE_NEGOTIATION_READY"
    exit 1
}
elseif ($anySupported.Count -ge 1) {
    Write-Host "VERDICT: R2a FIELD SUPPORTED (LEASE→READY→DISPATCH→ANSWER on at least one observer)"
    if ($anyRefuse.Count -ge 1) {
        Write-Host "  + CORRECT_REFUSE_NO_DISPATCH on other observer(s) — expected R2a block, not failure"
    }
    Write-Host "  R2b remains HOLD unless dual legitimate OFFER evidence appears"
    exit 0
}
elseif ($anyB.Count -ge 1) {
    Write-Host "VERDICT: Case B — R2a OK; no answer → execution/answer ingress. Do NOT rollback R2a."
    exit 0
}
elseif ($anyRefuse.Count -ge 1 -and ($anySupported.Count -eq 0) -and ($anyB.Count -eq 0)) {
    Write-Host "VERDICT: CORRECT_REFUSE only — no positive dispatch path this run; not R2a regression"
    exit 0
}
else {
    Write-Host "VERDICT: FAIL / CLASSIFY — see Case columns"
    Write-Host "  A_FIELD_SUPPORTED | B_READY_OFFER_NO_ANSWER | CORRECT_REFUSE_NO_DISPATCH | REGRESSION_DISPATCH_WITHOUT_READY"
    exit 1
}
