# RCA-003 — Presentation convergence (entry)

**Status:** OPEN / INDEPENDENT TRACK — **not** WiFi recovery  
**Date:** 2026-08-10  
**Trigger:** Field / soak observation of sticky `recovering` after protocol terminal

## Problem naming (frozen)

```text
Not: WiFi recovery broken
Not: EDGE_RECOVERED failed
Not: fix recovery timeout / media sticky

Gap: PRESENTATION_CONVERGENCE_MISSING
     protocol terminal (EDGE_RECOVERED) not consumed by presentation projection
```

## Suspected seam

```text
EDGE_RECOVERED
       |
       v
Conference / UVCP / ActivityFrame projection
       |
       X  (candidate)
       |
UI recovering=[peer] still shown
while media / edge already CONNECTED / recovered
```

## Admission evidence (minimum)

Before any code change, capture one episode with all of:

```text
1. EDGE_RECOVERED (or equivalent recovery terminal) for the peer
2. Media / ICE CONNECTED (or equivalent healthy media fact)
3. UI / projection still recovering=* after (1)+(2)
4. Timestamp order proving (3) is after (1)+(2), not mid-recovery
```

## In scope

- Presentation / UVCP / banner / pill projection consumption of recovery terminal
- R30-J / ADR-0025 presentation model alignment (if applicable)

## Out of scope (do not touch)

```text
INV-T3 · RRA-005 Phase-2 · RCA-001 ownership · RCA-002 reacquisition
Conference same-session acceptance predicate
Completion admission · RNA-5/6 · membership · ICE timeout · retry counts
```

## Relation to last-mile freeze

Parent: [wifi-recovery-last-mile-freeze-20260810.md](./wifi-recovery-last-mile-freeze-20260810.md)  
Soak: [wifi-recovery-protocol-last-mile-v1-soak-run-card.md](./wifi-recovery-protocol-last-mile-v1-soak-run-card.md)

Soak may **count** sticky UI; it must **not** fail the recovery matrix solely on `UI_CLEAR` absence.

## Next

1. Collect admission episode (above 1–4) during P1 soak or passive observation  
2. Desk-only projection read-path audit  
3. Impl only after admission + narrow design review — **no recovery edits**
