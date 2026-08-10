# RCA-003 — Presentation Convergence after Recovery

**Status:** OPEN — Round-4 ownership **TRACED** · design/fix **not authorized** · **no product code**  
**Date:** 2026-08-11  
**Name (frozen):** Presentation Convergence after Recovery  
**Parent milestone:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)  
**R4 trace:** [rca-003-r4-conference-media-unavailable-ownership-trace.md](./rca-003-r4-conference-media-unavailable-ownership-trace.md)

| Round | Result | Doc |
|-------|--------|-----|
| 1 | Case C (`EDGE_RECOVERED` absent) | [adj](./rca-003-round1-adjudication-20260810-210401.md) |
| 2 | Case E — `DEGRADED_STUCK` confirmed | [adj](./rca-003-round2-adjudication-20260810-211253.md) |
| 3 | D-like — `DEGRADED_TRANSITIONAL` (may self-clear) | [adj](./rca-003-round3-adjudication-20260810-220243.md) |
| 4 | Ownership trace COMPLETE | [R4](./rca-003-r4-conference-media-unavailable-ownership-trace.md) |

## R4 three lines

```text
SET:    enterFailedMediaResidency → FAILED_MEDIA_RECOVERY (attempt_timeout / MEMBERSHIP_CONVERGENCE_*)
CLEAR:  RecoveryResidencyClearPolicy (obligation closed + iceConnected + receivePathLive)
Consumer: UserVisibleConnectivityProjection ← mediaUnavailablePeer
```

## Current finding (freeze)

```text
Recovery last-mile: PASS / NOT IMPLICATED
Remaining seam: FAILED_MEDIA residency SET vs ADR-0045 CLEAR admission
(not a Meeting-pill boolean bug)
```

## Model (freeze)

```text
Media lifecycle
      |
      v
FAILED_MEDIA residency / MediaUsabilityFact
      |
      v
Presentation projection (degraded pill)
```

## Closed directions

```text
WiFi recovery · Phase-2 delivery · media ownership · ICE restart main chain
simple recovering flag not cleared · more flap soak
```

## Next

Design review only if changing residency clear timing / invalidation after recovery evidence — **not** UI patch.
