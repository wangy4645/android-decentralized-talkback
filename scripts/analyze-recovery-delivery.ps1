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