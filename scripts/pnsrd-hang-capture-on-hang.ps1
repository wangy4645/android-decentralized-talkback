# Primary Native SRD Fault — hang capture EXECUTE (read-only, runbook §6)
# Invoke as soon as TIER_JNI is observed. Does NOT force-stop or retry SRD.

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [string]$HostSerial = "HTUBB21B09220661",
    [string]$Package = "com.talkback.appprod",
    [string]$FailingEdge = ""
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $LogDir)) {
    Write-Error "LogDir not found: $LogDir (run pnsrd-hang-capture-start-run.ps1 first)"
}
$adb = (Get-Command adb).Source
$capturedAt = Get-Date -Format o
Add-Content (Join-Path $LogDir "CAPTURE_README.txt") "`nhang_capture_started=$capturedAt"

# --- 6.1 Watchdog / lease snapshot ---
$snapshotPath = Join-Path $LogDir "watchdog-lease-snapshot.txt"
$patterns = @(
    "SRD_NATIVE_CALL_ENTER", "SRD_NATIVE_CALL_EXIT", "SRD_JNI_RETURN", "SRD_CALLBACK_ENTER",
    "SRD_WATCHDOG_GAP", "NATIVE_DOMAIN_EXECUTION_", "NATIVE_DOMAIN_LEASE_",
    "NATIVE_DOMAIN_HOLDER_", "DOMAIN_IDLE_E3", "DOMAIN_BLOCKED", "WEBRTC ICE",
    "SRD_MUTEX", "CONFERENCE_MEDIA_EDGE"
)
$m01Log = Join-Path $LogDir "M01-full.log"
if (Test-Path $m01Log) {
    Select-String -Path $m01Log -Pattern ($patterns -join "|") | Select-Object -Last 120 |
        ForEach-Object { $_.Line } | Set-Content $snapshotPath -Encoding UTF8
} else {
    & $adb -s $HostSerial logcat -d -v threadtime | Select-String -Pattern ($patterns -join "|") |
        Select-Object -Last 120 | ForEach-Object { $_.Line } | Set-Content $snapshotPath -Encoding UTF8
}
Write-Host "Wrote $snapshotPath"

# --- Anchor: last SRD_NATIVE_CALL_ENTER ---
$enterLines = @()
if (Test-Path $m01Log) {
    $enterLines = Select-String -Path $m01Log -Pattern "SRD_NATIVE_CALL_ENTER" | ForEach-Object { $_.Line }
} else {
    $enterLines = & $adb -s $HostSerial logcat -d -v threadtime | Select-String "SRD_NATIVE_CALL_ENTER" |
        ForEach-Object { $_.Line }
}
$lastEnter = $enterLines | Select-Object -Last 1
if ($lastEnter) {
    Set-Content (Join-Path $LogDir "timeline-anchor.txt") -Value $lastEnter -Encoding UTF8
    Write-Host "Anchor: $lastEnter"
} else {
    Write-Warning "No SRD_NATIVE_CALL_ENTER in log yet"
}

# --- 6.2 PID ---
$pidOut = & $adb -s $HostSerial shell pidof $Package 2>$null
$pidOut = ($pidOut -split "\s+")[0]
if (-not $pidOut) {
    Write-Error "pidof $Package failed on $HostSerial"
}
Set-Content (Join-Path $LogDir "PID.txt") -Value "pid=$pidOut package=$Package serial=$HostSerial captured=$capturedAt"
Write-Host "PID=$pidOut"

# --- 6.3 SIGQUIT (non-terminating) ---
Write-Host "Sending SIGQUIT (kill -3) — do not use kill -9"
& $adb -s $HostSerial shell kill -3 $pidOut
Start-Sleep -Seconds 4
& $adb -s $HostSerial logcat -d -v threadtime | Out-File (Join-Path $LogDir "M01-post-sigquit.log") -Encoding utf8
Write-Host "Wrote M01-post-sigquit.log"

& $adb -s $HostSerial logcat -d -v threadtime -s PNSRD_INST:* 2>$null |
    Out-File (Join-Path $LogDir "inst-events.log") -Encoding utf8
Write-Host "Wrote inst-events.log (PNSRD_INST)"

& $adb -s $HostSerial shell debuggerd -b $pidOut 2>&1 |
    Out-File (Join-Path $LogDir "debuggerd.txt") -Encoding utf8

# --- 6.4 Thread inventory ---
$threadList = & $adb -s $HostSerial shell "for t in /proc/$pidOut/task/*; do cat `$t/comm; done" 2>$null
$threadList | Sort-Object -Unique | Set-Content (Join-Path $LogDir "thread-comm.txt") -Encoding UTF8
Write-Host "Wrote thread-comm.txt"

# --- 6.5 Timeline grep from post-sigquit (native threads) ---
$nativeThreads = Join-Path $LogDir "native-thread-grep.txt"
Select-String -Path (Join-Path $LogDir "M01-post-sigquit.log") -Pattern `
    "signaling_thread|worker_thread|network_thread|tb-edge-|talkback-coordinator|AudioRecord|AudioTrack|WebRtcAudio" |
    Select-Object -First 200 | ForEach-Object { $_.Line } | Set-Content $nativeThreads -Encoding UTF8

# --- ADJUDICATION template ---
$adjPath = Join-Path $LogDir "ADJUDICATION.txt"
if (-not (Test-Path $adjPath)) {
    @(
        "capture_id: $(Split-Path $LogDir -Leaf)"
        "verdict: PENDING_REVIEW"
        "tier: TIER_JNI | TIER_PRE_JNI"
        "session_id:"
        "failing_edge: $FailingEdge"
        "host_srd_ordinal:"
        "device_host: M01 $HostSerial"
        "package: $Package"
        "webrtc: stream-webrtc-android:1.3.10"
        "captured_at: $capturedAt"
        ""
        "watchdog:"
        "  lastEvent="
        "  missing="
        "  state="
        "  holderEdge="
        ""
        "timeline_anchor: see timeline-anchor.txt"
        ""
        "stacks:"
        "  failing_edge_thread:"
        "    name="
        "    blocked_in="
        "  signaling_thread:"
        "    state="
        "    top_frames="
        "  worker_thread:"
        "    state="
        "    top_frames="
        "  network_thread:"
        "    state="
        "    top_frames="
        "  audio_threads:"
        "    names="
        "    top_frames="
        ""
        "mechanism_class: S1 | S2 | S3 | S7 | INCONCLUSIVE"
        "mechanism_statement:"
        ""
        "next_route: native_fix_review | architecture_capability_review | lower_trace_authorization"
        ""
        "prohibited_actions_confirmed: no_force_stop no_retry no_lease_release yes"
        ""
        "notes: Fill from M01-post-sigquit.log — PASS only if wait site named"
    ) | Set-Content $adjPath -Encoding UTF8
}

Write-Host ""
Write-Host "Capture complete. Review:"
Write-Host "  $adjPath"
Write-Host "  $LogDir\M01-post-sigquit.log"
Write-Host "  $LogDir\native-thread-grep.txt"
Write-Host "Stop collectors when done:"
Write-Host "  .\scripts\pnsrd-hang-capture-stop-run.ps1 -LogDir `"$LogDir`""
