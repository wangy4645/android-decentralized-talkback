# X7 data-plane observation — STOP + adjudicate M03->M02
param(
    [Parameter(Mandatory = $true)]
    [string]$RunId
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = (Get-Command adb).Source
$logDir = Join-Path $repoRoot "logs\$RunId"
$statePath = Join-Path $logDir "collector-state.json"

if (-not (Test-Path $logDir)) { throw "Missing logDir: $logDir" }

if (Test-Path $statePath) {
    $state = Get-Content $statePath -Raw | ConvertFrom-Json
    foreach ($h in $state.handles) {
        $proc = Get-Process -Id $h.pid -ErrorAction SilentlyContinue
        if ($proc) {
            Stop-Process -Id $h.pid -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 300
        }
        # flush remaining buffer
        & $adb -s $h.serial logcat -d -v threadtime `
            -s Talkback:I MEETING_MEDIA_SHADOW:I 2>&1 |
            Add-Content -Path $h.path -Encoding utf8
    }
}

function Read-Log([string]$label) {
    $p = Join-Path $logDir "$label-logcat.txt"
    if (-not (Test-Path $p)) { return @() }
    return Get-Content $p -ErrorAction SilentlyContinue
}

function Find-CaptureOnWindow([string[]]$lines) {
    $hits = @()
    foreach ($line in $lines) {
        if ($line -match "PTT_TIMING event=captureON session=([^\s]+).*?(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})") {
            $hits += [pscustomobject]@{
                Session = $Matches[1]
                Timestamp = $Matches[2]
                Line = $line
            }
        }
    }
    return $hits
}

function Parse-ShadowPhase([string[]]$lines, [string]$phase) {
    $out = @()
    foreach ($line in $lines) {
        if ($line -match "PROFILE01_SHADOW_RUNTIME phase=$phase\b") { $out += $line }
        if ($line -match "MEETING_MEDIA_SHADOW.*phase=$phase\b") { $out += $line }
    }
    return $out
}

function Max-LongField([string[]]$lines, [string]$field) {
    $max = [long]0
    foreach ($line in $lines) {
        if ($line -match "$field=(\d+)") {
            $v = [long]$Matches[1]
            if ($v -gt $max) { $max = $v }
        }
    }
    return $max
}

function Test-TagPresent([string[]]$lines) {
    return ($lines | Where-Object { $_ -match "MEETING_MEDIA_SHADOW" } | Measure-Object).Count -gt 0
}

$m03 = Read-Log "M03"
$m02 = Read-Log "M02"

$verdict = @{
    runId = $RunId
    finished = (Get-Date -Format o)
    validity = @{}
    x7 = @{}
    evidence = @{}
}

$verdict.validity.m03ShadowTag = Test-TagPresent $m03
$verdict.validity.m02ShadowTag = Test-TagPresent $m02
$verdict.validity.m03CaptureOn = @(Find-CaptureOnWindow $m03).Count -gt 0
$verdict.validity.m02CaptureOn = @(Find-CaptureOnWindow $m02).Count -gt 0

if (-not $verdict.validity.m03ShadowTag -or -not $verdict.validity.m02ShadowTag) {
    $verdict.x7.overall = "INCONCLUSIVE_INVALID_EVIDENCE"
    $verdict.x7.reason = "MEETING_MEDIA_SHADOW tag missing on one or both devices"
} elseif (-not $verdict.validity.m03CaptureOn) {
    $verdict.x7.overall = "INCONCLUSIVE_INVALID_EVIDENCE"
    $verdict.x7.reason = "M03 captureON not found"
} else {
    $captureHits = Find-CaptureOnWindow $m03
    $sessionId = $captureHits[-1].Session
    $pttTs = $captureHits[-1].Timestamp
    $verdict.evidence.sessionId = $sessionId
    $verdict.evidence.m03CaptureOn = $pttTs

    $m03Session = $m03 | Where-Object { $_ -match [regex]::Escape($sessionId) -or $_ -match "MEETING_MEDIA_SHADOW" }
    $m02Session = $m02 | Where-Object { $_ -match [regex]::Escape($sessionId) -or $_ -match "MEETING_MEDIA_SHADOW" }

    $fenced = Parse-ShadowPhase $m03Session "SHADOW_TX_FENCED"
    $txActivity = Parse-ShadowPhase $m03Session "SHADOW_TX_ACTIVITY"
    $rxActivity = Parse-ShadowPhase $m02Session "SHADOW_RX_ACTIVITY"
    $rxAdmit = Parse-ShadowPhase $m02Session "SHADOW_RX_ADMISSION"
    $ingressFunnel = Parse-ShadowPhase $m02Session "INGRESS_FUNNEL"

    $verdict.evidence.txFenced = $fenced
    $verdict.evidence.txActivity = $txActivity
    $verdict.evidence.rxActivity = $rxActivity
    $verdict.evidence.rxAdmission = $rxAdmit
    $verdict.evidence.ingressFunnel = $ingressFunnel

    # Window: lines at/after last captureON on M03 (time-ordered logcat)
    $windowM03 = @()
    $past = $false
    foreach ($line in $m03) {
        if ($line -match [regex]::Escape($pttTs)) { $past = $true }
        if ($past) { $windowM03 += $line }
    }
    $windowTx = Parse-ShadowPhase $windowM03 "SHADOW_TX_ACTIVITY"
    $windowFenced = Parse-ShadowPhase $windowM03 "SHADOW_TX_FENCED"

    $txBefore = Max-LongField (Parse-ShadowPhase ($m03Session | Select-Object -First ([Math]::Max(0, ($m03Session.Count - 500)))) "SHADOW_TX_ACTIVITY") "packetsSent"
    $txAfter = Max-LongField $windowTx "packetsSent"
    $txDelta = $txAfter - $txBefore

    $verdict.evidence.txPacketsSentBeforePtt = $txBefore
    $verdict.evidence.txPacketsSentMaxInWindow = $txAfter
    $verdict.evidence.txPacketsSentDelta = $txDelta

    if ($windowFenced.Count -gt 0) {
        $verdict.x7.x7a = "FAIL"
        $verdict.x7.overall = "STOP_X7a"
        $verdict.x7.reason = ($windowFenced | Select-Object -Last 1)
    } elseif ($txDelta -gt 0 -or ($windowTx.Count -gt 0 -and $txAfter -gt 0)) {
        $verdict.x7.x7a = "PASS"
        $rxMax = Max-LongField $rxActivity "receivedDatagrams"
        if ($rxMax -le 0 -and $rxActivity.Count -eq 0) {
            $verdict.x7.x7b = "FAIL"
            $verdict.x7.overall = "STOP_X7b"
            $verdict.x7.reason = "M03 TX activity in PTT window but M02 SHADOW_RX_ACTIVITY absent"
        } else {
            $verdict.x7.x7b = "PASS"
            $admitted = $rxAdmit | Where-Object { $_ -match "outcome=ADMITTED" }
            $rejected = $rxAdmit | Where-Object { $_ -match "outcome=REJECTED" }
            if ($rejected.Count -gt 0 -and $admitted.Count -eq 0) {
                $verdict.x7.x7c = "FAIL"
                $verdict.x7.overall = "STOP_X7c"
                $verdict.x7.reason = ($rejected | Select-Object -Last 1)
            } elseif ($admitted.Count -gt 0) {
                $verdict.x7.x7c = "PASS"
                $verdict.x7.x7f = if ($ingressFunnel.Count -gt 0) { "OBSERVED" } else { "NO_MARKER_IN_CAPTURE" }
                $verdict.x7.overall = "STOP_X7c_PASS_ADVANCE_X7fg"
                $verdict.x7.reason = "RX_ADMISSION ADMITTED — inspect INGRESS_FUNNEL offline"
            } else {
                $verdict.x7.x7c = "INCONCLUSIVE"
                $verdict.x7.overall = "INCONCLUSIVE"
                $verdict.x7.reason = "M02 received datagrams but no SHADOW_RX_ADMISSION marker"
            }
        }
    } else {
        $verdict.x7.x7a = "INCONCLUSIVE"
        $verdict.x7.overall = "INCONCLUSIVE"
        $verdict.x7.reason = "captureON without TX_FENCED or TX_ACTIVITY delta in PTT window (A'/B blind spot)"
    }
}

$verdictPath = Join-Path $logDir "X7_ADJUDICATION.json"
$verdict | ConvertTo-Json -Depth 6 | Set-Content $verdictPath -Encoding UTF8

@(
    "finished=$(Get-Date -Format o)"
    "verdict=$($verdict.x7.overall)"
    "adjudication=$verdictPath"
) | Add-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

Write-Host "Adjudication: $verdictPath"
Write-Host "Overall: $($verdict.x7.overall)"
if ($verdict.x7.reason) { Write-Host "Reason: $($verdict.x7.reason)" }
Get-Content $verdictPath
