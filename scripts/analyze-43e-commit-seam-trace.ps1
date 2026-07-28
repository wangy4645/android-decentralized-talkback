param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
$LogDir = Resolve-Path $LogDir
$files = Get-ChildItem $LogDir -Filter "*-talkback.log" | Sort-Object Name
if (-not $files) { throw "No *-talkback.log under $LogDir" }

function Get-Field([string]$Line, [string]$Name) {
    if ($Line -match ("(?:^|\s)" + [regex]::Escape($Name) + "=([^\s]+)")) { return $Matches[1] }
    return $null
}
function Get-Ts([string]$Line) {
    if ($Line -match '^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})') { return $Matches[1] }
    return $null
}

$events = New-Object System.Collections.Generic.List[object]
foreach ($f in $files) {
    $mod = if ($f.Name -match '^(M\d+)-') { $Matches[1] } else { $f.BaseName }
    Get-Content $f.FullName -Encoding UTF8 | ForEach-Object {
        $line = $_
        $kind = $null
        if ($line -match 'ICE_RESTART_DEFERRED ') { $kind = "DEFERRED" }
        elseif ($line -match 'NEGOTIATION_RELEASED ') { $kind = "RELEASED" }
        elseif ($line -match 'GROUP_ACCEPT_HANDOFF ') { $kind = "HANDOFF" }
        elseif ($line -match 'ANSWERER_TRANSACTION_COMMIT ') { $kind = "COMMIT" }
        elseif ($line -match 'RECOVERY_WAKEUP_FIRED' -and $line -match 'NEGOTIATION_RELEASED') { $kind = "DRAIN" }
        elseif ($line -match 'RECOVERY_ICE_RESTART_DISPATCHED') { $kind = "DISPATCH" }
        elseif ($line -match 'RECOVERY_ICE_RESTART_INTENT_TERMINAL') { $kind = "TERMINAL" }
        elseif ($line -match 'op=SETTLING state=ANSWERER_SETTLED') { $kind = "SETTLED" }
        if ($null -eq $kind) { return }
        $remote = Get-Field $line "remote"
        if (-not $remote) { $remote = Get-Field $line "edge" }
        $gen = Get-Field $line "gen"
        if (-not $gen) { $gen = Get-Field $line "obligationGen" }
        $events.Add([pscustomobject]@{
            Ts = Get-Ts $line; Module = $mod; Remote = $remote; Kind = $kind
            IntentId = Get-Field $line "intentId"; Reason = Get-Field $line "reason"
            GateBlock = Get-Field $line "gateBlock"; Terminal = Get-Field $line "terminal"
            Path = Get-Field $line "path"; Commit = Get-Field $line "commit"
            Attempt = Get-Field $line "attempt"; Gen = $gen; Line = $line
        }) | Out-Null
    }
}

$deferred = @($events | Where-Object { $_.Kind -eq "DEFERRED" })
$terminals = @($events | Where-Object { $_.Kind -eq "TERMINAL" })
$handoffs = @($events | Where-Object { $_.Kind -eq "HANDOFF" })
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("4.3-E Commit Seam Trace")
[void]$sb.AppendLine("logdir=$LogDir")
[void]$sb.AppendLine("events=$($events.Count) deferred=$($deferred.Count) terminals=$($terminals.Count)")
[void]$sb.AppendLine("")

$missingReason = @($deferred | Where-Object { -not $_.Reason })
$missingIntent = @($deferred | Where-Object { -not $_.IntentId -or $_.IntentId -eq "NONE" })
$bareStale = @($terminals | Where-Object {
    $_.Terminal -eq "STALE_DISCARD" -and (
        -not $_.Reason -or $_.Reason -notin @("OBLIGATION_CLOSED","SUPERSEDED","RELEASE_MISSING")
    )
})
[void]$sb.AppendLine("=== Observability ===")
[void]$sb.AppendLine("each_DEFERRED_has_reason=$(($missingReason.Count -eq 0)) missing=$($missingReason.Count)")
[void]$sb.AppendLine("each_DEFERRED_has_intentId=$(($missingIntent.Count -eq 0)) missing=$($missingIntent.Count)")
[void]$sb.AppendLine("each_STALE_has_named_reason=$(($bareStale.Count -eq 0)) bare=$($bareStale.Count)")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("=== DEFERRED by reason ===")
foreach ($g in ($deferred | Group-Object Reason)) { [void]$sb.AppendLine("  $($g.Name)=$($g.Count)") }
[void]$sb.AppendLine("")
[void]$sb.AppendLine("=== Intent lifecycles ===")

