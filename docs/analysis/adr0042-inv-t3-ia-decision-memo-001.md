# ADR-0042 INV-T3-SCHEDULE — IA Gate Decision Memo

**ID:** adr0042-inv-t3-ia-decision-memo-001  
**Date:** 2026-08-10  
**Type:** IA GATE DECISION · BOUNDARY REVIEW (pre-diff)  
**Subject:** `adr0042-inv-t3-implementation-submission-001.md` (INV-T3-SUB-001)  
**Contract:** INV-T3-SCHEDULE ([amendment](./adr0042-inv-t3-schedule-amendment-draft-001.md))

---

## Verdict

```text
Step 4 IA Gate Review (boundary)     COMPLETE
Overall outcome                      ACCEPT (boundary)

Implementation planning            AUTHORIZED
Implementation plan                SUBMITTED — adr0042-inv-t3-implementation-plan-001.md
Diff inventory                     SUBMITTED — adr0042-inv-t3-diff-inventory-001.md
Diff gate review                   COMPLETE — PASS WITH CONSTRAINTS
Diff / src/main mutation           AUTHORIZED (bounded)
G4 progress oracle draft           NEXT (Commit 4)
APK / field run                      NOT AUTHORIZED
```

**Meaning of ACCEPT (boundary):** The submission correctly defines what may be implemented, what must not, and how to detect boundary violation. A future diff may enter **implementation planning** and **desk-test design** under the conditions below.

**Does not mean:** Production code, test expansion, or APK build is authorized without a subsequent **diff inventory review**.

---

## Review scope

This memo adjudicates **submission boundary only** (no Kotlin diff exists).

| Reviewed | Not reviewed |
|----------|----------------|
| Seam ownership model | Timer duration / backoff |
| Thread / execution boundary intent | Exact log token names |
| Lifecycle preservation design | G4 desk oracle implementation |
| Contract compliance of proposed delta | WiFi field success rate |
| Regression scope freeze | ADR-0042 §4 merge timing |

---

## IA-001 Ownership — PASS

**Question:** Does INV-T3-SCHEDULE progress ownership fall on Recovery Controller?

**Finding:** Submission places progress window arm / satisfy / terminal in `ConferenceEdgeRecoveryController`. Coordinator role is limited to `onRequestReattach` → `executeRecoveryReattachSend` (transport executor). Forbidden surfaces explicitly reject Coordinator retry queue and schedule policy.

**Code corroboration (current baseline):**

- `applyReattachDispatchOutcome(SEND_FAILED)` lives in Recovery Controller; sets `RECOVERY_PENDING`, `QUEUED`, arms `WAKEUP_ARMED`, logs `obligationOpen=true` — does **not** escalate to `FAILED_MEDIA` residency.
- `TalkbackCoordinator.onRequestReattach` is a synchronous callback wired at construction; executes send via `runOnCoordinatorSync` — no retry lifecycle ownership.

**Rejected pattern (submission §3.6):**

```text
Controller detects SEND_FAILED → Coordinator retry task → Coordinator owns lifecycle
```

**Ruling:** PASS. Ownership model is correct and consistent with existing seams.

---

## IA-004 Thread / Execution Boundary — PASS (with binding conditions)

**Question:** Does the candidate introduce scheduler → recovery → transport responsibility inversion?

**Finding:** Submission preserves:

```text
Recovery decides progress
Coordinator executes (async via existing callback)
Transport reports send truth
```

Recovery Controller already uses injected `scheduler` for watchdog / debounce. Progress window timing should extend this **obligation-scoped** pattern — not introduce a cross-session retry framework.

**Binding conditions (mandatory at diff review):**

| # | Condition |
|---|-----------|
| T1 | Progress window timer owned by Recovery Controller's existing `scheduler` injection — **obligation-scoped** only |
| T2 | Redispatch **must** traverse `onRequestReattach` → `runOnCoordinatorSync` → `executeRecoveryReattachSend` |
| T3 | **No** scheduler thread direct WebRTC / ICE API calls |
| T4 | **No** synchronous blocking wait for send completion inside Recovery progress logic |
| T5 | **No** bypass of `coordinatorExecutor` / `runOnCoordinatorSync` for reattach send |

**Ruling:** PASS subject to T1–T5 at diff inventory. Any violation → **REVISE** regardless of test color.

---

## IA-005 Lifecycle Preservation — PASS (with binding conditions)

**Question:** Does the candidate preserve obligation semantics, completion inputs, and terminal meaning?

**Finding:** Submission lifecycle delta is **additive**:

```text
SEND_FAILED → PROGRESS_WINDOW_ARMED (new)
WAKEUP_ARMED (existing capability deferral) MAY coexist
```

Explicitly forbidden in design chain:

```text
SEND_FAILED → FAILED (obligation terminal via send failure alone)
OPEN + no retry → automatic failure without explicit disposition
```

Current `SEND_FAILED` path keeps obligation open (`obligationOpen=true` log) and does not enter `enterFailedMediaResidency` — aligned with submission intent.

**Binding conditions:**

