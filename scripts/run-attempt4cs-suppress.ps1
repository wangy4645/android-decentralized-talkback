# Attempt-4c-S — SUPPRESS_SUCCESSOR_ATTEMPT topology exercise (no D1)
# Evidence classification only. Does not score Joint PASS/FAIL.

param(
    [Parameter(Mandatory = $true)][string]$LogDir,
    [string]$Adb = "C:\adb\adb.exe",
    [string]$AuthSerial = "2d73067a",
    [string]$RemoteSerial = "MDX0220416001963",
    [string]$RemoteModule = "M03",
    [int]$WatchTimeoutSec = 180,
    [int]$PostSignalCollectSec = 20,
    [long]$TtlMs = 180000
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
    "purpose=harness_successor_suppression_topology",
    "noD1=true",
    "noR4Impl=true",
    "facts=SUPPRESS_SUCCESSOR_ATTEMPT_ARMED|APPLIED|EXPIRED|HARNESS_SUCCESSOR_SUPPRESSION_APPLIED",
    "classification=CASE_S_A|CASE_S_B|CASE_S_C|INCONCLUSIVE|ABORT_ADOPTION_LEAK"
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

function Read-NewLines([string]$path, [ref]$offset) {
    if (-not (Test-Path $path)) { return @() }
    $fs = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        $fs.Seek($offset.Value, [IO.SeekOrigin]::Begin) | Out-Null
        $sr = New-Object IO.StreamReader($fs)
        $chunk = $sr.ReadToEnd()
        $offset.Value = $fs.Position
        if (-not $chunk) { return @() }
        return @($chunk -split "`r?`n" | Where-Object { $_ })
    } finally { $fs.Dispose() }
}

Log "Attempt-4c-S start topologyMode=EXERCISE_SUPPRESSED_SUCCESSOR"
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
    Log "WATCH armed - FLAP NOW: $RemoteModule WiFi OFF 25-35s then ON; observe SUPERSEDED + no ADMIT_SUCCESSOR"
    $deadline = (Get-Date).AddSeconds($WatchTimeoutSec)
    $collectUntil = $null
    $authOff = [int64]0
    while ((Get-Date) -lt $deadline) {
        $null = Read-NewLines $authStream ([ref]$authOff)
        $all = @()
        if (Test-Path $authStream) { $all = @(Get-Content $authStream -ErrorAction SilentlyContinue) }
        $sArmed = (@($all | Where-Object { $_ -match "SUPPRESS_SUCCESSOR_ATTEMPT_ARMED" })).Count -ge 1
        $sApplied = (@($all | Where-Object { $_ -match "SUPPRESS_SUCCESSOR_ATTEMPT_APPLIED|HARNESS_SUCCESSOR_SUPPRESSION_APPLIED" })).Count -ge 1
        $sAdmit = (@($all | Where-Object { $_ -match "ADMIT_SUCCESSOR_OBLIGATION_EPISODE" -and $_ -match "remote=$RemoteModule" })).Count -ge 1
        $sSuper = (@($all | Where-Object { $_ -match "RECOVERY_DELIVERY_LINEAGE_SUPERSEDED|CLOSED_SUPERSEDED|RECOVERY_OBLIGATION_CLOSED" })).Count -ge 1
        Log "4cs wait armed=$sArmed applied=$sApplied admit=$sAdmit superOrClosed=$sSuper"
        if (($sSuper -or $sApplied -or $sAdmit) -and -not $collectUntil) {
            $collectUntil = (Get-Date).AddSeconds($PostSignalCollectSec)
            Log "topology signal seen; collecting ${PostSignalCollectSec}s"
        }
        if ($collectUntil -and (Get-Date) -ge $collectUntil) {
            Log "topology collection complete"
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
    @(
        "result=CASE_S_$caseS",
        "topologyMode=EXERCISE_SUPPRESSED_SUCCESSOR",
        "classification=$classPath",
        "exit=$exitCode"
    ) | Set-Content -Path $report -Encoding utf8
    Log "4cs complete caseS=$caseS exit=$exitCode"
    exit $exitCode
} finally {
    if ($authProc -and -not $authProc.HasExited) { Stop-Process -Id $authProc.Id -Force -ErrorAction SilentlyContinue }
}
