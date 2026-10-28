# ADR-0057 F8 — read-only static diff: armeabi-v7a (stock) vs arm64-v8a (instrumentation)
param(
    [string]$OutDir = "",
    [string]$V7aSo = "",
    [string]$Arm64So = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$hybridAar = Join-Path $repoRoot "libs\pnsrd-inst-1-stream-webrtc-android.aar"
$mavenAar = Join-Path $repoRoot "local-maven\io\getstream\stream-webrtc-android\1.3.10-pnsrd-inst-1\stream-webrtc-android-1.3.10-pnsrd-inst-1.aar"
$deliveryAudit = Join-Path $repoRoot "webrtc-instrumentation\pnsrd-inst-1\artifacts\runtime-delivery-audit-20260826"

if (-not $OutDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRoot "logs\adr0057-f8-v7a-arm64-static-diff-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Resolve-SoPath {
    param([string]$Explicit, [string]$AbiLeaf)
    if ($Explicit -and (Test-Path $Explicit)) { return $Explicit }
    $artifactSo = Join-Path $repoRoot "webrtc-instrumentation\pnsrd-inst-1\artifacts\extracted\jni\$AbiLeaf\libjingle_peerconnection_so.so"
    if (Test-Path $artifactSo) { return $artifactSo }
    foreach ($aar in @($hybridAar, $mavenAar)) {
        if (-not (Test-Path $aar)) { continue }
        $extractRoot = Join-Path $OutDir "extract-$AbiLeaf"
        if (-not (Test-Path $extractRoot)) {
            New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            [System.IO.Compression.ZipFile]::ExtractToDirectory($aar, $extractRoot)
        }
        $candidate = Join-Path $extractRoot "jni\$AbiLeaf\libjingle_peerconnection_so.so"
        if (Test-Path $candidate) { return $candidate }
    }
    $deliverySo = Join-Path $deliveryAudit "extract-so\lib\$AbiLeaf\libjingle_peerconnection_so.so"
    if (Test-Path $deliverySo) { return $deliverySo }
    return $null
}

$v7So = Resolve-SoPath $V7aSo "armeabi-v7a"
$arm64So = Resolve-SoPath $Arm64So "arm64-v8a"
if (-not $v7So) { throw "v7a .so not found — pass -V7aSo or build/extract hybrid AAR with armeabi-v7a" }
if (-not $arm64So) { throw "arm64 .so not found — pass -Arm64So or build instrumentation AAR" }

$patterns = @(
    "WebRTC-",
    "mdns",
    "mDNS",
    "candidate",
    "Candidate",
    "PORTALLOCATOR",
    "DisableAdapter",
    "local.network",
    "privacy",
    "pnsrd-inst",
    "F8_"
)

function Get-Strings([string]$path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $text = [System.Text.Encoding]::ASCII.GetString($bytes)
    $matches = [regex]::Matches($text, '[\x20-\x7E]{6,}')
    return $matches | ForEach-Object { $_.Value } | Sort-Object -Unique
}

$v7Strings = Get-Strings $v7So
$arm64Strings = Get-Strings $arm64So
$v7Set = [System.Collections.Generic.HashSet[string]]::new([string[]]$v7Strings)
$arm64Set = [System.Collections.Generic.HashSet[string]]::new([string[]]$arm64Strings)

$onlyV7 = $v7Strings | Where-Object { -not $arm64Set.Contains($_) }
$onlyArm64 = $arm64Strings | Where-Object { -not $v7Set.Contains($_) }

$outFile = Join-Path $OutDir "F8_V7A_ARM64_STATIC_DIFF.txt"
$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("ADR-0057 F8 v7a vs arm64 static string diff (read-only)")
[void]$sb.AppendLine("v7a: $v7So")
[void]$sb.AppendLine("arm64: $arm64So")
[void]$sb.AppendLine("")

$v7Hash = (Get-FileHash $v7So -Algorithm SHA256).Hash
$arm64Hash = (Get-FileHash $arm64So -Algorithm SHA256).Hash
[void]$sb.AppendLine("sha256 v7a=$v7Hash")
[void]$sb.AppendLine("sha256 arm64=$arm64Hash")
[void]$sb.AppendLine("")

foreach ($pat in $patterns) {
    $v7Hits = $v7Strings | Where-Object { $_ -match $pat } | Select-Object -First 30
    $a64Hits = $arm64Strings | Where-Object { $_ -match $pat } | Select-Object -First 30
    [void]$sb.AppendLine("=== pattern: $pat ===")
    [void]$sb.AppendLine("-- v7a ($($v7Hits.Count) shown) --")
    foreach ($h in $v7Hits) { [void]$sb.AppendLine("  $h") }
    [void]$sb.AppendLine("-- arm64 ($($a64Hits.Count) shown) --")
    foreach ($h in $a64Hits) { [void]$sb.AppendLine("  $h") }
    $v7OnlyPat = $onlyV7 | Where-Object { $_ -match $pat } | Select-Object -First 10
    $a64OnlyPat = $onlyArm64 | Where-Object { $_ -match $pat } | Select-Object -First 10
    if ($v7OnlyPat -or $a64OnlyPat) {
        [void]$sb.AppendLine("-- v7a-only (sample) --")
        foreach ($h in $v7OnlyPat) { [void]$sb.AppendLine("  $h") }
        [void]$sb.AppendLine("-- arm64-only (sample) --")
        foreach ($h in $a64OnlyPat) { [void]$sb.AppendLine("  $h") }
    }
    [void]$sb.AppendLine("")
}

[void]$sb.AppendLine("NOTE: v7a may be stock Maven slice; arm64 is instrumentation overlay.")
[void]$sb.AppendLine("ABI asymmetry is a control variable — not proof of ABI root cause.")
$sb.ToString() | Set-Content $outFile -Encoding UTF8
Write-Host "Wrote $outFile"
