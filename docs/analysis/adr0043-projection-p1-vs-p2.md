# ADR-0043 — Projection P1 vs P2 (Decision Memo Precursor)

**Status:** **APPROVED FOR NEXT DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Decision:** P1 second-truth risk **LOWER (accepted)** · P2 viable **with constraints** · P-selection **STILL OPEN**  
**Wording patch:** P1 = reduced retention window · **INV-0043-P2-BOUNDARY-001**  
**Upstream:** [adr0043-projection-p-decision-criteria.md](./adr0043-projection-p-decision-criteria.md) (**APPROVED**)  
**Next:** [adr0043-projection-selection-memo.md](./adr0043-projection-selection-memo.md) (**DRAFT FOR REVIEW**)

---

## Status board

```text
P1 vs P2 Comparison:      APPROVED
P1 second-truth risk:     LOWER (accepted)
P2 viability:             ACCEPT with constraints
P3:                       FALLBACK ONLY
P-selection:              STILL OPEN
Implementation / Field:   FROZEN
```

---

## Core difference (frozen)

```text
P1: authority evidence is obtained when needed
P2: authority evidence is projected and retained
```

### P1

```text
Authority → query → Issuer → consume → Decision
```

Issuer holds a **current answer**, not a long-lived belief → lower second-truth-locus risk.

**Misread guard:**

```text
P1 is NOT "query = always fresh"
P1 reduces retention-window / stale-persistence risk
P1 still needs: response scope bind · epoch bind · correlation
```

### P2

Problem is not publish. Problem is:

```text
Authority PRESENT → Issuer stored PRESENT → later decide from stored value
→ projection cache becomes local truth → MIXED (T1 issuer + T2 authority)
```

P2 **not excluded**: valuable for multi-requester / high-frequency recovery / authority-driven state propagation — only if:

```text
projection artifact ≠ authority replica
P2 can project evidence, but cannot create another membership database
```

### INV-0043-P2-BOUNDARY-001

> A P2 projection artifact MUST NOT be consumed as current PRESENT evidence after its authority validity boundary is no longer provable.

Not a lock to TTL / cache duration / timer. Focus:

```text
Can we still prove this artifact represents authority current context?
```

### P3

```text
P3 = FALLBACK ONLY
```

Changes who initiates convergence → Ownership redesign, not projection optimization.

---

## Single question answered here

> When ownership delta is similar, which is less likely to create a second truth locus?

**Accepted desk answer:** P1 lower risk; P2 viable under P2-BOUNDARY-001 (+ no digest≈PRESENT).

**Not answered here:** ACCEPT P1 or P2 as architecture baseline.

---

## Candidate checklist (unchanged)

Both must pass TRUTH-001 · F-MIN-001 · IP-001 · C-OWN-MIN · PROJ-OWN-001 before selection.

---

## Next

→ [adr0043-projection-selection-memo.md](./adr0043-projection-selection-memo.md)

Grill: accept **P1 as v0 projection baseline**?  
`P selected ≠ Implementation approved`

---

## One-line statement

> P1 pulls current answers (lower second-truth risk; still needs response binds); P2 may publish only under P2-BOUNDARY-001 (artifact ≠ replica); P3 fallback — selection memo next; still no implementation.
