# RCA-003 — Presentation Convergence after Recovery

**Status:** OPEN — Round-1 **observation authorized** / **no code**  
**Date:** 2026-08-10  
**Name (frozen):** Presentation Convergence after Recovery  
**Not:** “UI bug” / “WiFi recovery broken” / “修 UI”  
**Parent milestone:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)  
**Round-1 card:** [rca-003-presentation-convergence-observation-round1.md](./rca-003-presentation-convergence-observation-round1.md)  
**Soak note:** `logs/lastmile-soak-20260810-192332/` (roster disappear / sync observed; protocol PASS)

## Entry gate for all future UX-after-WiFi reports

```text
EDGE_RECOVERED 是否已经产生？
```

If **yes** → recovery does **not** own the remaining gap; stay on this track (or Session Churn).  
If **no** → recovery triage only (Case C).

## Goal

Prove whether the recovery terminal propagates into presentation projection:

```text
EDGE_RECOVERED
        |
        v
Conference Runtime State
        |
        v
Roster / ActivityFrame
        |
        v
UI recovering pill
```

## Problem naming (frozen)

```text
Gap: PRESENTATION_CONVERGENCE_MISSING
     backend / edge recovered  ≠  UI connected
```

## Observation cases

| Case | Pattern | Track |
|------|---------|-------|
| A | EDGE_RECOVERED → UI clears recovering | Normal |
| B | EDGE_RECOVERED → runtime OK → UI sticky / sync / missing roster | This RCA |
| C | no EDGE_RECOVERED | Recovery triage (not RCA-003 impl) |

## Three facts

| Fact | Meaning |
|------|---------|
| EDGE_RECOVERED exists | Exclude recovery ownership |
| Runtime cleared recovering member | Locate projection |
| UI consumed latest state | Locate UI layer |

## Admission evidence (minimum, before any code)

```text
1. EDGE_RECOVERED for the peer
2. Media / ICE CONNECTED (or equivalent healthy media fact)
3. UI / projection still recovering=* / sync / missing after (1)+(2)
4. Timestamp order: (3) after (1)+(2)
```

## Out of scope (do not touch)

```text
INV-T3 · RRA-005 Phase-2 · RCA-001 · RCA-002
Conference same-session acceptance
Completion · RNA-5/6 · membership · ICE timeout · retry
Session Churn / Join Stability (separate name — not “WiFi recovery join issue”)
```

## Related

- [wifi-recovery-last-mile-freeze-20260810.md](./wifi-recovery-last-mile-freeze-20260810.md)  
- [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)

## Next

1. Execute Round-1 observation card (no code)  
2. Classify A / B / C with F1–F3  
3. Impl only after Case B admission + narrow design review
