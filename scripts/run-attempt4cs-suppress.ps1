# Attempt-4c-S — SUPPRESS_SUCCESSOR_ATTEMPT topology exercise (no D1)
# Protocol: ARM -> OFF+HOLD until obligation CLOSED/DEADLINE -> ON -> collect APPLIED/ADMIT

param(
    [Parameter(Mandatory = $true)][string]$LogDir,
    [string]$Adb = "C:\adb\adb.exe",
    [string]$AuthSerial = "2d73067a",
    [string]$RemoteSerial = "MDX0220416001963",
    [string]$RemoteModule = "M03",
    [int]$FailOpenTimeoutSec = 90,
    [int]$ClosedTimeoutSec = 120,
    [int]$PostOnCollectSec = 60,
    [long]$TtlMs = 600000
)

$ErrorActionPreference = "Continue"
if (-not (Test-Path $Adb)) { throw "adb not found: $Adb" }
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
New-Item -ItemType Directory -Force -Path "C:\adb\tmp" | Out-Null

$phaseLog = Join-Path $LogDir "phase-ops.txt"
$dumpDir = Join-Path $LogDir "adb-dumps"
New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null
$report = Join-Path $LogDir "ATTEMPT4CS_REPORT.txt"
$authStream = Join-Path $LogDir "auth-stream.log"
$contract = Join-Path $LogDir "ATTEMPT4CS_CONTRACT.txt"

@(
    "EXERCISE_MODE=SUPPRESS_SUCCESSOR_TOPOLOGY",
    "attempt=4cs",
    "topologyMode=EXERCISE_SUPPRESSED_SUCCESSOR",
    "protocol=ARM->OFF_HOLD_UNTIL_CLOSED->ON->collect_APPLIED",
    "noD1=true",
    "noR4Impl=true"
) | Set-Content -Path $contract -Encoding utf8

function Log([string]$m) {
    $line = "$((Get-Date -Format 'HH:mm:ss.fff')) $m"
    Add-Content -Path $phaseLog -Value $line -Encoding utf8
    Write-Host $line
}

function Invoke-AdbCmd([string]$argumentString) {
    $stamp = Get-Date -Format "HHmmssfff"
    $outFile = "C:\adb\tmp\o-$stamp-$PID.txt"
    $errFile = "C:\adb\tmp\e-$stamp-$PID.txt"
    try {
        $null = Start-Process -FilePath $Adb -ArgumentList $argumentString -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $outFile -RedirectStandardError $errFile
        $text = ""
        if (Test-Path $outFile) { $text = [IO.File]::ReadAllText($outFile) }
        if (Test-Path $errFile) { $text += [IO.File]::ReadAllText($errFile) }
        return $text
    } finally {
        Remove-Item $outFile, $errFile -ErrorAction SilentlyContinue
    }
}

function BAuth([string]$action, [string]$extra = "") {
    $s = "-s $AuthSerial shell am broadcast -p com.talkback.appprod -a $action --es remote $RemoteModule"
    if ($extra) { $s = "$s $extra" }
    $o = (Invoke-AdbCmd $s).Trim()
    Log "AUTH_BCAST $action :: $o"
}

