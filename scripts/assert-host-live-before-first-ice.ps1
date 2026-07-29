# Assert host LIVE timing vs ADR-0020 G1 / PRD story 2.
# RED if host projects CONNECTING while connected=0 (solo / pre-first-invitee-ICE).
param(
  [Parameter(Mandatory=$true)][string]$LogPath
)
$lines = Select-String -Path $LogPath -Pattern 'CONFERENCE_RUNTIME_PROJECTION.*host=true' | ForEach-Object { $_.Line }
$red = @($lines | Where-Object { $_ -match 'phase=CONNECTING' -and $_ -match 'connected=0' })
$activeFirst = @($lines | Where-Object { $_ -match 'phase=ACTIVE' -and $_ -match 'connected=1' })
$awaitIce = (Select-String -Path $LogPath -Pattern 'awaiting_first_invitee_ice' | Measure-Object).Count
Write-Host "host_CONNECTING_connected0=$($red.Count)"
Write-Host "host_ACTIVE_connected1=$($activeFirst.Count)"
Write-Host "awaiting_first_invitee_ice=$awaitIce"
if ($red.Count -gt 0) {
  Write-Host "VERDICT=RED (ADR-0020 G1 violated: host CONNECTING with zero remote ICE)"
  $red | Select-Object -First 2 | ForEach-Object { Write-Host "  $_" }
  exit 1
}
Write-Host "VERDICT=GREEN"
exit 0
