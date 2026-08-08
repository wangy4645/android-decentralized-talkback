# ADR-0043 — Architecture Close

**Status:** **APPROVED** · **architecture decision chain CLOSED** · **Implementation AUTHORIZED** via [adr0043-implementation-authorization.md](./adr0043-implementation-authorization.md) · **Field NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Package:** **P1 + O1 + F1/F4** · Class II **DEFERRED** · Seam II **DEFERRED**  
**O1 boundary:** [adr0043-o1-boundary.md](./adr0043-o1-boundary.md) (**APPROVED**)  
**Selection:** [adr0043-o-selection-decision.md](./adr0043-o-selection-decision.md) (**ACCEPTED**)

---

## Status board

```text
Architecture direction:   CLOSED / ACCEPTED
Implementation auth:      ACCEPTED · v0 runtime AUTHORIZED (narrow)
Field:                    NOT AUTHORIZED
```

---

## Closed architecture decisions

| Domain | Decision | Status |
|--------|----------|--------|
| Truth locus | Authority accepted membership context | **CLOSED** |
| Observed gap | MIXED · ARCHITECTURE GAP OBSERVED | Fact frozen |
| Projection | **P1** query | **CLOSED** |
| Freshness min | **F1 Epoch + F4 Scope** | **CLOSED** |
| Invalid PRESENT | IP-1…IP-8 · IP-001 | **CLOSED** |
| Auth law | PRESENT ≠ GRANT · P1-AUTH-001 | **CLOSED** |
| Auth ownership | **O1** issuer adjudicates (within auth rules) | **CLOSED** |
| O1 boundary | may/must-not | **CLOSED** |
| Class II | F2/F3/F5 | **DEFERRED** |
| Seam II | wait / establish / terminate | **DEFERRED** |
| O2 | | **RESERVED** |
| O3 / T3 | | **FALLBACK ONLY** |
| Handler | `NO_MEMBERSHIP_CONTEXT` terminal | **UNCHANGED** |
| Option C | synthetic context | **OUT** |

---

## One-line architecture freeze

> **ADR-0043 architecture direction ACCEPTED: Authority holds membership truth; P1 projects time-bounded evidence; issuer (O1) may authorize GROUP_RESYNC only with valid F1∧F4 PRESENT within authorization rules — never from local belief. Class II and Seam II deferred. v0 implementation is separately authorized; field is not.**

---

## What is closed

```text
ADR-0043 architecture decision chain
```

## What is not granted by Architecture Close alone

```text
Field validation
Timeout / FSM / handler change
Scope beyond Implementation Authorization
```

Implementation permission: [adr0043-implementation-authorization.md](./adr0043-implementation-authorization.md) (**ACCEPTED**).
