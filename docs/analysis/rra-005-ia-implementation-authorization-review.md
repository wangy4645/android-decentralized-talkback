# RRA-005-IA — Implementation Authorization Review

**ID:** rra-005-ia  
**Date:** 2026-08-10  
**Type:** AUTHORIZATION GATE  
**Status:** **GRANTED** (bounded)  
**Parents:** [rra-005](./rra-005-reattach-delivery-phase-ownership-design.md) · [rra-005-ic](./rra-005-ic-implementation-candidate-review.md) (PASS)

```text
Implementation Candidate Review   PASS
Implementation Authorization      GRANTED (compressed engineering path)
```

---

## Why GRANT now (process compression)

Discovery (RRA-001–004) justified heavy desk process.  
The gap is now a **controlled additive** Phase-2 observation on REATTACH — not unknown-domain exploration.

```text
RRA-005 COMPLETE → Implementation → Desk tests → Field EP
```

Further RRA-006 layers would be diminishing returns for this Android module change.

---

## GRANT conditions (only three)

1. **Only REATTACH delivery path**
2. **Only add Phase-2 ownership** (observation lifecycle)
3. **Forbid changing existing state semantics** (`SATISFIED`, completion, retry, ICE, membership, Coordinator ownership)

---

## Frozen boundaries (under GRANT)

### Change

```text
ALLOW:
  Delivery truth state additions
  REATTACH Phase-2 wiring (SENT → arm → OBTAINED | EXPIRED)
  Thin ReattachDeliveryProgressFacade

FORBID:
  Retry / backoff / Global RetryManager
  Completion predicate changes
  ICE / media / membership changes
  Coordinator ownership expansion
  DeliveryProgressOwner → retry() / fail() / complete()
```

### Test

```text
Phase-1 still valid: SEND_FAILED → SENT → SATISFIED
Phase-2 additive:    SENT → WAITING → OBTAINED | EXPIRED
EXPIRED ≠ FAILED_MEDIA
```

### Evidence

```text
Observe: TRANSPORT_SENT, REMOTE_RECEIPT_ACKED,
         REATTACH_DELIVERY_PROGRESS_*, EDGE_RECOVERED
Not: SENT / SATISFIED alone as delivery success
```

---

## Impl sketch (landed)

```text
SENT → satisfyProgressWindow (unchanged)
     → reattachDeliveryProgress.arm(...)
RECEIPT → markEvidenceObtained(...)
deadline → EXPIRED (fact only)
```
