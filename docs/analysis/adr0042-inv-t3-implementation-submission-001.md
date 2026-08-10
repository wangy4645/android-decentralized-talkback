# ADR-0042 INV-T3-SCHEDULE — Implementation Candidate Submission

**ID:** adr0042-inv-t3-implementation-submission-001  
**Date:** 2026-08-10  
**Type:** IMPLEMENTATION CANDIDATE SUBMISSION · **NOT AUTHORIZATION**  
**Contract:** INV-T3-SCHEDULE ([amendment](./adr0042-inv-t3-schedule-amendment-draft-001.md) · [acceptance review](./adr0042-inv-t3-schedule-acceptance-review-001.md))  
**Production mutation:** **NOT AUTHORIZED**

```text
Step 3A Contract Gap Review          COMPLETE
Step 3B Schedule Design              COMPLETE
Step 3C Amendment Acceptance         COMPLETE

Step 4 Candidate Submission          SUBMITTED (this document)
Step 4 IA Gate Review                COMPLETE — adr0042-inv-t3-ia-decision-memo-001.md
Step 4 IA outcome                    ACCEPT (boundary)
Implementation plan                  SUBMITTED — adr0042-inv-t3-implementation-plan-001.md
Diff inventory                       SUBMITTED
Diff gate review                     COMPLETE — PASS WITH CONSTRAINTS
src/main mutation                    AUTHORIZED (bounded)
G4 oracle                            Commit 4
```

**Submission header**

| Field | Value |
|-------|-------|
| Submission ID | INV-T3-SUB-001 |
| Submitter | WiFi Recovery / ADR-0042 track |
| Date | 2026-08-10 |
| Commit | _none — submission is pre-diff_ |
| Branch | _none — submission is pre-diff_ |
| PR | _none — submission is pre-diff_ |

```text
Purpose:
  Answer IA review: "If implementation is approved, what may change,
  what must not change, and how is boundary violation detected?"

This document does NOT authorize src/main changes, test expansion, or APK build.
IA gate ACCEPT required before any implementation candidate diff.
```

---

## §1 — Implementation intent

### Implement

```text
INV-T3-SCHEDULE bounded progress after outbound reattach SEND_FAILED
```

### Do not implement

```text
rollbackNegotiation() / ADR-0049 rollback reuse
ICE restart strategy / candidate policy / connection priority changes
fan-out suppression / session isolation gate
membership retry ownership / digest-as-schedule dependency
completion predicate change (ADR-0038)
watchdog / obligation deadline budget change
Coordinator retry queue / retry counting / schedule policy
```

### One-line intent

> Add obligation-owned progress scheduling after `SEND_FAILED`; preserve existing recovery ownership and transport semantics.

### Core boundary (frozen)

```text
MUST establish bounded progress  ≠  MUST deliver successfully
```

Recovery decides progress · Coordinator executes · Transport reports truth.

---

## §2 — Existing seam mapping

**Seam inventory only.** No implementation design in this section.

### 2.1 Recovery Controller (`ConferenceEdgeRecoveryController`)

```text
ConferenceEdgeRecoveryController
    |
    +-- applyReattachDispatchOutcome()
    |       |
    |       +-- SEND_FAILED handling
    |       |       → RECOVERY_PENDING
    |       |       → reattachDeliveryState = QUEUED
    |       |       → recordMediaActionDeferred(PARTICIPANT_REATTACH, MEDIA_NOT_READY)
    |       |       → WAKEUP_ARMED (binding = ROUTE_CONVERGED / edge)
    |       |
    |       +-- SENT / other dispatch outcomes
    |
    +-- recordMediaActionDeferred()
    |       → defer reason + WAKEUP_ARMED arming
    |
    +-- reevaluateOpenObligation()
    |       → external trigger entry (ROUTE_CONVERGED-class, etc.)
    |
    +-- runCompletionEvaluationStub()
    |       → action gate evaluation
    |       → if DISPATCH_REATTACH permitted → onRequestReattach callback
    |
    +-- closeObligation() / terminal disposition paths
    |       → OBLIGATION_DEADLINE · EDGE_RECOVERED · etc.
    |
    +-- EdgeRecoveryRecord (obligation state, wakeupBinding, deadline)
```

### 2.2 Coordinator (`TalkbackCoordinator`)

```text
TalkbackCoordinator
    |
    +-- onRequestReattach callback (wired at construction)
    |       → executeRecoveryReattachSend()
    |
    +-- executeRecoveryReattachSend()
    |       → local sendto truth (INV-T1)
    |       → SEND_FAILED / SENT fact back to Recovery
    |
    +-- onMeshIceStateChanged() → ROUTE_CONVERGED injection
    |
    +-- forceControlReconciliationAfterDigestRefresh()
            → membership digest side-effect path (not schedule owner)
```

### 2.3 Transport / ICE (read-only for this candidate)

```text
ICE / mesh transport layer
    → route state facts consumed by Coordinator
    → no recovery lifecycle ownership today
```

### 2.4 Present vs missing

