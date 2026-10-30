# P0.2 — Production Case A Smoke adjudication (G1–G4)
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"

function Resolve-LogPath {
    param([string]$Dir, [string]$Module)
    foreach ($name in @("$Module-talkback.log", "$Module-logcat-dump.txt")) {
        $p = Join-Path $Dir $name
        if (Test-Path $p) { return $p }
    }
    return Join-Path $Dir "$Module-talkback.log"
}

function Count-Hits {
    param([string]$Path, [string]$Pattern)
    if (-not (Test-Path $Path)) { return 0 }
    @(Select-String -Path $Path -Pattern $Pattern).Count
}

function First-HitLine {
    param([string]$Path, [string]$Pattern)
    if (-not (Test-Path $Path)) { return $null }
    $m = Select-String -Path $Path -Pattern $Pattern | Select-Object -First 1
    if ($m) { return $m.Line.Trim() }
    return $null
}

function Last-HitLine {
    param([string]$Path, [string]$Pattern)
    if (-not (Test-Path $Path)) { return $null }
    $m = Select-String -Path $Path -Pattern $Pattern | Select-Object -Last 1
    if ($m) { return $m.Line.Trim() }
    return $null
}

$m01 = Resolve-LogPath -Dir $LogDir -Module "M01"
$m02 = Resolve-LogPath -Dir $LogDir -Module "M02"
$m03 = Resolve-LogPath -Dir $LogDir -Module "M03"
$all = @{ M01 = $m01; M02 = $m02; M03 = $m03 }

$audit = [System.Collections.Generic.List[string]]::new()
$adj = [System.Collections.Generic.List[string]]::new()
$audit.Add("P0.2 Production Case A Smoke — Audit")
$audit.Add("LogDir: $(Split-Path $LogDir -Leaf)")
$audit.Add("")

# --- G1 (from file if present) ---
$g1File = Join-Path $LogDir "PRODUCTION_SMOKE_G1.txt"
$g1Pass = $false
if (Test-Path $g1File) {
    $g1Text = Get-Content $g1File -Raw
    $g1Pass = $g1Text -match "G1_VERDICT=PASS"
    $audit.Add("=== G1 Production artifact identity ===")
    $audit.Add($g1Text.Trim())
} else {
    $audit.Add("=== G1 Production artifact identity ===")
    $audit.Add("WARN: PRODUCTION_SMOKE_G1.txt missing — run adr0057-production-smoke-g1-verify.ps1")
}

# --- Forbidden inst tags in logs ---
$audit.Add("")
$audit.Add("=== Inst instrumentation (must be absent) ===")
$instForbidden = @("F8_NATIVE_", "F8_PORT_", "F8_CANDIDATE_", "F8_QUERY_DEFAULT_LOCAL", "fallback_same_network_best_ip")
$instPass = $true
foreach ($tag in $instForbidden) {
    $n = 0
    foreach ($p in $all.Values) { $n += (Count-Hits $p $tag) }
    $ok = ($n -eq 0)
    if (-not $ok) { $instPass = $false }
    $audit.Add(("  {0,-35} {1,4} {2}" -f $tag, $n, $(if ($ok) { "OK" } else { "HIT" })))
}

# --- G2 Transport ---
$audit.Add("")
$audit.Add("=== G2 Required triad transport (ICE_CONNECTED) ===")
$g2 = [ordered]@{
    "M01 ICE_CONNECTED M02" = (Count-Hits $m01 "ICE_CONNECTED.*peer=M02|peer=M02.*ICE_CONNECTED|ice=CONNECTED.*M02|M02.*ice=CONNECTED")
    "M01 ICE_CONNECTED M03" = (Count-Hits $m01 "ICE_CONNECTED.*peer=M03|peer=M03.*ICE_CONNECTED|ice=CONNECTED.*M03|M03.*ice=CONNECTED")
    "M02 ICE_CONNECTED M01" = (Count-Hits $m02 "ICE_CONNECTED.*peer=M01|peer=M01.*ICE_CONNECTED|ice=CONNECTED.*M01|M01.*ice=CONNECTED")
    "M02 ICE_CONNECTED M03" = (Count-Hits $m02 "ICE_CONNECTED.*peer=M03|peer=M03.*ICE_CONNECTED|ice=CONNECTED.*M03|M03.*ice=CONNECTED")
    "M03 ICE_CONNECTED M01" = (Count-Hits $m03 "ICE_CONNECTED.*peer=M01|peer=M01.*ICE_CONNECTED|ice=CONNECTED.*M01|M01.*ice=CONNECTED")
    "M03 ICE_CONNECTED M02" = (Count-Hits $m03 "ICE_CONNECTED.*peer=M02|peer=M02.*ICE_CONNECTED|ice=CONNECTED.*M02|M02.*ice=CONNECTED")
}
$g2Pass = $true
foreach ($k in $g2.Keys) {
    $n = $g2[$k]
    $ok = $n -ge 1
    if (-not $ok) { $g2Pass = $false }
    $audit.Add(("  {0,-35} {1,4} {2}" -f $k, $n, $(if ($ok) { "OK" } else { "MISS" })))
}

