# RCA-003 — Presentation Convergence after Recovery

**Status:** OPEN — Round-3 **D-like** · Round-4 **desk authorized** · **no product code** · **no more flap soak**  
**Date:** 2026-08-10  
**Name (frozen):** Presentation Convergence after Recovery  
**Parent milestone:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)

| Round | Result | Doc |
|-------|--------|-----|
| 1 | Case C (`EDGE_RECOVERED` absent) | [adj](./rca-003-round1-adjudication-20260810-210401.md) |
| 2 | Case E — `DEGRADED_STUCK` confirmed | [adj](./rca-003-round2-adjudication-20260810-211253.md) |
| 3 | D-like — `DEGRADED_TRANSITIONAL` (may self-clear) | [adj](./rca-003-round3-adjudication-20260810-220243.md) |
| 4 | Identify peer-healthy SoT (`mediaUnavailable` set/clear) | [desk](./rca-003-presentation-convergence-desk-round4.md) |

## Current finding (freeze)

```text
Recovery last-mile: PASS / NOT IMPLICATED
Presentation convergence depends on a peer-health aggregation path after media recovery.

Open: identify degraded projection owner
      (mediaUnavailable produce/clear)
```

```text
Was:  WiFi recovery unstable / recovering sticky
Now:  which state decides peer is healthy after recovery?
```

## Model (freeze)

```text
Media lifecycle
      |
      v
Peer health aggregation
      |
      v
Presentation projection
```

## Closed directions

```text
WiFi recovery · Phase-2 delivery · media ownership · ICE restart main chain
simple recovering flag not cleared
```

## Next

Round-4 desk only — no flap soak.
