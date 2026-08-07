# ADR-X1: Control Admission After Recovery Delivery

## Status

**Status:** PROPOSED — **REVIEW GATE** (docs only — implementation BLOCKED until approved)  
**Date:** 2026-08-08  
**Parents:** [X1 mini design note](../analysis/post-adr0040-control-admission-contract-mini-design.md) · [validation run card](../analysis/post-adr0040-control-admission-validation-run-card.md) · ADR-0040 (VERIFIED) · ADR-0021

**Evidence:**

| Run | Role |
|-----|------|
| `logs/rca-m03-fence-validation-20260807-215855/` attempt-7 | Forensic confirmation |
| `logs/post-adr0040-control-admission-20260808-061947/` attempt-10 | Independent reproduction |

## Summary

Freeze the **post-ADR-0040 control admission contract**: define when a recovery attempt is **eligible to continue waiting** and when failure is **legitimately terminal** — not how to make recovery succeed.

After delivery is acknowledged (`REMOTE_RECEIPT_ACKED`), the system MUST reevaluate control admission using delivery, ownership, glare, and admission-deadline facts.

**Confirmed defect (architecture wording):**

> Post-ADR-0040 Control Admission Contract gap: `REMOTE_RECEIPT_ACKED` does not enter the admission reevaluation graph; under bilateral recovery glare the attempt continues in an unadmitted state until `CONTROL_RECONCILIATION_TIMEOUT`.

**Do not write:** watchdog bug · reattach failed · Recovery broken · improve recovery success rate

---

## Motivation

X1 solves:

```text
ambiguous admission state
        ↓
timeout decision legitimacy
```

X1 does **not** solve:

```text
recovery algorithm
        ↓
make it work
```

**Goal statement (frozen):**

> Define when a recovery attempt is eligible to continue waiting, and when failure is legitimately terminal.

## Non-goals

- **Not** improve recovery success rate or optimize recovery algorithm
- No global watchdog budget enlargement
- No failed-media residency exit change (X2 — HOLD)
- No UI / presence / UVCP projection change
- No `MediaUsabilityFact` change
- No membership fence / RCA-M03 H1 reopen
- No ADR-0040 ownership regression relitigation
- No completion predicate change (ADR-0038)
- No ADR-0039 changes
- No SMS-related code
- No `REMOTE_RECEIPT_ACKED` → `CONTROL_STARTED` direct promotion
- No modification of `FAILED_MEDIA_RECOVERY` phase semantics in X1 PR

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

#### DELIVERED_BUT_NOT_ADMITTED lifecycle (required)

Cannot define enter without exit — otherwise X1 replicates old residency problems.

```text
Enter:
    REMOTE_RECEIPT_ACKED
    AND
    !CONTROL_ADMITTED

Exit (any one):
    CONTROL_ADMITTED
    OR
    explicit terminal admission rejection
    OR
    admission deadline exceeded
```

While in this state, attempt classification is:

```text
WAITING_FOR_ADMISSION
```

Not success. Not failure. Not eligible for timeout on `controlPlaneStarted == false` alone.

### Fact 2 — Admission

```text
CONTROL_ADMITTED
```

Sources (existing paths only):

```text
REATTACH_ACCEPTED
OR
validated E2 transition (glare-free only — see §2.4)
```

### Fact 3 — Conflict

```text
NEGOTIATION_OWNER_CONFLICT
```

**MUST** be an input to timeout eligibility and E2 permission — not a side observation.

Field proof (attempt-10):

```text
receipt exists
+ control missing
+ glare unresolved
= legal WAITING_FOR_ADMISSION state
```

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
if receipt: success                              ❌
if !controlPlaneStarted: timeout                 ❌
if !controlPlaneStarted && receiptMissing: timeout ❌
```

---

## 2.4 Decision — E2 shortcut priority (X1 v1 boundary)

**Question:** Under bilateral glare, may `REATTACH_MEDIA_ALREADY_LIVE` cross the control boundary?

**X1 v1 answer:**

```text
Glare detected (unresolved ownership conflict)
        ↓
E2 shortcut SUPPRESSED
```

```text
E2 allowed:     no active conflict
E2 suppressed: conflict unresolved
```

Rationale (attempt-10): `wireOwner != canonicalOwner` — E2 may bypass ownership resolution.

This is an **X1 v1 boundary**, not a permanent design conclusion. Future ADR may relax after bilateral ownership resolution contract is defined.

---

## 3. Required behavior (contract)

### X1-1 — Receipt triggers reevaluation

On `REMOTE_RECEIPT_ACKED` for an open initiator-edge attempt:

```text
onRemoteReceiptAcked()
        ↓
