# GA Evidence Set — aggregate G1/G2/G3 independent gate verdicts.
# Exit: 0 all PASS · 1 any VALID FAIL · 2 any INVALID/ENV_BLOCKED/incomplete
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"

function Read-GateResult([string]$Name) {
    $path = Join-Path $LogDir "$Name.txt"
    if (-not (Test-Path $path)) { return @{ verdict = "MISSING"; reason = "no_result_file" } }
    $map = @{}
    Get-Content $path | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') { $map[$Matches[1].Trim()] = $Matches[2].Trim() }
    }
    if ($map['G1_RESULT']) { $map['verdict'] = $map['G1_RESULT'] }
    if ($map['G2_RESULT']) { $map['verdict'] = $map['G2_RESULT'] }
    if ($map['G3_RESULT']) { $map['verdict'] = $map['G3_RESULT'] }
    return $map
}

$g1 = Read-GateResult "G1_RESULT"
$g2 = Read-GateResult "G2_RESULT"
$g3 = Read-GateResult "G3_RESULT"

$v1 = $g1['verdict']
$v2 = $g2['verdict']
$v3 = $g3['verdict']

$allPass = ($v1 -eq "PASS") -and ($v2 -eq "PASS") -and ($v3 -eq "PASS")
$anyValidFail = ($v1 -eq "VALID_FAIL") -or ($v2 -eq "VALID_FAIL") -or ($v3 -eq "VALID_FAIL")
$anyBlocked = ($v2 -eq "ENV_BLOCKED")

$overall = if ($allPass) { "PASS" } elseif ($anyValidFail) { "VALID_FAIL" } else { "INCOMPLETE" }

$lines = @(
    "GA_EVIDENCE_SET"
    "G1=$v1"
    "G2=$v2"
    "G3=$v3"
    "overall=$overall"
    "semantic_independence=G1_scale_G2_real_mesh_G3_duration"
    "no_cross_substitution=true"
    ""
    "G1_reason=$($g1['reason'])"
    "G2_reason=$($g2['reason'])"
    "G3_reason=$($g3['reason'])"
)

if ($allPass) {
    $lines += "GA_EVIDENCE_SET=PASS"
    $lines += "Next=ADR-0056_RETIREMENT_GA_AUTHORIZATION_REVIEW"
    $lines += "ReviewItems=default_multicast_path;adr0056_rollback_vs_retire;ga_scope_nodes_underlay"
} elseif ($anyValidFail) {
    $lines += "GA_EVIDENCE_SET=VALID_FAIL"
    $lines += "Next=RCA_PER_FAILED_GATE_NO_CROSS_GATE_STOP"
} else {
    $lines += "GA_EVIDENCE_SET=INCOMPLETE"
    $lines += "Next=CONTINUE_PARALLEL_GATES_INVALID_FIX_AND_RERUN"
}

$out = $lines -join "`n"
$out | Set-Content -Encoding utf8 (Join-Path $LogDir "GA_EVIDENCE_SET.txt")
Write-Host $out

if ($allPass) { exit 0 }
if ($anyValidFail) { exit 1 }
exit 2
