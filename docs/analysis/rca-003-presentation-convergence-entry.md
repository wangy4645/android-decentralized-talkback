# RCA-003 — Presentation Convergence after Recovery

**Status:** OPEN — R4 ownership **COMPLETE** · R5 residency clear **design OPEN** · **no product code**  
**Date:** 2026-08-11  
**Name (frozen):** Presentation Convergence after Recovery  
**Parent milestone:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)

| Round | Result | Doc |
|-------|--------|-----|
| 1 | Case C (`EDGE_RECOVERED` absent) | [adj](./rca-003-round1-adjudication-20260810-210401.md) |
| 2 | Case E — `DEGRADED_STUCK` | [adj](./rca-003-round2-adjudication-20260810-211253.md) |
| 3 | D-like — `DEGRADED_TRANSITIONAL` | [adj](./rca-003-round3-adjudication-20260810-220243.md) |
| 4 | Ownership trace COMPLETE | [R4](./rca-003-r4-conference-media-unavailable-ownership-trace.md) |
| 5 | Failed Media Residency Clear Contract | [R5](./rca-003-r5-failed-media-residency-clear-contract.md) |

## Freeze

```text
RCA-003 R4 COMPLETE

Next:
RCA-003 R5
Failed Media Residency Clear Contract

Scope: clear/invalidation semantics
Not: UI · WiFi recovery · ICE · Retry
```

## Remaining seam

```text
FAILED_MEDIA residency lifecycle
vs
current media health → pill
```

Not “why recovery fails.”

## R4 three lines (closed)

```text
SET:    enterFailedMediaResidency → FAILED_MEDIA_RECOVERY
CLEAR:  RecoveryResidencyClearPolicy (closed ∧ ice ∧ receivePathLive)
Consumer: UVCP ← mediaUnavailablePeer
```

## Next

R5.1–R5.3 design answers only — then Implementation Candidate (separate auth).
