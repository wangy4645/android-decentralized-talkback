# ADR-0043 — O-Selection Decision

**Status:** **ACCEPTED** · **architecture ownership closed** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Decision:** **ACCEPT A** — **O1 Issuer adjudicates**  
**Package:** **P1 + O1 + F1/F4** · Class II **DEFERRED** · Seam II **DEFERRED**  
**Next:** [adr0043-o1-boundary.md](./adr0043-o1-boundary.md) · [adr0043-architecture-close.md](./adr0043-architecture-close.md)

---

## Status board

```text
O-selection:              ACCEPTED · O1
Projection:               P1 CLOSED
Freshness minimum:        F1 + F4 CLOSED
Class II (F2/F3/F5):      DEFERRED
Seam II:                  DEFERRED
O2:                       RESERVED
O3:                       FALLBACK ONLY
Implementation / Field:   FROZEN
```

---

## Decision

| Option | Result |
|--------|--------|
| **A** ACCEPT O1 | **SELECTED** |
| B Defer | Rejected |
| C Prefer O2 | Rejected → **RESERVED** |
| D Escalate O3 | Rejected → **FALLBACK ONLY** |

---

## Why O1

Under frozen law (TRUTH-001 · F-MIN-001 · IP-001 · PROJ-OWN-001 · P1-AUTH-001 · O-INV-001…006), O1 is the path that does **not** introduce a new ownership locus.

```text
Authority → accepted membership context truth
        → P1 evidence
        → Issuer authorization decision
        → GROUP_RESYNC dispatch
```

Not: issuer local belief → GROUP_RESYNC.

O1 keeps current **action** ownership; corrects **proof** source to authority-grounded evidence.

---

## O2 / O3

| ID | Status | Note |
|----|--------|------|
| O2 | **RESERVED** | Viable later; risk of admission cache → second truth |
| O3 | **FALLBACK ONLY** | Reopens T3 / Ownership / Projection / failure ownership |

---

## Frozen architecture package

```text
Truth locus:              Authority accepted membership context   CLOSED
Projection:               P1                                        CLOSED
Freshness minimum:        F1 + F4                                   CLOSED
Invalid patterns:         IP-1…IP-8                                 CLOSED
Authorization ownership:  O1                                        CLOSED
Class II:                 F2/F3/F5                                  DEFERRED
Seam II:                  wait/establish/terminate                  DEFERRED
```

```text
O selected ≠ Implementation approved
```

---

## One-line statement

> ACCEPTED: P1 + O1 + F1/F4; Class II and Seam II deferred; architecture direction closed pending O1 boundary + architecture close — no code/field.
