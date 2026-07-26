param(
    [switch]$CollectOnly,
    [switch]$AnalyzeOnly,
    [string]$LogDir = "",
    [string]$Device = "MDX0220416001963",
    [string]$Module = "M03",
    [string]$ExpectedRecoveryPeer = "M03",
    [int]$WifiOffSec = 30,
    [int]$PrrPathBudgetMs = 30000,
    [switch]$AutoFlap
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$defaultLogs = Join-Path $repoRoot "logs"
$fixtureDir = Join-Path $PSScriptRoot "fixtures\soak-r28-prr-v1"
if (-not $LogDir) {
    if ($AnalyzeOnly -and (Test-Path $fixtureDir)) {
        $LogDir = $fixtureDir
    } else {
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $LogDir = Join-Path $defaultLogs "obs-r28-prr-v1-$stamp"
    }
}

function Resolve-Adb {
    foreach ($c in @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "adb"
    )) {
        if ($c -eq "adb") { return $c }
        if (Test-Path $c) { return $c }
    }
    throw "adb not found"
}

$adb = Resolve-Adb
$logPath = Join-Path $LogDir "$Module-talkback.log"

if (-not $AnalyzeOnly) {
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
    & $adb -s $Device logcat -G 16M | Out-Null
    & $adb -s $Device logcat -c | Out-Null
    @"
R28-PRR v1 soak (G-PRR only)
device=$Device module=$Module
started=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
expectedRecoveryPeer=$ExpectedRecoveryPeer
prrPathBudgetMs=$PrrPathBudgetMs
protocol=three-device mesh idle -> transport epoch change (WiFi flap ~${WifiOffSec}s or repair path)
gates=G-PRR-1,G-PRR-2,G-PRR-3
report_only=G-L4-2 (BIDIRECTIONAL_READY)
not_judged=G-R28,REATTACH_SENT,EDGE_RECOVERED
filter=Talkback:I *:S
"@ | Set-Content -Path (Join-Path $LogDir "meta.txt") -Encoding UTF8

    $logJob = Start-Job -ScriptBlock {
        param($adbPath, $dev, $outPath)
        & $adbPath -s $dev logcat -v time Talkback:I *:S 2>&1 |
            Out-File -FilePath $outPath -Encoding utf8
    } -ArgumentList $adb, $Device, $logPath
    $logJob | Export-Clixml -Path (Join-Path $LogDir "logjob-$Module.xml")
    Write-Host "Collecting -> $logPath (job=$($logJob.Id))"
    Write-Host "Scenario: three-device mesh idle; WiFi OFF ${WifiOffSec}s (or repair path), WiFi ON, wait <=$([math]::Ceiling($PrrPathBudgetMs / 1000))s."
    if ($AutoFlap) {
        Write-Host "AutoFlap: WiFi OFF ${WifiOffSec}s on $Device ..."
        Start-Sleep -Seconds 5
        & $adb -s $Device shell svc wifi disable | Out-Null
        Start-Sleep -Seconds $WifiOffSec
        & $adb -s $Device shell svc wifi enable | Out-Null
        Write-Host "AutoFlap: WiFi ON; waiting ${PrrPathBudgetMs}ms PRR path budget..."
        Start-Sleep -Seconds ([math]::Ceiling($PrrPathBudgetMs / 1000))
        Stop-Job $logJob -ErrorAction SilentlyContinue
        Receive-Job $logJob -ErrorAction SilentlyContinue | Out-Null
        Remove-Job $logJob -Force -ErrorAction SilentlyContinue
    } else {
        Write-Host "Manual: flap WiFi ~${WifiOffSec}s, wait for repair/PRR. Then: -AnalyzeOnly -LogDir '$LogDir'"
    }
    if ($CollectOnly) { exit 0 }
}

if (-not (Test-Path $logPath)) {
    throw "Log not found: $logPath (use fixtures with -AnalyzeOnly or collect first)"
}

$lines = Get-Content $logPath -Encoding UTF8

