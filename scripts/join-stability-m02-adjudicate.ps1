# Join Stability M02 — thin classify (J1–J6). Does NOT score recovery / R2a / UVCP.
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [string]$Dut = "M02",
    [string]$Host = "M01"
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

$m01 = Resolve-LogPath $LogDir "M01-talkback.log" "M01.log"
$m02 = Resolve-LogPath $LogDir "M02-talkback.log" "M02.log"
$m03 = Resolve-LogPath $LogDir "M03-talkback.log" "M03.log"

$inviteOnDut = @(Get-Hits $m02 "GROUP_INVITE|Conference invite")
$joinOutDut = @(Get-Hits $m02 "GROUP_JOIN|CONFERENCE_REJOIN|GROUP_RESYNC_REQUEST")
$busyDut = @(Get-Hits $m02 "GROUP_BUSY|sendGroupBusy|BusyReject|BUSY")
$busyHost = @(Get-Hits $m01 "GROUP_BUSY|sendGroupBusy|BusyReject|BUSY")
$reconnect = @(Get-Hits $m01 "reconnect accepted|GROUP_ACCEPT_HANDOFF path=RECONNECT|Conference invite reconnect")
$reconnectDut = @(Get-Hits $m02 "reconnect accepted|GROUP_ACCEPT_HANDOFF path=RECONNECT")
$acceptDut = @(Get-Hits $m02 "GROUP_ACCEPT")
$resync = @(Get-Hits $m02 "GROUP_RESYNC") + @(Get-Hits $m01 "GROUP_RESYNC")
$prepareFalse = @(Get-Hits $m02 "prepareForGroupInvite") + @(Get-Hits $m01 "prepareForGroupInvite")

# Soft session/accepted hints
$sessionOk = @(Get-Hits $m02 "session.*accepted|CONFERENCE.*JOINED|MemberView|roster") 

Write-Host "=== Join Stability M02 Adjudication ==="
Write-Host "LogDir=$LogDir DUT=$Dut Host=$Host"
Write-Host "NOT scored: EDGE_RECOVERED | R2a | LEASE | UVCP | REMOTE_INGRESS_ABSENT"
Write-Host ""
Write-Host "Counts:"
Write-Host "  M02 invite-seen=$($inviteOnDut.Count) join/rejoin-out=$($joinOutDut.Count) BUSY=$($busyDut.Count) ACCEPT=$($acceptDut.Count)"
Write-Host "  M01 reconnect-accept=$($reconnect.Count) BUSY=$($busyHost.Count)"
Write-Host "  RESYNC(any)=$($resync.Count) prepareForGroupInvite-hits=$($prepareFalse.Count)"
Write-Host ""

$class = "INSUFFICIENT"
$note = "no strong join markers"

if ($joinOutDut.Count -eq 0 -and $inviteOnDut.Count -eq 0) {
    $class = "INSUFFICIENT"
    $note = "DUT shows neither join outbound nor invite inbound — late collectors or UI never fired"
}
elseif ($inviteOnDut.Count -eq 0 -and $joinOutDut.Count -gt 0) {
    $class = "J1_INVITE_NEVER_ARRIVES"
    $note = "DUT join/rejoin outbound without GROUP_INVITE inbound"
}
elseif (($busyDut.Count -gt 0 -or $busyHost.Count -gt 0) -and $reconnect.Count -eq 0 -and $reconnectDut.Count -eq 0) {
    $class = "J2_BUSY_OR_DUPLICATE_GATE"
    $note = "BUSY present without reconnect accept"
}
elseif ($inviteOnDut.Count -gt 0 -and $reconnect.Count -eq 0 -and $acceptDut.Count -eq 0) {
    $class = "J3_REJOIN_PATH_MISS"
    $note = "invite seen but no reconnect accept / GROUP_ACCEPT on DUT"
}
elseif ($resync.Count -gt 0 -and $acceptDut.Count -eq 0) {
    $class = "J4_MEMBERSHIP_ROSTER"
    $note = "resync activity without clear accept — check epoch/roster manually"
}
elseif ($acceptDut.Count -gt 0 -or $reconnect.Count -gt 0) {
    $class = "PROTOCOL_JOIN_SEEN"
    $note = "protocol join/reconnect markers present — if UI still stuck, lean J5 (manual)"
}

Write-Host "CLASS=$class"
Write-Host "NOTE=$note"
Write-Host ""
Write-Host "Manual next: grep invite/join/BUSY/reconnect on M01+M02; assign final J1–J6 in RUN_META."
Write-Host "Do not patch recovery / R2a from this class."

if ($class -eq "INSUFFICIENT") { exit 2 }
exit 0
