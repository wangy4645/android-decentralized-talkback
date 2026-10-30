# PRODUCT-CHAIN 2–3 member field soak — directed launcher
param(
    [ValidateSet("prep", "start", "pull", "smoke")]
    [string]$Step = "smoke",
    [string]$RunId = "",
    [int]$DurationSec = 120,
    [string[]]$Devices = @("M01", "M02", "M03")
)

$ErrorActionPreference = "Stop"
if ($Devices.Count -eq 1 -and $Devices[0] -match ",") {
    $Devices = $Devices[0].Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
}

$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = (Get-Command adb).Source
$TestPackage = "com.talkback.test"
$TestClass = "com.talkback.lab.conference.ProductChainFieldSoakInstrumentedTest"

$deviceMap = @{
    "M01" = @{ Serial = "HTUBB21B09220661"; Module = "M01" }
    "M02" = @{ Serial = "DSJ-2407041"; Module = "M02" }
    "M03" = @{ Serial = "MDX0220416001963"; Module = "M03" }
}

if (-not $RunId) {
    $RunId = "product-chain-field-soak-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}
$outDir = Join-Path $repoRoot "logs\$RunId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Resolve-Serial([string]$Name) {
    $usb = $deviceMap[$Name].Serial
    $wirelessFile = Join-Path $repoRoot "logs\field-device-wireless.json"
    if (Test-Path $wirelessFile) {
        $json = Get-Content $wirelessFile -Raw | ConvertFrom-Json
        $hit = $json | Where-Object { $_.name -eq $Name } | Select-Object -First 1
        if ($hit -and $hit.wireless) {
            $state = (& $adb -s $hit.wireless get-state 2>&1 | Out-String).Trim()
            if ($state -eq "device") { return $hit.wireless }
        }
    }
    return $usb
}

function Invoke-Prep {
    & (Join-Path $PSScriptRoot "adr0060-phase1-field-device-prep.ps1") -Devices $Devices
}

function Invoke-Start {
    Write-Host "RUN_ID=$RunId DURATION=$DurationSec DEVICES=$($Devices -join ',')"
    foreach ($name in $Devices) {
        $serial = Resolve-Serial $name
        $module = $deviceMap[$name].Module
        Write-Host "START $name serial=$serial module=$module"
        & $adb -s $serial shell am instrument -w -r `
            -e class $TestClass `
            -e runId $RunId `
            -e durationSec $DurationSec `
            -e deviceLabel $name `
            -e localModuleId $module `
            -e leaveRejoin true `
            "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
    }
}

function Invoke-Pull {
    foreach ($name in $Devices) {
        $serial = Resolve-Serial $name
        $remoteRel = "files/product-chain-field-soak-$RunId"
        $local = Join-Path $outDir $name
        New-Item -ItemType Directory -Force -Path $local | Out-Null
        $listing = & $adb -s $serial shell "run-as com.talkback.test ls $remoteRel" 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "No report dir on $name ($serial): $listing"
            continue
        }
        foreach ($file in ($listing | ForEach-Object { "$_".Trim() } | Where-Object { $_ -and $_ -notmatch "Permission|No such" })) {
            $content = & $adb -s $serial shell "run-as com.talkback.test cat $remoteRel/$file" 2>&1
            if ($LASTEXITCODE -eq 0) {
                $outFile = Join-Path $local $file
                Set-Content -Path $outFile -Value ($content -join "`n") -Encoding utf8
                Write-Host "PULLED $name/$file"
            } else {
                Write-Warning "pull failed $name/$file : $content"
            }
        }
    }
    Write-Host "PULLED -> $outDir"
}

switch ($Step) {
    "prep" { Invoke-Prep }
    "start" { Invoke-Start }
    "pull" { Invoke-Pull }
    "smoke" {
        # Short directed smoke on first available device (not formal 120s multi-node)
        $Devices = @($Devices[0])
        $DurationSec = 5
        if (-not $RunId.StartsWith("product-chain")) {
            $RunId = "product-chain-smoke-" + (Get-Date -Format "yyyyMMdd-HHmmss")
        }
        Invoke-Start
        Invoke-Pull
    }
}

Write-Host "DONE step=$Step runId=$RunId"
Write-Host "Card: docs/analysis/product-chain-2-3-member-field-soak-run-card.md"
