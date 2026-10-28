# RCA5-A focused adjudication. Exit: 0 PASS · 1 FAIL · 2 INVALID
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"

function Get-ModuleLog([string]$Module) {
    foreach ($name in @("$Module-logcat.txt", "$Module-talkback.log")) {
        $p = Join-Path $LogDir $name
        if (Test-Path $p) { return $p }
    }
    return $null
}

function Read-KvFile([string]$Path) {
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    Get-Content $Path | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') { $map[$Matches[1].Trim()] = $Matches[2].Trim() }
    }
    return $map
}

function Count-Matches([string]$Path, [string]$Pattern) {
    if (-not $Path -or -not (Test-Path $Path)) { return 0 }
    return @(Select-String -Path $Path -Pattern $Pattern -ErrorAction SilentlyContinue).Count
}

$m01 = Get-ModuleLog "M01"
$m02 = Get-ModuleLog "M02"
$rc1 = Read-KvFile (Join-Path $LogDir "RC1_GATE.txt")
$manifest = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_MANIFEST.txt")
$sessionId = if ($manifest['hostSessionId']) { $manifest['hostSessionId'] } elseif ($rc1['hostSessionId']) { $rc1['hostSessionId'] } else { "" }

if (-not $m01 -or -not $m02) {
    @("RCA5A_RESULT=INVALID", "reason=missing_log_capture") | Set-Content (Join-Path $LogDir "RCA5A_RESULT.txt") -Encoding utf8
    Write-Host "RCA5A verdict=INVALID reason=missing_log_capture"
    exit 2
}

# Host TX (M01) or peer RX ingress (M02) both prove media path activity.
$m01Tx = (Count-Matches $m01 "SHADOW_TX_ACTIVITY|INGRESS_ACTIVITY|WIRE_INGRESS .*outcome=APPLIED") -gt 0
$m02Ingress = (Count-Matches $m02 "INGRESS_ACTIVITY|INGRESS_FUNNEL") -gt 0
$mediaPathOk = $m01Tx -or $m02Ingress
$cmeCount = Count-Matches $m02 "SHADOW_PLAYOUT_TICK_FAILED.*ConcurrentModificationException"
$playoutCycles = Count-Matches $m02 "SHADOW_PLAYOUT_CYCLE"
$cutoverOk = (Count-Matches $m02 "RC1_FIELD outcome=CUTOVER_SUCCEEDED session=$sessionId") -gt 0
$multicastActive = (Count-Matches $m02 "AUDIBLE_OWNERSHIP_STATE session=$sessionId state=MULTICAST_ACTIVE") -gt 0
# Prefer production writes under MULTICAST_PRODUCTION; probe active=true alone is too early (often 0 at arm).
$prodFromCycle = Select-String -Path $m02 -Pattern "SHADOW_PLAYOUT_CYCLE sessionId=$sessionId.*successfulPlayoutWrites=([0-9]+).*audioTrackOwner=MULTICAST_PRODUCTION|audioTrackOwner=MULTICAST_PRODUCTION.*successfulPlayoutWrites=([0-9]+)" -ErrorAction SilentlyContinue |
    ForEach-Object {
        if ($_.Line -match 'successfulPlayoutWrites=([0-9]+)') { [long]$Matches[1] }
    }
$prodWritesProbe = Select-String -Path $m02 -Pattern "MULTICAST_AUDIBLE_PROBE session=$sessionId .*successfulProductionWrites=([0-9]+)" -ErrorAction SilentlyContinue |
    ForEach-Object { if ($_.Line -match 'successfulProductionWrites=([0-9]+)') { [long]$Matches[1] } }
$maxProdWrites = @(
    @($prodFromCycle)
    @($prodWritesProbe)
) | Where-Object { $_ -ne $null } | Measure-Object -Maximum | Select-Object -ExpandProperty Maximum
if ($null -eq $maxProdWrites) { $maxProdWrites = 0 }

$playoutWritesGrowing = $false
$cycleLines = Select-String -Path $m02 -Pattern "SHADOW_PLAYOUT_CYCLE sessionId=$sessionId.*successfulPlayoutWrites=([0-9]+)" -ErrorAction SilentlyContinue
if ($cycleLines -and $cycleLines.Count -ge 2) {
    $first = [long]($cycleLines[0].Line -replace '.*successfulPlayoutWrites=([0-9]+).*', '$1')
    $last = [long]($cycleLines[-1].Line -replace '.*successfulPlayoutWrites=([0-9]+).*', '$1')
    $playoutWritesGrowing = ($last -gt $first)
}

$queuedCount = Count-Matches $m02 "INGRESS_FUNNEL.*jitterOutcome=QUEUED"
$reorderCount = Count-Matches $m02 "INGRESS_FUNNEL.*jitterOutcome=REORDER_DISPLACEMENT_EXCEEDED"

$checks = [ordered]@{
    MEDIA_PATH = $mediaPathOk
    NO_CME = ($cmeCount -eq 0)
    PLAYOUT_CYCLES = ($playoutCycles -ge 3)
    PLAYOUT_WRITES_GROWING = ($playoutWritesGrowing -or $maxProdWrites -gt 0)
    CUTOVER_SUCCEEDED = $cutoverOk
    MULTICAST_ACTIVE = $multicastActive
    PRODUCTION_WRITES_GT0 = ($maxProdWrites -gt 0)
}

$failures = @($checks.Keys | Where-Object { -not $checks[$_] })
$verdict = if ($failures.Count -eq 0) { "PASS" } else { "FAIL" }

$lines = @(
    "RCA5A_PLAYOUT_FOCUSED"
    "sessionId=$sessionId"
    "verdict=$verdict"
    "MEDIA_PATH=$($checks.MEDIA_PATH) m01Tx=$m01Tx m02Ingress=$m02Ingress"
    "NO_CME=$($checks.NO_CME) cmeCount=$cmeCount"
    "PLAYOUT_CYCLES=$($checks.PLAYOUT_CYCLES) count=$playoutCycles"
    "PLAYOUT_WRITES_GROWING=$($checks.PLAYOUT_WRITES_GROWING)"
    "CUTOVER_SUCCEEDED=$($checks.CUTOVER_SUCCEEDED)"
    "MULTICAST_ACTIVE=$($checks.MULTICAST_ACTIVE)"
    "PRODUCTION_WRITES_GT0=$($checks.PRODUCTION_WRITES_GT0) maxProdWrites=$maxProdWrites"
    "INGRESS_FUNNEL_QUEUED=$queuedCount REORDER=$reorderCount"
    "failures=$($failures -join ',')"
    "RCA5A_RESULT=$verdict"
    $(if ($verdict -eq "PASS") { "Next=RCA4_MEMBERSHIP_PUBLICATION_THEN_G2" } else { "Next=RCA5B2_DEBUG_OR_RETRY" })
)
$lines -join "`n" | Set-Content (Join-Path $LogDir "RCA5A_RESULT.txt") -Encoding utf8
Write-Host ($lines -join "`n")
exit $(if ($verdict -eq "PASS") { 0 } else { 1 })