$executed=0; $staleClosed=0; $staleSuper=0; $staleMissing=0; $openOrUnknown=0
$answererReleased=0; $answererNoRelease=0; $signalingMissingWake=0
$intentIds = @($deferred | ForEach-Object { $_.IntentId } | Where-Object { $_ } | Select-Object -Unique)
foreach ($id in $intentIds) {
    $birth = @($deferred | Where-Object { $_.IntentId -eq $id } | Select-Object -First 1)[0]
    $life = @($events | Where-Object { $_.Module -eq $birth.Module -and $_.IntentId -eq $id })
    $term = @($life | Where-Object { $_.Kind -eq "TERMINAL" } | Select-Object -Last 1)
    $rel = @($life | Where-Object { $_.Kind -eq "RELEASED" })
    $drain = @($life | Where-Object { $_.Kind -eq "DRAIN" })
    $disp = @($life | Where-Object { $_.Kind -eq "DISPATCH" })
    $chain = "DEFERRED"
    if ($rel.Count -gt 0) { $chain += "->RELEASED" }
    if ($drain.Count -gt 0) { $chain += "->DRAIN" }
    if ($disp.Count -gt 0) { $chain += "->DISPATCH" }
    if ($term.Count -gt 0) {
        $tr = $term[0].Reason; $tt = $term[0].Terminal
        $chain += "->$tt($tr)"
        if ($tt -eq "EXECUTED") { $executed++ }
        elseif ($tr -eq "OBLIGATION_CLOSED") { $staleClosed++ }
        elseif ($tr -eq "SUPERSEDED") { $staleSuper++ }
        elseif ($tr -eq "RELEASE_MISSING") { $staleMissing++ }
        else { $openOrUnknown++ }
    } else { $chain += "->OPEN"; $openOrUnknown++ }
    if ($birth.Reason -eq "ANSWERER_SETTLING") {
        if ($rel.Count -gt 0) { $answererReleased++ } else { $answererNoRelease++ }
    }
    if ($birth.Reason -eq "SIGNALING_NOT_STABLE" -and $rel.Count -eq 0) { $signalingMissingWake++ }
    [void]$sb.AppendLine("intentId=$id edge=$($birth.Module)->$($birth.Remote) attempt=$($birth.Attempt) gen=$($birth.Gen) reason=$($birth.Reason) chain=$chain")
}

[void]$sb.AppendLine("")
[void]$sb.AppendLine("=== Terminal accounting ===")
[void]$sb.AppendLine("EXECUTED=$executed")
[void]$sb.AppendLine("STALE_DISCARD:OBLIGATION_CLOSED=$staleClosed")
[void]$sb.AppendLine("STALE_DISCARD:SUPERSEDED=$staleSuper")
[void]$sb.AppendLine("STALE_DISCARD:RELEASE_MISSING=$staleMissing")
[void]$sb.AppendLine("OPEN_OR_UNNAMED=$openOrUnknown")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("=== Case stats ===")
[void]$sb.AppendLine("ANSWERER_SETTLING with RELEASED=$answererReleased")
[void]$sb.AppendLine("ANSWERER_SETTLING without RELEASED=$answererNoRelease")
[void]$sb.AppendLine("SIGNALING_NOT_STABLE without RELEASED=$signalingMissingWake")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("=== GROUP_ACCEPT_HANDOFF ===")
foreach ($pathName in @("INVITE","RECONNECT","JOIN","JOIN_FIRST")) {
    $rows = @($handoffs | Where-Object { $_.Path -eq $pathName })
    if ($rows.Count -eq 0) { continue }
    $sk = @($rows | Where-Object { $_.Commit -eq "SKIPPED" }).Count
    $pe = @($rows | Where-Object { $_.Commit -eq "PENDING" }).Count
    $fa = @($rows | Where-Object { $_.Commit -eq "FALSE" }).Count
    [void]$sb.AppendLine("path=$pathName total=$($rows.Count) SKIPPED=$sk PENDING=$pe FALSE=$fa")
}
$inviteSkipped = @($handoffs | Where-Object { $_.Path -in @("INVITE","RECONNECT") -and $_.Commit -eq "SKIPPED" })
[void]$sb.AppendLine("invite_or_reconnect_commit_SKIPPED=$($inviteSkipped.Count)")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("=== Invite SKIPPED samples ===")
foreach ($row in ($inviteSkipped | Select-Object -First 10)) {
    $idx = $row.Line.IndexOf("GROUP_ACCEPT"); if ($idx -lt 0) { $idx = 0 }
    [void]$sb.AppendLine("$($row.Ts) $($row.Module) $($row.Line.Substring($idx))")
}
[void]$sb.AppendLine("")
[void]$sb.AppendLine("=== EXECUTED samples ===")
foreach ($row in ($terminals | Where-Object { $_.Terminal -eq "EXECUTED" })) {
    [void]$sb.AppendLine("$($row.Ts) $($row.Module) intentId=$($row.IntentId) remote=$($row.Remote) $($row.Reason)")
}
[void]$sb.AppendLine("")
$obsPass = ($missingReason.Count -eq 0) -and ($missingIntent.Count -eq 0) -and ($bareStale.Count -eq 0)
if ($deferred.Count -eq 0) { [void]$sb.AppendLine("OVERALL=INCONCLUSIVE") }
elseif ($obsPass) { [void]$sb.AppendLine("OVERALL=PASS_OBSERVABILITY") }
else { [void]$sb.AppendLine("OVERALL=FAIL_OBSERVABILITY") }
[void]$sb.AppendLine("")
if ($inviteSkipped.Count -gt 0) { [void]$sb.AppendLine("HINT=invite_commit_seam (INVITE/RECONNECT commit=SKIPPED confirmed)") }
elseif ($signalingMissingWake -gt 0) { [void]$sb.AppendLine("HINT=wakeup_predicate_grill") }
else { [void]$sb.AppendLine("HINT=none_or_golden_only") }
[void]$sb.AppendLine("NOTE: M01 unrecovered is orthogonal; soak judges deferred lifecycle facts only.")

$text = $sb.ToString()
$out = Join-Path $LogDir "43e-commit-seam-trace.txt"
$utf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($out, $text, $utf8)
# also rewrite analyzer as utf8
$scriptPath = "d:\workspace\project\talkback\talkback\scripts\analyze-43e-commit-seam-trace.ps1"
# skip rewriting full script body here; analysis output is enough
Write-Output $text
Write-Output "Wrote $out"