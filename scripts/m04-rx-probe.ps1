# M04 multicast RX probe — Step 1 of M03/M04 level & starvation triage.
# Captures 4-device logcat for -DurationSec, samples M04 wifi/power/multicast
# state every -SampleSec, then summarizes per-device RX pps, ingress source
# distribution, REAL frames per source, and TX capture RMS. Observation only.
param(
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "MDX0220416001963",
    [string]$M04Serial = "192.168.31.206:5555",
    [int]$DurationSec = 180,
    [int]$SampleSec = 10,
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = (Get-Command adb).Source

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "m04-rx-probe-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}
$logDir = Join-Path $repoRoot "logs\$RunId"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# 239.255.42.99 as /proc/net/igmp little-endian hex
$groupHex = "632AFFEF"

function Start-ObsLog([string]$Serial, [string]$Label) {
    & $adb -s $Serial logcat -c 2>&1 | Out-Null
    $out = Join-Path $logDir "$Label-logcat.txt"
    $args = @("-s", $Serial, "logcat", "-v", "threadtime",
        "-s", "Talkback:I", "MEETING_MEDIA_SHADOW:I", "*:S")
    $proc = Start-Process -FilePath $adb -ArgumentList $args `
        -RedirectStandardOutput $out -PassThru -NoNewWindow
    return @{ Label = $Label; Serial = $Serial; Path = $out; Process = $proc }
}

function Sample-M04State([string]$Serial) {
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    $wake = (& $adb -s $Serial shell "dumpsys power | grep -E 'mWakefulness=|mIsPowered='" 2>&1 | Out-String) -replace '\s+', ' '
    $locks = (& $adb -s $Serial shell "dumpsys wifi | grep -A3 'Multicast Locks held'" 2>&1 | Out-String) -replace '\s+', ' '
    $igmp = (& $adb -s $Serial shell "cat /proc/net/igmp" 2>&1 | Out-String)
    $ErrorActionPreference = $prev
    $joined = if ($igmp -match $groupHex) { "JOINED" } else { "NOT_JOINED" }
    $lockHeld = if ($locks -match 'talkback\.conference\.media') { "HELD" } else { "NOT_HELD" }
    return "$(Get-Date -Format 'HH:mm:ss') group239.255.42.99=$joined mcastLock=$lockHeld $wake"
}

if ($M04Serial -match '^\d+\.\d+\.\d+\.\d+:') { & $adb connect $M04Serial 2>&1 | Out-Null }

$handles = @()
foreach ($e in @(
    @{ Label = "M01"; Serial = $M01Serial }, @{ Label = "M02"; Serial = $M02Serial },
    @{ Label = "M03"; Serial = $M03Serial }, @{ Label = "M04"; Serial = $M04Serial })) {
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    $state = (& $adb -s $e.Serial get-state 2>&1 | Out-String).Trim()
    $ErrorActionPreference = $prev
    if ($state -ne "device") { Write-Warning "OFFLINE $($e.Label) $($e.Serial)"; continue }
    $handles += Start-ObsLog $e.Serial $e.Label
    Write-Host "logcat $($e.Label) -> $($handles[-1].Path)"
}

$statePath = Join-Path $logDir "M04-state-samples.txt"
Write-Host "sampling M04 state every ${SampleSec}s for ${DurationSec}s ..."
$deadline = (Get-Date).AddSeconds($DurationSec)
while ((Get-Date) -lt $deadline) {
    $line = Sample-M04State $M04Serial
    Add-Content -Path $statePath -Value $line
    Write-Host "  $line"
    Start-Sleep -Seconds $SampleSec
}

foreach ($h in $handles) { try { Stop-Process -Id $h.Process.Id -Force } catch {} }
Start-Sleep -Seconds 1

# ---------------- summary ----------------
$summary = @()
$summary += "RUN $RunId  duration=${DurationSec}s"
$summary += ""
foreach ($h in $handles) {
    $lbl = $h.Label
    $lines = Get-Content $h.Path
    $summary += "== $lbl =="

    # RX pps from SHADOW_RX_ACTIVITY receivedDatagrams (first/last with timestamps)
    $rx = $lines | Select-String 'SHADOW_RX_ACTIVITY.*receivedDatagrams=(\d+)'
    if ($rx.Count -ge 2) {
        $t0 = [datetime]::ParseExact(($rx[0].Line.Substring(0,18)), "MM-dd HH:mm:ss.fff", $null)
        $t1 = [datetime]::ParseExact(($rx[-1].Line.Substring(0,18)), "MM-dd HH:mm:ss.fff", $null)
        $d0 = [long]$rx[0].Matches[0].Groups[1].Value
        $d1 = [long]$rx[-1].Matches[0].Groups[1].Value
        $sec = ($t1 - $t0).TotalSeconds
        $pps = if ($sec -gt 0) { ($d1 - $d0) / $sec } else { 0 }
        $summary += ("  RX datagrams {0}->{1} over {2:N0}s = {3:N1} pps  (1 source ~50, 3 remote+self ~200)" -f $d0, $d1, $sec, $pps)
    } else { $summary += "  RX: no SHADOW_RX_ACTIVITY" }

    # ingress source distribution
    $ing = $lines | Select-String 'INGRESS_(FUNNEL|ACTIVITY).*moduleId=(M0\d)' | ForEach-Object { $_.Matches[0].Groups[2].Value } | Group-Object | Sort-Object Name
    $summary += "  ingress samples by source: " + (($ing | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join " ")

    # REAL vs PLC per remote source from A3_MIX_INPUT (sum over window)
    $mix = $lines | Select-String 'A3_MIX_INPUT.*sourceInput=(.*?) mixedFrames=\d+ mixedRmsDbfs=(-?[\d.]+)'
    if ($mix.Count -gt 0) {
        $agg = @{}
        foreach ($m in $mix) {
            foreach ($src in ($m.Matches[0].Groups[1].Value -split '\|')) {
                if ($src -match '^(M0\d):realFrames=(\d+),.*?plcFrames=(\d+)') {
                    if (-not $agg[$Matches[1]]) { $agg[$Matches[1]] = @{ real = 0L; plc = 0L } }
                    $agg[$Matches[1]].real += [long]$Matches[2]; $agg[$Matches[1]].plc += [long]$Matches[3]
                }
            }
        }
        $mixRms = $mix | ForEach-Object { [double]$_.Matches[0].Groups[2].Value } | Measure-Object -Average -Minimum -Maximum
        $summary += "  mix windows=$($mix.Count) mixedRms avg={0:N1} min={1} max={2}" -f $mixRms.Average, $mixRms.Minimum, $mixRms.Maximum
        foreach ($k in ($agg.Keys | Sort-Object)) {
            $r = $agg[$k].real; $p = $agg[$k].plc; $tot = $r + $p
            $pct = if ($tot -gt 0) { 100.0 * $r / $tot } else { 0 }
            $summary += ("    {0}: real={1} plc={2} realPct={3:N1}%" -f $k, $r, $p, $pct)
        }
    } else { $summary += "  mix: no A3_MIX_INPUT" }

    # TX capture RMS
    $tx = $lines | Select-String 'G5_VA2_ADJ.*rmsDbfs=(-?[\d.]+)' | ForEach-Object { [double]$_.Matches[0].Groups[1].Value }
    if ($tx) {
        $s = $tx | Measure-Object -Average -Minimum -Maximum
        $summary += ("  TX capture rms n={0} avg={1:N1} min={2} max={3} dBFS" -f $s.Count, $s.Average, $s.Minimum, $s.Maximum)
    } else { $summary += "  TX: no G5_VA2_ADJ" }
    $summary += ""
}
$summary += "== M04 state samples =="
$summary += (Get-Content $statePath)

$sumPath = Join-Path $logDir "summary.txt"
$summary | Set-Content -Path $sumPath
$summary | ForEach-Object { Write-Host $_ }
Write-Host "`nsummary -> $sumPath"
