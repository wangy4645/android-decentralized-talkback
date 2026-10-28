# ADR-0057 F8b candidate emission audit — read-only adjudication from Case A logs
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
$devices = @("M01", "M02", "M03")
$out = Join-Path $LogDir "F8_CANDIDATE_EMISSION_AUDIT.txt"
$adj = Join-Path $LogDir "F8_CANDIDATE_EMISSION_ADJUDICATION.txt"

function Get-Lines([string]$path, [string]$pattern) {
    if (-not (Test-Path $path)) { return @() }
    return Select-String -Path $path -Pattern $pattern -AllMatches |
        ForEach-Object { $_.Line }
}

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("ADR-0057 F8b host candidate emission audit")
[void]$sb.AppendLine("LogDir: $(Split-Path $LogDir -Leaf)")
[void]$sb.AppendLine("")

foreach ($name in $devices) {
    $log = Join-Path $LogDir "$name-talkback.log"
    [void]$sb.AppendLine("=== $name ===")
    if (-not (Test-Path $log)) {
        [void]$sb.AppendLine("  LOG_MISSING")
        [void]$sb.AppendLine("")
        continue
    }

    $bindOk = (Get-Lines $log 'F8_NATIVE_BIND_SOCKET.*bindingResult=SUCCESS').Count
    $portWlan = (Get-Lines $log 'F8_PORT_CREATED.*networkName=wlan0').Count
    $prepareLan = (Get-Lines $log 'F8_PORT_PREPARE.*192\.168\.10\.').Count
    $createdLan = (Get-Lines $log 'F8_CANDIDATE_CREATED.*address=192\.168\.10\.').Count
    $surfacedLan = (Get-Lines $log 'F8_CANDIDATE_SURFACED.*address=192\.168\.10\.').Count
    $filterReject = Get-Lines $log 'F8_CANDIDATE_FILTER.*verdict=rejected' | Select-Object -First 5
    $filterXform = Get-Lines $log 'F8_CANDIDATE_FILTER.*verdict=transformed' | Select-Object -First 5
    $prune = Get-Lines $log 'F8_CANDIDATE_PRUNE' | Select-Object -First 5
    $genLan = (Get-Lines $log "GENERATED.*192\.168\.10\.").Count
    $genLoop = (Get-Lines $log "GENERATED.*127\.0\.0\.1|GENERATED.*::1").Count

    [void]$sb.AppendLine("  F8_NATIVE bind_success=$bindOk")
    [void]$sb.AppendLine("  F8_PORT_CREATED wlan0=$portWlan")
    [void]$sb.AppendLine("  F8_PORT_PREPARE lan=$prepareLan")
    [void]$sb.AppendLine("  F8_CANDIDATE_CREATED lan=$createdLan")
    [void]$sb.AppendLine("  F8_CANDIDATE_SURFACED lan=$surfacedLan")
    [void]$sb.AppendLine("  GENERATED lan=$genLan loopback=$genLoop")

    $loss = "UNKNOWN"
    if ($bindOk -gt 0 -and $portWlan -eq 0) { $loss = "after_bind_before_port_created" }
    elseif ($portWlan -gt 0 -and $prepareLan -eq 0) { $loss = "port_created_before_prepare" }
    elseif ($prepareLan -gt 0 -and $createdLan -eq 0) { $loss = "prepare_before_candidate_created" }
    elseif ($createdLan -gt 0 -and $surfacedLan -eq 0) { $loss = "created_before_surfaced" }
    elseif ($surfacedLan -gt 0 -and $genLan -eq 0) { $loss = "surfaced_before_java_generated" }
    elseif ($genLan -gt 0) { $loss = "NONE (lan host present)" }
    elseif ($bindOk -gt 0 -and $genLoop -gt 0) { $loss = "bind_ok_loopback_only" }
    [void]$sb.AppendLine("  FIRST_LOSS_GUESS: $loss")

    if ($filterReject) {
        [void]$sb.AppendLine("  F8_CANDIDATE_FILTER rejected (sample):")
        foreach ($line in $filterReject) { [void]$sb.AppendLine("    $line") }
    }
    if ($filterXform) {
        [void]$sb.AppendLine("  F8_CANDIDATE_FILTER transformed (sample):")
        foreach ($line in $filterXform) { [void]$sb.AppendLine("    $line") }
    }
    if ($prune) {
        [void]$sb.AppendLine("  F8_CANDIDATE_PRUNE (sample):")
        foreach ($line in $prune) { [void]$sb.AppendLine("    $line") }
    }
    [void]$sb.AppendLine("")
}

$sb.ToString() | Set-Content $out -Encoding UTF8

$verdict = @(
    "ADR-0057 F8b — Host Candidate Emission Gap (trace-only)"
    "LogDir: $(Split-Path $LogDir -Leaf)"
    ""
    "F8 posture:"
    "  CONTRACT RETAINED"
    "  A2 Java propagation: FIELD PROVEN / CLOSED"
    "  Native NetworkMonitor consumption: FIELD PROVEN / CLOSED"
    "  wlan0 enumeration / network map / android_setsocknetwork: PASS"
    "  FIELD OBJECTIVE: FAIL / OPEN"
    ""
    "Residual: ICE port allocator / UDPPort host candidate emission"
    "F9: NOT OPEN"
    ""
    "See F8_CANDIDATE_EMISSION_AUDIT.txt for per-device FIRST_LOSS_GUESS."
    "Decision tree: talkback/docs/analysis/adr0057-f8-candidate-emission-audit.md"
    ""
    "NOT: F8 contract change · A1 · registry/bridge · socket binding · behavior fix"
)

$verdict | Set-Content $adj -Encoding UTF8
Write-Host "Wrote $out"
Write-Host "Wrote $adj"
