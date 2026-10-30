# Real Mesh E2E — Underlay Migration Validation launcher
# Nature: same product chain; real Mesh iface bind + cross-device multicast TX/RX.
# NOT N=10 capacity. NOT Meeting UI. NOT ADR-0056.
param(
    [ValidateSet("preflight", "prep", "start", "pull", "smoke")]
    [string]$Step = "preflight",
    [string]$RunId = "",
    [int]$DurationSec = 120,
    [string]$Iface = "wlan0",
    [string]$Sender = "M02",
    [string]$Receiver = "M01",
    [string]$ExtraReceiver = "",
    [string[]]$Devices = @("M01", "M02", "M03")
)

$ErrorActionPreference = "Stop"
if ($Devices.Count -eq 1 -and $Devices[0] -match ",") {
    $Devices = $Devices[0].Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
}

function Invoke-Adb([string]$Serial, [string[]]$ShellArgs) {
    # adb often writes diagnostics to stderr; do not treat as terminating.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = & $adb -s $Serial @ShellArgs 2>&1 | ForEach-Object { "$_" }
        return ,$lines
    } finally {
        $ErrorActionPreference = $prev
    }
}

$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = (Get-Command adb).Source
$TestPackage = "com.talkback.test"
$TestClass = "com.talkback.lab.conference.RealMeshE2EInstrumentedTest"

$deviceMap = @{
    "M01" = @{ Serial = "HTUBB21B09220661"; Module = "M01" }
    "M02" = @{ Serial = "DSJ-2407041"; Module = "M02" }
    "M03" = @{ Serial = "MDX0220416001963"; Module = "M03" }
}

if (-not $RunId) {
    $RunId = "real-mesh-e2e-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}
$outDir = Join-Path $repoRoot "logs\$RunId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Resolve-Serial([string]$Name) {
    $usb = $deviceMap[$Name].Serial
    $wirelessFile = Join-Path $repoRoot "logs\field-device-wireless.json"
    if (Test-Path $wirelessFile) {
        $json = Get-Content $wirelessFile -Raw | ConvertFrom-Json
        $hit = $json | Where-Object { $_.name -eq $Name } | Select-Object -First 1
        if ($hit -and $hit.wireless) {
            $state = (& $adb -s $hit.wireless get-state 2>&1 | Out-String).Trim()
            if ($state -eq "device") { return $hit.wireless }
        }
    }
    return $usb
}

