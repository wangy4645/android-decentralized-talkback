# ADR-X1: Control Admission After Recovery Delivery

## Status

**Status:** PROPOSED (docs only — no behavior change)  
**Date:** 2026-08-08  
**Parents:** [X1 mini design note](../analysis/post-adr0040-control-admission-contract-mini-design.md) · [validation run card](../analysis/post-adr0040-control-admission-validation-run-card.md) · ADR-0040 (VERIFIED) · ADR-0021

**Evidence:**

| Run | Role |
|-----|------|
| `logs/rca-m03-fence-validation-20260807-215855/` attempt-7 | Forensic confirmation |
| `logs/post-adr0040-control-admission-20260808-061947/` attempt-10 | Independent reproduction |

## Summary

Freeze the **post-ADR-0040 control admission contract**: after recovery delivery is acknowledged (`REMOTE_RECEIPT_ACKED`), the system MUST reevaluate control admission using delivery, ownership, glare, and admission-deadline facts — not treat receipt as success, and not timeout merely because `controlPlaneStarted == false`.

**Confirmed defect (architecture wording):**

> Post-ADR-0040 Control Admission Contract gap: `REMOTE_RECEIPT_ACKED` does not enter the admission reevaluation graph; under bilateral recovery glare the attempt continues in an unadmitted state until `CONTROL_RECONCILIATION_TIMEOUT`.

**Do not write:** watchdog bug · reattach failed · Recovery broken

## Non-goals

- No global watchdog budget enlargement
- No failed-media residency exit change (X2 — HOLD)
- No UI / presence / UVCP projection change
- No membership fence / RCA-M03 H1 reopen
- No ADR-0040 ownership regression relitigation
- No completion predicate change (ADR-0038)
- No `REMOTE_RECEIPT_ACKED` → `CONTROL_STARTED` direct promotion

---

## 1. Problem

ADR-0040 guarantees:

```text
ownership resumed
attempt alive
watchdog active
```

It does **not** guarantee:

```text
control admission completed
controlPlaneStarted() == true
```

Implicit assumption today:

```text
ownership resumed → control handshake naturally progresses
```

That assumption fails under **bilateral recovery glare**.

### Confirmed failure chain

```text
WiFi flap
    ↓
Recovery ownership (ADR-0040: owner resumed)
    ↓
REATTACH_REQUESTED
    ↓
REMOTE_RECEIPT_ACKED
    ↓
    X  missing edge
    ↓
Admission reevaluation missing
    ↓
NEGOTIATION_OWNER_CONFLICT
    ↓
controlPlaneStarted=false
    ↓
CONTROL_RECONCILIATION_TIMEOUT
    ↓
FAILED_MEDIA_RECOVERY
    ↓
presence sticky (downstream symptom — X2)
```

Layers verified PASS in field: transport · ownership · glare detection · membership.

**Single gap:**

```text
receipt fact → admission decision graph
```

---

## 2. Decision — Three orthogonal facts

### Fact 1 — Delivery

```text
REMOTE_RECEIPT_ACKED
```

Meaning: peer received recovery intent.

**Not** success. **Not** failure. Third state:

```text
DELIVERED_BUT_NOT_ADMITTED
```

### Fact 2 — Admission

```text
CONTROL_ADMITTED
```

Sources (existing paths only):

```text
REATTACH_ACCEPTED
OR
validated E2 transition (glare-free only)
```

### Fact 3 — Conflict

```text
NEGOTIATION_OWNER_CONFLICT
```

Determines whether E2 shortcut is permitted.

### Admission decision inputs

```text
deliveryFact
+ ownershipFact
+ glareFact
+ controlAdmissionDeadline
= admission decision
```

Forbidden predicates:

```text
if receipt: success                    ❌
if !controlPlaneStarted: timeout        ❌
```

---

## 3. Required behavior (contract)

### X1-1 — Receipt triggers reevaluation

On `REMOTE_RECEIPT_ACKED` for an open initiator-edge attempt:

```text
onRemoteReceiptAcked()
        ↓
reevaluateControlAdmission()
```

Reevaluation MUST consider glare state and MUST NOT promote delivery to admission.

### X1-2 — Glare-aware timeout eligibility

Attempt timeout is legitimate only when:

```text
admissionRejected
OR admissionDeadlineExpired
OR terminalConflictResolvedAsFailure
```

NOT when:

```text
controlPlaneStarted == false alone
```

### X1-3 — E2 shortcut guard (unchanged intent, explicit)

Path B (`REATTACH_MEDIA_ALREADY_LIVE` / E2 boundary) remains valid **only when glare-free**.

Under `NEGOTIATION_OWNER_CONFLICT` / `DROP_OWNERSHIP_CONFLICT`: E2 MUST be suppressed; system waits for admission resolution or explicit reject/deadline.

---

## 4. Implementation boundary

### Allowed (minimal)

| Item | Scope |
|------|--------|
| Receipt → reevaluation event wiring | `onRemoteReceiptAcked` → `reevaluateControlAdmission` |
| Glare-aware timeout predicate | `CanAttemptTimeout?` per §3 |
| Regression test | receipt + glare + no `REATTACH_ACCEPTED` → no premature `FAILED_MEDIA_RECOVERY` |

### Forbidden

| Item | Reason |
|------|--------|
| Increase attempt/obligation timeout budgets | Defers incorrect admission, does not fix graph |
| Clear failed-media residency on timer | Symptom patch (X2) |
| `REMOTE_RECEIPT_ACKED` → `controlPlaneStarted=true` | Violates delivery/admission layering |
| Recovery FSM phase addition | Contract wiring only |
| Membership / completion predicate changes | Out of scope |

---

## 5. Regression test contract (required before merge)

Scenario (harness or desk):

```text
REATTACH_REQUESTED
    ↓
REMOTE_RECEIPT_ACKED
    ↓
GLARE (NEGOTIATION_OWNER_CONFLICT)
    ↓
no REATTACH_ACCEPTED yet
    ↓
clock advances within admission budget
    ↓
assert: NOT FAILED_MEDIA_RECOVERY (premature)
```

This test defines the contract; implementation exists to satisfy it.

---

## 6. X2 — Failed-media residency (frozen)

Do **not** implement residency exit in the same PR as X1.

Rationale:

```text
If X1 fixes admission → may never enter FAILED_MEDIA_RECOVERY
If FAILED_MEDIA_RECOVERY persists after X1 → reopen failed residency exit authority (separate track)
```

---

## 7. RCA-M03 closure statement

```text
RCA-M03

Trigger:              WiFi flap
Primary defect:       Post-ADR-0040 control admission contract gap
Confirmed mechanism:  REMOTE_RECEIPT_ACKED not reevaluated
Secondary symptom:    FAILED_MEDIA_RECOVERY residency / presence sticky

Excluded:
  SMS regression
  ADR-0040 failure
  membership divergence
  UI projection bug
  watchdog duration as root cause
```

---

## 8. Status board

```text
X1 Control Admission Contract
    Status:           CONFIRMED
    ADR-X1:            PROPOSED (this doc)
    Implementation:    NOT STARTED

Evidence:
    attempt-7  forensic
    attempt-10 independent reproduction

X2 residency exit:   HOLD
```
