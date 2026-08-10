# RCA-003 Round-1 — adjudication

**Date:** 2026-08-10  
**Log:** `logs/rca003-pres-conv-20260810-210401/`  
**Status:** ADJUDICATED — observe-only; **no code**  
**Parent:** [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)

## Frozen result

```text
RCA-003 Round-1

Result:
  Recovery chain NOT implicated
  Presentation convergence NOT YET proven

Classification:
  Case C (EDGE_RECOVERED absent)

Observation:
  degraded projection persists after partial recovery signals
```

**Do not write:** `Recovery done / UI wrong` · `Case B` · `WiFi recovery broken` · reopen Phase-2 / Delivery / Ownership / Reattach.

`degraded` on M03 does **not** pull this episode back into recovery last-mile.

## Why not Case B

Gate:

```text
EDGE_RECOVERED = 0
```

Evidence only supports:

```text
Recovery progressed partially
      ↓
some runtime/media state changed
      ↓
presentation projection remains degraded
```

It does **not** prove a recovery terminal was produced.

## Timeline (authoritative)

| Time | Fact |
|------|------|
| 21:05:23 | M02 `NETWORK_LOST` |
| 21:05:48 | M02 `OBTAINED` + `reconnect accepted` |
| 21:05:28+ | M03 `MEDIA_LIFECYCLE M02 DEGRADED`; pill `recovering=[M02]` |
| 21:05:56+ | M01: `recovering=[]` but `connectingHint=M02 degraded...` |
| 21:06:36+ | M01: `connected=3 recovering=[]` (recovering cleared) |
| late | M03: `recovering=[]` + **`M02 degraded...`** + `connected=2`; HEARTBEAT/HELLO from M02 present |
| 21:06:49 | hangup — **observation window cut**; final natural settle unknown |

## New observation (freeze)

Prior focus: `recovering` sticky.  
This round:

```text
recovering cleared
       +
degraded remains
```

These are **not** the same state source:

```text
Recovery / recovering projection     ≠     Media health (degraded) projection
recovering cleared                   ≠     fully healthy
```

Do not assume `recovering=[]` implies healthy UX.

## Hangup note

Hangup did not “cause” the degrade; it **cuts the observation window**. Round-1 cannot decide whether degrade would have cleared naturally.

## Next

Round-2 only — [rca-003-presentation-convergence-observation-round2.md](./rca-003-presentation-convergence-observation-round2.md)  
Question: is `degraded` a transient middle state or a durable end state?