function Invoke-Preflight {
    Write-Host "=== REAL MESH E2E PREFLIGHT iface=$Iface ==="
    Write-Host 'EVIDENCE: REAL MESH E2E / NOT N=10 / NOT Meeting UI / NOT ADR-0056'
    $summary = @()
    foreach ($name in $Devices) {
        $serial = Resolve-Serial $name
        Write-Host "--- $name ($serial) ---"
        $state = (Invoke-Adb $serial @("get-state") | Out-String).Trim()
        if ($state -ne "device") {
            Write-Warning "$name not online: $state"
            $summary += [pscustomobject]@{ Device = $name; Ok = $false; Reason = "OFFLINE"; Ip = "" }
            continue
        }
        $ifaces = Invoke-Adb $serial @("shell", "ip -o link show")
        $addr = Invoke-Adb $serial @("shell", "ip -o -4 addr show $Iface")
        $addrLine = ($addr | Out-String).Trim()
        $ip = ""
        if ($addrLine -match "inet\s+(\d+\.\d+\.\d+\.\d+)") { $ip = $Matches[1] }
        $ok = $ip -ne ""
        Write-Host "ifaces:`n$($ifaces -join "`n")"
        Write-Host "$Iface addr: $addrLine"
        Write-Host "parsedIp=$ip ok=$ok"
        $summary += [pscustomobject]@{ Device = $name; Ok = $ok; Reason = $(if ($ok) { "IFACE_UP" } else { "IFACE_NO_IPV4" }); Ip = $ip }
    }
    $summary | Format-Table -AutoSize | Out-String | Write-Host
    $summary | ConvertTo-Json | Set-Content -Path (Join-Path $outDir "preflight.json") -Encoding utf8

    $up = @($summary | Where-Object { $_.Ok })
    if ($up.Count -lt 2) {
        Write-Host "ENV_BLOCKED R1: need >=2 devices with $Iface IPv4 (have $($up.Count))"
        return
    }
    $prefixes = @($up | ForEach-Object { ($_.Ip -split '\.')[0..2] -join '.' } | Select-Object -Unique)
    if ($prefixes.Count -gt 1) {
        Write-Host "ENV_BLOCKED R1: divergent L3 underlay prefixes: $($prefixes -join ', ')"
        Write-Host "Bring all participants onto the SAME Mesh underlay before start."
        return
    }
    Write-Host "PREFLIGHT_OK candidates=$($up.Device -join ',') prefix=$($prefixes[0]).x iface=$Iface"
    Write-Host "Next: start with -Sender/-Receiver on same underlay (ExtraReceiver optional)."
}

function Invoke-Prep {
    $appApk = Join-Path $repoRoot "talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
    $testApk = Join-Path $repoRoot "android-board-talkback\build\outputs\apk\androidTest\debug\android-board-talkback-debug-androidTest.apk"
    Write-Host "Building APKs (product + androidTest)..."
    Push-Location $repoRoot
    try {
        & .\gradlew.bat :talkback-app:assembleDebug :android-board-talkback:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw "gradle build failed exit=$LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    $targets = @($Sender, $Receiver)
    if ($ExtraReceiver) { $targets += $ExtraReceiver }
    $targets = $targets | Select-Object -Unique
    foreach ($name in $targets) {
        $serial = Resolve-Serial $name
        Write-Host "INSTALL $name ($serial)"
        $ErrorActionPreference = "Continue"
        & $adb -s $serial install -r $appApk | Out-Host
        & $adb -s $serial install -r $testApk | Out-Host
        $ErrorActionPreference = "Stop"
    }
}

function Start-Role([string]$Name, [string]$Role) {
    $serial = Resolve-Serial $Name
    $module = $deviceMap[$Name].Module
    $outLog = Join-Path $outDir "$Name-instrument.out.txt"
    $errLog = Join-Path $outDir "$Name-instrument.err.txt"
    Write-Host "START $Name role=$Role serial=$serial iface=$Iface"
    $argList = @(
        "-s", $serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $TestClass,
        "-e", "runId", $RunId,
        "-e", "durationSec", "$DurationSec",
        "-e", "deviceLabel", $Name,
        "-e", "localModuleId", $module,
        "-e", "role", $Role,
        "-e", "iface", $Iface,
        "-e", "leaveRejoin", "true",
        "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
    )
    Start-Process -FilePath $adb -ArgumentList $argList `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
        -WindowStyle Hidden -PassThru
}

function Invoke-Start {
    if ([string]::IsNullOrWhiteSpace($Iface)) {
        throw "Iface required (R1) - refuse default-route accidental success"
    }
    Write-Host "RUN_ID=$RunId DURATION=$DurationSec IFACE=$Iface SENDER=$Sender RECEIVER=$Receiver EXTRA=$ExtraReceiver"
    Write-Host 'EVIDENCE HEADER: REAL MESH E2E / 2-3 devices / REAL MESH multicast / PRODUCT-CHAIN'
    Write-Host 'NOT N=10 / NOT Meeting UI / NOT ADR-0056'

    # Receivers first so IGMP join precedes TX
    $recvJobs = @()
    $recvJobs += Start-Role $Receiver "RECEIVER"
    if ($ExtraReceiver -and $deviceMap.ContainsKey($ExtraReceiver)) {
        Start-Sleep -Seconds 2
        $recvJobs += Start-Role $ExtraReceiver "RECEIVER"
    }
    Start-Sleep -Seconds 5
    $sendJob = Start-Role $Sender "SENDER"

    Write-Host "Waiting for instrumented runs (duration=${DurationSec}s + buffer)..."
    $waitSec = $DurationSec + 120
    $deadline = (Get-Date).AddSeconds($waitSec)
    $all = @($recvJobs) + @($sendJob)
    while ((Get-Date) -lt $deadline) {
        $alive = @($all | Where-Object { -not $_.HasExited })
        if ($alive.Count -eq 0) { break }
        Start-Sleep -Seconds 5
    }
    foreach ($p in $all) {
        if (-not $p.HasExited) {
            Write-Warning "process still running pid=$($p.Id) — continue to pull"
        }
    }
}

function Invoke-Pull {
    $names = @($Sender, $Receiver)
    if ($ExtraReceiver) { $names += $ExtraReceiver }
    $names = $names | Select-Object -Unique
    foreach ($name in $names) {
        $serial = Resolve-Serial $name
        $remoteRel = "files/real-mesh-e2e-$RunId"
        $local = Join-Path $outDir $name
        New-Item -ItemType Directory -Force -Path $local | Out-Null
        $listing = & $adb -s $serial shell "run-as com.talkback.test ls $remoteRel" 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "No report dir on $name ($serial): $listing"
            continue
        }
        foreach ($file in ($listing | ForEach-Object { "$_".Trim() } | Where-Object { $_ -and $_ -notmatch "Permission|No such" })) {
            $content = & $adb -s $serial shell "run-as com.talkback.test cat $remoteRel/$file" 2>&1
            if ($LASTEXITCODE -eq 0) {
                $outFile = Join-Path $local $file
                Set-Content -Path $outFile -Value ($content -join "`n") -Encoding utf8
                Write-Host "PULLED $name/$file"
            } else {
                Write-Warning "pull failed $name/$file : $content"
            }
        }
    }
    Write-Host "PULLED -> $outDir"
    Write-Host "Adjudicate R1-R4 from *-report.json (receiver wireAdmitAccepted>0 required for R2)."
}

