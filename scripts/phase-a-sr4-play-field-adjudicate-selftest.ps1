# PA-SR4-PLAY adjudicator synthetic self-test (harness-only; no devices).
$ErrorActionPreference = "Stop"
$adj = Join-Path $PSScriptRoot "phase-a-sr4-play-field-adjudicate.ps1"
$fixRoot = Join-Path $PSScriptRoot "fixtures\phase-a-sr4-play-field"

$cases = @(
    @{ Name = "pass-synthetic-peer-playout"; ExpectExit = 0; ExpectVerdict = "PASS_PA_SR4_PLAY" },
    @{ Name = "fail-no-playout"; ExpectExit = 1; ExpectVerdict = "FAIL_PA_SR4_PLAY" },
    @{ Name = "invalid-no-tx"; ExpectExit = 2; ExpectVerdict = "INVALID_TX_PREREQUISITE" },
    @{ Name = "invalid-no-ingress"; ExpectExit = 2; ExpectVerdict = "INVALID_INGRESS_PREREQUISITE" },
    @{ Name = "invalid-no-peer-session"; ExpectExit = 2; ExpectVerdict = "INVALID_PEER_SESSION" }
)

$failed = 0
foreach ($c in $cases) {
    $dir = Join-Path $fixRoot $c.Name
    Write-Host "=== SELFTEST $($c.Name) expect exit=$($c.ExpectExit) verdict=$($c.ExpectVerdict) ==="
    & powershell -NoProfile -ExecutionPolicy Bypass -File $adj -LogDir $dir | Out-Null
    $code = $LASTEXITCODE
    $adjFile = Join-Path $dir "ADJUDICATION.txt"
    $verdictLine = if (Test-Path $adjFile) {
        (Select-String -Path $adjFile -Pattern "^VERDICT=" | Select-Object -First 1).Line
    } else { "VERDICT=MISSING" }
    $ok = ($code -eq $c.ExpectExit) -and ($verdictLine -eq "VERDICT=$($c.ExpectVerdict)")
    if ($ok) {
        Write-Host "PASS code=$code $verdictLine"
    } else {
        Write-Host "FAIL code=$code expected=$($c.ExpectExit) $verdictLine expectedVerdict=$($c.ExpectVerdict)"
        $failed++
    }
    Write-Host ""
}

if ($failed -gt 0) {
    Write-Host "SELFTEST_RESULT=FAIL failed=$failed"
    exit 1
}
Write-Host "SELFTEST_RESULT=PASS cases=$($cases.Count)"
exit 0
