# RCA-003 Round-2 — adjudication (Case E CONFIRMED)

**Date:** 2026-08-10  
**Log:** `logs/rca003-pres-conv-r2-20260810-211253/`  
**Status:** ADJUDICATED — observe-only; **no code**  
**Parent:** [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)

## Frozen result

```text
RCA-003 Round-2

Case: E
Status: CONFIRMED (observation)

Recovery last-mile: PASS / NOT IMPLICATED
Presentation convergence: OPEN
```

## Breakthrough (freeze)

```text
Not: Recovery incomplete
Yes: Recovery complete evidence decoupled from presentation degraded projection
```

## Evidence chain

**Host (M01) — recovery terminal exists:**

```text
NETWORK_LOST(M02)
        |
        v
EDGE_RECOVERED(remote=M02)     @ 21:13:51
```

**DUT (M02) — presentation health stuck:**

```text
MEDIA_LIFECYCLE M01 = CONNECTED   @ 21:13:57
        |
        v
recovering=[]
        |
        v
connectingHint=M01 degraded...   @ 21:14:10 → still at window end ~21:16:18
```

Asymmetry: **M01/M03 pills returned healthy**; durable degrade is **M02’s view of M01**.

No hangup in observation window.

## Finding (frozen wording)

```text
health/runtime recovered  ≠  presentation health projection recovered
recovering cleared        ≠  fully healthy UX
```

Not “recovering flag stuck.”

## Seam (fixed)

```text
Media Lifecycle State
          |
          v
Presentation Projection State
```

**Closed / do not reopen:** WiFi · ICE restart · reattach · delivery · ownership · last-mile recovery.

## Next

Round-3 — identify **degraded state owner / last writer**  
[rca-003-presentation-convergence-observation-round3.md](./rca-003-presentation-convergence-observation-round3.md)
