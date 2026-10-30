# ADR-0056 Phase 4-8 — Field shadow evidence adjudication (evidence availability only; NOT migration sign-off).
param(
    [Parameter(Mandatory = $true)]
    [string]$RunId
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$logDir = Join-Path $repoRoot "logs/$RunId"
$metaPath = Join-Path $logDir "RUN_META.txt"
$evidencePath = Join-Path $logDir "SHADOW_FIELD_EVIDENCE.txt"
$outPath = Join-Path $logDir "ADJUDICATION.txt"

if (-not (Test-Path $metaPath)) {
    throw "Missing RUN_META.txt under $logDir"
}

function Read-KvFile([string]$Path) {
    $map = @{}
    Get-Content $Path | ForEach-Object {
        if ($_ -match "^([^=]+)=(.*)$") {
            $map[$Matches[1]] = $Matches[2]
        }
    }
    return $map
}

$meta = Read-KvFile $metaPath
$lines = @()
$lines += "phase=4-8"
$lines += "runId=$RunId"
$lines += "adjudicated=$(Get-Date -Format o)"
$lines += "migrationAuthorization=NOT_GRANTED"
$lines += "productionBehavior=UNCHANGED_EXPECTED"

if (-not (Test-Path $evidencePath)) {
    $lines += "evidenceStatus=MISSING"
    $lines += "verdict=INCOMPLETE — no SHADOW_FIELD_EVIDENCE.txt"
} else {
    $text = Get-Content $evidencePath -Raw
    $lines += "evidenceStatus=AVAILABLE"
    $lines += "containsBundleStart=$($text -match 'SHADOW_FIELD_RUN_EVIDENCE')"
    $lines += "containsBundleEnd=$($text -match 'END_SHADOW_FIELD_RUN_EVIDENCE')"

    $stop = ([regex]::Match($text, "stopConditionViolations=([^\r\n]+)")).Groups[1].Value
    if ($stop -and $stop -ne "") {
        $lines += "stopConditionViolations=$stop"
        $lines += "verdict=STOP — control contamination or authority violation"
    } else {
        $lines += "stopConditionViolations="
        $lines += "verdict=EVIDENCE_COLLECTED — review mismatch taxonomy; migration still pending"
    }

    foreach ($cat in @(
        "TOPOLOGY_FACT_MISMATCH",
        "EDGE_IDENTITY_MISMATCH",
        "LIFECYCLE_ORDER_MISMATCH",
        "RECOVERY_PROJECTION_MISMATCH",
        "UNKNOWN_FACT"
    )) {
        $m = [regex]::Match($text, "mismatchCategory\.$cat=(\d+)")
        if ($m.Success) {
            $lines += "mismatch.$cat=$($m.Groups[1].Value)"
        }
    }

    $unknown = ([regex]::Match($text, "unknownFactCount=(\d+)")).Groups[1].Value
    if ($unknown) { $lines += "unknownFactCount=$unknown" }
    $ordering = ([regex]::Match($text, "timelineOrderingViolations=(\d+)")).Groups[1].Value
    if ($ordering) { $lines += "orderingViolations=$ordering" }
    $recovery = ([regex]::Match($text, "recoveryPredictionCoverage=(\d+)")).Groups[1].Value
    if ($recovery) { $lines += "recoveryPredictionCoverage=$recovery" }
}

$lines | Set-Content $outPath -Encoding UTF8
Write-Host "Adjudication written: $outPath"
Get-Content $outPath | ForEach-Object { Write-Host $_ }
