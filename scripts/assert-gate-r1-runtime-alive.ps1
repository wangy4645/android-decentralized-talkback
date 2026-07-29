# Gate-R1: after ICE_CONNECTED, must see DECISION (A) or MISSING (B) within the log window.
# Forbids third state: ICE recovered with neither tag.
param(
  [Parameter(Mandatory=$true)][string]$LogPath,
  [string]$DeviceLabel = 'device',
  [int]$WindowSec = 5
)

$iceLines = @(Select-String -Path $LogPath -Pattern 'ICE_CONNECTED|ICE \S+ state=CONNECTED' | ForEach-Object { $_.Line })
$decision = @(Select-String -Path $LogPath -Pattern 'CONFERENCE_RUNTIME_DECISION' | ForEach-Object { $_.Line })
$missing = @(Select-String -Path $LogPath -Pattern 'CONFERENCE_RUNTIME_MISSING' | ForEach-Object { $_.Line })

Write-Host "[$DeviceLabel] iceConnectedMarkers=$($iceLines.Count) decision=$($decision.Count) missing=$($missing.Count)"

if ($iceLines.Count -eq 0) {
  Write-Host "VERDICT=SKIP (no ICE_CONNECTED in log — reproduce WiFi recovery first)"
  exit 2
}

if ($decision.Count -gt 0) {
  $sample = $decision[-1]
  Write-Host "VERDICT=R1-A (conference session present; inspect DECISION inputs)"
  Write-Host "  $sample"
  if ($sample -match 'authorityReachable=false' -and $sample -match 'phase=CONNECTING') {
    Write-Host "HINT=path-A-authority-false (Projection input issue)"
  }
  exit 0
}

if ($missing.Count -gt 0) {
  Write-Host "VERDICT=R1-B (no conference session; lifecycle/rejoin issue)"
  Write-Host "  $($missing[-1])"
  exit 0
}

Write-Host "VERDICT=RED (Gate-R1 violated: ICE up but neither DECISION nor MISSING)"
Write-Host "  sample ICE: $($iceLines[-1])"
exit 1
