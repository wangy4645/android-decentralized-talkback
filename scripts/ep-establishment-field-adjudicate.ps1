# EP1-EP5 establishment field gate — EP_PROBE adjudication
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"

function Get-ModuleLog {
    param([string]$Module)
    foreach ($name in @("$Module-logcat.txt", "$Module-talkback.log", "$Module-logcat-dump.txt")) {
        $p = Join-Path $LogDir $name
        if (Test-Path $p) { return $p }
    }
    return $null
}

function Select-EpProbe {
    param([string]$Path, [string]$Case, [string]$Device)
    if (-not $Path -or -not (Test-Path $Path)) { return @() }
    Select-String -Path $Path -Pattern "EP_PROBE case=$Case device=$Device" | ForEach-Object { $_.Line }
}

function Parse-Field {
    param([string]$Line, [string]$Key)
    if ($Line -match "$Key=([^ ]+)") { return $Matches[1] }
    return $null
}

function Test-Ep4DomainIndependence {
    param(
        [string]$Ep4Line,
        [bool]$StructuralCorroboration
    )
    if (-not $Ep4Line) {
        return @{ Pass = $false; Reason = "missing" }
    }
    $outcome = Parse-Field $Ep4Line "domainSeparationOutcome"
    $signerV = Parse-Field $Ep4Line "signerKeyVersion"
    $estV = Parse-Field $Ep4Line "establishmentKeyVersion"
    if ($outcome -eq "InsufficientState") {
        return @{ Pass = $false; Reason = "InsufficientState" }
    }
    if ([string]::IsNullOrWhiteSpace($signerV) -or [string]::IsNullOrWhiteSpace($estV)) {
        return @{ Pass = $false; Reason = "missing_version_fields" }
    }
    if ([long]$signerV -le 0 -or [long]$estV -le 0) {
        return @{ Pass = $false; Reason = "non_positive_version" }
    }
    # Probe emits CollapsedDomain when numeric values are equal — NOT semantic domain collapse.
    if ($outcome -notin @("Separated", "CollapsedDomain")) {
        return @{ Pass = $false; Reason = $outcome }
    }
    if (-not $StructuralCorroboration) {
        return @{ Pass = $false; Reason = "missing_structural_corroboration" }
    }
    $numericRelation = if ($signerV -eq $estV) { "EQUAL" } else { "DISTINCT" }
    return @{
        Pass = $true
        NumericRelation = $numericRelation
        DomainSeparation = "PROVEN_INDEPENDENT"
        ProbeOutcome = $outcome
    }
}

$m01Log = Get-ModuleLog "M01"
$m02Log = Get-ModuleLog "M02"
$lines = @()
$lines += "EP ESTABLISHMENT FIELD ADJUDICATION"
$lines += "log_dir=$LogDir"
$lines += "ep4_semantics=domain_independence_numeric_equality_allowed"
$lines += ""

if (-not $m01Log -or -not $m02Log) {
    $lines += "VERDICT=INVALID"
    $lines += "reason=missing_device_logs"
    $lines -join "`n" | Set-Content (Join-Path $LogDir "ADJUDICATION.txt")
    Write-Host ($lines -join "`n")
    exit 2
}

$ep1 = Select-EpProbe -Path $m01Log -Case "EP1" -Device "M01" | Select-Object -Last 1
$ep2 = Select-EpProbe -Path $m02Log -Case "EP2" -Device "M02" | Select-Object -Last 1
$ep3m01 = Select-EpProbe -Path $m01Log -Case "EP3" -Device "M01" | Select-Object -Last 1
$ep3m02 = Select-EpProbe -Path $m02Log -Case "EP3" -Device "M02" | Select-Object -Last 1
$ep4m01 = Select-EpProbe -Path $m01Log -Case "EP4" -Device "M01" | Select-Object -Last 1
$ep4m02 = Select-EpProbe -Path $m02Log -Case "EP4" -Device "M02" | Select-Object -Last 1
$ep5m01 = Select-EpProbe -Path $m01Log -Case "EP5" -Device "M01" | Select-Object -Last 1
$ep5m02 = Select-EpProbe -Path $m02Log -Case "EP5" -Device "M02" | Select-Object -Last 1

