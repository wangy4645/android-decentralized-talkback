# Invoke project-local Gradle 8.7 from .gradle-home (no wrapper download).
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = "Stop"
$TalkbackRoot = Split-Path $PSScriptRoot -Parent
$projectGradleHome = Join-Path $TalkbackRoot ".gradle-home"
if (-not (Test-Path $projectGradleHome)) {
    throw "FAIL: missing project Gradle cache $projectGradleHome"
}

$distRoot = Join-Path $projectGradleHome "wrapper\dists\gradle-8.7-bin"
$complete = Get-ChildItem $distRoot -Directory -ErrorAction SilentlyContinue | Where-Object {
    Test-Path (Join-Path $_.FullName "gradle-8.7\bin\gradle.bat")
} | Select-Object -First 1
if (-not $complete) {
    throw "FAIL: Gradle 8.7 not extracted locally; populate $distRoot first"
}

$gradleBat = Join-Path $complete.FullName "gradle-8.7\bin\gradle.bat"
$env:GRADLE_USER_HOME = $projectGradleHome
Write-Host "GRADLE_USER_HOME=$projectGradleHome"
Write-Host "Using local Gradle: $gradleBat"

Push-Location $TalkbackRoot
try {
    & $gradleBat --no-daemon -p $TalkbackRoot @GradleArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
