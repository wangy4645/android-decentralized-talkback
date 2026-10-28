# Adjudicate G-RES-3-H1c formal topology qualification (NOT G-RES-3 capacity PASS).

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $LogDir)) { throw "LogDir not found: $LogDir" }

$bundleDir = Join-Path $LogDir "bundle"
$adj = Get-ChildItem -Path $bundleDir -Recurse -Filter "h1c-adjudication.json" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $adj) { throw "h1c-adjudication.json missing" }
$h1c = Get-Content $adj.FullName -Raw | ConvertFrom-Json
$manifest = Get-ChildItem -Path $bundleDir -Recurse -Filter "manifest.json" | Select-Object -First 1
$manifestJson = if ($manifest) { Get-Content $manifest.FullName -Raw | ConvertFrom-Json } else { $null }

$report = @"
# G-RES-3-H1c Formal Topology Qualification Adjudication

```text
topologyValidity:           $($h1c.topologyValidity)
harnessRuntimeValidity:     $($h1c.harnessRuntimeValidity)
formalEvidenceTopology:     $($h1c.formalEvidenceTopology)
c3Projection:               $($h1c.c3Projection)   (informational — NOT G-RES-3 FAIL)

formal 3-run evidence:      NOT STARTED
G-RES-3 status:             EVIDENCE PENDING
```

## Manifest

topologyId: $($manifestJson.topologyId)
topologyClass: $($manifestJson.topologyClass)
distinctReceiverIpCount: $($manifestJson.distinctReceiverIpCount)
sender localIp: $($manifestJson.senderDut.localIp)

## Reasons

$($h1c.reasons -join ", ")

## Decision

$(if ($h1c.formalEvidenceTopology -eq "ELIGIBLE" -and $h1c.c3Projection -eq "LIKELY_PASS") {
    "Proceed to 3×QUALIFYING @ N=9 (600s measurement)."
} elseif ($h1c.formalEvidenceTopology -eq "ELIGIBLE" -and $h1c.c3Projection -eq "LIKELY_FAIL") {
    "Topology eligible but C3 projection LIKELY_FAIL — capacity remediation before formal 3-run."
} else {
    "Fix topology / sinks / harness — rerun H1c."
})
"@

$path = Join-Path $LogDir "ADJUDICATION.md"
Set-Content -Path $path -Value $report -Encoding UTF8
Write-Host $report
if ($h1c.formalEvidenceTopology -ne "ELIGIBLE") { exit 1 }
