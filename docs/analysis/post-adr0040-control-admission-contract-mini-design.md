# Post-ADR-0040 Control Admission Contract (X1 Mini Design Note)

**Status:** DRAFT (design only — no behavior change)  
**Date:** 2026-08-08  
**Parent:** [OBS-M03](./obs-m03-post-media-recovery-residency.md) · ADR-0040 (VERIFIED)  
**Field evidence:** `logs/rca-m03-fence-validation-20260807-215855/` · M03→M02 attempt-7 · `nonce=c9916187-54d7-4b26-a1f2-82da527229d9`

---

## Problem

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

Under **bilateral recovery glare**, the system can reach:

```text
REATTACH_SENT
REMOTE_RECEIPT_ACKED
negotiationConflictDetected
no REATTACH_ACCEPTED on initiator edge
attempt_timeout → FAILED_MEDIA_RECOVERY
```

This is a **contract gap**, not an ADR-0040 regression.

### Regression classification (frozen vocabulary)

| Layer | Verdict |
|-------|---------|
| Code regression | No evidence |
| Behavior regression | Confirmed |
| Scenario regression | Confirmed (bilateral glare uncovered) |
| Contract regression | Confirmed (post-ADR-0040 admission undefined) |

**One line:** Behavioral regression caused by an uncovered post-ADR-0040 control admission contract gap under bilateral recovery glare.

---

## Attempt-7 evidence (frozen)

```text
21:59:47.226  RECOVERY_REATTACH_SENT (M03→M02)
21:59:47.251  REMOTE_RECEIPT_ACKED
21:59:47.280  DROP_OWNERSHIP_CONFLICT (canonicalOwner=M03 wireOwner=M02)
21:59:49.697  M02 RECOVERY_REATTACH_INBOUND (same nonce, ~2.5s later)
              M02 REATTACH_ACCEPTED on M03-edge only — not on M03→M02 edge
21:59:57.520  attempt_timeout · CONTROL_RECONCILIATION_TIMEOUT
              M03: no REATTACH_ACCEPTED remote=M02 (entire run)
22:00:27.522  OBLIGATION_DEADLINE · residency sticks (downstream OBS)
```

**Ruled out:** reattach never sent · transport lost · simple remote reject.  
**Ruled in:** bilateral glare + admission unresolved + watchdog terminal timeout.

---

## Non-goals

```text
❌ modify recovery ownership (ADR-0040)
❌ modify completion predicate (ADR-0038)
❌ modify membership / RNA-5/6
❌ clear FAILED_MEDIA_RECOVERY residency (X2 — hold)
❌ UI / presence workaround
❌ revert SMS / unrelated features
❌ enlarge recovery budget without admission contract
```

---

## Three orthogonal facts (prefer facts over new FSM phase)

Do **not** add `CONTROL_ADMISSION_PENDING` enum yet. Model admission with facts:

### Fact 1 — Delivery (existing)

```text
reattachDeliveryState:
  TRANSPORT_SENT | REMOTE_RECEIPT_ACKED | ...
```

**Means:** peer received the reattach signal.  
**Does not mean:** control plane admitted.

### Fact 2 — Admission (to define)

```text
controlAdmissionState:
  UNKNOWN | WAITING | ACCEPTED | REJECTED
```

**Means:** whether this attempt is allowed to cross the control-plane boundary (`REATTACH_ACCEPTED` / `ICE_RESTARTING`).

Sources (candidates — not decided here):

- `REATTACH_ACCEPTED` phase transition
- `RECOVERY_CONTROL_PLANE_BOUNDARY` (E2 shortcut)
- explicit remote reject
- admission deadline exceeded

### Fact 3 — Conflict (observation)

```text
negotiationConflictDetected:
  canonicalOwner vs wireOwner mismatch
  (e.g. DROP_OWNERSHIP_CONFLICT / NEGOTIATION_OWNER_CONFLICT)
```

**Means:** admission cannot be assumed to advance until glare is resolved or budget expires.

---

## Contract questions

### Q1 — Who owns control admission authority?

**Must separate three layers:**

| Layer | Attempt-7 |
|-------|-----------|
| Recovery ownership | M03 `PARTICIPANT_REATTACH` ✅ |
| Negotiation ownership | M03 `canonicalOwner` vs M02 `wireOwner` → conflict |
| Control admission | Never `controlPlaneStarted` on M03→M02 ❌ |

Admission authority is **not** the same as recovery ownership or negotiation ownership.

