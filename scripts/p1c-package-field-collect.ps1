# P1-C MEDIA_KEY_PACKAGE field gate — collect only (no adjudication).
#
# Captures M01/M02 Talkback logs + freezes PRECONDITIONS / RUN_META / optional manifest.
# Operator triggers positive package path + C-F7 negative fixture while collectors run.
#
# Usage:
#   .\talkback\scripts\p1c-package-field-collect.ps1 `
#     -EstablishmentKeyVersion 1 `
#     -BuildSha <sha> `
#     -VersionName <name> `
#     -ProvisioningManifest path\to\PROVISIONING_MANIFEST.txt `
#     -WaitSeconds 180
#
# Then:
#   .\talkback\scripts\p1c-package-field-adjudicate.ps1 -LogDir <dir>
param(
    [Parameter(Mandatory = $true)]
    [long]$EstablishmentKeyVersion,
    [string]$ExpectedRecipientModuleId = "M02",
    [string]$HostModuleId = "M01",
    [string]$PackageName = "com.talkback.appprod",
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$BuildSha = "",
    [string]$VersionName = "",
    [string]$ProvisioningManifest = "",
    [string]$Ep2CorroborationNote = "",
    [int]$WaitSeconds = 0,
    [string]$NegativeFixtureStatus = "pending_operator",
    [string]$NegativeFixtureReason = "RECIPIENT_KEY_VERSION_MISMATCH"
)

$ErrorActionPreference = "Stop"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$logDir = Join-Path $repoRoot "talkback\logs\p1c-package-field-$stamp"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Start-DeviceLog {
    param([string]$Serial, [string]$Label)
    $out = Join-Path $logDir "$Label-logcat.txt"
    Start-Process -FilePath "adb" -ArgumentList @("-s", $Serial, "logcat", "-c") -NoNewWindow -Wait | Out-Null
    $proc = Start-Process -FilePath "adb" `
        -ArgumentList @("-s", $Serial, "logcat", "-v", "threadtime", "-s", "Talkback:I", "MEETING_MEDIA_SHADOW:I", "*:S") `
        -RedirectStandardOutput $out -PassThru -NoNewWindow
    return @{ Process = $proc; Path = $out; Serial = $Serial; Label = $Label }
}

function Stop-DeviceLog {
    param($Handle)
    if ($null -eq $Handle) { return }
    if ($Handle.Process -and -not $Handle.Process.HasExited) {
        Stop-Process -Id $Handle.Process.Id -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "P1-C package field COLLECT (no adjudication)"
Write-Host "Log directory: $logDir"
Write-Host ""
Write-Host "Frozen scope: ORIGIN -> peer factType=11 -> INGEST APPLIED -> SUPPLEMENT READY"
Write-Host "Do NOT require CREATION APPLIED / INCOMPLETE_DRAIN / Meeting OPERATIONAL"
Write-Host ""

$m01Log = Start-DeviceLog -Serial $M01Serial -Label "M01"
$m02Log = Start-DeviceLog -Serial $M02Serial -Label "M02"

@"
run_stamp=$stamp
gate=P1-C_PACKAGE_FIELD
hostModuleId=$HostModuleId
expectedRecipientModuleId=$ExpectedRecipientModuleId
establishmentKeyVersion=$EstablishmentKeyVersion
m01_serial=$M01Serial
m02_serial=$M02Serial
packageName=$PackageName
buildSha=$BuildSha
versionName=$VersionName
collect_only=true
adjudicate_script=talkback/scripts/p1c-package-field-adjudicate.ps1
"@ | Set-Content -Encoding utf8 (Join-Path $logDir "RUN_META.txt")

@"
P1-C package field PRECONDITIONS
buildSha=$BuildSha
versionName=$VersionName
devices=M01=$M01Serial M02=$M02Serial
hostModuleId=$HostModuleId
expectedRecipientModuleId=$ExpectedRecipientModuleId
establishmentKeyVersion=$EstablishmentKeyVersion
sameCandidateApk=YES|NO
productionGbcTrustWiring=YES|NO
productionEstablishmentWiring=YES|NO
p1cOriginBridge=YES|NO
p1cFieldObs=YES|NO
epProvisioningValid=YES|NO
ep2KeystoreVerifiedCorroboration=$Ep2CorroborationNote
doNotRequireCreationApplied=true
doNotAdjudicateIncompleteDrain=true
doNotAdjudicateMeetingOperational=true
"@ | Set-Content -Encoding utf8 (Join-Path $logDir "PRECONDITIONS.txt")

@"
# C-F7 negative fence declaration
# Prefer OBS PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE injected=true as authority.
# File-only injected=true without OBS => INVALID (fixture not actually injected).
injected=false
status=$NegativeFixtureStatus
expectedRejectReason=$NegativeFixtureReason
fixtureType=RECIPIENT_KEY_VERSION_MISMATCH
negativeFixtureId=
expectedRecipientEstablishmentKeyVersion=
wireRecipientKeyVersion=
injectionMethod=adb_broadcast_P1C_CF7_RECIPIENT_KEY_VERSION_MISMATCH
notes=After positive leg: adb shell am broadcast -a com.talkback.appprod.debug.P1C_CF7_RECIPIENT_KEY_VERSION_MISMATCH --es remote M02
"@ | Set-Content -Encoding utf8 (Join-Path $logDir "NEGATIVE_FIXTURE.txt")

if ($ProvisioningManifest -and (Test-Path $ProvisioningManifest)) {
    Copy-Item -Force $ProvisioningManifest (Join-Path $logDir "PROVISIONING_MANIFEST.txt")
    Write-Host "Copied PROVISIONING_MANIFEST.txt"
} else {
    @"
# Place or copy EP PROVISIONING_MANIFEST.txt here before adjudicate.
# Required keys:
#   M02.establishmentKeyVersion=<V*>
#   M02.publicKeyFingerprint=<hex>
"@ | Set-Content -Encoding utf8 (Join-Path $logDir "PROVISIONING_MANIFEST.txt")
    Write-Host "Wrote PROVISIONING_MANIFEST.txt placeholder — replace with EP manifest before adjudicate"
}

Write-Host ""
Write-Host "Collectors running. Operator actions while waiting:"
Write-Host "  1) Positive: create conference enough for M01 CREATION + factType=11 fanout to $ExpectedRecipientModuleId"
Write-Host "  2) Negative (C-F7): inject $NegativeFixtureReason; set NEGATIVE_FIXTURE.txt injected=true"
Write-Host "  3) Do not wait for CREATION APPLIED / Meeting OPERATIONAL"
Write-Host ""

if ($WaitSeconds -gt 0) {
    Write-Host "Waiting $WaitSeconds seconds..."
    Start-Sleep -Seconds $WaitSeconds
} else {
    Write-Host "Press Enter to stop collectors..."
    [void][System.Console]::ReadLine()
}

Stop-DeviceLog $m01Log
Stop-DeviceLog $m02Log
Start-Sleep -Seconds 1

@"
collect_stopped_at=$(Get-Date -Format o)
m01_log=$($m01Log.Path)
m02_log=$($m02Log.Path)
next=Update NEGATIVE_FIXTURE.txt if C-F7 was injected, then run adjudicate.
"@ | Set-Content -Encoding utf8 (Join-Path $logDir "COLLECT_DONE.txt")

Write-Host ""
Write-Host "Collect complete: $logDir"
Write-Host "Adjudicate with:"
Write-Host "  .\talkback\scripts\p1c-package-field-adjudicate.ps1 -LogDir `"$logDir`""
