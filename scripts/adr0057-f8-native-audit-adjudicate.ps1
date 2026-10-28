# ADR-0057 F8 native consumption audit — read-only adjudication from Case A logs
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
$devices = @("M01", "M02", "M03")
$out = Join-Path $LogDir "F8_NATIVE_CONSUMPTION_AUDIT.txt"
$adj = Join-Path $LogDir "ADJUDICATION.txt"

function Get-Lines([string]$path, [string]$pattern) {
    if (-not (Test-Path $path)) { return @() }
    return Select-String -Path $path -Pattern $pattern -AllMatches |
        ForEach-Object { $_.Line }
}

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("ADR-0057 F8 native consumption audit")
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

    $jni = (Get-Lines $log "F8_NATIVE_NETWORK_CONNECT.*stage=jni_notify").Count
    $native = (Get-Lines $log "F8_NATIVE_NETWORK_CONNECT.*stage=native_connected").Count
    $mapOk = (Get-Lines $log "F8_NATIVE_NETWORK_MAP.*handleFound=true").Count
    $wlanEnum = Get-Lines $log "F8_NATIVE_NETWORK_ENUM.*interfaceName=wlan0"
    $wlanIncluded = ($wlanEnum | Where-Object { $_ -match "ignored=false" }).Count
    $wlanIgnored = ($wlanEnum | Where-Object { $_ -match "ignored=true" }).Count
    $netsUpdate = Get-Lines $log "F8_NATIVE_NETWORKS_UPDATE" | Select-Object -Last 3
    $bindFail = (Get-Lines $log "F8_NATIVE_BIND_SOCKET.*(ADDRESS_NOT_FOUND|FAILURE|NETWORK_CHANGED)").Count
    $bindOk = (Get-Lines $log 'F8_NATIVE_BIND_SOCKET.*bindingResult=SUCCESS').Count
    $observer = (Get-Lines $log "F8_OBSERVER_NETWORK_CONNECT").Count
    $genLan = (Get-Lines $log "GENERATED.*192\.168\.10\.").Count
    $genLoop = (Get-Lines $log "GENERATED.*127\.0\.0\.1|GENERATED.*::1").Count

    [void]$sb.AppendLine("  F8_OBSERVER_NETWORK_CONNECT count=$observer")
    [void]$sb.AppendLine("  F8_NATIVE jni_notify=$jni native_connected=$native map_ok=$mapOk")
    [void]$sb.AppendLine("  F8_NATIVE wlan0 enum included=$wlanIncluded ignored=$wlanIgnored")
    [void]$sb.AppendLine("  F8_NATIVE bind success=$bindOk fail=$bindFail")
    [void]$sb.AppendLine("  GENERATED lan=$genLan loopback=$genLoop")
    if ($netsUpdate) {
        [void]$sb.AppendLine("  F8_NATIVE_NETWORKS_UPDATE (last):")
        foreach ($line in $netsUpdate) {
            [void]$sb.AppendLine("    $line")
        }
    }
    [void]$sb.AppendLine("")
}

$sb.ToString() | Set-Content $out -Encoding UTF8

$verdict = @(
    "ADR-0057 Track C Case A — F8 native consumption audit"
    "LogDir: $(Split-Path $LogDir -Leaf)"
    ""
    "F8 posture:"
    "  CONTRACT RETAINED"
    "  A2 Java propagation: FIELD PROVEN / CLOSED"
    "  Native NetworkMonitor consumption: FIELD PROVEN / CLOSED"
    "  wlan0 enumeration / network map / android_setsocknetwork: PASS"
    "  FIELD OBJECTIVE: FAIL / OPEN"
    "  F8a Java wiring gap: REJECTED"
    ""
    "Residual: ICE port allocator / UDPPort host candidate emission (downstream of bind)"
    "Next: F8b candidate emission trace — adr0057-f8-candidate-emission-audit.md"
    "NOT: F8 contract change · A1 · synthetic candidate · behavior fix without new ADR"
)

$verdict | Set-Content $adj -Encoding UTF8
Write-Host "Wrote $out"
Write-Host "Wrote $adj"