function Dump-Auth([string]$tag) {
    $f = Join-Path $dumpDir ("auth-" + $tag + "-" + (Get-Date -Format "HHmmssfff") + ".log")
    $null = Start-Process -FilePath $Adb -ArgumentList "-s $AuthSerial logcat -d -v threadtime" `
        -NoNewWindow -Wait -RedirectStandardOutput $f -RedirectStandardError ($f + ".err")
    return $f
}

function Get-AuthAll {
    if (-not (Test-Path $authStream)) { return @() }
    return @(Get-Content $authStream -ErrorAction SilentlyContinue)
}

function Test-AuthMatch([string]$pattern) {
    return (@(Get-AuthAll | Where-Object { $_ -match $pattern })).Count -ge 1
}

Log "Attempt-4c-S start protocol=OFF_HOLD_UNTIL_CLOSED"
$null = Invoke-AdbCmd "-s $AuthSerial logcat -c"
$null = Invoke-AdbCmd "-s $RemoteSerial logcat -c"
Start-Sleep -Milliseconds 300

BAuth "com.talkback.appprod.debug.SUPPRESS_SUCCESSOR_ATTEMPT_CLEAR"
Start-Sleep -Milliseconds 300
BAuth "com.talkback.appprod.debug.SUPPRESS_SUCCESSOR_ATTEMPT_ARM" "--el ttlMs $TtlMs"

Remove-Item $authStream -ErrorAction SilentlyContinue
$authProc = Start-Process -FilePath $Adb -ArgumentList "-s $AuthSerial logcat -v threadtime Talkback:I *:S" `
    -NoNewWindow -PassThru -RedirectStandardOutput $authStream -RedirectStandardError ($authStream + ".err")

try {
    Log "STEP1: WiFi OFF NOW on $RemoteModule — HOLD OFF (do NOT turn ON yet)"
    $failOpenDeadline = (Get-Date).AddSeconds($FailOpenTimeoutSec)
    $sawFail = $false
    while ((Get-Date) -lt $failOpenDeadline) {
        $sawFail = (Test-AuthMatch "ICE_DISCONNECTED.*remote=$RemoteModule") -or (Test-AuthMatch "RECOVERY_ATTEMPT_OPENED.*remote=$RemoteModule")
        Log "wait FAIL/OPEN sawFail=$sawFail armed=$(Test-AuthMatch 'SUPPRESS_SUCCESSOR_ATTEMPT_ARMED')"
        if ($sawFail) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $sawFail) { Log "WARN: no ICE fail within ${FailOpenTimeoutSec}s — keep OFF; continuing to CLOSED wait" }

    Log "STEP2: KEEP WiFi OFF — waiting OBLIGATION_DEADLINE / CLOSED (do NOT turn ON)"
    $closedDeadline = (Get-Date).AddSeconds($ClosedTimeoutSec)
    $sawClosed = $false
    while ((Get-Date) -lt $closedDeadline) {
        $sawClosed = (Test-AuthMatch "OBLIGATION_DEADLINE") -or (Test-AuthMatch "RECOVERY_OBLIGATION_CLOSED.*remote=$RemoteModule") -or (Test-AuthMatch "obligationCloseReason=")
        $applied = Test-AuthMatch "SUPPRESS_SUCCESSOR_ATTEMPT_APPLIED|HARNESS_SUCCESSOR_SUPPRESSION_APPLIED"
        Log "wait CLOSED sawClosed=$sawClosed applied=$applied (still HOLD OFF)"
        if ($sawClosed) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $sawClosed) { Log "WARN: CLOSED not seen in ${ClosedTimeoutSec}s — proceed to ON anyway for evidence" }

    Log "STEP3: WiFi ON NOW on $RemoteModule — collect successor/suppress seam"
    $onDeadline = (Get-Date).AddSeconds($PostOnCollectSec)
    while ((Get-Date) -lt $onDeadline) {
        $applied = Test-AuthMatch "SUPPRESS_SUCCESSOR_ATTEMPT_APPLIED|HARNESS_SUCCESSOR_SUPPRESSION_APPLIED"
        $admit = Test-AuthMatch "ADMIT_SUCCESSOR_OBLIGATION_EPISODE.*remote=$RemoteModule"
        $ignore = Test-AuthMatch "reason=suppress_successor_attempt"
        $recovered = Test-AuthMatch "candidate=RECOVERED|RECOVERY_EDGE_RECOVERED.*remote=$RemoteModule"
        Log "post-ON applied=$applied admit=$admit ignore=$ignore recovered=$recovered"
        if ($applied -or $admit) {
            Log "successor-seam signal seen; draining 15s"
            Start-Sleep -Seconds 15
            break
        }
        Start-Sleep -Seconds 2
    }

    $null = Dump-Auth "4cs-topology"
    $analyzer = Join-Path $PSScriptRoot "analyze-attempt4cs-suppress.ps1"
    $classPath = Join-Path $LogDir "ATTEMPT4CS_SUPPRESS_CLASSIFICATION.txt"
    & $analyzer -LogDir $LogDir -RemoteModule $RemoteModule -ReportPath $classPath
    $exitCode = $LASTEXITCODE
    $caseLine = @(Get-Content $classPath -ErrorAction SilentlyContinue | Where-Object { $_ -match "^caseS=" } | Select-Object -Last 1)
    $caseS = if ($caseLine) { ($caseLine -replace "^caseS=", "") } else { "INCONCLUSIVE" }
    @("result=CASE_S_$caseS", "topologyMode=EXERCISE_SUPPRESSED_SUCCESSOR", "classification=$classPath", "exit=$exitCode") |
        Set-Content -Path $report -Encoding utf8
    Log "4cs complete caseS=$caseS exit=$exitCode"
    exit $exitCode
} finally {
    if ($authProc -and -not $authProc.HasExited) { Stop-Process -Id $authProc.Id -Force -ErrorAction SilentlyContinue }
}
