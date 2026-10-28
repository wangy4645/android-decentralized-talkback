# PA-SR5 adjudicator synthetic self-test (harness-only; no devices).
$ErrorActionPreference = "Stop"
$adj = Join-Path $PSScriptRoot "phase-a-sr5-field-adjudicate.ps1"
$fixRoot = Join-Path $PSScriptRoot "fixtures\phase-a-sr5-field"

$cases = @(
    @{ Name = "pass-synthetic"; ExpectExit = 0; ExpectVerdict = "PASS_PA_SR5" },
    @{ Name = "fail-no-revoke"; ExpectExit = 1; ExpectVerdict = "FAIL_PA_SR5" },
    @{ Name = "invalid-no-tx"; ExpectExit = 2; ExpectVerdict = "INVALID_SR4_PREREQUISITE" },
    @{ Name = "invalid-legacy-leave"; ExpectExit = 2; ExpectVerdict = "INVALID_STIMULUS" }
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
