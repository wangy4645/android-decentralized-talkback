# Meeting Product Integration — Phase A Origin Discovery + Shadow Adjudication
# Manual Meeting UX on device; this script only captures/pulls log evidence.
# NO harness Profile01 injection. NO product code changes.
param(
    [ValidateSet("prep", "capture", "pull", "smoke")]
    [string]$Step = "smoke",
    [string]$RunId = "",
    [string]$HostDevice = "M01",
    [string]$ParticipantDevice = "M02",
    [string[]]$Devices = @("M01", "M02")
)

$ErrorActionPreference = "Stop"
if ($Devices.Count -eq 1 -and $Devices[0] -match ",") {
    $Devices = $Devices[0].Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
}

$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = (Get-Command adb).Source
$LogTagPattern = "MEETING_MEDIA_SHADOW|WIRE_INGRESS|CONFERENCE_SIGNED_FACT"

$deviceMap = @{
    "M01" = @{ Serial = "HTUBB21B09220661"; Module = "M01" }
    "M02" = @{ Serial = "DSJ-2407041"; Module = "M02" }
    "M03" = @{ Serial = "MDX0220416001963"; Module = "M03" }
}

if (-not $RunId) {
    $RunId = "meeting-product-origin-discovery-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}
$outDir = Join-Path $repoRoot "logs\$RunId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Resolve-Serial([string]$Name) {
    if (-not $deviceMap.ContainsKey($Name)) { throw "Unknown device label: $Name" }
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
    Write-Host "RUN_ID=$RunId"
    Write-Host "Phase A Origin Discovery — device prep"
    $list = & $adb devices 2>&1
    Write-Host $list
    foreach ($name in $Devices) {
        $serial = Resolve-Serial $name
        $state = (& $adb -s $serial get-state 2>&1 | Out-String).Trim()
        if ($state -ne "device") {
            Write-Warning "$name ($serial) not ready: $state"
        } else {
            Write-Host "OK $name serial=$serial"
        }
    }
    Write-Host ""
    Write-Host "Manual sequence (product Meeting UI):"
    Write-Host "  1. Host create → wait for wire/shadow"
    Write-Host "  2. Participant join → media ready"
    Write-Host "  3. leave/rejoin"
    Write-Host "  4. Host end"
    Write-Host "Use -Step capture before step 1; -Step pull after step 4."
}

function Invoke-Capture {
    Write-Host "RUN_ID=$RunId OUT=$outDir"
    foreach ($name in $Devices) {
        $serial = Resolve-Serial $name
        $outFile = Join-Path $outDir "$name-logcat.txt"
        Write-Host "CAPTURE $name serial=$serial -> $outFile"
        # Clear buffer then start background logcat (caller stops with Ctrl+C or pull step uses dump)
        & $adb -s $serial logcat -c 2>&1 | Out-Null
        Start-Process -FilePath $adb -ArgumentList @(
            "-s", $serial,
            "logcat",
            "-v", "threadtime",
            "*:S",
            "MEETING_MEDIA_SHADOW:I",
            "MEETING_MEDIA_SHADOW:W",
            "MEETING_MEDIA_SHADOW:E"
        ) -RedirectStandardOutput $outFile -NoNewWindow
    }
    Write-Host "Logcat capture started. Run manual Meeting sequence, then: -Step pull"
}

function Invoke-Pull {
    Write-Host "RUN_ID=$RunId OUT=$outDir"
    foreach ($name in $Devices) {
        $serial = Resolve-Serial $name
        $outFile = Join-Path $outDir "$name-logcat.txt"
        Write-Host "PULL $name serial=$serial"
        $dump = & $adb -s $serial logcat -d -v threadtime 2>&1 | Out-String
        $filtered = $dump -split "`n" | Where-Object { $_ -match $LogTagPattern }
        if ($filtered.Count -eq 0) {
            # Fallback: full dump slice for manual grep
            $filtered = $dump -split "`n" | Where-Object {
                $_ -match "SHADOW|WIRE_INGRESS|DEFERRED_|APPLIED|CONFERENCE_SIGNED"
            }
        }
        $filtered | Set-Content -Path $outFile -Encoding UTF8
        Write-Host "  lines=$($filtered.Count) -> $outFile"
    }

    $summary = @{
        runId = $RunId
        evidenceScope = "MEETING_PRODUCT_PHASE_A_ORIGIN_DISCOVERY"
        hostDevice = $HostDevice
        participantDevice = $ParticipantDevice
        capturedAt = (Get-Date).ToString("o")
        note = "Fill adjudication matrix in meeting-product-integration-phase-a-origin-discovery-run-card.md"
    }
    $summary | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $outDir "run-header.json") -Encoding UTF8
    Write-Host "Wrote $(Join-Path $outDir 'run-header.json')"
    Write-Host ""
    Write-Host "Grep checklist:"
    Write-Host "  WIRE_INGRESS.*outcome=APPLIED  → wire facts received"
    Write-Host "  SHADOW.*DEFERRED_NO_FACT       → waiting on origin"
    Write-Host "  SHADOW.*outcome=APPLIED        → shadow converged"
}

function Invoke-Smoke {
    Invoke-Prep
    Write-Host ""
    Write-Host "SMOKE: no automated Meeting driver — origin discovery requires manual UX + capture/pull."
}

switch ($Step) {
    "prep" { Invoke-Prep }
    "capture" { Invoke-Capture }
    "pull" { Invoke-Pull }
    "smoke" { Invoke-Smoke }
    default { throw "Unknown step: $Step" }
}
