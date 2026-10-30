# ADR-0058 — adjudicate underlay dual-node multicast probe
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [double]$MinDeliveryRate = 0.90,
    [long]$MaxInterArrivalJitterP99Ms = 120,
    [int]$MaxConsecutiveLoss = 5
)

$ErrorActionPreference = "Stop"

function Find-ReportJson {
    param([string]$Dir, [string]$Module, [string]$Role)
    $direct = Join-Path $Dir "device-reports\$Module-$Role.json"
    if (Test-Path $direct) { return $direct }
    $glob = Get-ChildItem -Path (Join-Path $Dir "device-reports") -Filter "*-$Role.json" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "$Module-*" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($glob) { return $glob.FullName }
    return $null
}

function Read-JsonFile([string]$Path) {
    if (-not $Path -or -not (Test-Path $Path)) { return $null }
    return Get-Content $Path -Raw | ConvertFrom-Json
}

$metaFile = Join-Path $LogDir "RUN_META.txt"
$senderModule = "M01"
$receiverModule = "M02"
if (Test-Path $metaFile) {
    $meta = Get-Content $metaFile -Raw
    if ($meta -match "sender=(M0[123])") { $senderModule = $Matches[1] }
    if ($meta -match "receiver=(M0[123])") { $receiverModule = $Matches[1] }
}

$senderPath = Find-ReportJson -Dir $LogDir -Module $senderModule -Role "sender"
$receiverPath = Find-ReportJson -Dir $LogDir -Module $receiverModule -Role "receiver"
$sender = Read-JsonFile $senderPath
$receiver = Read-JsonFile $receiverPath

$adj = [System.Collections.Generic.List[string]]::new()
$adj.Add("ADR-0058 Underlay Multicast Dual-Node Probe — Adjudication")
$adj.Add("LogDir: $(Split-Path $LogDir -Leaf)")
$adj.Add("Direction: $senderModule -> $receiverModule")
$adj.Add("")

# --- G0 setup ---
$g0Pass = $false
$adj.Add("=== G0 Transport setup (multicast join) ===")
if (-not $receiver) {
    $adj.Add("FAIL: receiver report missing")
} else {
    $join = [bool]$receiver.joinSucceeded
    $lockReleased = [bool]$receiver.multicastLockReleasedOnEnd
    $adj.Add(("  receiver.joinSucceeded              {0}" -f $join))
    $adj.Add(("  receiver.multicastLockReleasedOnEnd {0}" -f $lockReleased))
  if ($sender) {
        $adj.Add(("  sender.joinSucceeded                {0}" -f ([bool]$sender.joinSucceeded)))
    } else {
        $adj.Add("  WARN: sender report missing")
    }
    $g0Pass = $join -and $lockReleased
}
$adj.Add(("G0_VERDICT={0}" -f $(if ($g0Pass) { "PASS" } else { "FAIL" })))
$adj.Add("")

# --- G1 delivery ---
$g1Pass = $false
$deliveryRate = $null
$adj.Add("=== G1 Delivery rate (receiver / sender) ===")
if ($sender -and $receiver) {
    $sent = [double]$sender.metrics.packetsSent
    $received = [double]$receiver.metrics.receivedCount
    if ($sent -gt 0) {
        $deliveryRate = $received / $sent
        $adj.Add(("  packetsSent={0} received={1} deliveryRate={2:P2} threshold={3:P0}" -f $sent, $received, $deliveryRate, $MinDeliveryRate))
        $g1Pass = $deliveryRate -ge $MinDeliveryRate
    } else {
        $adj.Add("FAIL: packetsSent=0")
    }
} else {
    $adj.Add("FAIL: missing sender or receiver JSON")
}
$adj.Add(("G1_VERDICT={0}" -f $(if ($g1Pass) { "PASS" } else { "FAIL" })))
$adj.Add("")

