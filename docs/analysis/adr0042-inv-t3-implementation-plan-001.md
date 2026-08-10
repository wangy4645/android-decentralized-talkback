# ADR-0042 INV-T3-SCHEDULE — Implementation Plan

**ID:** adr0042-inv-t3-implementation-plan-001  
**Date:** 2026-08-10  
**Type:** IMPLEMENTATION PLAN · **NOT PRODUCTION AUTHORIZATION**  
**Contract:** INV-T3-SCHEDULE  
**Parents:** [IA decision memo](./adr0042-inv-t3-ia-decision-memo-001.md) · [submission](./adr0042-inv-t3-implementation-submission-001.md) · [amendment](./adr0042-inv-t3-schedule-amendment-draft-001.md)

```text
Implementation plan              SUBMITTED (this document)
Diff inventory                   SUBMITTED — adr0042-inv-t3-diff-inventory-001.md
Diff gate review                 COMPLETE — PASS WITH CONSTRAINTS
src/main mutation                AUTHORIZED (bounded — see diff gate memo)
G4 progress oracle               NEXT (Commit 4, after impl)
```

**Discipline:** IA ACCEPT (boundary) proves a valid implementation **may** be designed. It does **not** prove any particular diff may merge.

---

## §1 — Scope

### Implement

```text
SEND_FAILED (outbound reattach, initiatesReattach)
    →
Recovery-owned bounded progress window
    →
obligation-owned redispatch opportunity (when ADR-0032 action gate permits)
    →
explicit terminal disposition (existing paths + PROGRESS_WINDOW_EXPIRED when applicable)
```

### Do not implement

```text
retry framework / RetryManager / RecoveryScheduler / RetryQueue / GlobalRecoveryService
global scheduler or cross-session coordination
ICE restart policy / candidate policy / connection priority changes
rollbackNegotiation() / ADR-0049 reuse
fan-out suppression / session isolation gate
membership retry ownership / digest-as-primary-schedule
completion predicate change (ADR-0038)
watchdog / obligation deadline budget change
```

### One-line

> Add obligation-owned bounded progress after outbound reattach `SEND_FAILED`; preserve transport semantics and existing recovery ownership.

---

## §2 — Runtime ownership

```text
ConferenceEdgeRecoveryController
        |
        | owns
        v
Progress Window (arm / fire / satisfy / expire)
Obligation lifecycle + terminal disposition

        |
        | requests (existing seam)
        v
TalkbackCoordinator.onRequestReattach
        |
        | runOnCoordinatorSync → executeRecoveryReattachSend
        v
Transport (send truth: SENT / SEND_FAILED)
```

**Forbidden:**

```text
TalkbackCoordinator
      |
      v
retry lifecycle / schedule policy / retry queue
```

**Binding conditions (from IA memo):** T1–T5 (scheduler obligation-scoped; redispatch via `onRequestReattach`; no WebRTC from scheduler thread; no sync blocking wait).

---

## §3 — Minimal state delta

### Current (SEND_FAILED outbound reattach)

```text
SEND_FAILED
    |
    v
RECOVERY_PENDING + QUEUED
    |
    v
recordMediaActionDeferred → WAKEUP_ARMED (ROUTE_CONVERGED binding)
    |
    v
scheduleWatchdog (existing)
    |
    v
wait for external reevaluate only → redispatch OR silent wait → deadline
```

### Target (additive)

```text
SEND_FAILED (initiatesReattach only)
    |
    v
existing INV-T2 handling (unchanged)
    +
PROGRESS_WINDOW_ARMED (new schedule fact)
    |
    +---- external event MAY accelerate (reevaluateOpenObligation — unchanged entry)
    |
    +---- bounded recovery-owned progress trigger MUST fire
    |         → runCompletionEvaluationStub / equivalent redispatch path
    |         → onRequestReattach when DISPATCH_REATTACH permitted
    |
    v
redispatch attempt OR explicit terminal
```

### Explicitly not introducing

```text
RETRYING
RETRY_COUNT
BACKOFF_LEVEL
retry loop state machine
```

Progress window is a **liveness schedule complement**, not a generic retry framework.

### Coexistence with WAKEUP_ARMED

```text
WAKEUP_ARMED     = capability deferral (existing; retained)
PROGRESS_WINDOW  = schedule guarantee (new; additive)
```

Both may be active. External wakeup **accelerates**; it does **not** replace obligation-owned progress trigger.

---

## §4 — Candidate seam (allowed touch surface)

### Primary (expected modify)

| Unit | Role |
|------|------|
| `ConferenceEdgeRecoveryController` | Schedule owner: arm window on `SEND_FAILED`; fire progress trigger; satisfy on redispatch; expire with explicit disposition |
| `EdgeRecoveryModels.kt` / `EdgeRecoveryRecord` | Obligation-scoped progress fields + additive enum/facts |
| Recovery lifecycle log tokens | `RECOVERY_PROGRESS_WINDOW_*` (names TBD at diff; additive only) |

### Secondary (expected modify, post-diff gate)

| Unit | Role |
|------|------|
| `Adr0042P0InvariantSuiteTest` / new G4 desk test | Progress liveness oracle (after diff gate ACCEPT) |
| `recovery-obligation-exit-audit.ps1` | Optional: recognize new lifecycle tokens (observability only) |

### Coordinator (no change expected)