### Q2 — When is attempt timeout legal?

**Not:**

```text
phase == REATTACH_REQUESTED && watchdog fired
```

**Should be:**

```text
AttemptTimeoutAllowed =
    admissionRejected
    OR admissionDeadlineExceeded
    OR (negotiationConflictUnresolved AND glareResolutionBudgetExceeded)
```

Timeout is a **terminal admission outcome**, not a substitute for unresolved WAITING.

### Q3 — Receipt semantics

```text
REMOTE_RECEIPT_ACKED  ==  delivered
REMOTE_RECEIPT_ACKED  !=  accepted
REMOTE_RECEIPT_ACKED  !=  controlPlaneStarted
```

Four layers (frozen):

```text
SEND → TRANSPORT → CONTROL_ACCEPT → SESSION_CONVERGE
 PASS     PASS         FAIL            FAIL   (attempt-7)
```

### Q4 — Does `REMOTE_RECEIPT_ACKED` trigger E2 re-evaluation?

**Yes — as evaluation trigger, not success signal.**

```text
REMOTE_RECEIPT_ACKED
        ↓
reevaluate admission evidence
        ↓
    no glare?  → E2 shortcut MAY be considered (Path B)
    glare?     → suppress E2; wait for Path A or glare resolution
```

Existing code path (ADR-0022 §4.3-D): `reattachMediaAlreadyLiveEvidenceSatisfied` → `crossControlPlaneBoundary(REATTACH_MEDIA_ALREADY_LIVE)`. Attempt-7 never logged this — receipt did not re-enter evaluation chain.

### Q5 — Success paths under glare

Two paths coexist; **glare changes eligibility:**

| Path | Flow | When allowed |
|------|------|--------------|
| **A (traditional)** | `REATTACH_REQUESTED` → `REATTACH_ACCEPTED` → `ICE_RESTARTING` | Always (including glare) |
| **B (E2 shortcut)** | `REATTACH_REQUESTED` → receipt + media live → `ICE_RESTARTING` | **Glare-free only** |

**Rationale:** Path B means "recovery already evident; skip re-negotiation." Under bilateral glare both sides may believe they can advance — shortcut would bypass ownership resolution.

---

## Watchdog predicate (sketch — not implementation)

```text
WatchdogDeferWhile =
    admissionState == WAITING
    AND deliveryEvidencePresent
    AND NOT admissionDeadlineExceeded
    AND NOT (glareConflict AND glareBudgetExceeded)

WatchdogTerminalWhen =
    admissionRejected
    OR admissionDeadlineExceeded
    OR (glareConflict AND glareBudgetExceeded)
```

**Not:** `if receiptAck: defer forever` (too wide).  
**Not:** `increase attempt_budget globally` (masks contract).

---

## Relationship to downstream OBS (X2)

```text
If X1 correct:
  attempt should not enter FAILED_MEDIA_RECOVERY spuriously
  → X2 residency exit gap may not manifest

If X1 patched only by clearing residency:
  hides control admission failure
  → forbidden
```

X2 remains **HOLD** pending X1 validation.

---

## Directed validation (Step 2 — not started)

**Goal:** validate design assumptions, not reproduce bug.

```text
Topology:  M01 / M02 / M03 · SSID happy
Trigger:   M03 WiFi flap only
Observe:
  - receipt → E2 reevaluation triggered?
  - glare → E2 suppressed?
  - CONTROL_PLANE_BOUNDARY ever logged on initiator edge?
  - admission result vs timeout timing
```

---

## Expected outcome class (if X1 lands)

```text
1 × mini design note (this doc)
1 × small ADR (X1 boundary freeze)
1 × predicate + receipt re-evaluation + glare budget PR
1 × directed validation run
```

**Not** ADR-0040-scale. **Not** FSM rewrite.

---

## Status board

```text
ADR-0040              VERIFIED · ownership PASS
SMS                   EXCLUDED
Root scenario         bilateral recovery glare
Root contract gap     control admission (post-0040)
Watchdog              premature terminal timeout (symptom)
Residency exit (X2)   HOLD · downstream only

Design note           DONE (d16029e)
Directed validation   AUTHORIZED → [run card](./post-adr0040-control-admission-validation-run-card.md)
ADR-X1                NOT STARTED
Implementation        NOT STARTED

Hypothesis (pre-field):
    receipt may not enter admission reevaluation chain

Next: field run → Branch A/B/C adjudication → ADR-X1 draft (if warranted)
```
