# EP operator — Step 3: workstation authority signs duo establishment revision
param(
    [Parameter(Mandatory = $true)]
    [string]$BatchDir,
    [long]$TaskProfileRevision = 20
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$gradleRoot = Join-Path $repoRoot "talkback"

foreach ($label in @("M01", "M02")) {
    $evidence = Join-Path $BatchDir "$label-evidence.txt"
    if (-not (Test-Path $evidence)) {
        Write-Error "missing $evidence — run provision + pull first"
    }
}
$moduleLabels = Get-ChildItem $BatchDir -Filter "M??-evidence.txt" | ForEach-Object { $_.BaseName -replace '-evidence$', '' } | Sort-Object
Write-Host "Evidence present for: $($moduleLabels -join ',')"

$env:EP_BATCH_DIR = (Resolve-Path $BatchDir).Path
$env:EP_TASK_PROFILE_REVISION = "$TaskProfileRevision"
$env:EP_RUN_AUTHORITY_BATCH_TEST = "1"

$localGradleCandidates = @(
    "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat",
    "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.7-bin\508e4583105e\gradle-8.7\bin\gradle.bat",
    "$env:USERPROFILE\.gradle\manual-gradle-8.7\gradle-8.7\bin\gradle.bat"
)
$gradleCmd = $localGradleCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $gradleCmd) {
    $gradleCmd = Join-Path $gradleRoot "gradlew.bat"
    if (-not (Test-Path $gradleCmd)) { $gradleCmd = Join-Path $gradleRoot "gradlew" }
}

Push-Location $gradleRoot
Write-Host "EP_BATCH_DIR=$env:EP_BATCH_DIR"
Write-Host "Running authority harness via Gradle ($gradleCmd)..."
& $gradleCmd --offline -PepAuthorityOnly :android-board-talkback:testDebugUnitTest `
    --tests "com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentOperatorAuthorityFieldHarnessTest.runBatchFromEnv" `
    --rerun-tasks
$exit = $LASTEXITCODE
Pop-Location
if ($exit -ne 0) { exit $exit }

Write-Host "`n--- PROVISIONING_MANIFEST.txt ---"
Get-Content (Join-Path $BatchDir "PROVISIONING_MANIFEST.txt")
Write-Host "`nSigned deliveries:"
Get-ChildItem (Join-Path $BatchDir "establishment-signed-*.bin") | ForEach-Object { $_.Name }
