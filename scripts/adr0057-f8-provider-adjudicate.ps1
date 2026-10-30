# ADR-0057 F8b provider audit — read-only adjudication
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
$devices = @("M01", "M02", "M03")
$out = Join-Path $LogDir "F8_PROVIDER_AUDIT.txt"
$adj = Join-Path $LogDir "F8_PROVIDER_ADJUDICATION.txt"

function Get-Lines([string]$path, [string]$pattern) {
    if (-not (Test-Path $path)) { return @() }
    return Select-String -Path $path -Pattern $pattern -AllMatches |
        ForEach-Object { $_.Line }
}

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("ADR-0057 F8b default-local-address provider audit")
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

    $queryV4 = Get-Lines $log 'F8_QUERY_DEFAULT_LOCAL family=2' | Select-Object -Last 3
    $setLocal = Get-Lines $log 'F8_SET_DEFAULT_LOCAL' | Select-Object -Last 2
    $getFail = (Get-Lines $log 'F8_GET_DEFAULT_LOCAL family=2 success=false').Count
    $getOk = (Get-Lines $log 'F8_GET_DEFAULT_LOCAL family=2 success=true').Count
    $lookupFail = (Get-Lines $log 'F8_DEFAULT_LOCAL_ADDRESS.*branch=lookup_failed.*wlan0').Count
    $materialized = (Get-Lines $log 'F8_DEFAULT_LOCAL_ADDRESS.*branch=materialized.*wlan0').Count
    $genLan = (Get-Lines $log "GENERATED.*192\.168\.10\.").Count

    [void]$sb.AppendLine("  F8_QUERY_DEFAULT_LOCAL v4 samples=$($queryV4.Count)")
    [void]$sb.AppendLine("  F8_SET_DEFAULT_LOCAL samples=$($setLocal.Count)")
    [void]$sb.AppendLine("  F8_GET_DEFAULT_LOCAL v4 success=$getOk fail=$getFail")
    [void]$sb.AppendLine("  F8_DEFAULT_LOCAL wlan0 materialized=$materialized lookup_failed=$lookupFail")
    [void]$sb.AppendLine("  GENERATED lan=$genLan")

    $verdict = "UNKNOWN"
    if ($getFail -gt 0 -and $lookupFail -gt 0 -and $materialized -eq 0) {
        $verdict = "query_cache_empty_lookup_failed"
    } elseif ($materialized -gt 0 -or $genLan -gt 0) {
        $verdict = "provider_projection_ok"
    }
    [void]$sb.AppendLine("  PROVIDER_GUESS: $verdict")

    if ($queryV4) {
        [void]$sb.AppendLine("  F8_QUERY_DEFAULT_LOCAL v4 (last):")
        foreach ($line in $queryV4) { [void]$sb.AppendLine("    $line") }
    }
    if ($setLocal) {
        [void]$sb.AppendLine("  F8_SET_DEFAULT_LOCAL (last):")
        foreach ($line in $setLocal) { [void]$sb.AppendLine("    $line") }
    }
    [void]$sb.AppendLine("")
}

$sb.ToString() | Set-Content $out -Encoding UTF8

@(
    "ADR-0057 F8b — default-local-address provider (trace-only)"
    "LogDir: $(Split-Path $LogDir -Leaf)"
    ""
    "F8b: ROOT SEAM CONFIRMED · Residual: QueryDefaultLocalAddress / provider projection"
    "Behavior fix: HOLD (no GetBestIP fallback without audit closure)"
    ""
    "See F8_PROVIDER_AUDIT.txt"
    "Decision tree: talkback/docs/analysis/adr0057-f8-default-local-address-provider-audit.md"
) | Set-Content $adj -Encoding UTF8

Write-Host "Wrote $out"
Write-Host "Wrote $adj"
