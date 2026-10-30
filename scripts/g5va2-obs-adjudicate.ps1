# Parse G5_VA2_ADJ windows and apply G5-VA2 desk thresholds.
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"

function Get-SegmentStats([string]$Path, [string]$Module) {
    $frames = 0
    $raw2 = 0
    $raw3 = 0
    $hang2 = 0
    $hang3 = 0
    $windows = 0
    Select-String -Path $Path -Pattern "G5_VA2_ADJ .* module=$Module " | ForEach-Object {
        if ($_.Line -match 'frames=(\d+).*?raw2=(\d+) raw3=(\d+) hang2=(\d+) hang3=(\d+)') {
            $windows++
            $frames += [int]$matches[1]
            $raw2 += [int]$matches[2]
            $raw3 += [int]$matches[3]
            $hang2 += [int]$matches[4]
            $hang3 += [int]$matches[5]
        }
    }
    if ($frames -eq 0) { return $null }
    return [pscustomobject]@{
        Module = $Module
        Windows = $windows
        Frames = $frames
        Raw2Rate = $raw2 / $frames
        Raw3Rate = $raw3 / $frames
        Hang2Rate = $hang2 / $frames
        Hang3Rate = $hang3 / $frames
    }
}

$idle = $null
$speech = $null
foreach ($label in @("M02", "M03")) {
    $path = Join-Path $LogDir "$label-logcat.txt"
    if (-not (Test-Path $path)) { continue }
    $stats = Get-SegmentStats $path $label
    if ($null -eq $stats) { continue }
    if ($label -eq "M02") { $idle = $stats }
    if ($label -eq "M03") { $speech = $stats }
}

Write-Host "=== G5-VA2 segment stats ==="
if ($idle) {
    Write-Host ("M02 idle: frames={0} hang2={1:P1} hang3={2:P1} raw2={3:P1} raw3={4:P1}" -f `
        $idle.Frames, $idle.Hang2Rate, $idle.Hang3Rate, $idle.Raw2Rate, $idle.Raw3Rate)
} else {
    Write-Host "M02 idle: MISSING"
}
if ($speech) {
    Write-Host ("M03 speech: frames={0} hang2={1:P1} hang3={2:P1} raw2={3:P1} raw3={4:P1}" -f `
        $speech.Frames, $speech.Hang2Rate, $speech.Hang3Rate, $speech.Raw2Rate, $speech.Raw3Rate)
} else {
    Write-Host "M03 speech: MISSING"
}

$idleMax = 0.10
$speechMin = 0.70
$verdict = "INCONCLUSIVE"
$rationale = "missing segment"
if ($idle -and $speech) {
    if ($idle.Hang3Rate -le $idleMax -and $speech.Hang3Rate -ge $speechMin) {
        $verdict = "ACCEPT_MODE_3"
        $rationale = "mode3 idle hang <=10% and speech hang >=70%"
    } elseif ($idle.Hang3Rate -gt $idleMax) {
        $verdict = "REJECT_WEBRTC_VAD_ONLY"
        $rationale = "mode3 idle hang still >10%"
    } elseif ($speech.Hang3Rate -lt $speechMin) {
        $verdict = "REJECT_WEBRTC_VAD_ONLY"
        $rationale = "mode3 speech hang <70%"
    } else {
        $rationale = "thresholds not met"
    }
}

Write-Host "=== VERDICT: $verdict ==="
Write-Host $rationale
@(
    "verdict=$verdict"
    "rationale=$rationale"
    "adjudicated=$(Get-Date -Format o)"
) | Set-Content -Encoding utf8 (Join-Path $LogDir "g5va2-verdict.txt")