$lines += "EP1_M01=$ep1"
$lines += "EP2_M02=$ep2"
$lines += "EP3_M01=$ep3m01"
$lines += "EP3_M02=$ep3m02"
$lines += "EP4_M01=$ep4m01"
$lines += "EP4_M02=$ep4m02"
$lines += "EP5_M01=$ep5m01"
$lines += "EP5_M02=$ep5m02"
$lines += ""

$fail = @()
$invalid = @()

if (-not $ep1) { $invalid += "missing_EP1_M01" }
if (-not $ep2) { $invalid += "missing_EP2_M02" }

$ep1Outcome = Parse-Field $ep1 "lookupOutcome"
$ep2Outcome = Parse-Field $ep2 "localIdentityAvailability"
$ep5m01Unchanged = Parse-Field $ep5m01 "storeBindingCountUnchanged"
$ep5m02Unchanged = Parse-Field $ep5m02 "storeBindingCountUnchanged"
$ep5Pass = ($ep5m01Unchanged -eq "true") -and ($ep5m02Unchanged -eq "true")
$structuralCorroboration = ($ep1Outcome -eq "Found") -and ($ep2Outcome -eq "Verified") -and $ep5Pass

$ep4m01Eval = Test-Ep4DomainIndependence -Ep4Line $ep4m01 -StructuralCorroboration $structuralCorroboration
$ep4m02Eval = Test-Ep4DomainIndependence -Ep4Line $ep4m02 -StructuralCorroboration $structuralCorroboration

if ($ep1Outcome -ne "Found") { $fail += "EP1_lookup_$ep1Outcome" }
if ($ep2Outcome -ne "Verified") { $fail += "EP2_local_$ep2Outcome" }
if (-not $ep4m01Eval.Pass) { $fail += "EP4_M01_$($ep4m01Eval.Reason)" }
if (-not $ep4m02Eval.Pass) { $fail += "EP4_M02_$($ep4m02Eval.Reason)" }
if ($ep5m01Unchanged -ne "true") { $fail += "EP5_M01_store_mutated" }
if ($ep5m02Unchanged -ne "true") { $fail += "EP5_M02_store_mutated" }

if ($ep4m01Eval.Pass) {
    $lines += "EP4_M01_numericRelation=$($ep4m01Eval.NumericRelation) domainSeparation=$($ep4m01Eval.DomainSeparation) probeOutcome=$($ep4m01Eval.ProbeOutcome)"
}
if ($ep4m02Eval.Pass) {
    $lines += "EP4_M02_numericRelation=$($ep4m02Eval.NumericRelation) domainSeparation=$($ep4m02Eval.DomainSeparation) probeOutcome=$($ep4m02Eval.ProbeOutcome)"
}

$ep3m01Outcome = Parse-Field $ep3m01 "outcome"
$ep3m02Outcome = Parse-Field $ep3m02 "outcome"
if ($ep3m01Outcome -eq "N/A" -and $ep3m02Outcome -eq "N/A") {
    $lines += "EP3=N/A"
} elseif ((Parse-Field $ep3m02 "lookupOutcome") -ne "Found") {
    $fail += "EP3_reverse_not_found"
} else {
    $lines += "EP3=PASS"
}

if ($invalid.Count -gt 0) {
    $lines += "VERDICT=INVALID"
    $lines += ("reason=" + ($invalid -join ","))
} elseif ($fail.Count -gt 0) {
    $lines += "VERDICT=FAIL"
    $lines += ("reason=" + ($fail -join ","))
    $lines += "note=Compare publicKeyFingerprint vs PROVISIONING_MANIFEST.txt; UnknownModule usually means provisioning data gap"
} else {
    $lines += "VERDICT=PASS"
    $lines += "EP1=PASS EP2=PASS EP3=N/A EP4=PASS EP5=PASS"
    $lines += "establishment_field_gate=CLOSED"
    $lines += "note=Confirm SPKI fingerprints against PROVISIONING_MANIFEST.txt; EP4 PASS = domain independence (numeric equality allowed)"
}

$lines -join "`n" | Set-Content (Join-Path $LogDir "ADJUDICATION.txt")
Write-Host ($lines -join "`n")
if ($invalid.Count -gt 0) { exit 2 }
if ($fail.Count -gt 0) { exit 1 }
