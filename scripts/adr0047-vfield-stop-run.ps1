# ADR-0047 V-field' — stop collectors + snapshot full logcat dump
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "2d73067a"
    "M03" = "MDX0220416001963"
}

$adb = $null
foreach ($c in @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "C:\Users\happy\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
    )) {
    if (Test-Path $c) { $adb = $c; break }
}
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) { throw "adb not found" }

if (-not (Test-Path $LogDir)) { throw "LogDir not found: $LogDir" }

$pidsFile = Join-Path $LogDir "COLLECTOR_PIDS.txt"
if (Test-Path $pidsFile) {
    Get-Content $pidsFile | ForEach-Object {
        if ($_ -match "collectorPid=(\d+)") {
            $collectorPid = [int]$Matches[1]
            Stop-Process -Id $collectorPid -Force -ErrorAction SilentlyContinue
            Write-Host "STOPPED collector pid=$collectorPid"
        }
    }
}

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $dump = Join-Path $LogDir "$name-logcat-dump.txt"
    & $adb -s $serial logcat -d -v time -s Talkback:I *:S | Out-File -FilePath $dump -Encoding utf8
    Write-Host "DUMP $name -> $dump"
}

"stopped=$(Get-Date -Format o)" | Add-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8
Write-Host "Done. LogDir=$LogDir"
