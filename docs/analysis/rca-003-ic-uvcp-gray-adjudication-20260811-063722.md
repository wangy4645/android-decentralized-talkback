# RCA-003 IC gray-2 — Case 3 adjudication (`20260811-063722`)

**Date:** 2026-08-11  
**LogDir:** `logs/rca003-ic-uvcp-gray-20260811-063722/`  
**APK:** #157  
**Correction:** Case 3 **does not** require `EDGE_RECOVERED` (prior bar was over-strict).

## Verdict

```text
RCA-003 IC (#157)

Implementation      PASS
Case-3 validation   PASS
Field verification  PASS
Status              CLOSED
```

## Case 3 gate (authoritative)

```text
FAILED_MEDIA residency
+ current media healthy
+ receivePathLive=true
→ UVCP must not inherit residency
```

**Not:** `EDGE_RECOVERED=true`.

## Evidence (M01 → M02, ×29 @ ~06:38:48+)

```text
media=CONNECTED
ice=CONNECTED
receivePathLive=true
edgeRecoveryPhase=FAILED_MEDIA_RECOVERY
obligationOpen=true
mediaUnavailable=false
finalPresence=ONLINE
connectingHint=null
```

Same shape on M02 → M01.  
`CONNECTED + mediaUnavailable=true` = **0** on M01/M02/M03.

## Before / after

| Era | Path |
|-----|------|
| Pre-#157 | residency → OR into UVCP → `mediaUnavailable=true` → pill degraded while media healthy |
| Post-#157 | residency isolated; current media → `mediaUnavailable=false` → ONLINE |

## Related

- Gray-1 (`062506`): exclusion — unrecovered edge correctly degraded; not IC FAIL  
- Mesh M03↔M02 unrecovered: **PARK** under Session Churn / Mesh Edge Recovery Stability — not RCA-003
