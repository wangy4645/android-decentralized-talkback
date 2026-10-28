# ADR-0057 F8b ANY-address materialization — read-only adjudication
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
$devices = @("M01", "M02", "M03")
$out = Join-Path $LogDir "F8_MATERIALIZATION_AUDIT.txt"
$adj = Join-Path $LogDir "F8_MATERIALIZATION_ADJUDICATION.txt"

function Get-Lines([string]$path, [string]$pattern) {
    if (-not (Test-Path $path)) { return @() }
    return Select-String -Path $path -Pattern $pattern -AllMatches |
        ForEach-Object { $_.Line }
}

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("ADR-0057 F8b ANY-address materialization audit")
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

    $bindLan = Get-Lines $log 'F8_SOCKET_BIND.*requestedLocalAddress=192\.168\.10\.' | Select-Object -First 5
    $bindAnyEffective = (Get-Lines $log 'F8_SOCKET_BIND.*requestedLocalAddress=192\.168\.10\..*effectiveBindAddress=0\.0\.0\.0').Count
    $bindSuccessNb = (Get-Lines $log 'F8_SOCKET_BIND.*requestedLocalAddress=192\.168\.10\..*networkBindResult=0').Count
    $getsockAny = (Get-Lines $log 'F8_SOCKET_GETSOCKNAME.*returnedLocalAddress=0\.0\.0\.0').Count
    $dla = Get-Lines $log 'F8_DEFAULT_LOCAL_ADDRESS' | Select-Object -First 8
    $dlaSkip = (Get-Lines $log 'F8_DEFAULT_LOCAL_ADDRESS.*branch=skip_').Count
    $dlaMat = (Get-Lines $log 'F8_DEFAULT_LOCAL_ADDRESS.*branch=materialized').Count
    $dlaFail = (Get-Lines $log 'F8_DEFAULT_LOCAL_ADDRESS.*branch=provider_').Count
    $finalLan = (Get-Lines $log 'F8_PORT_ADDRESS_FINAL.*address=192\.168\.10\.').Count
    $finalAny = (Get-Lines $log 'F8_PORT_ADDRESS_FINAL.*address=0\.0\.0\.0').Count
    $genLan = (Get-Lines $log "GENERATED.*192\.168\.10\.").Count

    [void]$sb.AppendLine("  F8_SOCKET_BIND lan→SUCCESS binder=$bindSuccessNb lan→effectiveANY=$bindAnyEffective")
    [void]$sb.AppendLine("  F8_SOCKET_GETSOCKNAME any=$getsockAny")
    [void]$sb.AppendLine("  F8_DEFAULT_LOCAL_ADDRESS skip=$dlaSkip materialized=$dlaMat provider_fail=$dlaFail")
    [void]$sb.AppendLine("  F8_PORT_ADDRESS_FINAL lan=$finalLan any=$finalAny")
    [void]$sb.AppendLine("  GENERATED lan=$genLan")

    $loss = "UNKNOWN"
    if ($bindSuccessNb -gt 0 -and $bindAnyEffective -gt 0 -and $dlaMat -eq 0 -and $finalAny -gt 0) {
        $loss = "binder_success_rewrites_ANY_then_materialization_failed"
    } elseif ($dlaSkip -gt 0 -and $finalAny -gt 0) {
        $loss = "default_local_address_skipped"
    } elseif ($dlaFail -gt 0 -and $finalAny -gt 0) {
        $loss = "provider_lookup_failed"
    } elseif ($dlaMat -gt 0 -and $finalAny -gt 0) {
        $loss = "materialized_then_overwritten"
    } elseif ($finalLan -gt 0 -or $genLan -gt 0) {
        $loss = "NONE (lan host present)"
    }
    [void]$sb.AppendLine("  FIRST_LOSS_GUESS: $loss")

    if ($bindLan) {
        [void]$sb.AppendLine("  F8_SOCKET_BIND sample:")
        foreach ($line in $bindLan) { [void]$sb.AppendLine("    $line") }
    }
    if ($dla) {
        [void]$sb.AppendLine("  F8_DEFAULT_LOCAL_ADDRESS sample:")
        foreach ($line in $dla) { [void]$sb.AppendLine("    $line") }
    }
    [void]$sb.AppendLine("")
}

$sb.ToString() | Set-Content $out -Encoding UTF8

@(
    "ADR-0057 F8b — ANY-address materialization (trace-only)"
    "LogDir: $(Split-Path $LogDir -Leaf)"
    ""
    "F8: CONTRACT RETAINED · NetworkMonitor/bind FIELD PROVEN/CLOSED · filter BEHAVIOR CORRECT"
    "F8b: ROOT SEAM CONFIRMED · FIELD OBJECTIVE FAIL/OPEN"
    "Residual: UDPPort local-address materialization (ANY → concrete wlan0 IP)"
    "F9: NOT OPEN · Behavior fix: NOT AUTHORIZED"
    ""
    "See F8_MATERIALIZATION_AUDIT.txt"
    "Decision tree: talkback/docs/analysis/adr0057-f8-any-address-materialization-audit.md"
) | Set-Content $adj -Encoding UTF8

Write-Host "Wrote $out"
Write-Host "Wrote $adj"