switch ($Step) {
    "preflight" { Invoke-Preflight }
    "prep" { Invoke-Prep }
    "start" { Invoke-Start; Invoke-Pull }
    "pull" { Invoke-Pull }
    "smoke" {
        $DurationSec = 15
        if (-not $RunId.StartsWith("real-mesh")) {
            $RunId = "real-mesh-smoke-" + (Get-Date -Format "yyyyMMdd-HHmmss")
        }
        Invoke-Preflight
        $pf = Get-Content (Join-Path $outDir "preflight.json") -Raw | ConvertFrom-Json
        $senderOk = @($pf | Where-Object { $_.Device -eq $Sender -and $_.Ok }).Count -gt 0
        $recvOk = @($pf | Where-Object { $_.Device -eq $Receiver -and $_.Ok }).Count -gt 0
        if (-not ($senderOk -and $recvOk)) {
            Write-Host "SMOKE skipped: ENV_BLOCKED R1 (Sender/Receiver iface not ready)"
            break
        }
        $sIp = ($pf | Where-Object { $_.Device -eq $Sender }).Ip
        $rIp = ($pf | Where-Object { $_.Device -eq $Receiver }).Ip
        $sPref = ($sIp -split '\.')[0..2] -join '.'
        $rPref = ($rIp -split '\.')[0..2] -join '.'
        if ($sPref -ne $rPref) {
            Write-Host "SMOKE skipped: ENV_BLOCKED R1 divergent underlay $sIp vs $rIp"
            break
        }
        Invoke-Start
        Invoke-Pull
    }
}

Write-Host "DONE step=$Step runId=$RunId"
Write-Host "Card: docs/analysis/real-mesh-e2e-underlay-migration-run-card.md"
