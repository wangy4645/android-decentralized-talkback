# ADR-0043 — Implementation Authorization

**Status:** **ACCEPTED** · **v0 runtime implementation AUTHORIZED** · **Field NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Architecture:** [adr0043-architecture-close.md](./adr0043-architecture-close.md) (**APPROVED** · **CLOSED**)  
**Package:** **P1 + O1 + F1/F4** · Class II **DEFERRED** · Seam II **DEFERRED**  
**Plan:** [adr0043-implementation-plan.md](./adr0043-implementation-plan.md)

---

## Status board

```text
Architecture:                 CLOSED
Implementation Authorization: ACCEPTED
v0 runtime code:              AUTHORIZED (narrow seam below)
Unit / desk tests:            AUTHORIZED
Branch / PR:                  AUTHORIZED for this seam only
Field validation:             NOT AUTHORIZED
Timeout / UI / handler soft:  FORBIDDEN
```

---

## Authorization grant

> **AUTHORIZED:** implement Seam I v0 so the recovery issuer cannot dispatch `GROUP_RESYNC` without valid authority-grounded P1 PRESENT evidence (F1∧F4), and O1 may grant dispatch only within authorization rules (PRESENT ≠ GRANT).

```text
Architecture Close alone was insufficient.
This document is the missing permission.
```

---

## v0 in scope

| Item | Requirement |
|------|-------------|
| Seam | Issuer path before `GROUP_RESYNC` / `requestMembershipConvergenceFromAuthority` |
| Projection | **P1**: obtain authority-grounded context-existence evidence (abstract semantics; minimal wire as needed) |
| Freshness | **F1 + F4** bind at authorization evaluation time |
| Ownership | **O1**: issuer decides dispatch **within authorization rules** given valid PRESENT |
| Law | INV-0043-DB-001 · P1-AUTH-001 · IP-001 · O-INV-001…006 · O1 boundary |

**Behavioral intent (M0 class):**

```text
WITHOUT valid PRESENT (F1∧F4) → MUST NOT dispatch GROUP_RESYNC
WITH PRESENT → may enter O1 auth; grant still not automatic from PRESENT alone if other rules deny
UNKNOWN / mismatch / stale epoch → no P1-path grant (not invent ABSENT)
```

---

## Explicitly out of scope (FORBIDDEN in this authorization)

```text
NO:
- soften / change NO_MEMBERSHIP_CONTEXT handler acceptance
- synthetic / recovery-only membership context (Option C)
- Seam II establish / wait-policy / terminate-as-completion
- F2 consume / F3 TTL / F5 revalidate as P0 requirements
- O2 admission artifact / O3 component
- timeout / watchdog budget enlarge
- UI / banner / reconnect presentation changes
- ADR-0042 SENT / transport semantics
- ADR-0038 completion predicate / X1 / X2
- field flap / directed field runs
- membership mutation authority changes (ADR-0023)
```

---

## Minimal implementation seams (guidance)

1. **Gate on issuer emit path** — `maybeRequestMembershipConvergenceForConferenceRecovery` / `requestMembershipConvergenceFromAuthority` (and PEER_EDGE_READY retry) must not send without P1+O1 allow.  
2. **P1 evidence obtain** — authority-originated existence answer bound to scope + decision epoch + correlation (per P1 design boundary). Prefer smallest change that satisfies semantics; do not build a second membership DB.  
3. **O1 evaluate** — consume evidence; re-check F-MIN at auth time; PRESENT necessary not sufficient.  
4. **Observability** — log why blocked (UNKNOWN / ABSENT / epoch-scope fail / auth deny) without inventing ABSENT from timeout.

Exact wire/API left to implementer within these semantics — not a license to expand scope.

---

## Acceptance criteria (desk)

| ID | Criterion |
|----|-----------|
| AC-1 | No `GROUP_RESYNC` dispatch on conference-recovery path without valid PRESENT (F1∧F4) |
| AC-2 | Digest match / reachability / local accepted conference alone cannot yield PRESENT |
| AC-3 | Cross decision-epoch PRESENT reuse blocked |
| AC-4 | Handler `NO_MEMBERSHIP_CONTEXT` behavior unchanged |
| AC-5 | Unit/desk tests cover: missing evidence blocks; valid PRESENT may proceed; IP-style promotions rejected |
| AC-6 | No timeout/UI/completion predicate edits in the same change set |

---

## Test intent (red → green)

```text
RED:  recovery issuer path emits GROUP_RESYNC with only local conference + digest / no authority PRESENT
GREEN: same setup blocked; emit only when P1 PRESENT valid under F1∧F4 and O1 grants
```

Field: **not** part of this authorization.

---

## Process

```text
1. Implement on branch (this seam only)
2. Red-then-green unit/desk tests
3. PR against main — cite this authorization + architecture close
4. Field validation requires a SEPARATE field authorization
```

---

## One-line statement

> Implementation AUTHORIZED for Seam I v0 (P1+O1+F1/F4 gate on GROUP_RESYNC issuer path); field, handler soften, Seam II, Class II, and timeouts remain forbidden.