$episodeStarted = @($lines | Where-Object { $_ -match 'PRR_EPISODE_STARTED' })
$helloSent = @($lines | Where-Object { $_ -match 'PRR_HELLO_SENT' })
$endpointReannounced = @($lines | Where-Object { $_ -match 'PRR_ENDPOINT_REANNOUNCED' })
$factObserved = @($lines | Where-Object { $_ -match 'PRR_FACT_OBSERVED' })
$scenarioFact = @($lines | Where-Object { $_ -match "PRR_FACT_OBSERVED" -and $_ -match "remoteModuleId=$ExpectedRecoveryPeer" })
$bidirectional = @($lines | Where-Object { $_ -match 'LINK_QUALIFICATION_STATE_CHANGED' -and $_ -match 'newState=BIDIRECTIONAL_READY' })
$firstInbound = @($lines | Where-Object { $_ -match 'LINK_FIRST_INBOUND_AFTER_REPAIR|LINK_FACT_RECEIVED.*FIRST_INBOUND' })
$recoveryPrrLeak = @($lines | Where-Object {
    $_ -match 'ConferenceEdgeRecovery|RECOVERY_' -and $_ -match 'PRR_'
})

$gPrr = [ordered]@{}
$gPrr['G-PRR-1_PRR_EPISODE_STARTED'] = ($episodeStarted.Count -gt 0)
$gPrr['G-PRR-2_PRR_HELLO_SENT'] = ($helloSent.Count -gt 0)
$gPrr['G-PRR-2_PRR_ENDPOINT_REANNOUNCED'] = ($endpointReannounced.Count -gt 0)
$gPrr['G-PRR-3_ANY_PRR_FACT_OBSERVED'] = ($factObserved.Count -gt 0)
$gPrrPass = ($gPrr.Values | Where-Object { $_ -eq $false }).Count -eq 0

$scenarioPass = ($scenarioFact.Count -gt 0)
$forbiddenRecoveryPrr = ($recoveryPrrLeak.Count -gt 0)

$report = @()
$report += "R28-PRR v1 soak analysis (G-PRR layer only)"
$report += "log=$logPath"
$report += "module=$Module expectedRecoveryPeer=$ExpectedRecoveryPeer prrPathBudgetMs=$PrrPathBudgetMs"
$report += ""
$report += "=== Architecture gates (G-PRR) ==="
foreach ($k in $gPrr.Keys) {
    $v = if ($gPrr[$k]) { "PASS" } else { "FAIL" }
    $report += "$k=$v"
}
$report += "G-PRR_OVERALL=$(if ($gPrrPass) { 'PASS' } else { 'FAIL' })"
$report += ""
$report += "=== Scenario assertion (non-gate) ==="
$report += "ASSERT_PRR_FACT_OBSERVED(remote=$ExpectedRecoveryPeer)=$(if ($scenarioPass) { 'PASS' } else { 'WARN' })"
$report += ""
$report += "=== Report-only (Qualification G-L4-2, not soak FAIL) ==="
$report += "G-L4-2_HINT firstInboundLines=$($firstInbound.Count) bidirectionalLines=$($bidirectional.Count)"
if ($bidirectional.Count -gt 0) {
    $report += "G-L4-2_WARN saw BIDIRECTIONAL_READY (report only)"
}
$report += ""
$report += "=== Not judged this session ==="
$report += "G-R28 / REATTACH_SENT / EDGE_RECOVERED: not evaluated"
$report += ""
$report += "=== FORBIDDEN check (Recovery controller PRR trace leak) ==="
if ($forbiddenRecoveryPrr) {
    $report += "FORBIDDEN_WARN recovery_path_prr_traces=$($recoveryPrrLeak.Count)"
    $report += $recoveryPrrLeak | Select-Object -First 3
} else {
    $report += "FORBIDDEN_OK no PRR traces on RecoveryController paths"
}
$report += ""
$report += "=== Three-layer PASS reminder ==="
$report += "PRR (G-PRR-1..3): $(if ($gPrrPass) { 'PASS' } else { 'FAIL' }) <- this soak"
$report += "Qualification (G-L4-2): report/UT only"
$report += "Recovery (G-R28): not judged"

$exitCode = 0
if (-not $gPrrPass) { $exitCode = 1 }

$reportPath = Join-Path $LogDir "r28-prr-v1-analysis.txt"
$report | Set-Content -Path $reportPath -Encoding UTF8
$report | ForEach-Object { Write-Host $_ }
Write-Host "Report -> $reportPath"
exit $exitCode
