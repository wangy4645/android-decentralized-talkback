# RCA-003 — Presentation Convergence after Recovery

**Status:** OPEN — Round-2 **Case E CONFIRMED** · Round-3 **authorized** · **no product code**  
**Date:** 2026-08-10  
**Name (frozen):** Presentation Convergence after Recovery  
**Parent milestone:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)

| Round | Result | Doc |
|-------|--------|-----|
| 1 | Case C (`EDGE_RECOVERED` absent) | [adjudication](./rca-003-round1-adjudication-20260810-210401.md) |
| 2 | Case E CONFIRMED | [adjudication](./rca-003-round2-adjudication-20260810-211253.md) |
| 3 | Identify degraded state owner | [observation](./rca-003-presentation-convergence-observation-round3.md) |

## Frozen finding (after Round-2)

```text
Recovery last-mile: PASS / NOT IMPLICATED
Presentation convergence: OPEN

Recovery terminal state exists
Media lifecycle CONNECTED
Presentation degraded projection persists (DUT view)
```

```text
Was:  WiFi recovery unstable
Now:  ordinary state-convergence bug after recovery success
```

## Seam (fixed)

```text
Media Lifecycle State
          |
          v
Presentation Projection State
```

**Do not dig:** WiFi · ICE · reattach · delivery · ownership.

## Entry gate (unchanged)

`EDGE_RECOVERED?` — Round-2 **yes** on host; remaining gap is presentation.

## Next

Round-3: T1/T2/T3 + last writer of `degraded` (hypotheses A cache vs B wider axes).  
Code only after admission.
