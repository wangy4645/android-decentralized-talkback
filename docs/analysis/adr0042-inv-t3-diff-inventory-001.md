# ADR-0042 INV-T3-SCHEDULE — Diff Inventory

**ID:** adr0042-inv-t3-diff-inventory-001  
**Date:** 2026-08-10  
**Type:** DIFF INVENTORY · **GATE PASS WITH CONSTRAINTS**  
**Companion:** [implementation plan](./adr0042-inv-t3-implementation-plan-001.md)  
**IA parent:** [decision memo](./adr0042-inv-t3-ia-decision-memo-001.md)  
**Gate decision:** [diff gate memo](./adr0042-inv-t3-diff-gate-decision-memo-001.md)

```text
Diff inventory                   SUBMITTED (this document)
Diff gate review                 COMPLETE — PASS WITH CONSTRAINTS
src/main mutation                AUTHORIZED (bounded)
Test expansion                   AUTHORIZED (Commit 4)
```

**Scope:** Proposed file touch list for INV-T3-SCHEDULE minimal seam. No Kotlin diff exists yet. Gate review adjudicates **intent to touch**, not line counts.

**Canonical tree:** `talkback/android-board-talkback/` only. Worktrees (`talkback-field-adr0042p0`, `wt-*`, `.wt-*`) are **out of scope** unless explicitly synced post-merge.

---

## §1 — Inventory table

| File | Change type | Reason | Gate | Notes |
|------|-------------|--------|------|-------|
| `src/main/.../ConferenceEdgeRecoveryController.kt` | **modify** | INV-T3-SCHEDULE owner: arm/fire/satisfy/expire progress window on `SEND_FAILED` | **allowed** | Primary seam; obligation-scoped scheduler callback |
| `src/main/.../EdgeRecoveryModels.kt` | **modify** | Additive `EdgeRecoveryRecord` fields + progress window enum | **allowed** | No retry counters / queue handles |
| `src/main/.../TalkbackCoordinator.kt` | **no change expected** | Executor seam sufficient (`onRequestReattach`) | **strict** | Any diff → strict review; default **REVISE** |
| `src/main/.../ice/*` · mesh transport | **forbidden** | Out of scope (IA-004) | **reject** | ICE restart / candidate policy |
| `src/main/.../membership/*` | **forbidden** | Out of scope | **reject** | No digest-as-schedule |
| `src/main/.../negotiation/*` | **forbidden** | Out of scope | **reject** | No rollback / ADR-0049 |
| `src/main/.../completion/*` | **forbidden** | ADR-0038 frozen | **reject** | Predicate unchanged |
| `src/main/.../RecoveryCompletionPolicy*` | **forbidden** | Completion inputs frozen | **reject** | Unless additive observation only — default reject |
| `src/test/.../Adr0042P0InvariantSuiteTest.kt` | **modify (deferred)** | G5 regression | **deferred** | After diff gate ACCEPT |
| `src/test/.../Adr0042P0ReattachSendFailedReactionTest.kt` | **modify (deferred)** | SEND_FAILED baseline | **deferred** | After diff gate ACCEPT |
| `src/test/.../*InvT3Schedule*` (new) | **add (deferred)** | G4 progress oracle | **deferred** | After diff gate ACCEPT + impl |
| `docs/adr/0042-*.md` | **modify (optional)** | Merge INV-T3-SCHEDULE into ADR-0042 §4 | **review** | May parallel impl; not blocking diff gate |
| `scripts/recovery-obligation-exit-audit.ps1` | **modify (optional)** | Recognize `RECOVERY_PROGRESS_WINDOW_*` tokens | **review** | Observability only; no behavior |
| `RetryManager` / `RecoveryScheduler` / new service class | **forbidden** | Framework creep | **reject** | IA memo primary risk |

---

## §2 — Allowed modify detail

### 2.1 `ConferenceEdgeRecoveryController.kt`

**Expected touch points (function-level, not line budget):**

| Function / area | Change |
|-----------------|--------|
| `applyReattachDispatchOutcome(SEND_FAILED)` | Arm progress window when `initiatesReattach` |
| New private helpers (names TBD) | `armProgressWindow`, `fireProgressWindow`, `satisfyProgressWindow`, `expireProgressWindow` — **obligation-scoped only** |
| `runCompletionEvaluationStub` / `reevaluateOpenObligation` | Accept progress-owned trigger; may accelerate existing path |
| `scheduleWatchdog` | **No budget change**; may coordinate subordination only |
| `recordMediaActionDeferred` | **Unchanged** unless additive log correlation |
| `closeObligation` | May emit `PROGRESS_WINDOW_EXPIRED` observation; **no predicate change** |

**Must not add:** cross-edge maps, global timer registry, Coordinator callbacks for schedule policy.

### 2.2 `EdgeRecoveryModels.kt`

**Expected additive types:**

| Type | Gate |
|------|------|
| `ProgressWindowState` enum (`NONE`, `ARMED`, `FIRED`, `SATISFIED`, `EXPIRED`) | **allowed** |
| `EdgeRecoveryRecord` fields (see plan §5) | **allowed** |

**Must not add:** `RETRYING`, `RETRY_COUNT`, `BACKOFF_LEVEL`, queue reference types.

---

## §3 — Coordinator strict review rule

```text
Default:  TalkbackCoordinator.kt  →  NO CHANGE

If diff touches Coordinator:
  - Only acceptable: wiring bugfix with zero schedule semantics
  - Any retry policy / timer / queue / lifecycle ownership  →  REVISE
  - Any new public API for recovery scheduling  →  REJECT
```

