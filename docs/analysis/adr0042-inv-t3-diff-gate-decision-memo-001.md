# ADR-0042 INV-T3-SCHEDULE — Diff Gate Decision Memo

**ID:** adr0042-inv-t3-diff-gate-decision-memo-001  
**Date:** 2026-08-10  
**Type:** DIFF GATE DECISION  
**Subject:** `adr0042-inv-t3-diff-inventory-001.md` · [implementation plan](./adr0042-inv-t3-implementation-plan-001.md)  
**IA parent:** [IA decision memo](./adr0042-inv-t3-ia-decision-memo-001.md) (ACCEPT boundary)

---

## Verdict

```text
Diff Gate Review                 COMPLETE
Overall decision                 PASS WITH IMPLEMENTATION CONSTRAINTS

Commits 1–4                      COMPLETE
  1 Model facts                  DONE
  2 Progress window arm          DONE
  3 Dispatch path reuse          DONE
  4 G4 oracle + G5 Adr0042       DONE

src/main mutation                AUTHORIZED (landed Commits 1–3)
Desk G4/G5                       GREEN
APK / field run                    NOT AUTHORIZED
```

**Meaning:** Proposed touch surface is **equivalent** to IA-approved surface. Implementation may proceed under frozen constraints below — **not** unbounded.

**Discipline:** IA ACCEPT = may design. Diff Gate PASS = this inventory may touch `src/main` at listed seams only.

---

## Gate rulings

### DG-001 Ownership boundary — PASS

**Check:** Progress window owner remains Recovery Controller.

| Inventory row | Ruling |
|---------------|--------|
| `ConferenceEdgeRecoveryController.kt` | **PASS** — obligation, deadline, lifecycle facts already owned; no new owner introduced |

Aligns with IA-001. Primary mutation confined to Controller.

---

### DG-002 Model expansion — PASS WITH REVIEW

**Check:** `EdgeRecoveryModels.kt` changes are additive and describe **progress**, not retry algorithm.

| Allowed | Forbidden |
|---------|-----------|
| `PROGRESS_WINDOW_*` state / metadata | `RetryState`, `RetryPolicy`, `RetryQueue`, `BackoffState` |
| Lifecycle facts on `EdgeRecoveryRecord` | Cross-obligation queue handles |

**Binding:** Every model field must answer "where is progress?" — not "how many retries?" or "what backoff?"

**At commit review:** Model-only diff (Commit 1) re-checked against this gate before Controller logic lands.

---

### DG-003 Coordinator boundary — PASS (strict)

**Check:** `TalkbackCoordinator.kt` — no change expected.

| Situation | Ruling |
|-----------|--------|
| No Coordinator diff | **PASS** |
| Coordinator diff for new retry entrypoint (e.g. `scheduleRecoveryRetry()`) | **REJECT** |
| Coordinator diff for existing `onRequestReattach` call-path reuse only | **REVISE** — must prove zero schedule semantics |

Default for any unexpected Coordinator touch: **REVISE**.

---

### DG-004 Scheduler usage — PASS WITH THREAD CHECK

**Check:** Reuse existing `scheduler` pattern (as `scheduleWatchdog`); no responsibility inversion.

| Allowed | Forbidden |
|---------|-----------|
| Recovery Controller → `scheduler` callback → coordinator execution request | Scheduler thread → direct WebRTC / ICE API |
| `onRequestReattach` → `runOnCoordinatorSync` (existing) | Synchronous blocking wait for send in progress logic |

Re-verify call graph at each Controller commit (Commits 2–3).

---

### DG-005 Non-goal containment — PASS

| Domain | Ruling |
|--------|--------|
| rollback / ADR-0049 | **PASS** — not in inventory |
| fan-out / session isolation | **PASS** |
| ICE policy | **PASS** — forbidden rows |
| membership | **PASS** |
| completion predicate | **PASS** |

Inventory does not authorize forbidden surfaces.

---

## Frozen implementation constraints

All implementation commits **must** satisfy:

```text
1. Primary mutation: ConferenceEdgeRecoveryController (lifecycle logic)
2. Model: EdgeRecoveryModels additive lifecycle state only (progress, not retry algorithm)
3. Coordinator: no ownership expansion; no new retry entrypoint
4. Scheduler: no WebRTC direct call; redispatch via onRequestReattach only
5. No retry framework abstraction (RetryManager, GlobalRecoveryService, shared queue)
6. No completion / terminal semantics change (INV-T2, ADR-0038 frozen)
```

Violation at any commit → stop; return to **REVISE** or **REJECT** per inventory §4.

---

## Authorized implementation sequence

Incremental commits — each verifiable against constraints:

| Commit | Scope | Gate re-check |
|--------|-------|---------------|
| **1** | `EdgeRecoveryModels.kt` — additive state/facts only | DG-002 |
| **2** | `ConferenceEdgeRecoveryController` — progress-window arm/fire/satisfy/expire | DG-001, DG-004 |
| **3** | Existing dispatch reuse (`runCompletionEvaluationStub` / `onRequestReattach`) | DG-003, DG-004 |
| **4** | G4 progress oracle + G5 desk tests (`Adr0042P0*`) | G4 criteria (IA memo) |

**Not in Commit 1–3:** test greening for acceptance; G4 oracle before Controller behavior exists.

---

## Post-implementation verification (before APK)

```text
1. Actual diff vs inventory table (§1) — no forbidden files
2. Call graph: SEND_FAILED → progress window → onRequestReattach
3. Adr0042P0 suite GREEN (G5)
4. G4 desk test: progress window armed → opportunity → explicit terminal
5. Optional: recovery-obligation-exit-audit.ps1 token recognition
```

Field validation remains separate authorization.

---

## Explicitly not authorized

```text
WiFi soak / field run
APK build for field
Coordinator schedule policy
ADR-0042 §4 doc merge (optional parallel)
Framework / multi-commit scope creep beyond inventory
```

---

## References

| Doc | Role |
|-----|------|
| `adr0042-inv-t3-diff-inventory-001.md` | Reviewed inventory |
| `adr0042-inv-t3-implementation-plan-001.md` | Execution model |
| `adr0042-inv-t3-ia-decision-memo-001.md` | IA T1–T5, L1–L5 |

---

## One-line gate

> Touch surface **matches** IA approval: Recovery owns progress; Coordinator executes; models additive only — implement in four bounded commits.