| Present today | Missing today |
|---------------|---------------|
| `SEND_FAILED` transport fact (INV-T2) | Obligation-owned **bounded progress owner** |
| Obligation open/close state | Auditable **progress window** lifecycle |
| Obligation deadline / watchdog | Guaranteed **recovery-owned redispatch opportunity** before silent terminal |
| `WAKEUP_ARMED` / `WAKEUP_FIRED` / `WAKEUP_EXPIRED` | Progress path independent of sole external trigger |
| `onRequestReattach` dispatch seam | — |
| ADR-0032 action gate | — |

**Gap label (frozen):** `state owner ≠ progress owner`.

---

## §3 — Allowed change boundary

### 3.1 Recovery Controller — ALLOWED

```text
arm / satisfy / expire bounded progress window (obligation-scoped)
track progress window subordinate to existing obligation deadline
request bounded redispatch opportunity via existing onRequestReattach
emit lifecycle facts for INV-T3-SCHEDULE observability
integrate with existing WAKEUP_ARMED deferral (capability wait)
reach explicit terminal disposition (incl. PROGRESS_WINDOW_EXPIRED when applicable)
```

**Ceiling:** changes confined to `ConferenceEdgeRecoveryController` and closely coupled recovery models (`EdgeRecoveryRecord`, recovery lifecycle enums/facts).

### 3.2 Coordinator — ALLOWED (executor only)

```text
execute requested redispatch (existing executeRecoveryReattachSend path)
return send truth (SENT / SEND_FAILED) unchanged
consume-only: continue injecting reachability facts (ROUTE_CONVERGED, etc.)
```

### 3.3 Coordinator — FORBIDDEN

```text
retry policy / retry counting / schedule ownership
recovery obligation lifecycle ownership
retry queue / periodic redispatch timer owned by Coordinator
membership-driven schedule as primary progress mechanism
```

### 3.4 Transport / ICE — FORBIDDEN

```text
ICE restart behavior change
candidate policy change
connection priority change
send-fact semantics change (INV-T1 / INV-T2)
```

### 3.5 Adjacent domains — FORBIDDEN

```text
TalkbackCoordinator generic retry queue
ADR-0049 rollbackNegotiation reuse
fan-out / session isolation admission
membership / digest refresh ownership
ADR-0038 completion predicate
RNA-5 / RNA-6
UI / banner / timeout budget
```

### 3.6 Anti-pattern guard (Step 4 primary risk)

**Rejected candidate shape:**

```text
SEND_FAILED
    ↓
Coordinator retry queue
    ↓
periodic ICE restart
```

**Required shape:**

```text
Recovery decides progress
Coordinator executes
Transport reports truth
```

Any diff introducing Coordinator-owned scheduling or ICE policy rewrite → **Outcome: REJECT** at IA gate.

---

## §4 — Proposed lifecycle delta

**State transition description only.** No retry loop · no retry count · no backoff algorithm.

### 4.1 Current (field-observed)

```text
SEND_FAILED
    |
    v
WAKEUP_ARMED (external-event binding)
    |
    v
wait for external event only
    |
    +---- qualifying external event → redispatch
    |
    +---- no qualifying event → silent wait → deadline / ICE_FAILED terminal
```

### 4.2 Candidate (contract-shaped)

```text
SEND_FAILED
    |
    v
PROGRESS_WINDOW_ARMED          ← obligation-owned schedule fact
    |
    +---- external event MAY accelerate
    |
    +---- recovery-owned bounded progress opportunity MUST exist
    |         (when action gate permits dispatch)
    |
    v
redispatch attempt (via onRequestReattach)
    |
    +---- delivery success → existing success / recovery paths
    |
    +---- delivery failure → existing failure paths
    |         (may re-enter progress window per policy; not specified here)
    |
    v
explicit terminal disposition
    (EDGE_RECOVERED | PROGRESS_WINDOW_EXPIRED | OBLIGATION_DEADLINE | …)
```

### 4.3 Relationship to existing wakeup facts

```text
WAKEUP_ARMED (capability deferral)     MAY coexist — not replaced
PROGRESS_WINDOW_ARMED (schedule)       NEW — satisfies INV-T3-SCHEDULE
```

External events (`ROUTE_CONVERGED`, etc.) **accelerate**; they are not the **sole** progress path.

### 4.4 Explicitly deferred to post-IA implementation design

```text
progress window duration
max redispatch opportunities per window
backoff / jitter
exact log token names
timer implementation mechanism
```

These are **not** part of this submission and must not block IA boundary review.

---

## §5 — Acceptance evidence plan

Maps Step 3C gates G1–G5 to verifiable evidence **at IA review time** (desk) and **post-impl** (when authorized).

