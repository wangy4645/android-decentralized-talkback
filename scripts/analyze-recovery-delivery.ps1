# ADR-0035 PR1/PR2 — Recovery Delivery Observability analyzer
# Evidence only: no Completion/UVCP. PR2 adds retry/exhaustion classification.
#
# Usage:
#   .\scripts\analyze-recovery-delivery.ps1 -LogDir logs\signal-path-<stamp>

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [string]$OfferLineageId = ""
)

$ErrorActionPreference = "Stop"
$Utf8NoBom = New-Object System.Text.UTF8Encoding $false

function Read-LogFileUtf8([string]$path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $enc = New-Object System.Text.UTF8Encoding $true
        return $enc.GetString($bytes) -split "`r?`n"
    }
    return $Utf8NoBom.GetString($bytes) -split "`r?`n"
}

function Write-ReportUtf8([string]$path, [string]$content) {
    [System.IO.File]::WriteAllText($path, $content, $Utf8NoBom)
}

if (-not (Test-Path $LogDir)) { throw "LogDir not found: $LogDir" }

$files = Get-ChildItem $LogDir -Filter "*-talkback.log" -ErrorAction SilentlyContinue | Sort-Object Name
if (-not $files) { $files = Get-ChildItem $LogDir -Filter "*.log" | Sort-Object Name }
if (-not $files) { throw "No *.log under $LogDir" }

$all = @()
foreach ($f in $files) {
    $device = if ($f.BaseName -match "^(M\d+)") { $Matches[1] } else { $f.BaseName }
    foreach ($line in (Read-LogFileUtf8 $f.FullName)) {
        if ($line) { $all += [pscustomobject]@{ Device = $device; Line = $line } }
    }
}

function Get-Field([string]$line, [string]$name) {
    $pat = "\b" + [regex]::Escape($name) + "=([^\s]+)"
    if ($line -match $pat) { return $Matches[1] }
    return $null
}

function Get-LogTime([string]$line) {
    if ($line -match "^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})") { return $Matches[1] }
    return $null
}

function Parse-LogStamp([string]$stamp) {
    if (-not $stamp) { return $null }
    try { return [datetime]::ParseExact($stamp, "MM-dd HH:mm:ss.fff", $null) } catch { return $null }
}

function In-TimeWindow([string]$lineTime, [datetime]$start, [datetime]$end) {
    if (-not $lineTime) { return $true }
    $parsed = Parse-LogStamp $lineTime
    if (-not $parsed) { return $true }
    if ($parsed -lt $start) { return $false }
    if ($parsed -gt $end) { return $false }
    return $true
}

function Has-Token([string]$line, [string]$token) {
    return $line -match [regex]::Escape($token)
}

function Step-PassFail([bool]$observed) {
    if ($observed) { return "PASS" }
    return "FAIL"
}

$requested = @($all | Where-Object { Has-Token $_.Line "RECOVERY_DELIVERY_REQUESTED" })
if ($OfferLineageId) {
    $requested = @($requested | Where-Object { (Get-Field $_.Line "offerLineageId") -eq $OfferLineageId })
}

