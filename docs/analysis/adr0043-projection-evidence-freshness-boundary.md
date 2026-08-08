# ADR-0043 — Projection Evidence Freshness Boundary

**Status:** **APPROVED DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Wording patch:** INV-0043-F-001 · F1 epoch · F2 consume · scope mismatch · AP-5 cross-ref  
**ADR:** [0043-conference-recovery-membership-context-boundary.md](../adr/0043-conference-recovery-membership-context-boundary.md) (**ACCEPTED · Option A**)  
**Upstream:** [adr0043-context-projection-boundary.md](./adr0043-context-projection-boundary.md) (**APPROVED** · **INV-0043-PROJ-001**)  
**Evidence model:** [adr0043-seam-i-authority-context-evidence.md](./adr0043-seam-i-authority-context-evidence.md) (**APPROVED**)

---

## Status board

```text
Projection boundary:         APPROVED · INV-0043-PROJ-001
Freshness boundary:          APPROVED · INV-0043-F-001
P1 / P2 / P3:                OPEN
P4:                          OBSERVED ONLY
F1–F5:                       OPEN
Next (optional):             minimal sufficient F combination (contract only)
Implementation:              NOT AUTHORIZED
Runtime / Field:             FROZEN
```

---

## Order (frozen)

```text
freeze F contract
    ↓
discuss P projection
    ↓
implementation   ← NOT AUTHORIZED
```

Do not select P then retrofit F — PRESENT must remain an authority-truth projection contract, not an impl field.

---

## Single question (this document)

> Even when authority projects **PRESENT**, under what conditions may the issuer treat that PRESENT as still representing **current accepted membership context** — and how does PRESENT **cease** to be usable?

```text
Not: transport (ADR-0042 CLOSED)
Not: selecting P1/P2/P3 or F1–F5
Not: TTL numbers / watchdog enlarge / FSM
Only: Freshness · Scope · Epoch · Revocation contract
```

---

## Core invariants

### INV-0043-PROJ-001 (inherited)

> PRESENT is time-bounded evidence of authority accepted context, not a durable assertion of existence.

```text
stale PRESENT → UNKNOWN
(not ABSENT — issuer lost proof; only authority may assert ABSENT)
```

### INV-0043-F-001

> A PRESENT projection is valid only while **all** required binding dimensions remain valid. Loss of **any** required dimension invalidates PRESENT.

```text
PRESENT =
    Freshness OK
  ∧ Scope OK
  ∧ Epoch OK
  ∧ Revocation NOT SEEN
```

Any failure → `PRESENT → UNKNOWN` (unless authority explicitly returns **ABSENT**).

PRESENT is a **conjunction**, not a union of partial matches. Example forbidden:

```text
Freshness OK ∧ Scope OK ∧ Epoch mismatch  ⇒  still PRESENT   ✗
```

Epoch is a recovery **decision boundary**, not optional metadata.

---

## F-axis matrix (OPEN — no selection)

| Axis | Question |
|------|----------|
| **Freshness** | Is the evidence still within a valid obtain / use window? |
| **Scope** | Same conference / channel / membership scope? |
| **Epoch** | Current recovery decision epoch (and/or context generation)? |
| **Revocation** | What forces PRESENT → UNKNOWN (or authority ABSENT)? |

### Bind mechanisms (candidates)

| ID | Axis focus | Mechanism | Notes |
|----|------------|-----------|-------|
| **F1** | Epoch | Decision-epoch bind | PRESENT **MUST NOT** cross recovery decision epoch boundary unless **revalidated** (semantic invalidation, not merely TTL) |
| **F2** | Freshness / consume | Consume-on-dispatch | OPEN. `consume ≠ revoke authority truth` — projection evidence consumed ≠ membership context destroyed |
| **F3** | Freshness / expiry | Explicit expiry | `expired → UNKNOWN`; **forbidden** `expired → ABSENT` |
| **F4** | Scope / generation | Context-id / generation bind | Necessary; M0 `requestSession UNKNOWN` / conference id mismatch class = scope binding failure |
| **F5** | Freshness / edge | Re-validate before dispatch | **Candidate mechanism only** — must not become default or implicitly force P1 |

```text
F1–F5 = OPEN
May combine later as minimal sufficient set
P-selection remains OPEN
```

---

## Invalidation matrix (semantic — not impl)

| Event | Resulting evidence | Notes |
|-------|-------------------|-------|
| Bound epoch ends / new decision epoch (no revalidation) | **UNKNOWN** | F1 · semantic boundary |
| Explicit authority ABSENT | **ABSENT** | Only authority may assert |
| Expiry / lose freshness proof | **UNKNOWN** | Must not stay PRESENT |
| Scope / generation mismatch | **MUST NOT** be treated as PRESENT; state is **UNKNOWN** unless authority explicitly returns **ABSENT** | Issuer must not declare ABSENT from local mismatch |
| Consume-on-dispatch used | **UNKNOWN** until re-prove | If F2 selected later; does not destroy authority context |
| Digest match alone | *never PRESENT* | AP-1 |
| Reachability alone | *never PRESENT* | AP-2 |
| Historical PRESENT reuse | *forbidden* | AP-3 · PROJ-001 |
| Prior handler accept | *never PRESENT* | AP-4 |
| Evidence accumulation across times | *forbidden* | AP-5 |

---

## Relation to P1 / P2 / P3

| P | Must answer on F-axes |
|---|----------------------|
| P1 | Response freshness · correlation epoch · scope identity (future wire — not this layer) |
| P2 | Publication freshness · authority origin · **not** lastSeenDigest≈PRESENT |
| P3 | Trigger vs act gap; ownership reopen if selected |

P4: no legitimate PRESENT projection.

---

## Explicit non-goals

```text
NO:
- select F1–F5 or P1–P3
- wire formats / concrete TTL / timeout enlarge
- default to F5 sync-query-before-every-dispatch
- treat as transport RCA
- runtime / field / branch
- handler softening
```

---

## Next (optional, still docs)

→ [adr0043-freshness-minimum-sufficient-set.md](./adr0043-freshness-minimum-sufficient-set.md) — Class I (F1+F4) vs Class II (F2/F3/F5); grill A/B/C/D.

---

## One-line statement

> INV-0043-F-001: PRESENT requires all binding dimensions (Freshness ∧ Scope ∧ Epoch ∧ no Revocation); any loss → UNKNOWN; F1–F5 remain OPEN candidates under PROJ-001 — freeze F contract before P, before code.
