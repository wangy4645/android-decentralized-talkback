# ADR-0058 E3a.2a — Probe Observability Completion

## Status

```text
SCOPE:     observability only — no transport / pacing / lock / gate changes
PROBE:     v2 JSON (backward compatible with v1 adjudicators)
MEDIA:     NOT AUTHORIZED
```

## Added receiver metrics (v2)

| Field | Meaning |
|-------|---------|
| `interArrivalGapOver120MsCount` | Count of consecutive receive gaps strictly > `FROZEN_MAX_PLAYOUT_DELAY_MS` (120 ms) |
| `maxInterArrivalGapMs` | Maximum observed inter-arrival gap (ms) |

Unchanged: `interArrivalMsP50/P95/P99`, jitter percentiles, `maxConsecutiveLoss`, 50 pps / 120 B wire.

## v1 compatibility

Historical runs without the new fields adjudicate as:

```text
interArrivalGapOver120MsCount = UNKNOWN
maxInterArrivalGapMs          = UNKNOWN
```

E3a.2 gates remain **burst + P99 gap only**. Delivery % is observability, not a frozen GREEN gate.

## Implementation

```text
UnderlayMulticastProbeMetrics.snapshot()
  + maxInterArrivalGapMs
  + interArrivalGapOver120MsCount
```

## Next

Proceed to **E3a.3 — Underlay Remediation / Repeatability Run** (`adr0058-e3a3-underlay-remediation-run-card.md`).
