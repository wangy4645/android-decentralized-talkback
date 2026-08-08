# ADR-0043 — O-Selection Constraint Memo

**Status:** **APPROVED FOR NEXT DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Decision:** **O-INV-001…006 ACCEPT** · O-selection **REMAINS OPEN**  
**Wording patch:** O2 admission ≠ membership truth  
**Upstream:** [adr0043-p1-authorization-boundary.md](./adr0043-p1-authorization-boundary.md) (**APPROVED**)  
**Next:** [adr0043-o-evaluation-criteria.md](./adr0043-o-evaluation-criteria.md) (**DRAFT FOR REVIEW**)

---

## Status board

```text
O-Selection Constraint Memo: APPROVED
O-INV-001…006:               ACCEPTED
O-selection:                 STILL OPEN
Implementation / Field:      FROZEN
```

---

## Responsibility split (frozen)

```text
membership context truth     → Authority
P1 evidence production       → Authority
Evidence validation          → Issuer-side projection consumer
GROUP_RESYNC authorization   → O-selection decides (later)
```

```text
authority owns context truth
issuer owns recovery intent framing
```

Prevents P1 from collapsing into an “authority admission system” that rewrites truth.

---

## O-INV-001…006 — ACCEPTED

| ID | Invariant |
|----|-----------|
| **O-INV-001** | No truth rewrite — cannot dispatch ≠ membership does not exist |
| **O-INV-002** | Evidence-gated only — PRESENT → authorization evaluation; not evaluation → hunt for proof |
| **O-INV-003** | No auto-promote — PRESENT ≠ GRANT |
| **O-INV-004** | Deny ≠ ABSENT — ABSENT / UNKNOWN / DENY independent |
| **O-INV-005** | No silent ownership move — P3 ⇒ OWN reopen |
| **O-INV-006** | Intent ≠ proof — local session ≠ PRESENT |

```text
ABSENT  = authority statement
UNKNOWN = insufficient evidence
DENY    = action not allowed
```

---

## Candidate mapping (constraint check — not selection)

| O | Pass if… | Risk |
|---|----------|------|
| **O1** | Eval-time F-MIN (A3); no invent PRESENT | Stale window if eval delayed |
| **O2** | If ever selected: authority returns an **action admission artifact separate from membership truth** — not `admission = truth` | Protocol surface; must not soften handler or rewrite membership |
| **O3** | No invent T3 without need; OWN reopen likely | Scope creep |

```text
O2 misconception to forbid:
  authority admission = authority truth

O2 correct shape (if ever):
  membership context truth
        +
  authorization artifact
```

```text
O1 / O2 / O3 = OPEN
```

---

## Next

→ [adr0043-o-evaluation-criteria.md](./adr0043-o-evaluation-criteria.md)

How to **evaluate** O candidates under O-INV-001…006 — without ACCEPT.

---

## One-line statement

> Any O must obey O-INV-001…006 under P1+AUTH; constraints approved, selection still OPEN — evaluation criteria next.
