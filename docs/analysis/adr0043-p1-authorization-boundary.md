# ADR-0043 — P1 Authorization Boundary

**Status:** **APPROVED FOR NEXT DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Decision:** **INV-0043-P1-AUTH-001 ACCEPT** · A1–A5 ACCEPT  
**Wording patch:** UNKNOWN must not grant via P1 path (not “never recover”)  
**Upstream:** [adr0043-p1-design-boundary.md](./adr0043-p1-design-boundary.md) (**APPROVED**)  
**Next:** [adr0043-o-selection-constraint-memo.md](./adr0043-o-selection-constraint-memo.md) (**DRAFT FOR REVIEW**)

---

## Status board

```text
P1 Authorization Boundary: APPROVED
INV-0043-P1-AUTH-001:      ACCEPTED
O-selection:               OPEN
F2/F3/F5 / Seam II:        OPEN
Implementation / Field:    FROZEN
Runtime:                   FROZEN
```

---

## Separation (frozen)

```text
Authority truth
      ↓
P1 evidence
      ↓
Authorization
      ↓
Dispatch
```

Must not collapse to:

```text
Authority truth → P1 PRESENT → GROUP_RESYNC
```

```text
truth ≠ evidence ≠ authorization
PRESENT ≠ automatic GROUP_RESYNC
```

---

## INV-0043-P1-AUTH-001 — ACCEPTED

> PRESENT P1 evidence is a **necessary prerequisite** for authorization evaluation of `GROUP_RESYNC`, not a sufficient grant to dispatch.

| Evidence | Enter authorization evaluation? | Dispatch grant via P1 path? |
|----------|----------------------------------|-----------------------------|
| **PRESENT** (F1∧F4 + correlation) | **May** enter | Only if authorization rule allows |
| **ABSENT** (authority-asserted) | May conclude deny | No |
| **UNKNOWN** | Must not treat as PRESENT | **MUST NOT** produce a dispatch grant through the P1 evidence path |

```text
UNKNOWN MUST NOT produce a dispatch grant through the P1 evidence path.
≠ “UNKNOWN forever forbids all recovery”
A/B/C and Seam II remain OPEN for non-P1-grant paths.
```

---

## A1–A5 — ACCEPTED

| ID | Principle |
|----|-----------|
| **A1** | Authorization does not redefine membership truth — authority owns context truth; issuer owns action evaluation |
| **A2** | Authorization consumes only IP-001-legal evidence (no digest / topology / local-session → PRESENT) |
| **A3** | At evaluation time, re-check F-MIN-001 for the **active** decision epoch (`query time ≠ authorization time`) |
| **A4** | Evidence obtained ≠ dispatch approved |
| **A5** | Authorization reject ≠ authority ABSENT |

---

## UNKNOWN / ABSENT / DENY (independent)

| Type | Meaning |
|------|---------|
| **ABSENT** | Authority asserts no context |
| **UNKNOWN** | Current proof of PRESENT unavailable |
| **DENY** | Evidence may exist, but action not allowed |

```text
ABSENT  ≠ UNKNOWN
UNKNOWN ≠ DENY
DENY    ≠ ABSENT
```

Authorization deny does not rewrite membership truth.

---

## Explicit non-goals

```text
NO: ACCEPT O1/O2/O3 · wire / API · retry/timeout/FSM · Seam II · branch/runtime/field
```

---

## Next

→ [adr0043-o-selection-constraint-memo.md](./adr0043-o-selection-constraint-memo.md)

What ownership invariants must any O1/O2/O3 satisfy under P1 + AUTH — **without selecting O**.

---

## One-line statement

> PRESENT is necessary not sufficient; UNKNOWN cannot grant via P1 path; ABSENT/UNKNOWN/DENY stay distinct; O-selection still OPEN under A1–A5.