Current baseline: `onRequestReattach` already uses `runOnCoordinatorSync` + `dispatchRecoveryReattachOutcome` — sufficient for executor role.

---

## §4 — Diff gate decision rules

### PASS

All of:

```text
Recovery Controller owns new progress lifecycle
Coordinator unchanged OR strict-review exception with zero schedule semantics
No files in forbidden rows modified
No behavior change outside SEND_FAILED + initiatesReattach progress path
No new global retry abstraction
```

**Outcome:** `src/main` mutation **AUTHORIZED** (bounded to inventory PASS rows).

### REVISE

Any of:

```text
global retry abstraction (RetryManager, shared queue, cross-session timer)
Coordinator schedule ownership
ICE / membership / completion files touched
progress window forces dispatch through blocked action gate
new service class outside allowed inventory
line budget >> minimal seam (framework creep)
```

**Outcome:** Revise inventory / plan; diff gate remains **NOT AUTHORIZED**.

### REJECT

Any of:

```text
completion predicate changed
obligation open/close semantics changed
rollback / ADR-0049 mixed in
SEND_FAILED escalates to FAILED_MEDIA / obligation terminal (INV-T2 violation)
```

**Outcome:** Return to design; do not implement.

---

## §5 — Call graph delta (proposed)

```text
applyReattachDispatchOutcome(SEND_FAILED)
    |
    +-- [existing] recordMediaActionDeferred → WAKEUP_ARMED
    +-- [existing] scheduleWatchdog
    +-- [new] armProgressWindow(record)
            |
            v
    scheduler.schedule { fireProgressWindow(record) }
            |
            v
    runCompletionEvaluationStub / reevaluateOpenObligation
    (progress-owned trigger)
            |
            v
    onRequestReattach → TalkbackCoordinator (unchanged)
            |
            v
    applyReattachDispatchOutcome(outcome)
```

**Verification at diff review:** No edge from progress scheduler to ICE API; no Coordinator → schedule feedback loop.

---

## §6 — Fact map (additive)

| Fact (proposed) | When | Replaces? |
|-----------------|------|-----------|
| `RECOVERY_PROGRESS_WINDOW_ARMED` | SEND_FAILED + initiatesReattach | No |
| `RECOVERY_PROGRESS_WINDOW_FIRED` | Progress trigger runs | No |
| `RECOVERY_PROGRESS_WINDOW_SATISFIED` | Redispatch opportunity taken / delivery progressed | No |
| `RECOVERY_PROGRESS_WINDOW_EXPIRED` | Window ended without required progress | No |
| `RECOVERY_WAKEUP_ARMED` | Capability deferral | **Existing — unchanged** |
| `obligationOpen=true` on SEND_FAILED | INV-T2 | **Existing — unchanged** |

---

## §7 — Deferred inventory (post diff gate ACCEPT)

| File | When | Gate |
|------|------|------|
| `Adr0042P0InvariantSuiteTest.kt` | With impl | G5 regression |
| `Adr0042P0ReattachSendFailedReactionTest.kt` | With impl | SEND_FAILED baseline |
| New G4 desk test | After impl candidate exists | Progress oracle |
| `recovery-obligation-exit-audit.ps1` | Optional | Observability |

**G4 oracle remains frozen** until diff gate ACCEPT — oracle tests **implementation**, not design guess.

---

## §8 — Line budget guidance (soft)

Not a hard gate; framework-creep signal.

| Category | Guidance |
|----------|----------|
| `ConferenceEdgeRecoveryController` | Small, localized helpers; avoid sweeping refactor |
| `EdgeRecoveryModels` | ≤ few fields + one enum |
| New files | **0** preferred; **REJECT** if new service package |
| Coordinator | **0** lines preferred |

Large diff without inventory amendment → **REVISE**.

---

## §9 — Diff gate review checklist

| # | Check | Pass? |
|---|-------|-------|
| D1 | Only PASS-row `src/main` files modified | _pending diff_ |
| D2 | No forbidden files in diff | _pending diff_ |
| D3 | No `RetryManager` / global queue | _pending diff_ |
| D4 | `onRequestReattach` remains sole redispatch executor | _pending diff_ |
| D5 | INV-T2 SEND_FAILED path preserved | _pending diff_ |
| D6 | `initiatesReattach` guard on progress arm | _pending diff_ |
| D7 | Progress window subordinate to obligation deadline | _pending diff_ |
| D8 | G4/G5 tests added with impl (not before) | _deferred_ |

**Current status:** Inventory **SUBMITTED** for pre-implementation gate. **Gate PASS WITH CONSTRAINTS** — see diff gate decision memo. Actual diff verification at each commit.

---

## §10 — Next steps

```text
1. Diff gate review (this inventory)     ← current
2. If PASS → authorize src/main mutation
3. Implement per plan-001
4. G4 oracle + desk tests
5. Re-verify call graph against inventory
6. Field validation (separate authorization)
```

**Not now:** WiFi soak · premature G4 · APK.

---

## §11 — References

| Doc | Role |
|-----|------|
| `adr0042-inv-t3-implementation-plan-001.md` | How (companion) |
| `adr0042-inv-t3-ia-decision-memo-001.md` | IA binding T1–T5, L1–L5 |
| `adr0042-inv-t3-implementation-submission-001.md` | Boundary ceiling |

---

## §12 — One-line inventory gate

> Touch **only** Recovery Controller + Record models for obligation-scoped progress — Coordinator executes, nothing else moves.
