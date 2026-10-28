# P0.2 G1 — verify installed APK embeds production WebRTC .so (not inst)
param(
    [string]$LogDir = "",
    [string]$ProdArm64 = "e1c28990307c3ed20bb29760e1f30278655d11f4100e52742269b51c8af3cc84",
    [string]$ProdV7 = "3289469ce96907ac031d81b5b68db8ff67a49c6a6b4ef856e3bdf1dbfd445ded"
)

$ErrorActionPreference = "Stop"
$devices = [ordered]@{
    "M01" = @{ Serial = "HTUBB21B09220661"; Abi = "armeabi-v7a"; Ref = $ProdV7 }
    "M02" = @{ Serial = "DSJ-2407041"; Abi = "arm64-v8a"; Ref = $ProdArm64 }
    "M03" = @{ Serial = "MDX0220416001963"; Abi = "arm64-v8a"; Ref = $ProdArm64 }
}
$Package = "com.talkback.appprod"
$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "logs\adr0057-production-smoke-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$forbidden = @(
    "F8_NATIVE_", "F8_PORT_", "F8_CANDIDATE_", "F8_QUERY_DEFAULT_LOCAL",
    "f8_native_trace", "pnsrd-inst-1", "PNSRD_INST"
)
$lines = @("P0.2 G1 — Production Artifact Identity", "verified=$(Get-Date -Format o)", "")
$fail = 0

foreach ($name in $devices.Keys) {
    $serial = $devices[$name].Serial
    $abi = $devices[$name].Abi
    $ref = $devices[$name].Ref
    $state = & $adb -s $serial get-state 2>$null
    if ($state -ne "device") {
        $lines += "$name ($serial): SKIP offline"
        $fail++
        continue
    }
    $apkPath = (& $adb -s $serial shell pm path $Package 2>$null) -replace "^package:", "" -replace "`r", ""
    if (-not $apkPath) {
        $lines += "${name}: FAIL package not installed"
        $fail++
        continue
    }
    $tmp = Join-Path $env:TEMP "g1-$name-$(Get-Random)"
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        & $adb -s $serial pull $apkPath (Join-Path $tmp "app.apk") | Out-Null
        $extract = Join-Path $tmp "extract"
        $zip = Join-Path $tmp "app.zip"
        Copy-Item (Join-Path $tmp "app.apk") $zip -Force
        Expand-Archive -Path $zip -DestinationPath $extract -Force
        $so = Join-Path $extract "lib\$abi\libjingle_peerconnection_so.so"
        if (-not (Test-Path $so)) {
            $lines += "${name}: FAIL missing .so at lib/$abi"
            $fail++
            continue
        }
        $hash = (Get-FileHash $so -Algorithm SHA256).Hash.ToLower()
        $hashOk = ($hash -eq $ref.ToLower())
        $wslSo = ($so -replace '\\', '/') -replace '^([A-Za-z]):', { '/mnt/' + $_.Groups[1].Value.ToLower() }
        $strings = wsl bash -lc "strings '$wslSo' 2>/dev/null" 2>$null
        $forbHit = @()
        foreach ($m in $forbidden) {
            if ($strings -match [regex]::Escape($m)) { $forbHit += $m }
        }
        if ($hashOk -and $forbHit.Count -eq 0) {
            $lines += "${name}: PASS hash=$hash abi=$abi"
        } else {
            $lines += "${name}: FAIL hash=$hash expected=$ref forbidden=$($forbHit -join ',')"
            $fail++
        }
    }
    finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

$lines += ""
$lines += $(if ($fail -eq 0) { "G1_VERDICT=PASS" } else { "G1_VERDICT=FAIL ($fail)" })
$out = Join-Path $LogDir "PRODUCTION_SMOKE_G1.txt"
$lines | Set-Content $out -Encoding UTF8
$lines | ForEach-Object { Write-Host $_ }
exit $(if ($fail -eq 0) { 0 } else { 1 })
