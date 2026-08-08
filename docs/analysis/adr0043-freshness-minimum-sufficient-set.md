# ADR-0043 — Freshness Minimum Sufficient Set

**Status:** **APPROVED FOR NEXT DESIGN LAYER** · **ACCEPT A** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Decision:** **A** — F1 Epoch + F4 Scope as **semantic minimum**  
**Upstream:** [adr0043-projection-evidence-freshness-boundary.md](./adr0043-projection-evidence-freshness-boundary.md) (**APPROVED** · **INV-0043-F-001**)  
**Next:** [adr0043-projection-p-comparison.md](./adr0043-projection-p-comparison.md) (**DRAFT**) — P1/P2/P3 comparison only; selection still OPEN.
**ADR:** [0043-conference-recovery-membership-context-boundary.md](../adr/0043-conference-recovery-membership-context-boundary.md)

---

## Status board

```text
Decision:                 ACCEPT A
Semantic minimum:         F1 + F4   FROZEN
F2 / F3 / F5:             optional validity-maintenance mechanisms (OPEN)
P-selection:              STILL OPEN
Implementation:           NOT AUTHORIZED
Field:                    FROZEN
```

---

## Grill outcome

| Option | Result |
|--------|--------|
| **A** ACCEPT F1+F4 as semantic minimum | **SELECTED** |
| B Add F3 expiry into semantic minimum | Rejected |
| C Add F2 consume into semantic minimum | Rejected |
| D Keep OPEN | Rejected (would leave P design without minimum contract) |

### Why A

**F4 Scope:** Without scope, `PRESENT(scope=A)` can be used for `scope=B` → PRESENT meaningless. `scope mismatch → UNKNOWN` must hold.

**F1 Epoch:** Without epoch, evidence crosses recovery decision attempts → historical PRESENT reuse → violates **INV-0043-PROJ-001**. `epoch mismatch → UNKNOWN` must hold. F1 is a **boundary**, not TTL.

### Why not B (F3)

Expiry is *how* to discover untrustworthiness, not *what* binds evidence to this context. P1 query→response→immediate dispatch may need no TTL and still satisfy same scope + same epoch. `expiry ≠ truth requirement`.

### Why not C (F2)

Consume guards **evidence reuse**, not **evidence validity**. Undispatched `PRESENT(scope=A, epoch=7)` may still reflect live authority context. `consume ≠ invalidate truth` / `consume ≠ revoke authority truth`.

---

## Frozen model

### INV-0043-F-MIN-001

> A projected PRESENT is valid only when:
>
> - authority context **scope** matches, **and**
> - recovery decision **epoch** matches.
>
> Any failure of either condition invalidates PRESENT to **UNKNOWN**.

```text
F1 / F4 = necessary semantic binding
F2 / F3 / F5 = optional validity-maintenance mechanisms
```

**Do not write** `PRESENT = F1 + F4` as an equation that excludes later mechanisms. Accurate form:

```text
PRESENT requires at least F1 ∧ F4
(+ INV-0043-F-001: all *required* bindings, including these)
F2/F3/F5 may add required bindings for a chosen projection later
```

Under F-MIN-001 + F-001:

```text
scope mismatch  → UNKNOWN
epoch mismatch  → UNKNOWN
```

Issuer must not invent **ABSENT** from local mismatch (authority alone asserts ABSENT).

---

## Classification (frozen)

| Class | Members | Role |
|-------|---------|------|
| I Semantic necessity | **F1**, **F4** | Universal; any P1/P2/P3 |
| II Mechanism | F2, F3, F5 | Projection-specific; OPEN |

---

## Explicit non-goals

```text
NO:
- select P1/P2/P3
- freeze F2/F3/F5 into minimum
- obtain / cache / revalidate protocols
- runtime / field / branch
```

---

## Next layer

Do not select P yet. First exclude patterns that cannot satisfy TRUTH-001 / PROJ-001 / F-MIN-001:

→ [adr0043-projection-invalid-patterns.md](./adr0043-projection-invalid-patterns.md)

Then compare P1/P2/P3 inside the remaining legal space.

---

## One-line statement

> ACCEPT A: F1 Epoch + F4 Scope are the frozen semantic minimum for PRESENT (INV-0043-F-MIN-001); F2/F3/F5 stay optional mechanisms — invalid-pattern gate next, then P comparison; no implementation.
