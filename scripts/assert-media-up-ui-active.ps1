# Assert M03-style stuck Connecting: media/topology up but no ACTIVE conference UI projection.
# RED if TOPOLOGY sessionAccepted=true AND ICE_CONNECTED>0 AND (no CONFERENCE_RUNTIME phase=ACTIVE)
#    AND (no Meeting pill phase=ACTIVE).
param(
  [Parameter(Mandatory=$true)][string]$LogPath,
  [string]$DeviceLabel = 'M03'
)
$topo = (Select-String -Path $LogPath -Pattern 'TOPOLOGY_SNAPSHOT.*sessionAccepted=true' | Measure-Object).Count
$ice = (Select-String -Path $LogPath -Pattern 'ICE_CONNECTED' | Measure-Object).Count
$projActive = (Select-String -Path $LogPath -Pattern 'CONFERENCE_RUNTIME_PROJECTION.*phase=ACTIVE' | Measure-Object).Count
$projAny = (Select-String -Path $LogPath -Pattern 'CONFERENCE_RUNTIME_PROJECTION' | Measure-Object).Count
$pillActive = (Select-String -Path $LogPath -Pattern 'Meeting pill:.*phase=ACTIVE' | Measure-Object).Count
$projConnecting = (Select-String -Path $LogPath -Pattern 'CONFERENCE_RUNTIME_PROJECTION.*phase=CONNECTING' | Measure-Object).Count
Write-Host "[$DeviceLabel] topoAccepted=$topo iceConnected=$ice projAny=$projAny projActive=$projActive projConnecting=$projConnecting pillActive=$pillActive"
if ($topo -gt 0 -and $ice -gt 0 -and $projActive -eq 0 -and $pillActive -eq 0) {
  Write-Host "VERDICT=RED (media/topology up, no ACTIVE conference UI projection)"
  exit 1
}
Write-Host "VERDICT=GREEN"
exit 0