| Gate | Question | Evidence type | Pass condition |
|------|----------|---------------|----------------|
| **G1** Ownership | Does schedule live in Recovery Controller? | Diff inventory + call graph | No Coordinator schedule owner; `onRequestReattach` remains dispatch seam |
| **G2** Thread | Scope isolation? | Diff inventory + IA-004 | No ADR-0049 / completion / membership / ICE policy files in allowed set |
| **G3** Facts | Lifecycle facts preserved + new facts auditable? | Fact map | Existing obligation / wakeup / deadline facts unchanged; progress window facts additive |
| **G4** Progress | `SEND_FAILED` cannot silently wait to deadline? | Desk oracle (draft at impl) | `SEND_FAILED` → progress window armed → redispatch opportunity when gates allow → explicit terminal |
| **G5** Regression | ADR-0042 P0 unchanged outside progress path? | `Adr0042P0InvariantSuiteTest` + targeted cases | INV-T1/T2/T3 eligibility / t3 route-redispatch cases GREEN; no predicate drift |

### G4 oracle criteria (frozen — test draft deferred until IA ACCEPT)

**Assert:**

```text
SEND_FAILED
  → progress window created (lifecycle fact)
  → retry opportunity exists when action gate permits
  → terminal disposition explicit and attributable
```

**Do not assert:**

```text
WiFi flap recovery success rate
delivery success after SEND_FAILED
ICE_CONNECTED within N seconds
```

Oracle obeys contract; contract does not obey oracle.

### Post-IA evidence bundle (when diff exists)

```text
1. Diff inventory (files + line budget)
2. Call graph delta (SEND_FAILED → progress → onRequestReattach)
3. Fact map (existing + additive)
4. Thread / scope proof (§3 boundary checklist)
5. Desk tests (G4 + G5)
6. IA decision memo reference
```

---

## §6 — Required IA questions

Submission proactively answers for reviewers.

### IA-001 Ownership

> Does the candidate place retry **lifecycle** ownership in Recovery Controller?

**Expected answer:** Yes. Progress window arm/satisfy/terminal in `ConferenceEdgeRecoveryController`. Coordinator executes `onRequestReattach` only.

**Fail if:** Coordinator owns retry queue, schedule policy, or obligation terminal logic for progress.

---

### IA-002 Regression

> Does existing recovery behavior remain unchanged outside the `SEND_FAILED` progress path?

**Expected answer:** Yes. INV-T1/T2 send truth, INV-T3 eligibility, existing wakeup deferral, deadline watchdog, and completion inputs unchanged except additive progress facts.

**Fail if:** Completion predicate, membership retry, or transport semantics drift.

---

### IA-003 Contract

> Is INV-T3-SCHEDULE satisfied without expanding semantics?

**Expected answer:** Yes. Bounded progress established; delivery success not guaranteed; external events accelerate only.

**Fail if:** Candidate implies `MUST succeed`, adds Coordinator schedule owner, or uses digest as sole trigger.

---

### IA-004 Thread

> Does the candidate introduce blocking or scheduler ownership leakage?

**Expected answer:** No. No Coordinator retry queue; no ICE restart rewrite; no ADR-0049 touch; no fan-out isolation.

**Fail if:** Diff touches forbidden surfaces in §3.4–§3.5 or anti-pattern in §3.6.

---

### IA-005 Lifecycle

> Are existing obligation facts preserved?

**Expected answer:** Yes. `SEND_FAILED`, `WAKEUP_ARMED`, obligation open/close, deadline, and terminal disposition paths retained. Progress facts are **additive**.

**Fail if:** Existing facts removed, renamed incompatibly, or terminal paths become silent without explicit disposition.

---

## §7 — IA review path (proposed)

```text
Submission review (this document)
        |
        v
IA-001 Ownership → IA-004 Thread → IA-005 Lifecycle → IA-003 Contract
        |
        v
IA-002 Regression boundary
        |
        v
Diff inventory (when candidate diff proposed)
        |
        v
Desk evidence (G4 oracle + Adr0042P0 suite)
        |
        v
IA Gate Decision Memo → ACCEPT | REJECT | REVISE
```

**Contract tests last:** ownership violation → REJECT even if incidental tests GREEN.

---

## §8 — Out of scope for this submission

```text
src/main Kotlin changes
test file expansion
APK build / field run
progress window duration tuning
ADR-0042 §4 merge (pending IA ACCEPT + diff)
field validation run card
```

---

## §9 — References

| Doc | Role |
|-----|------|
| `adr0042-inv-t3-schedule-amendment-draft-001.md` | ACCEPTED contract |
| `adr0042-inv-t3-schedule-acceptance-review-001.md` | Architect acceptance |
| `recovery-reattach-liveness-schedule-design-001.md` | Step 3B design |
| `recovery-reattach-retry-liveness-investigation-001.md` | Seam / code path analysis |
| [ADR-0042](../adr/0042-recovery-reattach-transport-delivery-semantics.md) | Parent invariants |
| [ADR-0032](../adr/0032-recovery-dispatch-eligibility-contract.md) | Action gate |
| [ADR-0022](../adr/0022-recovery-completion-ownership.md) | INV-REC-001 |
| `Adr0042P0InvariantSuiteTest` | G5 regression anchor |
| `logs/recovery-layer-attribution-rfa-001-20260810-111902/` | EP04/EP05 field evidence |

---

## §10 — One-line submission gate

> If approved, implementation may add **obligation-owned bounded progress** after `SEND_FAILED` at the Recovery Controller seam — and **nowhere else**.
