# ADR-0058 E3a.2 — Delivery quality adjudication from existing probe JSON pairs
param(
    [Parameter(Mandatory = $true)]
    [string]$ForwardLogDir,
    [Parameter(Mandatory = $true)]
    [string]$ReverseLogDir,
    [int]$MaxConsecutivePlc = 5,
    [long]$MaxPlayoutDelayMs = 120
)

$ErrorActionPreference = "Stop"

function Read-Pair {
    param([string]$Dir)
    $reports = Join-Path $Dir "device-reports"
    $sender = Get-ChildItem $reports -Filter "*-sender.json" | Select-Object -First 1
    $receiver = Get-ChildItem $reports -Filter "*-receiver.json" | Select-Object -First 1
    if (-not $sender -or -not $receiver) { return $null }
    return @{
        Dir = $Dir
        Sender = Get-Content $sender.FullName -Raw | ConvertFrom-Json
        Receiver = Get-Content $receiver.FullName -Raw | ConvertFrom-Json
    }
}

function Get-MetricOrUnknown {
    param($Metrics, [string]$Name)
    if ($null -eq $Metrics) { return "UNKNOWN" }
    $props = @($Metrics.PSObject.Properties.Name)
    if ($props -notcontains $Name) { return "UNKNOWN" }
    $value = $Metrics.$Name
    if ($null -eq $value) { return "UNKNOWN" }
    return $value
}

function Score-Direction {
    param($Pair, [string]$Label)
    $metrics = $Pair.Receiver.metrics
    $sent = [double]$Pair.Sender.metrics.packetsSent
    $recv = [double]$metrics.receivedCount
    $delivery = if ($sent -gt 0) { $recv / $sent } else { 0 }
    $burst = [int]$metrics.maxConsecutiveLoss
    $gapP95 = $metrics.interArrivalMsP95
    $gapP99 = $metrics.interArrivalMsP99
    $jitP99 = $metrics.interArrivalJitterMsP99
    $gapsOver120 = Get-MetricOrUnknown $metrics "interArrivalGapOver120MsCount"
    $maxGap = Get-MetricOrUnknown $metrics "maxInterArrivalGapMs"

    $burstFail = $burst -gt $MaxConsecutivePlc
    $gapP99Fail = ($null -ne $gapP99) -and ([long]$gapP99 -gt $MaxPlayoutDelayMs)
    $severeBurst = $burst -ge 20
    $severeDelivery = $delivery -lt 0.50

    $verdict = "GREEN"
    if ($severeBurst -or $severeDelivery) { $verdict = "RED" }
    elseif ($burstFail -or $gapP99Fail) { $verdict = "AMBER" }

    return [ordered]@{
        Label = $Label
        RunId = $Pair.Receiver.runId
        DeliveryRate = $delivery
        PacketsSent = [int]$sent
        ReceivedCount = [int]$recv
        MaxConsecutiveLoss = $burst
        InterArrivalMsP95 = $gapP95
        InterArrivalMsP99 = $gapP99
        InterArrivalJitterMsP99 = $jitP99
        GapsOver120Count = $gapsOver120
        MaxInterArrivalGapMs = $maxGap
        BurstFail = $burstFail
        GapP99Fail = $gapP99Fail
        Verdict = $verdict
    }
}

$forward = Read-Pair $ForwardLogDir
$reverse = Read-Pair $ReverseLogDir
if (-not $forward -or -not $reverse) {
    throw "Missing sender/receiver JSON in one or both log dirs"
}

$fwdScore = Score-Direction $forward "M01->M02"
$revScore = Score-Direction $reverse "M02->M01"

$overall = "GREEN"
if ($fwdScore.Verdict -eq "RED" -and $revScore.Verdict -eq "RED") { $overall = "RED" }
elseif ($fwdScore.Verdict -eq "RED" -or $revScore.Verdict -eq "RED") { $overall = "AMBER" }
elseif ($fwdScore.Verdict -eq "AMBER" -or $revScore.Verdict -eq "AMBER") { $overall = "AMBER" }

$outDir = $ForwardLogDir
if (-not (Test-Path (Join-Path $ForwardLogDir "device-reports"))) { $outDir = Split-Path $ForwardLogDir -Parent }
$outPath = Join-Path $outDir "E3A2_DELIVERY_QUALITY_ADJUDICATION.txt"

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("ADR-0058 E3a.2 — Delivery Quality Adjudication")
$lines.Add("Frozen refs: MaxConsecutivePlcFrames=$MaxConsecutivePlc MaxPlayoutDelayMs=$MaxPlayoutDelayMs")
$lines.Add("E3a.2 gates: maxConsecutiveLoss + interArrivalMsP99 only (delivery % is observability, not a gate)")
$lines.Add("")
foreach ($s in @($fwdScore, $revScore)) {
    $lines.Add("=== $($s.Label) runId=$($s.RunId) ===")
    $lines.Add(("  delivery:        {0:P2} ({1}/{2}) [observability]" -f $s.DeliveryRate, $s.ReceivedCount, $s.PacketsSent))
    $lines.Add(("  max burst loss:  {0} (limit {1}) {2}" -f $s.MaxConsecutiveLoss, $MaxConsecutivePlc, $(if ($s.BurstFail) { "FAIL" } else { "OK" })))
    $lines.Add(("  gap P95/P99 ms:  {0} / {1} (limit {2}) {3}" -f $s.InterArrivalMsP95, $s.InterArrivalMsP99, $MaxPlayoutDelayMs, $(if ($s.GapP99Fail) { "P99 FAIL" } else { "OK" })))
    $lines.Add(("  jitter P99 ms:   {0}" -f $s.InterArrivalJitterMsP99))
    $lines.Add(("  gaps >120ms:     {0}" -f $s.GapsOver120Count))
    $lines.Add(("  max gap ms:      {0}" -f $s.MaxInterArrivalGapMs))
    $lines.Add(("  direction verdict: {0}" -f $s.Verdict))
    $lines.Add("")
}
$lines.Add("=== Overall E3a.2 ===")
$lines.Add("E3A2_DELIVERY_QUALITY_VERDICT=$overall")
$lines.Add("")
$lines.Add("Gate notes (per direction):")
$lines.Add("  maxConsecutiveLoss <= 5 | interArrivalMsP99 <= 120ms")
$lines.Add("  interArrivalGapOver120MsCount / maxInterArrivalGapMs = observability (v2+ JSON)")
$lines.Add("")
$lines.Add("Interpretation:")
$lines.Add("  GREEN  burst + P99 gap within runtime envelope on both directions")
$lines.Add("  AMBER  reachability established; burst and/or P99 gap fail (underlay tuning; no media engine)")
$lines.Add("  RED    catastrophic (delivery <50% or burst >=20 on a direction)")
$lines.Add("")
$lines.Add("E3a reachability (E3a.1): ESTABLISHED M01<->M02")
$lines.Add("Media engine: NOT AUTHORIZED unless E3a.2 GREEN on repeated runs")

$text = $lines -join "`n"
Set-Content $outPath $text -Encoding UTF8
Write-Host $outPath
Write-Host $text
