# PR-1 soak 日志汇总：F1_BREAK / PTT_METRIC 分位数 / CANONICAL_MISMATCH
# 用法: .\scripts\soak-summarize.ps1 -LogDir "d:\workspace\project\talkback\logs-soak-xxxx"

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

if (-not (Test-Path $LogDir)) {
    Write-Error "LogDir not found: $LogDir"
    exit 1
}

function Get-MetricMs {
    param([string]$Text, [string]$Pattern)
    $vals = [regex]::Matches($Text, $Pattern) | ForEach-Object {
        if ($_.Groups.Count -ge 2) { [int]$_.Groups[1].Value }
    }
    return ,$vals
}

function Percentile([int[]]$Sorted, [double]$P) {
    if ($Sorted.Count -eq 0) { return $null }
    $idx = [math]::Ceiling($P / 100.0 * $Sorted.Count) - 1
    if ($idx -lt 0) { $idx = 0 }
    if ($idx -ge $Sorted.Count) { $idx = $Sorted.Count - 1 }
    return $Sorted[$idx]
}

Write-Host "=== Soak summary: $LogDir ==="
Write-Host ""

$totalF1 = 0
$totalMismatch = 0
$allGrantDelay = [System.Collections.Generic.List[int]]::new()
$allCaptureDelay = [System.Collections.Generic.List[int]]::new()

Get-ChildItem (Join-Path $LogDir "*.txt") | ForEach-Object {
    $name = $_.Name
    $raw = Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue
    if (-not $raw) { $raw = "" }

    $f1 = ([regex]::Matches($raw, "INVARIANT_F1_BREAK")).Count
    $mismatch = ([regex]::Matches($raw, "CANONICAL_MISMATCH")).Count
    $pttDown = ([regex]::Matches($raw, "PTT_TIMING event=PTT_DOWN")).Count
    $grant = ([regex]::Matches($raw, "PTT_TIMING event=GRANT_APPLIED")).Count
    $capture = ([regex]::Matches($raw, "PTT_TIMING event=captureON")).Count

    $totalF1 += $f1
    $totalMismatch += $mismatch

    Get-MetricMs $raw "PTT_METRIC session=[^\s]+ grantDelay=(\d+)ms" | ForEach-Object { $allGrantDelay.Add($_) }
    Get-MetricMs $raw "PTT_METRIC session=[^\s]+ captureDelay=(\d+)ms" | ForEach-Object { $allCaptureDelay.Add($_) }

    $capWithoutGrant = if ($capture -gt $grant) { "WARN" } else { "ok" }
    Write-Host "$name  F1_BREAK=$f1  CANONICAL_MISMATCH=$mismatch  PTT_DOWN=$pttDown  GRANT=$grant  captureON=$capture  cap/grant=$capWithoutGrant"
}

Write-Host ""
Write-Host "--- Gate ---"
Write-Host "F1_BREAK total     : $totalF1 (gate: 0)"
Write-Host "CANONICAL_MISMATCH : $totalMismatch (gate: 0)"

if ($allGrantDelay.Count -gt 0) {
    $g = $allGrantDelay | Sort-Object
    Write-Host "grantDelay ms      : P50=$(Percentile $g 50) P95=$(Percentile $g 95) P99=$(Percentile $g 99) n=$($g.Count) (gate P95<500 P99<1000)"
}
if ($allCaptureDelay.Count -gt 0) {
    $c = $allCaptureDelay | Sort-Object
    Write-Host "captureDelay ms    : P50=$(Percentile $c 50) P95=$(Percentile $c 95) P99=$(Percentile $c 99) n=$($c.Count)"
}