reevaluateControlAdmission()
```

This is **event wiring**, not a new FSM phase.

Reevaluation MUST consider glare state and MUST NOT promote delivery to admission.

### X1-2 — Timeout eligibility predicate

Glare decision MUST be a predicate input. Pure helpers (suggested names):

```text
isAdmissionPending()
isTimeoutEligible()   // alias: RecoveryAttemptTimeoutEligible
```

**RecoveryAttemptTimeoutEligible** is true only when:

```text
terminalAdmissionFailure
OR
admissionDeadlineExpired
OR
explicitOwnershipResolutionFailure
```

**NOT** when:

```text
REMOTE_RECEIPT_ACKED
+ NEGOTIATION_OWNER_CONFLICT
+ CONTROL_ADMITTED=false
```

That combination classifies as:

```text
WAITING_FOR_ADMISSION
```

### X1-3 — E2 shortcut guard

Per §2.4: under `NEGOTIATION_OWNER_CONFLICT` / `DROP_OWNERSHIP_CONFLICT` with unresolved ownership, E2 MUST be suppressed; system waits for admission resolution, explicit reject, or admission deadline.

---

## 4. Implementation boundary (post-approval PR)

**Implementation BLOCKED until this ADR is APPROVED.**

### Allowed (minimal PR)

| Item | Scope |
|------|--------|
| **A. Event wiring** | `REMOTE_RECEIPT_ACKED` → `reevaluateControlAdmission()` — no new phase |
| **B. Predicate** | `isAdmissionPending()` · `isTimeoutEligible()` — glare-aware |
| **C. Tests** | Two regression tests (§5) |

### Explicitly excluded from X1 PR

```text
❌ MediaUsabilityFact change
❌ Presence projection change
❌ FAILED_MEDIA_RECOVERY cleanup / residency exit
❌ Membership changes
❌ ADR-0039 changes
❌ SMS related code
❌ Global timeout budget enlargement
❌ Recovery FSM phase addition
```

---

## 5. Regression test contract (required before merge)

### Test 1 — attempt-10 regression (no premature failure)

```text
REATTACH_REQUESTED
    ↓
REMOTE_RECEIPT_ACKED
    ↓
GLARE (NEGOTIATION_OWNER_CONFLICT)
    ↓
no CONTROL_ADMITTED yet
    ↓
advance clock within admission budget
    ↓
assert: NOT FAILED_MEDIA_RECOVERY
```

### Test 2 — legitimate timeout (not timeout disable)

```text
REATTACH_REQUESTED
    ↓
REMOTE_RECEIPT_ACKED
    ↓
explicit terminal admission rejection
    ↓
advance clock
    ↓
assert: FAILED_MEDIA_RECOVERY (or equivalent terminal admission failure)
```

Both tests define the contract; implementation exists to satisfy them.

---

## 6. X2 — Failed-media residency (frozen)

Do **not** implement residency exit in the same PR as X1.

```text
If X1 fixes admission → may never enter FAILED_MEDIA_RECOVERY
If FAILED_MEDIA_RECOVERY persists after X1 → reopen failed residency exit authority (separate track)
```

---

## 7. RCA-M03 closure statement

```text
RCA-M03                  CLOSED for diagnosis
X1 mechanism             CONFIRMED

Trigger:              WiFi flap
Primary defect:       Post-ADR-0040 control admission contract gap
Confirmed mechanism:  REMOTE_RECEIPT_ACKED not reevaluated
Secondary symptom:    FAILED_MEDIA_RECOVERY residency / presence sticky (X2 HOLD)

Excluded:
  SMS regression · ADR-0040 failure · membership divergence · UI bug · watchdog duration as root
```

---

## 8. Review gate checklist

Required before **APPROVED**:

| # | Constraint | Status |
|---|------------|--------|
| 1 | Motivation: timeout eligibility, not recovery success | ✅ in this revision |
| 2 | `DELIVERED_BUT_NOT_ADMITTED` enter + exit lifecycle | ✅ in this revision |
| 3 | Glare as timeout predicate input | ✅ in this revision |
| 4 | E2 suppressed under unresolved glare (X1 v1) | ✅ in this revision |

After approval:

```text
ADR-X1 APPROVED
        ↓
implementation branch
        ↓
small PR (wiring + predicate + 2 tests)
```

---

## 9. Status board

```text
RCA-M03                  CLOSED for diagnosis
X1 mechanism             CONFIRMED
ADR-X1                   PROPOSED — REVIEW GATE
Implementation           BLOCKED until contract approved
X2 residency exit        HOLD

Evidence:
    attempt-7  forensic
    attempt-10 independent reproduction
```
