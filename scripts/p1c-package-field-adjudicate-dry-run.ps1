# Dry-run fixture tests for p1c-package-field-adjudicate.ps1
#
# Usage:
#   .\talkback\scripts\p1c-package-field-adjudicate-dry-run.ps1
param(
    [string]$FixtureRoot = ""
)

$ErrorActionPreference = "Stop"
$scriptDir = $PSScriptRoot
$adjudicate = Join-Path $scriptDir "p1c-package-field-adjudicate.ps1"
if (-not $FixtureRoot) {
    $FixtureRoot = Join-Path $scriptDir "fixtures\p1c-package-field"
}

$expectedPath = Join-Path $FixtureRoot "EXPECTED_VERDICTS.txt"
if (-not (Test-Path $adjudicate)) { throw "Missing adjudicate script: $adjudicate" }
if (-not (Test-Path $expectedPath)) { throw "Missing EXPECTED_VERDICTS.txt: $expectedPath" }

$expected = @{}
Get-Content $expectedPath | ForEach-Object {
    if ($_ -match '^([^=]+)=(PASS|FAIL|INVALID)$') {
        $expected[$Matches[1].Trim()] = $Matches[2].Trim()
    }
}

$failures = New-Object System.Collections.Generic.List[string]
Write-Host "P1-C adjudicate dry-run"
Write-Host "FixtureRoot=$FixtureRoot"
Write-Host ""

foreach ($name in ($expected.Keys | Sort-Object)) {
    $dir = Join-Path $FixtureRoot $name
    if (-not (Test-Path $dir)) {
        $failures.Add("$name MISSING_DIR") | Out-Null
        Write-Host "FAIL $name — fixture directory missing"
        continue
    }
    $want = $expected[$name]
    $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $adjudicate -LogDir $dir 2>&1
    $code = $LASTEXITCODE
    $text = ($output | Out-String)
    $got = $null
    if ($text -match 'VERDICT=(PASS|FAIL|INVALID)') { $got = $Matches[1] }

    $codeOk = $false
    if ($want -eq "PASS" -and $code -eq 0) { $codeOk = $true }
    if ($want -eq "FAIL" -and $code -eq 1) { $codeOk = $true }
    if ($want -eq "INVALID" -and $code -eq 2) { $codeOk = $true }

    if ($got -eq $want -and $codeOk) {
        Write-Host "PASS $name VERDICT=$got exit=$code"
        if ($name -eq "pass-full") {
            foreach ($need in @(
                "C-F1 PASS",
                "C-F2 PASS",
                "C-F3 PASS",
                "C-F4 PASS",
                "C-F5 PASS",
                "C-F6 PASS",
                "C-F7 PASS",
                "POSITIVE LEG PASS",
                "decryptOutcome=SUCCESS",
                "establishmentPath=PRODUCTION_WIRED",
                "initialDelivery=PRE_BIND_DROPPED_ALLOWED",
                "postBindRepublish=EMITTED",
                "postBindIngress=APPLIED",
                "supplement=READY",
                "R1_FIELD=PASS"
            )) {
                if ($text -notmatch [regex]::Escape($need)) {
                    $failures.Add("$name missing:$need") | Out-Null
                    Write-Host "  FAIL detail missing: $need"
                }
            }
        }
        if ($name -eq "invalid-harness-only-republish") {
            if ($text -notmatch "r1_harness_only_republish_not_field_proven|harness_only_P1C_REPUBLISH") {
                $failures.Add("$name missing harness-only R1 reason") | Out-Null
                Write-Host "  FAIL detail missing harness-only R1 gate"
            }
        }
        if ($name -eq "fail-ready-after-applied" -and $text -notmatch "READY_after_APPLIED|ready_after_applied|READY_after_APPLIED") {
            # evidence string contains evidence_anomaly_READY_after_APPLIED
            if ($text -notmatch "evidence_anomaly_READY_after_APPLIED") {
                $failures.Add("$name missing order anomaly reason") | Out-Null
                Write-Host "  FAIL detail missing READY/APPLIED order anomaly"
            }
        }
    } else {
        $failures.Add("$name want=$want got=$got exit=$code") | Out-Null
        Write-Host "FAIL $name want=$want got=$got exit=$code"
    }
}

Write-Host ""
if ($failures.Count -gt 0) {
    Write-Host "DRY_RUN_VERDICT=FAIL"
    Write-Host ("failures=" + ($failures -join " | "))
    exit 1
}

Write-Host "DRY_RUN_VERDICT=PASS"
Write-Host ("cases=" + $expected.Count)
exit 0
