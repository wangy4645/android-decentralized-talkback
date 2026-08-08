# ADR-0043 — Projection Selection Memo

**Status:** **ACCEPTED** · **architecture direction only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Decision:** **ACCEPT A** — **v0 projection baseline = P1**  
**Upstream:** [adr0043-projection-p1-vs-p2.md](./adr0043-projection-p1-vs-p2.md) (**APPROVED**)  
**Next:** [adr0043-p1-design-boundary.md](./adr0043-p1-design-boundary.md) (**DRAFT FOR REVIEW**)

---

## Status board

```text
Projection Selection:     ACCEPTED
v0 baseline:              P1
P2:                       RESERVED (P2-BOUNDARY-001)
P3:                       FALLBACK ONLY (OWN reopen)
O-selection:              OPEN
Implementation / Field:   FROZEN
Runtime:                  FROZEN
```

---

## Critical boundary (frozen)

```text
P selected ≠ Implementation approved
```

> **ADR-0043 v0 projection baseline = P1. Authority remains the single truth locus; issuer consumes authority-grounded evidence at decision time. Selection does not authorize implementation.**

---

## Decision

| Option | Result |
|--------|--------|
| **A** ACCEPT P1 as v0 projection baseline | **SELECTED** |
| B Defer dual-open | Rejected |
| C Prefer P2 as v0 | Rejected (P2 remains RESERVED) |
| D Escalate P3 | Rejected (FALLBACK ONLY) |

---

## Why P1

| Constraint | P1 |
|------------|-----|
| TRUTH-001 single truth locus | Authority remains sole truth |
| F-MIN-001 scope ∧ epoch | Query response can bind |
| IP-001 | No digest / topology / local belief as PRESENT |
| PROJ-OWN-001 | Projection does not move truth ownership |
| C-OWN-MIN | Smallest ownership delta |

```text
P1:
  authority owns truth
          | query
          v
  issuer receives evidence
          v
  issuer decides action
```

Not:

```text
issuer stores belief → issuer reconstructs truth
```

Minimizes M0-class:

```text
authority context ≠ issuer assumption
```

---

## Frozen companion boundaries

### 1. P1 ≠ freshness solved

```text
P1 reduces retention risk
≠
P1 eliminates freshness design
```

Later design (not this selection): response scope · epoch · correlation binding.

### 2. P2 / P3 ranking

```text
P1 = v0 baseline
P2 = valid alternative if P2-BOUNDARY-001 can be proven
P3 = fallback only · requires OWN reopen
```

### 3. Ownership not opened

```text
O-selection OPEN
```

P1 means authority supplies **context evidence**, not automatic approval of convergence action.

```text
truth ≠ evidence ≠ authorization
```

---

## What this ACCEPT does **not** authorize

```text
NO:
- implementation / PR / branch
- wire format / protobuf / API
- FSM / timeout / field
- handler softening
- Seam II / Option C
```

---

## Next layer

**P1 design boundary only** (still no impl):

→ [adr0043-p1-design-boundary.md](./adr0043-p1-design-boundary.md)

Allowed: minimal response evidence semantics · abstract scope/epoch/correlation · evidence→authorization boundary.  
Forbidden: wire · proto · branch · runtime · field.

---

## One-line statement

> ACCEPTED: v0 projection = P1 (architecture only); authority truth, issuer consumes evidence at decision time; impl/field still frozen.
