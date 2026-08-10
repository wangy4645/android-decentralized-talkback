# RCA-003 — Presentation Convergence after Recovery

**Status:** OPEN / INDEPENDENT TRACK — **not** WiFi recovery  
**Date:** 2026-08-10  
**Name (frozen):** Presentation Convergence after Recovery  
**Not:** “UI bug” / “WiFi recovery broken”  
**Parent milestone:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)  
**Soak note:** `logs/lastmile-soak-20260810-192332/` (roster disappear / sync observed; protocol PASS)

## Problem naming (frozen)

```text
Not: WiFi recovery broken
Not: EDGE_RECOVERED failed
Not: fix recovery timeout / media sticky

Gap: PRESENTATION_CONVERGENCE_MISSING
     backend / edge recovered  ≠  UI connected
```

## Scope (narrow)

```text
EDGE_RECOVERED
      ↓
Roster / ActivityFrame / UVCP / pill projection
```

Only ask why:

```text
backend connected
        ≠
UI connected
```

Examples in soak (observe, do not mix into recovery FAIL): sticky `recovering`, peer vanish from host roster, peer shown as syncing after edge recovered.

## Suspected seam

```text
EDGE_RECOVERED
       |
       v
Conference / UVCP / ActivityFrame projection
       |
       X  (candidate)
       |
UI recovering=[peer] / sync / missing roster
while media / edge already CONNECTED / recovered
```

## Admission evidence (minimum)

Before any code change, capture one episode with all of:

```text
1. EDGE_RECOVERED (or equivalent recovery terminal) for the peer
2. Media / ICE CONNECTED (or equivalent healthy media fact)
3. UI / projection still recovering=* / sync / missing after (1)+(2)
4. Timestamp order proving (3) is after (1)+(2), not mid-recovery
```

## In scope

- Presentation / UVCP / banner / pill / roster projection consumption of recovery terminal
- R30-J / ADR-0025 presentation model alignment (if applicable)

## Out of scope (do not touch)

```text
INV-T3 · RRA-005 Phase-2 · RCA-001 ownership · RCA-002 reacquisition
Conference same-session acceptance predicate
Completion admission · RNA-5/6 · membership · ICE timeout · retry counts
M02 join / session churn (separate Session lifecycle track)
```

## Relation to last-mile freeze

Parent: [wifi-recovery-last-mile-freeze-20260810.md](./wifi-recovery-last-mile-freeze-20260810.md)  
Soak card: [wifi-recovery-protocol-last-mile-v1-soak-run-card.md](./wifi-recovery-protocol-last-mile-v1-soak-run-card.md)

`UI_CLEAR` absence does **not** fail recovery PASS.

## Next

1. Collect admission episode (above 1–4)  
2. Desk-only projection read-path audit  
3. Impl only after admission + narrow design review — **no recovery edits**