`TalkbackCoordinator` — **no modification anticipated**. Existing `onRequestReattach` → `runOnCoordinatorSync` → `executeRecoveryReattachSend` is sufficient. Any Coordinator diff → **strict review** per diff inventory.

### Forbidden new abstractions

```text
RetryManager
RecoveryScheduler
RetryQueue
GlobalRecoveryService
```

---

## §5 — Proposed record fields (subject to diff review)

**Candidate names only** — final names and types require diff inventory ACCEPT.

| Field (candidate) | Purpose |
|-------------------|---------|
| `progressWindowArmedAtMs` | When obligation-owned window was established |
| `progressDeadlineAtMs` | Window end — **subordinate** to `obligationDeadlineAtMs` |
| `progressWindowState` | `NONE` · `ARMED` · `FIRED` · `SATISFIED` · `EXPIRED` |
| `progressRedispatchAttempted` | Boolean: at least one obligation-owned redispatch opportunity was taken |

**Not proposed:** retry counters, backoff levels, global queue handles.

---

## §6 — Execution model (design, not code)

### 6.1 Arm (on SEND_FAILED)

**When:** `applyReattachDispatchOutcome(SEND_FAILED)` and `record.initiatesReattach == true`.

**Actions:**

1. Existing INV-T2 path unchanged (`RECOVERY_PENDING`, `QUEUED`, `recordMediaActionDeferred`, `scheduleWatchdog`).
2. Arm progress window on record (set `progressWindowState=ARMED`, `progressDeadlineAtMs` subordinate to obligation deadline).
3. Schedule **obligation-scoped** progress trigger via existing injected `scheduler` (same pattern as `scheduleWatchdog` — not a new global service).
4. Emit `RECOVERY_PROGRESS_WINDOW_ARMED` (or equivalent additive log).

### 6.2 Fire (bounded progress trigger)

**When:** Progress deadline reached OR external acceleration already caused redispatch (window satisfied early).

**Actions:**

1. Re-enter existing redispatch evaluation: `runCompletionEvaluationStub` / `reevaluateOpenObligation` with a **progress-owned trigger** (not `DIGEST_REFRESH`-class).
2. If `DISPATCH_REATTACH` permitted → `onRequestReattach`.
3. Mark `progressRedispatchAttempted=true` when opportunity taken.
4. Emit `RECOVERY_PROGRESS_WINDOW_FIRED`.

**If action gate blocks:** defer per INV-REC-001 (same as today) — progress window does **not** force send through blocked gate.

### 6.3 Satisfy / expire

| Outcome | Behavior |
|---------|----------|
| Redispatch `SENT` | Satisfy window; existing success path |
| Redispatch `SEND_FAILED` again | Re-arm or expire per policy (minimal: one opportunity per window; details at diff) |
| Window expires without opportunity when gate permitted | `PROGRESS_WINDOW_EXPIRED` disposition; explicit terminal — **not** silent deadline |
| Obligation deadline | Existing watchdog path; progress window subordinate |

### 6.4 Thread model

```text
Recovery scheduler callback
    → evaluate gates on Recovery thread context
    → onRequestReattach (blocks on runOnCoordinatorSync — existing pattern)
    → apply outcome back on Recovery
```

No new thread ownership. No scheduler → WebRTC direct calls.

---

## §7 — Lifecycle preservation (IA-005)

| Must preserve | Must not do |
|---------------|-------------|
| `obligationOpen` semantics | `SEND_FAILED` → immediate `FAILED` / `FAILED_MEDIA` |
| INV-T1/T2 send truth | Change completion predicate inputs |
| `WAKEUP_ARMED` deferral | Auto-fail OPEN without explicit disposition |
| Existing terminal reasons | Conflate `PROGRESS_WINDOW_EXPIRED` with `DELIVERY_FAILED` |

`PROGRESS_WINDOW_EXPIRED` meaning (frozen): progress was established; delivery not achieved before window/terminal policy — **not** a schedule violation.

---

## §8 — Non-goals (reaffirmed)

Same as submission §3.5 and IA memo framework-creep watch. Any implementation plan revision that introduces cross-obligation scheduling → return to diff inventory **REVISE**.

---

## §9 — Evidence sequence (after diff gate)

```text
1. diff-inventory-001.md          → diff gate review
2. diff gate ACCEPT               → src/main mutation authorized
3. implement minimal seam
4. G4 progress oracle + desk tests (G5 Adr0042P0 suite)
5. diff inventory re-verify against actual call graph
6. field validation run card (separate authorization)
```

**Not now:** WiFi soak · G4 oracle draft · APK · test greening before diff.

---

## §10 — References

| Doc | Role |
|-----|------|
| `adr0042-inv-t3-diff-inventory-001.md` | File-level gate (companion) |
| `adr0042-inv-t3-ia-decision-memo-001.md` | IA binding conditions |
| `recovery-reattach-retry-liveness-investigation-001.md` | Current code paths |
| `ConferenceEdgeRecoveryController.applyReattachDispatchOutcome` | SEND_FAILED hook |
| `ConferenceEdgeRecoveryController.scheduleWatchdog` | Existing scheduler pattern |
| `TalkbackCoordinator.onRequestReattach` | Executor seam |

---

## §11 — One-line plan gate

> Minimal fix: one obligation-scoped progress window after outbound `SEND_FAILED`, fired through existing redispatch evaluation — not a retry framework.
