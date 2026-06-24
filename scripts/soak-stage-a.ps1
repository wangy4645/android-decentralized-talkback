# Stage A soak: 三台并行 logcat 采集 + 可选安装 APK
# 用法:
#   .\scripts\soak-stage-a.ps1 -DurationMin 30
#   .\scripts\soak-stage-a.ps1 -InstallApk -DurationMin 45
#   .\scripts\soak-stage-a.ps1 -DurationMin 30 -OutDir "d:\workspace\project\talkback\logs-soak-pr1-20260624"

param(
    [switch]$InstallApk,
    [int]$DurationMin = 30,
    [string]$OutDir = "",
    [string]$ApkPath = ".\talkback-app\build\outputs\apk\debug\talkback-app-debug.apk",
    [string]$M01 = "HTUBB21B09220661",
    [string]$M02 = "2d73067a",
    [string]$M03 = "MDX0220416001963"
)

$ErrorActionPreference = "Stop"

$adb = $null
foreach ($c in @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe"
    )) {
    if (Test-Path $c) { $adb = $c; break }
}
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) {
    Write-Error "adb not found"
    exit 1
}

$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $OutDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path (Split-Path $repoRoot -Parent) "logs-soak-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# logcat 过滤: Invariant-F1 / PTT 指标 / 组同步 / floor / ICE
$logcatGrep = "Talkback:I"

function Invoke-Adb {
    param([string]$Device, [string[]]$AdbArgs)
    & $adb -s $Device @AdbArgs
}

$devices = @(
    @{ Id = $M01; Label = "M01" },
    @{ Id = $M02; Label = "M02" },
    @{ Id = $M03; Label = "M03" }
)

Write-Host "adb: $adb"
Write-Host "out: $OutDir"
Write-Host "duration: ${DurationMin} min"

if ($InstallApk) {
    $apkFull = Join-Path $repoRoot $ApkPath
    if (-not (Test-Path $apkFull)) {
        Write-Error "APK not found: $apkFull (run gradlew :talkback-app:assembleDebug)"
        exit 1
    }
    foreach ($d in $devices) {
        Write-Host "Installing $($d.Label) ($($d.Id))..."
        Invoke-Adb $d.Id @("install", "-r", $apkFull)
    }
}

foreach ($d in $devices) {
    Write-Host "Clear logcat $($d.Label)..."
    Invoke-Adb $d.Id @("logcat", "-c")
}

$durationSec = $DurationMin * 60
$jobs = @()
foreach ($d in $devices) {
    $outFile = Join-Path $OutDir "$($d.Label).txt"
    Write-Host "Start capture $($d.Label) -> $outFile"
    $job = Start-Job -ScriptBlock {
        param($AdbPath, $DevId, $Grep, $Path, $Sec)
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $AdbPath
        $psi.Arguments = "-s $DevId logcat -v time Talkback:I *:S"
        $psi.RedirectStandardOutput = $true
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $p = [System.Diagnostics.Process]::Start($psi)
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $lines = [System.Collections.Generic.List[string]]::new()
        while ($sw.Elapsed.TotalSeconds -lt $Sec) {
            if ($p.StandardOutput.EndOfStream) { Start-Sleep -Milliseconds 100; continue }
            $line = $p.StandardOutput.ReadLine()
            if ($null -eq $line) { Start-Sleep -Milliseconds 50; continue }
            $lines.Add($line)
        }
        try { $p.Kill() } catch { }
        $lines | Set-Content -Path $Path -Encoding UTF8
    } -ArgumentList $adb, $d.Id, $logcatGrep, $outFile, $durationSec
    $jobs += $job
}

Write-Host ""
Write-Host "=== SOAK RUNNING ${DurationMin} min ==="
Write-Host "三台进 CH-01，按 Stage A 脚本操作（见控制台说明）"
Write-Host "采集结束时间: $((Get-Date).AddMinutes($DurationMin).ToString('HH:mm:ss'))"
Write-Host ""

Wait-Job $jobs | Out-Null
Receive-Job $jobs -ErrorAction SilentlyContinue | Out-Null
Remove-Job $jobs -Force -ErrorAction SilentlyContinue

Write-Host "Done. Logs in $OutDir"
Write-Host ""
Write-Host "=== 快速验收 grep (PowerShell) ==="
Write-Host @"
`$dir = '$OutDir'
Get-ChildItem `$dir\*.txt | ForEach-Object {
  `$f = `$_.Name
  `$c = Get-Content `$_.FullName -Raw
  `$f1 = ([regex]::Matches(`$c,'INVARIANT_F1_BREAK')).Count
  `$ptt = ([regex]::Matches(`$c,'PTT_TIMING event=PTT_DOWN')).Count
  `$cap = ([regex]::Matches(`$c,'PTT_TIMING event=captureON')).Count
  `$grant = ([regex]::Matches(`$c,'PTT_TIMING event=GRANT_APPLIED')).Count
  `$mismatch = ([regex]::Matches(`$c,'CANONICAL_MISMATCH')).Count
  Write-Host "`$f F1_BREAK=`$f1 PTT_DOWN=`$ptt GRANT=`$grant captureON=`$cap CANONICAL_MISMATCH=`$mismatch"
}
"@