$reportPath = Join-Path $LogDir "RECOVERY_DELIVERY_REPORT.txt"
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("RECOVERY_DELIVERY_REPORT")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("logDir: $LogDir")
[void]$sb.AppendLine("generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
[void]$sb.AppendLine("")

if (-not $requested.Count) {
    [void]$sb.AppendLine("status: NO_RECOVERY_DELIVERY_REQUESTED")
    Write-ReportUtf8 $reportPath $sb.ToString()
    Write-Host "Wrote $reportPath (no requests)"
    exit 0
}

$txnIndex = 0
foreach ($req in $requested) {
    $txnIndex++
    $lineage = Get-Field $req.Line "offerLineageId"
    $from = Get-Field $req.Line "from"
    $to = Get-Field $req.Line "to"
    $deliveryAttempt = Get-Field $req.Line "deliveryAttemptId"
    $recoveryAttempt = Get-Field $req.Line "recoveryAttemptId"
    $session = Get-Field $req.Line "session"
    $reqTime = Get-LogTime $req.Line
    $edge = "$from->$to"

    $matchLineage = { param($row) (Get-Field $row.Line "offerLineageId") -eq $lineage }
    $matchRecovery = { param($row)
        $ra = Get-Field $row.Line "recoveryAttemptId"
        if (-not $recoveryAttempt) { return $true }
        if (-not $ra) { return $true }
        return $ra -eq $recoveryAttempt
    }

    $localAccept = @($all | Where-Object {
        $_.Device -eq $from -and (
            (Has-Token $_.Line "RECOVERY_DELIVERY_LOCAL_ACCEPTED") -or
            ((Has-Token $_.Line "OFFER_DELIVERY") -and (Has-Token $_.Line "LOCAL_ACCEPT"))
        ) -and (& $matchLineage $_) -and (& $matchRecovery $_)
    })
    $pending = @($all | Where-Object {
        $_.Device -eq $from -and (Has-Token $_.Line "RECOVERY_DELIVERY_PENDING") -and
        (& $matchLineage $_) -and (& $matchRecovery $_)
    })
    $peerRecv = @($all | Where-Object {
        $_.Device -eq $to -and (Has-Token $_.Line "RECOVERY_REATTACH_RECEIVED") -and
        (& $matchLineage $_) -and (Get-Field $_.Line "from") -eq $from
    })
    $ackSent = @($all | Where-Object {
        $_.Device -eq $to -and (Has-Token $_.Line "RECOVERY_REATTACH_ACK_SENT") -and
        (& $matchLineage $_) -and (Get-Field $_.Line "to") -eq $from
    })
    $ackRecv = @($all | Where-Object {
        $_.Device -eq $from -and (Has-Token $_.Line "RECOVERY_REATTACH_ACK_RECEIVED") -and
        (& $matchLineage $_) -and (Get-Field $_.Line "from") -eq $to
    })
    $confirmed = @($all | Where-Object {
        $_.Device -eq $from -and (Has-Token $_.Line "RECOVERY_DELIVERY_CONFIRMED") -and
        (& $matchLineage $_) -and (& $matchRecovery $_)
    })
    $ackAccepted = @($ackRecv | Where-Object { (Get-Field $_.Line "accepted") -eq "true" })

    $retryPending = @($all | Where-Object {
        $_.Device -eq $from -and (Has-Token $_.Line "RECOVERY_DELIVERY_RETRY_PENDING") -and
        (& $matchLineage $_) -and (& $matchRecovery $_)
    })
    $retryDeferred = @($all | Where-Object {
        $_.Device -eq $from -and (Has-Token $_.Line "RECOVERY_DELIVERY_RETRY_DEFERRED") -and
        (& $matchLineage $_) -and (& $matchRecovery $_)
    })
    $exhausted = @($all | Where-Object {
        $_.Device -eq $from -and (Has-Token $_.Line "RECOVERY_DELIVERY_EXHAUSTED") -and
        (& $matchLineage $_) -and (& $matchRecovery $_)
    })
    $attemptTimeline = @($all | Where-Object {
        $_.Device -eq $from -and (
            (Has-Token $_.Line "RECOVERY_DELIVERY_PENDING") -or
            (Has-Token $_.Line "RECOVERY_DELIVERY_RETRY_PENDING") -or
            (Has-Token $_.Line "RECOVERY_DELIVERY_EXHAUSTED") -or
            (Has-Token $_.Line "RECOVERY_DELIVERY_CONFIRMED")
        ) -and (& $matchLineage $_) -and (& $matchRecovery $_)
    })

    $classification = "DELIVERY_PENDING"
    if ($confirmed.Count -gt 0) { $classification = "DELIVERY_CONFIRMED" }
    elseif ($exhausted.Count -gt 0) { $classification = "DELIVERY_EXHAUSTED" }
    elseif ($retryPending.Count -gt 0) { $classification = "DELIVERY_RETRY_PENDING" }

    $d1Classification = "D1_PENDING"
    if ($confirmed.Count -gt 0 -and ($retryPending.Count -gt 0 -or $retryDeferred.Count -gt 0)) {
        $d1Classification = "D1_RETRY_CONFIRMED"
    }
    elseif ($confirmed.Count -gt 0) {
        $d1Classification = "D1_CONFIRMED"
    }
    elseif ($exhausted.Count -gt 0) {
        $d1Classification = "D1_EXHAUSTED"
    }

  # D1 ingress subclass (peer ingress only; does not reopen PR2 retry semantics)
    $d1IngressClass = "N/A"
    if ($peerRecv.Count -eq 0 -and ($localAccept.Count -gt 0 -or $pending.Count -gt 0)) {
        $windowStart = Parse-LogStamp $reqTime
        $windowEnd = $windowStart
        if ($attemptTimeline.Count -gt 0) {
            foreach ($step in $attemptTimeline) {
                $st = Parse-LogStamp (Get-LogTime $step.Line)
                if ($st -and $st -gt $windowEnd) { $windowEnd = $st }
            }
        }
        if ($exhausted.Count -gt 0) {
            $exSt = Parse-LogStamp (Get-LogTime $exhausted[0].Line)
            if ($exSt -and $exSt -gt $windowEnd) { $windowEnd = $exSt }
        }
        if ($windowStart) {
            $ingressStart = $windowStart.AddSeconds(-25)
            $ingressEnd = $windowEnd.AddSeconds(3)
        } else {
            $ingressStart = $null
            $ingressEnd = $null
        }
        $filterIngressWindow = {
            param($row)
            if (-not $ingressStart -or -not $ingressEnd) { return $true }
            return In-TimeWindow (Get-LogTime $row.Line) $ingressStart $ingressEnd
        }
        $senderIpPat = "dstIp=([0-9.]+)"
        $senderLocalAccept = @($all | Where-Object {
            $_.Device -eq $from -and (Has-Token $_.Line "OFFER_DELIVERY") -and
            (Has-Token $_.Line "stage=LOCAL_ACCEPT") -and
            (Get-Field $_.Line "offerLineageId") -eq $lineage
        })
        $dstIp = $null
        $senderIp = $null
        foreach ($la in $senderLocalAccept) {
            if ($la.Line -match "dst=([0-9.]+):") { $dstIp = $Matches[1]; break }
            if ($la.Line -match $senderIpPat) { $dstIp = $Matches[1]; break }
        }
        $senderSent = @($all | Where-Object {
            $_.Device -eq $from -and (Has-Token $_.Line "SIGNAL_DATAGRAM_SENT") -and
            (Has-Token $_.Line "signalType=GROUP_JOIN")
        })
        foreach ($s in $senderSent) {
            if ($s.Line -match "localIp=([0-9.]+)") { $senderIp = $Matches[1]; break }
        }
        if (-not $senderIp) {
            $senderIpHit = @($all | Where-Object {
                $_.Device -eq $to -and (Has-Token $_.Line "REMOTE_RECEIVE_OBSERVED") -and
                $_.Line -match "remote=$from\b" -and $_.Line -match "src=([0-9.]+):"
            } | Select-Object -First 1)
            if ($senderIpHit -and $senderIpHit.Line -match "src=([0-9.]+):") {
                $senderIp = $Matches[1]
            }
        }
        $peerWindow = @($all | Where-Object { $_.Device -eq $to -and (& $filterIngressWindow $_) })
        $peerFactsWindow = @($all | Where-Object {
            $_.Device -eq $to -and (
                (-not $ingressEnd) -or (In-TimeWindow (Get-LogTime $_.Line) $ingressStart $ingressEnd)
            )
        })
        $peerNetworkLost = @($peerFactsWindow | Where-Object {
            (Has-Token $_.Line "NETWORK_LOST")
        }).Count -gt 0
        $peerUnboundRebind = @($peerFactsWindow | Where-Object {
            (Has-Token $_.Line "SIGNAL_SOCKET_REBIND") -and $_.Line -match "boundNetworkId=unbound"
        }).Count -gt 0
        $peerEnetunreachToSender = @($peerWindow | Where-Object {
            (Has-Token $_.Line "SIGNAL_DATAGRAM_SEND_FAILED") -and
            (Has-Token $_.Line "ENETUNREACH") -and
            ($senderIp -and $_.Line -match [regex]::Escape($senderIp))
        }).Count -gt 0
        $peerUdpFromSender = @($peerWindow | Where-Object {
            (Has-Token $_.Line "OFFER_DELIVERY") -and (Has-Token $_.Line "UDP_DATAGRAM_RECEIVED") -and
            (($senderIp -and $_.Line -match [regex]::Escape($senderIp)) -or ($dstIp -and $_.Line -match [regex]::Escape($dstIp)))
        })
        $peerBestEffortFromSender = @($peerWindow | Where-Object {
            (Has-Token $_.Line "REMOTE_RECEIVE_OBSERVED") -and
            $_.Line -match "remote=$from\b"
        })
        $peerRecoveryDecoded = @($peerWindow | Where-Object {
            (Has-Token $_.Line "OFFER_DELIVERY") -and
            (Has-Token $_.Line "SIGNAL_ENVELOPE_DECODED") -and
            (Get-Field $_.Line "remote") -eq $from -and
            ((Get-Field $_.Line "pathKind") -eq "RECOVERY_REATTACH" -or
             (Get-Field $_.Line "joinIntent") -eq "RECOVERY_REATTACH")
        })
        $peerRecoveryRemoteReceive = @($peerWindow | Where-Object {
            (Has-Token $_.Line "OFFER_DELIVERY") -and (Has-Token $_.Line "REMOTE_RECEIVE") -and
            (Get-Field $_.Line "remote") -eq $from -and
            ((Get-Field $_.Line "pathKind") -eq "RECOVERY_REATTACH" -or
             (Get-Field $_.Line "joinIntent") -eq "RECOVERY_REATTACH") -and
            (Get-Field $_.Line "offerLineageId") -eq $lineage
        })
        $peerLargeUdpFromSender = @($peerWindow | Where-Object {
            (Has-Token $_.Line "SIGNAL_DATAGRAM_RECEIVED") -and
            ($senderIp -and $_.Line -match "srcIp=$senderIp\b") -and
            $_.Line -match "signalType=GROUP_JOIN" -and $_.Line -match "bytes=(\d+)" -and [int]$Matches[1] -ge 1500
        })
        if ($peerRecoveryRemoteReceive.Count -gt 0) {
            $d1IngressClass = "N/A_RECEIVED"
        }
        elseif ($peerNetworkLost -or $peerUnboundRebind -or ($peerEnetunreachToSender -and $peerUdpFromSender.Count -eq 0 -and $peerBestEffortFromSender.Count -eq 0)) {
            $d1IngressClass = "D1-A_PEER_INTERFACE_DOWN"
        }
        elseif ($peerRecoveryDecoded.Count -gt 0 -and $peerRecoveryRemoteReceive.Count -eq 0) {
            $d1IngressClass = "D1-C_LOG_CORRELATION_MISS"
        }
        elseif ($peerLargeUdpFromSender.Count -gt 0 -and $peerRecoveryDecoded.Count -eq 0) {
            $d1IngressClass = "D1-C_DECODE_OR_CLASSIFY_MISS"
        }
        elseif ($peerBestEffortFromSender.Count -gt 0 -and $peerLargeUdpFromSender.Count -eq 0) {
            $d1IngressClass = "D1-B_UDP_LOST_BEFORE_SOCKET"
        }
        elseif ($dstIp -and @($peerWindow | Where-Object {
            (Has-Token $_.Line "SIGNAL_SOCKET_BOUND") -and $_.Line -match "localAddress=([0-9.]+)" -and
            $Matches[1] -ne $dstIp -and $Matches[1] -ne "::"
        }).Count -gt 0) {
            $d1IngressClass = "D1-D_WRONG_DESTINATION"
        }
        else {
            $d1IngressClass = "D1-B_UDP_LOST_BEFORE_SOCKET"
        }
    }

    $ackParts = @()
    if ($ackSent.Count -gt 0) { $ackParts += "SENT" }
    if ($ackAccepted.Count -gt 0) { $ackParts += "RECEIVED" }
    elseif ($ackRecv.Count -gt 0) { $ackParts += "RECEIVED_REJECTED" }
    if (-not $ackParts.Count) { $ackParts = @("NONE") }
    $ackSummary = ($ackParts -join ", ")

    if ($txnIndex -gt 1) { [void]$sb.AppendLine("---") ; [void]$sb.AppendLine("") }
    [void]$sb.AppendLine("session: $session")
    [void]$sb.AppendLine("edge: $edge")
    [void]$sb.AppendLine("lineage: $lineage")
    [void]$sb.AppendLine("recoveryAttemptId: $recoveryAttempt")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("REQUESTED:")
    [void]$sb.AppendLine("  time: $reqTime")
    [void]$sb.AppendLine("  sender: $from")
    [void]$sb.AppendLine("  receiver: $to")
    [void]$sb.AppendLine("  deliveryAttempt: $deliveryAttempt")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("LOCAL_ACCEPT:")
    [void]$sb.AppendLine("  $(Step-PassFail ($localAccept.Count -gt 0))")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("DELIVERY_PENDING:")
    [void]$sb.AppendLine("  $(Step-PassFail ($pending.Count -gt 0))")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("REMOTE_RECEIVED:")
    [void]$sb.AppendLine("  $(Step-PassFail ($peerRecv.Count -gt 0))")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("ACK:")
    [void]$sb.AppendLine("  $ackSummary")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("classification:")
    [void]$sb.AppendLine("  $classification")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("d1:")
    [void]$sb.AppendLine("  $d1Classification")
    [void]$sb.AppendLine("")
    if ($d1IngressClass -ne "N/A") {
        [void]$sb.AppendLine("d1_ingress:")
        [void]$sb.AppendLine("  $d1IngressClass")
        [void]$sb.AppendLine("")
    }
    if ($attemptTimeline.Count -gt 0) {
        [void]$sb.AppendLine("attempt_timeline:")
        foreach ($step in $attemptTimeline) {
            $stepTime = Get-LogTime $step.Line
            $stepAttempt = Get-Field $step.Line "deliveryAttemptId"
            $stepTag = if ($step.Line -match "RECOVERY_DELIVERY_(\w+)") { $Matches[1] } else { "UNKNOWN" }
            [void]$sb.AppendLine("  time=$stepTime attempt=$stepAttempt phase=$stepTag")
        }
        [void]$sb.AppendLine("")
    }
}

Write-ReportUtf8 $reportPath $sb.ToString()
Write-Host "Wrote $reportPath"