# --- G1 auxiliary: LAN GENERATED on M02/M03 ---
$audit.Add("")
$audit.Add("=== G1 auxiliary — LAN host candidates (M02/M03) ===")
$lanM02 = (Count-Hits $m02 "ICE_TRANSPORT_CANDIDATE.*GENERATED.*192\.168\.10\.")
$lanM03 = (Count-Hits $m03 "ICE_TRANSPORT_CANDIDATE.*GENERATED.*192\.168\.10\.")
$lanPass = ($lanM02 -ge 1) -and ($lanM03 -ge 1)
$audit.Add(("  M02 GENERATED 192.168.10.x           {0,4} {1}" -f $lanM02, $(if ($lanM02 -ge 1) { "OK" } else { "MISS" })))
$audit.Add(("  M03 GENERATED 192.168.10.x           {0,4} {1}" -f $lanM03, $(if ($lanM03 -ge 1) { "OK" } else { "MISS" })))
$audit.Add("NOTE: LAN candidates are auxiliary; G2/G3/G4 are hard gates")

# --- G3 Readiness ---
$audit.Add("")
$audit.Add("=== G3 GROUP readiness (triad OPERATIONAL + transmitReady) ===")
$g3Pass = $true
foreach ($pair in @(
    @{ Name = "M01"; Path = $m01 },
    @{ Name = "M02"; Path = $m02 },
    @{ Name = "M03"; Path = $m03 }
)) {
    $triad = Count-Hits $pair.Path "transmitReady=true.*joinedMembers=M01,M02,M03.*meshRecoveryState=OPERATIONAL|meshRecoveryState=OPERATIONAL.*transmitReady=true.*joinedMembers=M01,M02,M03"
    $ok = $triad -ge 1
    if (-not $ok) { $g3Pass = $false }
    $audit.Add(("  {0} triad OPERATIONAL+transmitReady {1,4} {2}" -f $pair.Name, $triad, $(if ($ok) { "OK" } else { "MISS" })))
    $last = Last-HitLine $pair.Path "GROUP_TRANSITION_READINESS_SNAPSHOT.*joinedMembers=M01,M02,M03"
    if ($last) { $audit.Add("    last: $($last.Substring(0, [Math]::Min(160, $last.Length)))...") }
}

