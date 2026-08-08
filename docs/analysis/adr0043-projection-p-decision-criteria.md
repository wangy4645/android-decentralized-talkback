# ADR-0043 — Projection P Decision Criteria

**Status:** **APPROVED FOR NEXT DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Decision:** **C-OWN-MIN ACCEPT** · desk ordering **P1 ≳ P2 >> P3 ACCEPT** (ordering, not P selection)  
**Wording patch:** P2 retention · **INV-0043-PROJ-OWN-001**  
**Upstream:** [adr0043-projection-p-comparison.md](./adr0043-projection-p-comparison.md) (**APPROVED**)  
**Next:** [adr0043-projection-p1-vs-p2.md](./adr0043-projection-p1-vs-p2.md) (**DRAFT FOR REVIEW**)

---

## Status board

```text
C-OWN-MIN:                ACCEPT
Desk ordering:            P1 ≳ P2 >> P3  (ACCEPT as ordering)
INV-0043-PROJ-OWN-001:    FROZEN
P-selection:              STILL OPEN
Implementation / Field:   FROZEN
```

---

## Single question (frozen)

> Given F1 + F4, which projection incurs the **smallest ownership change**?

```text
Problem is not: who gives strongest consistency?
Problem is: how to stop issuer-inferred authority context
            without redefining recovery ownership?
```

```text
authority truth correctness
        +
minimum ownership disruption
    >
maximum centralization
```

---

## Hard gates G1–G5 (frozen)

| Gate | Rule |
|------|------|
| G1 | Authority-grounded PRESENT — authority produces truth; projection conveys evidence; issuer consumes evidence. **Forbidden:** issuer computes PRESENT |
| G2 | Scope + decision epoch (F-MIN-001) |
| G3 | `truth ≠ evidence ≠ authorization` |
| G4 | No IP-1…IP-8 |
| G5 | P4 not a candidate |

**G3 reminder** (any later P pick must still cite):

```text
WRONG: authority says PRESENT → issuer may dispatch
RIGHT: authority truth → projection evidence → issuer authorization rule
```

---

## Primary criterion

### C-OWN-MIN — ACCEPT

Prefer projection that passes G1–G5 with least change to who decides “may I emit GROUP_RESYNC / request convergence.”

| P | OWN delta | Note |
|---|-----------|------|
| **P1** | **Low** | Issuer keeps recovery intent; authority supplies proof |
| **P2** | **Low** (conditional) | Same shape if retention never becomes second truth |
| **P3** | **Large** | Who asks flips → Ownership reopen required |

Desk ordering **ACCEPT**: `P1 ≳ P2 >> P3` (ordering only).

### P2 retention (tightened)

```text
P2 evidence retention MUST NOT outlive authority validity semantics.
```

Risk is not cache existence per se, but:

```text
authority PRESENT → issuer cache → later reused as PRESENT
→ projection becomes second truth locus (MIXED)
```

Carry this hazard into any P2 selection discussion.

### P3

Not “bad” — changes problem class to ownership redesign. `P3 requires OWN reopen` stands.

---

## Secondary criteria (tie-break only)

| ID | Status | Note |
|----|--------|------|
| S1 Protocol surface | Secondary | Small protocol ≠ correct |
| S2 Digest-alias hazard | Secondary | P2-specific IP-1 recurrence path |
| S3 Deferred Class II | Secondary | F2/F3/F5 serve projection; must not define it |

---

## Additional invariant

### INV-0043-PROJ-OWN-001

> Projection may move **evidence availability**, but MUST NOT silently move **truth ownership**.

| P | Reading |
|---|---------|
| P1 | OK |
| P2 | OK iff cache ≠ truth / retention bounded by authority validity |
| P3 | **Explicit** ownership change (must reopen OWN) |

---

## Next

P candidate checklist before compare:

```text
1. TRUTH-001
2. F-MIN-001
3. IP-001
4. C-OWN-MIN
```

Then **P1 vs P2** only (P3 fallback if minimal-delta projections fail TRUTH-001 in practice):

→ [adr0043-projection-p1-vs-p2.md](./adr0043-projection-p1-vs-p2.md)

---

## One-line statement

> C-OWN-MIN accepted: minimize ownership disruption while keeping authority-grounded F1∧F4 evidence; P1 ≳ P2 >> P3 as desk order; PROJ-OWN-001 forbids silent truth-ownership move — P1 vs P2 next; selection still OPEN.