# --- G2 PLC budget ---
$g2Pass = $false
$adj.Add("=== G2 Consecutive loss vs MaxConsecutivePlcFrames (=5) ===")
if ($receiver) {
    $maxLoss = [int]$receiver.metrics.maxConsecutiveLoss
    $adj.Add(("  maxConsecutiveLoss={0} frozenMax={1}" -f $maxLoss, $MaxConsecutiveLoss))
    $adj.Add("  OBS interiorConsecutiveLoss=maxConsecutiveLoss (holes between firstSeq..lastSeq only)")
    if ($sender -and $receiver.metrics.lastSeq -ne $null -and $sender.metrics.lastSeqSent -ne $null) {
        $lastRecv = [long]$receiver.metrics.lastSeq
        $lastSent = [long]$sender.metrics.lastSeqSent
        $tailMiss = [Math]::Max(0, $lastSent - $lastRecv)
        $adj.Add(("  OBS terminalTailMissingPackets={0} (lastReceivedSeq+1..senderFinalSeq; NOT in G2 gate)" -f $tailMiss))
        if ($tailMiss -gt 0 -and $receiver.metrics.firstSeq -ne $null) {
            $adj.Add(("  OBS firstSeq={0} lastSeq={1} lastSeqSent={2}" -f $receiver.metrics.firstSeq, $lastRecv, $lastSent))
        }
    } elseif ($receiver.metrics.receivedCount -eq 0 -and $sender) {
        $adj.Add(("  OBS terminalTailMissingPackets={0} (zero receive; full sender span)" -f $sender.metrics.lastSeqSent))
        $adj.Add("  OBS firstSeq=n/a (no packets received)")
    }
    $g2Pass = $maxLoss -le $MaxConsecutiveLoss
} else {
    $adj.Add("FAIL: receiver report missing")
}
$adj.Add("  NOTE: terminal dropout / zero-delivery is a reachability/liveness concern, not G2 PLC burst")
$adj.Add(("G2_VERDICT={0}" -f $(if ($g2Pass) { "PASS" } else { "FAIL" })))
$adj.Add("")

# --- G3 jitter ---
$g3Pass = $false
$adj.Add("=== G3 Inter-arrival jitter P99 vs MaxPlayoutDelayMs (=120) ===")
if ($receiver) {
    $p99 = $receiver.metrics.interArrivalJitterMsP99
    if ($null -eq $p99) {
        $adj.Add("FAIL: interArrivalJitterMsP99 missing (insufficient samples?)")
    } else {
        $p99v = [long]$p99
        $adj.Add(("  interArrivalJitterMsP99={0} threshold={1}" -f $p99v, $MaxInterArrivalJitterP99Ms))
        $g3Pass = $p99v -le $MaxInterArrivalJitterP99Ms
    }
    if ($receiver.metrics.oneWayDelayMsP99) {
        $adj.Add(("  NOTE oneWayDelayMsP99={0} (clock-skew; not a hard gate)" -f $receiver.metrics.oneWayDelayMsP99))
    }
    $gapOver120 = $receiver.metrics.interArrivalGapOver120MsCount
    $maxGap = $receiver.metrics.maxInterArrivalGapMs
    if ($null -ne $gapOver120) {
        $adj.Add(("  OBS interArrivalGapOver120MsCount={0}" -f $gapOver120))
    } else {
        $adj.Add("  OBS interArrivalGapOver120MsCount=UNKNOWN (v1 JSON)")
    }
    if ($null -ne $maxGap) {
        $adj.Add(("  OBS maxInterArrivalGapMs={0}" -f $maxGap))
    } else {
        $adj.Add("  OBS maxInterArrivalGapMs=UNKNOWN (v1 JSON)")
    }
} else {
    $adj.Add("FAIL: receiver report missing")
}
$adj.Add(("G3_VERDICT={0}" -f $(if ($g3Pass) { "PASS" } else { "FAIL" })))
$adj.Add("")

$overall = $g0Pass -and $g1Pass -and $g2Pass -and $g3Pass
$adj.Add("=== Overall ===")
$adj.Add(("UNDERLAY_MULTICAST_PROBE_VERDICT={0}" -f $(if ($overall) { "PASS" } else { "FAIL" })))
$adj.Add("")
$adj.Add("Interpretation:")
$adj.Add("  PASS  => ADR-0059 underlay premise plausible for 2-node 50pps/120B probe")
$adj.Add("  FAIL  => do NOT proceed to media-engine investment without underlay remediation")
$adj.Add("  This probe does NOT authorize 0056 cutover or 8-10 node capacity")

$adjPath = Join-Path $LogDir "ADJUDICATION.txt"
$adj -join "`n" | Set-Content $adjPath -Encoding UTF8
Write-Host $adjPath
Get-Content $adjPath
