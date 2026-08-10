# RRA-005-IC — Implementation Candidate Review

**ID:** rra-005-ic  
**Date:** 2026-08-10  
**Type:** CANDIDATE REVIEW · **NOT IMPLEMENTATION** · **NOT AUTHORIZATION**  
**Status:** **PASS**  
**Parent:** [rra-005-reattach-delivery-phase-ownership-design.md](./rra-005-reattach-delivery-phase-ownership-design.md) (COMPLETE)  
**Successor:** [rra-005-ia-implementation-authorization-review.md](./rra-005-ia-implementation-authorization-review.md)

```text
RRA-001..004                 COMPLETE
RRA-005 Contract             COMPLETE
Implementation Candidate Review   PASS  ← this document
Implementation Authorization      PENDING / NOT YET
```

---

## Discipline

```text
Design review only.
Do NOT write product code.
Do NOT grant Implementation Authorization here.
```

---

## IC-1 — Facade is truth adapter only · **ACCEPTED**

```text
REATTACH
   ↓
DeliveryProgressFacade
   ├── observe SENT
   ├── wait remote evidence
   └── emit:
          DELIVERY_EVIDENCE_OBTAINED / DELIVERY_PROGRESS_OBTAINED
          DELIVERY_PROGRESS_EXPIRED
```

Facade converts:

```text
transport fact → delivery truth fact
```

Does **not** produce: retry / failure / completion decisions.

```text
Facade ≠ RetryController
Facade ≠ CompletionPolicy
Facade ≠ RecoveryOwner
```

---

## IC-2 — Phase-2 fully additive · **ACCEPTED**

Existing (unchanged):

```text
Phase 1: SEND_FAILED → PROGRESS_WINDOW → TRANSPORT_SENT → SATISFIED
```

Additive:

```text
Phase 2: TRANSPORT_SENT → WAITING_REMOTE_EVIDENCE
           → REMOTE_RECEIPT_ACKED → Completion reevaluate
           or EXPIRED
```

`SATISFIED` remains **dispatch opportunity consumed** — not delivery succeeded.

**Forbidden merge:**

```text
SATISFIED → WAITING_REMOTE_EVIDENCE   ❌  (merges two owners)
```

**Correct handoff:**

```text
ProgressWindow  --handoff fact-->  DeliveryProgress  --evidence-->  CompletionEvaluation
```

---

## IC-3 — ADR-0035 invariant reuse without offer ownership · **ACCEPTED WITH OWNERSHIP SEPARATION**

**May reuse:** delivery truth invariants (`TRANSPORT_SENT ≠ DELIVERY_EVIDENCE`, `REMOTE_RECEIPT_ACKED` as evidence boundary, bounded window, expire semantics).

**Must not reuse:** `OfferDeliveryPolicy` lifecycle ownership (episode / retries / terminal).

| | Offer delivery | Reattach delivery |
|--|----------------|-------------------|
| Object | membership offer | existing recovery episode |
| Miss meaning | offer not delivered | recovery chance lacked evidence |
| Owner | offer flow | edge recovery flow |
| Completion | offer accepted | edge recovered |

```text
Share invariant · do not share lifecycle
```

---

## Final adjudication

```text
IC-1 Facade = Truth Adapter              ACCEPTED
IC-2 Phase-2 additive                    ACCEPTED
IC-3 ADR-0035 invariant reuse            ACCEPTED WITH OWNERSHIP SEPARATION

Implementation Candidate Review          PASS
Implementation Authorization             NOT YET
```

Next: **Implementation Authorization Review** — not code.