| # | Condition |
|---|-----------|
| L1 | `PROGRESS_WINDOW_ARMED` / `PROGRESS_WINDOW_EXPIRED` are **additive facts** — existing obligation / wakeup / deadline facts unchanged |
| L2 | `PROGRESS_WINDOW_EXPIRED` ≠ `DELIVERY_FAILED` ≠ silent `OBLIGATION_DEADLINE` without progress attempt when action gate permitted |
| L3 | `obligationOpen` semantics unchanged; progress contract is a **subordinate active state**, not a new close predicate |
| L4 | ADR-0038 completion predicate inputs **unchanged** |
| L5 | INV-REC-001 capability pause still defers dispatch when gates block — progress window does **not** force send through blocked gates |

**Ruling:** PASS subject to L1–L5. IA-005 is the highest-risk gate at implementation time; diff review must include fact map.

---

## IA-003 Contract Compliance — PASS

**Question:** Does the candidate implement `MUST establish bounded progress` — not `MAY retry someday`?

**Finding:** Submission §4.2 requires:

```text
recovery-owned bounded progress opportunity MUST exist
    (when action gate permits dispatch)
external events MAY accelerate — not sole path
```

This matches accepted INV-T3-SCHEDULE text. EP04 field evidence (no ICE / digest / membership qualifying event) is the negative case the contract closes.

**Distinction preserved:**

```text
MUST establish bounded progress  ≠  MUST deliver successfully
```

**Ruling:** PASS. Contract shape is correct. Implementation must not weaken to event-only schedule (status quo).

---

## IA-002 Regression — PASS (scope frozen)

**Question:** Is regression verification scoped to `SEND_FAILED` reattach path without adjacent-domain drift?

**Finding:** Submission §3.5 forbids touch of rollback, fan-out admission, ICE policy, membership convergence, completion predicate. G5 anchor: `Adr0042P0InvariantSuiteTest` (INV-T1/T2/T3 eligibility).

**Regression scope (frozen):**

```text
In scope:     SEND_FAILED → progress window → redispatch opportunity → terminal
Out of scope: rollback · fan-out · ICE policy · membership · completion predicate
```

**Ruling:** PASS. Any diff touching forbidden surfaces → **REVISE** without merit review.

---

## Primary risk watch — framework creep

**Not a direction failure.** Submission direction is correct. Highest implementation risk:

```text
new recovery scheduler
global retry manager
shared retry queue
```

Would convert a **single lifecycle gap** into a **generic recovery framework** — violates minimum-seam principle.

**Diff-trigger rule:**

| Observation at diff review | Outcome |
|----------------------------|---------|
| Changes confined to `ConferenceEdgeRecoveryController` + `EdgeRecoveryRecord` (+ closely coupled enums/facts) | Proceed |
| New cross-obligation `RetryManager` / shared queue / session-agnostic scheduler service | **REVISE** |
| Coordinator acquires schedule ownership | **REJECT** |

---

## Gate summary

| Gate | Ruling | Notes |
|------|--------|-------|
| IA-001 Ownership | **PASS** | Recovery owns progress; Coordinator executor only |
| IA-004 Thread | **PASS** (T1–T5) | Existing scheduler + onRequestReattach pattern |
| IA-005 Lifecycle | **PASS** (L1–L5) | Additive facts; no SEND_FAILED→FAILED |
| IA-003 Contract | **PASS** | Bounded progress required; delivery not guaranteed |
| IA-002 Regression | **PASS** | Scope frozen to SEND_FAILED reattach path |

---

## Authorized next steps

```text
1. Implementation plan (design detail under binding conditions)
2. Diff inventory proposal (files + call graph + fact map)
3. G4 progress oracle draft (after diff inventory — obeys contract, not shapes it)
4. Desk tests (G4 + G5) with diff
```

## Explicitly not authorized

```text
src/main Kotlin changes (until diff inventory ACCEPT)
test expansion for acceptance greening
timer/backoff implementation without diff review
APK build / WiFi field soak
ADR-0042 §4 merge (may proceed in parallel with impl plan; not blocking)
```

---

## Secondary gate (when diff exists)

```text
Diff inventory
    → re-verify IA-001..005 against actual call graph
    → framework creep check (§ Primary risk watch)
    → G4 oracle + Adr0042P0 suite
    → Diff ACCEPT → production mutation AUTHORIZED
```

Contract tests last: ownership violation → **REJECT** even if tests GREEN.

---

## WiFi investigation — closed positioning

```text
Trigger:           WiFi interruption
Amplifier:         fan-out recovery concurrency
Failure mechanism: SEND_FAILED after reattach dispatch
Missing guarantee: obligation-owned bounded progress
Candidate fix:     INV-T3-SCHEDULE (boundary ACCEPTED)
```

Investigation chain: WiFi guess → fan-out attribution → reattach liveness seam → contract amendment → IA boundary ACCEPT.

---

## References

| Doc | Role |
|-----|------|
| `adr0042-inv-t3-implementation-submission-001.md` | Reviewed submission |
| `adr0042-inv-t3-schedule-amendment-draft-001.md` | ACCEPTED contract |
| `adr0042-inv-t3-schedule-acceptance-review-001.md` | Step 3C acceptance |
| `recovery-reattach-retry-liveness-investigation-001.md` | Seam evidence |
| `ConferenceEdgeRecoveryController.applyReattachDispatchOutcome` | SEND_FAILED baseline |
| `TalkbackCoordinator.onRequestReattach` | Executor seam |

---

## One-line gate

> Boundary **ACCEPTED**: obligation-owned bounded progress may be planned at Recovery Controller seam only — diff review and desk evidence still required before code.
