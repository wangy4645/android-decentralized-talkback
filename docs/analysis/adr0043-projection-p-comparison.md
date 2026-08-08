# ADR-0043 — Projection P1 / P2 / P3 Comparison

**Status:** **APPROVED FOR NEXT DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Wording patch:** evidence≠truth · P2 cache · P3 ownership · P4 observed-only  
**Purpose:** Compare which projection can provide **authority-grounded PRESENT evidence** satisfying **F1 + F4** without introducing issuer-local truth — **without selecting P**.  
**Upstream:** [adr0043-projection-invalid-patterns.md](./adr0043-projection-invalid-patterns.md) (**APPROVED** · **INV-0043-IP-001**)  
**Next:** [adr0043-projection-p-decision-criteria.md](./adr0043-projection-p-decision-criteria.md) (**DRAFT FOR REVIEW**)  
**Minimum F:** [adr0043-freshness-minimum-sufficient-set.md](./adr0043-freshness-minimum-sufficient-set.md) (**ACCEPT A**)

---

## Status board

```text
IP-001 / F-MIN-001:       FROZEN
P Comparison:             APPROVED
P-selection:              STILL OPEN
Implementation / Field:   FROZEN
```

---

## Single question (frozen wording)

> Which projection can provide **authority-grounded PRESENT evidence** that satisfies **F1 ∧ F4**, **without introducing issuer-local truth**?

```text
Truth exists at authority
Projection conveys evidence
Issuer consumes evidence
```

Do **not** read as “projection generates truth.”

```text
Discuss: natural F-MIN-001 / IP-001 / PROJ-001 fit without issuer-local truth
Do NOT discuss: retry · timeout · FSM · handler · implementation seam · Ownership pick
```

---

## Comparison frame

Legal path:

```text
Authority accepted context
        → projection (evidence)
        → PRESENT (scope-bound ∧ epoch-bound)
        → ownership may allow GROUP_RESYNC
```

| Criterion | Meaning |
|-----------|---------|
| **C1 Natural F4** | Scope bound to authority context |
| **C2 Natural F1** | Decision-epoch bound; cross-epoch reuse blocked |
| **C3 IP resistance** | Hard to slip into IP-1…IP-8 |
| **C4 Truth locus** | Authority remains truth; artifact ≠ truth |
| **C5 Ownership impact** | Does it force Ownership reopen? (noted, not decided here) |

---

## P1 — Issuer queries authority

```text
Issuer: "I need resync"
        ↓
Authority: "Does accepted context exist for this scope + epoch?"
        ↓
PRESENT / ABSENT (evidence)
```

| Criterion | Desk note |
|-----------|-----------|
| C1 / C2 | Strong natural fit to F-MIN-001 |
| C3 | Strong if response is authority-grounded; reachability ≠ answer |
| C4 | Authority evaluates; issuer holds **evidence only** |
| C5 | Aligns with O2-leaning path; query ≠ auto-authorize |

```text
query response ≠ authority truth itself
```

If P1 is later selected: response correlation · epoch binding · freshness — **later design**, not this comparison.

---

## P2 — Authority publishes evidence

```text
authority → publish projection artifact
issuer → consume if F1∧F4 → OWN → maybe RESYNC
```

```text
Publication is not truth; it is a truth projection.
```

| Criterion | Desk note |
|-----------|-----------|
| C1 / C2 | Requires explicit scope/epoch bind on the artifact |
| C3 | **Elevated** IP-1 risk if `lastSeenDigest ≈ PRESENT` |
| C4 | OK only if artifact ≠ truth |
| C5 | Evidence-consumer issuer; wait-for-evidence feasible |

```text
P2 ACCEPTABLE ONLY IF:
  published evidence remains authority-originated
  AND issuer cache never upgrades to independent truth
```

Otherwise:

```text
published PRESENT → issuer cache PRESENT → cache becomes truth
→ T1-like issuer cache + T2 authority = MIXED again
```

---

## P3 — Authority triggers

```text
authority holds context → authority triggers convergence / snapshot
issuer withholds spontaneous GROUP_RESYNC
```

Not a simple projection: changes **who asks for convergence**.

```text
FROM: issuer decides "should I request resync?"
TO:   authority decides "convergence action exists"
```

| Criterion | Desk note |
|-----------|-----------|
| C1–C4 | Strong locus; issuer does not mint PRESENT |
| C5 | **Requires Ownership reopen** — primary risk is ownership shift, not tech |

```text
P3 requires Ownership reopen
```

---

## P4 — Out of candidate set

```text
P4 = observed architecture (MIXED)
P4 ≠ candidate target
```

Retained only to show why MIXED yields M0-class failures. Not a compliant exit under TRUTH-001 / IP-001 / F-MIN-001.

---

## Comparative summary (not a selection)

| | P1 | P2 | P3 |
|-|----|----|-----|
| F1+F4 without issuer-local truth | High | Medium (bind + no cache-as-truth) | High |
| IP-1 digest trap | Low if strict | Elevated | Low |
| Ownership reopen | No by default | No by default | **Yes** |

```text
P1 / P2 / P3 = OPEN
Next asks minimal ownership change under F1+F4 — not "which is strongest"
```

---

## One-line statement

> Compare projections on authority-grounded F1∧F4 PRESENT evidence without issuer-local truth; P2 must not promote cache to truth; P3 forces ownership reopen; P4 observed-only — selection still OPEN.