# --- G4 Stability ---
$audit.Add("")
$audit.Add("=== G4 Post-triad stability ===")
$iceFail = 0
$oblReopen = 0
$postTriadFalse = 0
foreach ($p in $all.Values) {
    $iceFail += (Count-Hits $p "ICE_FAILED|ICE_DISCONNECTED|ice=FAILED")
    $oblReopen += (Count-Hits $p "ObligationOpen|obligation.*reopen")
}
# After first triad snapshot, count transmitReady=false with full triad membership
foreach ($pair in @(
    @{ Name = "M01"; Path = $m01 },
    @{ Name = "M02"; Path = $m02 },
    @{ Name = "M03"; Path = $m03 }
)) {
    if (-not (Test-Path $pair.Path)) { continue }
    $lines = Get-Content $pair.Path
    $triadSeen = $false
    foreach ($line in $lines) {
        if ($line -match "joinedMembers=M01,M02,M03" -and $line -match "meshRecoveryState=OPERATIONAL" -and $line -match "transmitReady=true") {
            $triadSeen = $true
            continue
        }
        if ($triadSeen -and $line -match "joinedMembers=M01,M02,M03" -and $line -match "transmitReady=false") {
            $postTriadFalse++
        }
    }
}
$g4Pass = ($iceFail -eq 0) -and ($oblReopen -eq 0)
$audit.Add(("  ICE_FAILED/DISCONNECTED (all)         {0,4} {1}" -f $iceFail, $(if ($iceFail -eq 0) { "OK" } else { "HIT" })))
$audit.Add(("  GBC obligation reopen (all)           {0,4} {1}" -f $oblReopen, $(if ($oblReopen -eq 0) { "OK" } else { "HIT" })))
$audit.Add(("  post-triad transmitReady=false (triad) {0,4} {1}" -f $postTriadFalse, $(if ($postTriadFalse -eq 0) { "OK" } else { "WARN bounded transient allowed" })))
if ($postTriadFalse -gt 3) { $g4Pass = $false }

# --- Final snapshots ---
$audit.Add("")
$audit.Add("=== Final readiness snapshots ===")
foreach ($pair in @(
    @{ Name = "M01"; Path = $m01 },
    @{ Name = "M02"; Path = $m02 },
    @{ Name = "M03"; Path = $m03 }
)) {
    $last = Last-HitLine $pair.Path "GROUP_TRANSITION_READINESS_SNAPSHOT"
    if ($last) {
        $audit.Add("$($pair.Name): $($last.Substring(0, [Math]::Min(200, $last.Length)))")
    } else {
        $audit.Add("$($pair.Name): (no snapshot)")
        $g3Pass = $false
    }
}

# --- Verdict ---
$hardPass = $g1Pass -and $instPass -and $g2Pass -and $g3Pass -and $g4Pass
$verdict = if ($hardPass) { "PASS" } else { "FAIL" }

$audit.Add("")
$audit.Add("=== Verdict ===")
$audit.Add("G1 artifact:     $(if ($g1Pass) { 'PASS' } else { 'FAIL' })")
$audit.Add("Inst forbidden:  $(if ($instPass) { 'PASS' } else { 'FAIL' })")
$audit.Add("G2 transport:    $(if ($g2Pass) { 'PASS' } else { 'FAIL' })")
$audit.Add("G3 readiness:    $(if ($g3Pass) { 'PASS' } else { 'FAIL' })")
$audit.Add("G4 stability:    $(if ($g4Pass) { 'PASS' } else { 'FAIL' })")
$audit.Add("LAN auxiliary:   $(if ($lanPass) { 'PASS' } else { 'N/A or MISS' })")
$audit.Add("")
$audit.Add("PRODUCTION_SMOKE_VERDICT=$verdict")

$adj.Add("P0.2 Production Case A Smoke — Adjudication")
$adj.Add("LogDir: $(Split-Path $LogDir -Leaf)")
$adj.Add("")
if ($verdict -eq "PASS") {
    $adj.Add("VERDICT: PASS")
    $adj.Add("P0.2 Production Case A Smoke: PASS")
    $adj.Add("Production Artifact Ship Readiness: PASS")
    $adj.Add("ADR-0057: remains CLOSED")
} else {
    $adj.Add("VERDICT: FAIL")
    $adj.Add("P0.2 Production Case A Smoke: FAIL")
    $adj.Add("Open: Post-ADR-0057 Production Artifact Integration Incident")
    $adj.Add("DO NOT: reopen ADR-0057 / reopen F8 / expand F8-FIX-1 / open F9")
}

$auditPath = Join-Path $LogDir "PRODUCTION_SMOKE_AUDIT.txt"
$adjPath = Join-Path $LogDir "PRODUCTION_SMOKE_ADJUDICATION.txt"
$audit | Set-Content $auditPath -Encoding UTF8
$adj | Set-Content $adjPath -Encoding UTF8

$audit | ForEach-Object { Write-Host $_ }
Write-Host ""
Write-Host "Wrote $auditPath"
Write-Host "Wrote $adjPath"
exit $(if ($verdict -eq "PASS") { 0 } else { 1 })
