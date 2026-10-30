# E.16 Joint D1 + C Orchestration Status

**Status:** `CLOSED / FIELD_VERIFIED` · **ARCHIVED / STOP THE LINE** — (`PASS_JOINT_D1_C` + `SUCCESSOR_START`)
**Phase-3 closure:** [e16-phase3-closure-field-verified.md](./e16-phase3-closure-field-verified.md) (**ARCHIVED**)
**Field Attempt #1:** `logs/joint-d1-c-e16-p3-act-20260804-171812` → `PASS_JOINT_D1_C` / CLEAN / `ACTIVATION_KIND=SUCCESSOR_START`
**Harness backlog:** [e16-harness-live-activation-observer-backlog.md](./e16-harness-live-activation-observer-backlog.md) (observability only)
**Prerequisite for later recovery:** Joint activation/convergence semantics verified; PR5-3 UVCP migration `CLOSED / MIGRATION VERIFIED` ([pr5-3-uvcp-baseline-status.md](./pr5-3-uvcp-baseline-status.md)); remaining unknowns = transport path resurrection ([l15-prr-baseline-status.md](./l15-prr-baseline-status.md))

**Field run card:** [e16-joint-1-clean-field-run.md](./e16-joint-1-clean-field-run.md)  
**Classification review:** [e16-joint-classification-review.md](./e16-joint-classification-review.md)  
**Admission contract:** [e16-clean-attempt-admission-contract.md](./e16-clean-attempt-admission-contract.md) (**FROZEN**)  
**Run card:** [e16-clean-attempt-run-card.md](./e16-clean-attempt-run-card.md) (Shape A + Field Readiness Gate F0–F5)  
**Edge trigger analysis:** [e16-clean-attempt-edge-trigger-analysis.md](./e16-clean-attempt-edge-trigger-analysis.md)  
**Edge admission gap:** [e16-edge-admission-gap-analysis.md](./e16-edge-admission-gap-analysis.md)  
**Edge lifecycle contract:** [e16-edge-lifecycle-contract-v1.md](./e16-edge-lifecycle-contract-v1.md) (**FROZEN** — Admission ≠ Activation)  
**Successor activation options:** [e16-successor-activation-evidence-options.md](./e16-successor-activation-evidence-options.md) (Option B shape chosen via token decision)  
**Activation producer contract:** [e16-activation-producer-contract.md](./e16-activation-producer-contract.md) (producer-emitted only)
**Successor activation transition:** [e16-successor-activation-transition-definition.md](./e16-successor-activation-transition-definition.md) (Contract v0 / S3)
**Successor activation token:** [e16-successor-activation-token-decision.md](./e16-successor-activation-token-decision.md) (**FROZEN** — RECOVERY_SUCCESSOR_STARTED)
**Implementation scope:** [e16-successor-activation-implementation-scope.md](./e16-successor-activation-implementation-scope.md) (**IMPLEMENTED** + FIELD_VERIFIED)
**Field authorization gate:** [e16-phase3-field-authorization-gate-v1.md](./e16-phase3-field-authorization-gate-v1.md) (**CONSUMED**)  
**ADR:** ADR-0022 §E.16 / §E.16.0 O0 Ownership Transaction Contract v1  
**Does not:** reopen D1/C under E16 | treat harness WARN as recovery bug | jump L.1.5 as E16 work | claim production WiFi fixed  
**Inputs:** [0022-recovery-completion-ownership.md](../adr/0022-recovery-completion-ownership.md) §E.15.15, §E.16 | `logs/joint-d1-c-20260801-*` | `R16_OWNERSHIP_AUDIT.txt` | `logs/joint-d1-c-e16-j1-20260804-150753` | `logs/joint-d1-c-e16-p3-act-20260804-171812`

---


## Frozen — Edge activation evidence (2026-08-04)

```text
Frozen:

ADMIT_SUCCESSOR is not an EDGE_START evidence.

Joint convergence requires authority-owned ActivationEvidence.

Successor pathway (chosen):
  kind  = SUCCESSOR_START
  event = RECOVERY_SUCCESSOR_STARTED
  emit  = after Admission / before Delivery
          in admitSuccessorObligationEpisode

EDGE pathway (existing):
  kind  = EDGE_START
  event = RECOVERY_EDGE_STARTED
```

**Also frozen:** do **not** accept `ADMIT_SUCCESSOR` as Joint edge-start substitute.  
**Producer red line:** [e16-activation-producer-contract.md](./e16-activation-producer-contract.md)  
**Token decision:** [e16-successor-activation-token-decision.md](./e16-successor-activation-token-decision.md)

```text
ActivationEvidence MUST be producer-emitted.
Classifier MUST NOT infer activation from admission,
delivery, or completion tokens.
Classifier MUST accept kind in { EDGE_START, SUCCESSOR_START }.
```

**Activation evidence contract:** **FROZEN**  
**Archive 161614 (pre-emit):** `FAIL_CLEAN_JOINT` / `J_E_D1_NOT_ESTABLISHED` (historical, correct)  
**Field Attempt #1 (post-emit):** `PASS_JOINT_D1_C` / CLEAN / `ACTIVATION_KIND=SUCCESSOR_START`

```text
E16-J1:           legal supersede? YES
Clean attempt:    YES
Joint convergence: ESTABLISHED (FIELD_VERIFIED)
Activation evidence contract: FROZEN + FIELD_VERIFIED
  kind=SUCCESSOR_START event=RECOVERY_SUCCESSOR_STARTED
  emit after Admission / before Delivery
Root gap:         none — E16 Phase-3 CLOSED / FIELD_VERIFIED
Next:             L.1.5/PRR baseline (separate; [l15-prr-baseline-status.md](./l15-prr-baseline-status.md)); harness liveActivationObserved backlog only
```


```text
ADMIT_SUCCESSOR → obligation exists (Admission)
        |
        |  needs distinct Activation transition (producer-emitted)
        v
EDGE_START_EVIDENCE → J-E / D1 lifecycle allowed
```

---

## Purpose

Record **§E.16 Joint D1 + C** orchestration archaeology and the **CLOSED / FIELD_VERIFIED** freeze.

Phase ladder is complete (0–3). Authoritative closure: [e16-phase3-closure-field-verified.md](./e16-phase3-closure-field-verified.md). Historical sections below retain pre-close archaeology; they do not reopen E16.

**Next frontier (outside E16):** [l15-prr-baseline-status.md](./l15-prr-baseline-status.md) (`AUTHORIZED / BASELINE OPEN`). PR5-3: [pr5-3-uvcp-baseline-status.md](./pr5-3-uvcp-baseline-status.md) (`CLOSED / MIGRATION VERIFIED`). Harness-only backlog: [e16-harness-live-activation-observer-backlog.md](./e16-harness-live-activation-observer-backlog.md).

---

## ADR frozen posture

```text
D1 (§E.15.15)                    CLOSED / FIELD_VERIFIED ✅  (2026-08-01)
PR5-2c-C                         CLOSED / VERIFIED ✅
J-X semantics pack               CLOSED
DeferredIntentAuthority Slice-1  CLOSED / VERIFIED (Phase-1/2)

Joint D1 + C Regression (§E.16)  CLOSED / FIELD_VERIFIED (2026-08-04)
Phase-3 field Joint               VERIFIED (single authorized attempt)
PR5-3 / UVCP                     CLOSED / MIGRATION VERIFIED (2026-08-04); L.1.5/PRR next
```


**Discipline:** Do not modify D1 or C production ownership code for this regression. Reuse existing debug injections only.

---

## What Joint must prove (four concerns)

| # | Concern |
|---|---------|
| 1 | D1 ingress miss must not falsely enter C (`NEGOTIATION_CAN_EXECUTE` / `HELD(dispatch)` without true negotiation conditions) |
| 2 | C must not swallow D1 retry (independent wakeups; no cross-domain impersonation) |
| 3 | Fence correlation must not cross-contaminate (D1 `offerLineageId` vs C `intentId/attemptId/obligationGen`) |
| 4 | `CompletionPolicy` must not close early — **J-F hard gate** |

**J-B hard requirement:**

```text
CREATED → CAN_EXECUTE → HELD(dispatch) → DRAIN_RETRY → REPROBE_PASS → EXECUTED

DISPATCHED alone = FAIL
DISPATCHED ≠ EXECUTED ≠ RECOVERED
```

---

## Recent harness work (completed)

`Pr52cDebugInjection.blockDispatch` is now wired into `TalkbackCoordinator.canDispatchRecoveryMediaAction`:

```text
if (Pr52cDebugInjection.isDispatchBlocked(sessionId, remoteModuleId)) {
    false   // forces dispatchReady=false on field-shaped probe path
} else {
    buildRecoveryEdgeReachabilitySnapshot(...).canDispatchRecoverySignal()
}
```

**UT:** `Pr52cBlockReadinessWiringTest` — block forces `HELD(dispatch)`; release → `EXECUTED`; inactive block preserves production readiness.

**What this enables:** Cleanest J-B injection path — `canDispatchRecoverySignal=false` while `negotiationExecutable=true` (ADR §E.14.12.4 priority #1).

**Orchestrator v2 (2026-08-04):** `run-joint-d1-c-orchestrator.ps1` emits provisional `ORCHESTRATOR_V2_CLASSIFICATION.txt` (O0–O3). Obligation is created and runtime-verified **before** flap arm (O2). Invalid exercises (`DISPATCHED_WITHOUT_INTENT`) abort without scoring J-B. **Sole final verdict** is `scripts/classify-joint-d1-c.ps1` → `JOINT_EVIDENCE_CLASSIFICATION.txt` (ADR §E.16.0).

**What this does NOT fix:** Historical silent slot loss (`201352` / Class 2) remains ownership failure under the new contract. Compliant Authority supersede is **not** scored as wipe.

---

## Field run inventory (10 runs, all incomplete)

| Run | J-A | J-B | J-C..F | Classification | Root hint |
|-----|-----|-----|--------|----------------|-----------|
| `145829` | FAIL | FAIL | C..F PASS | `FAIL_C_CHAIN` | CREATE too early → `GROUP_MESH`; prod dispatch before C |
| `150355` | — | — | — | (early attempt) | timing |
| `150808` | PASS | FAIL | C..F PASS | `FAIL_C_CHAIN` | `HELD`+`DRAIN_RETRY` but no `REPROBE_PASS`/`EXECUTED`; sticky DROP |
| `151247` | FAIL | FAIL | C..F PASS | `FAIL_D1_CHAIN` | D1 chain incomplete; C partial |
| `153247` | PASS | FAIL | J-E FAIL | `FAIL_C_CHAIN` | `DISPATCHED` without `EXECUTED`; fence break |
| `194918` | — | — | — | orchestration | — |
| `195351` | — | — | — | orchestration | — |
| `195948` | PASS | FAIL | C..F PASS | `FAIL_C_CHAIN` | `already_issued` → `STALE_DISCARD` before `HELD` |
| `200519` | PASS | NOT_VERIFIED | C..F PASS | `FAIL_C_CHAIN` | CREATE order fixed; `already_issued` on in-flight offer |
| `201352` | FAIL | NOT_VERIFIED | C..F PASS | `PREEMPT_OWNED_BUT_NEG_MISBOUND` | **J-X:** `MEDIA_NOT_READY` wipes R16 silently |

**Consistent across all runs:**

- **J-F always PASS** — no premature `RECOVERED` / `OBLIGATION_CLOSED(RECOVERED)` (completion safety holds)
- **J-C, J-D always PASS** — domain trigger purity (D1 does not forge C; C does not impersonate D1)
- **J-B always FAIL or NOT_VERIFIED** — never achieved literal `EXECUTED` + `REPROBE_PASS` in same episode

---

## Failure taxonomy (two classes — do not merge)

### Class 1 — Orchestration timing (invalid exercise, not product FAIL)

Prod recovery dispatches `ICE_RESTART_DISPATCHED(intentId=NONE)` before C harness owns the attempt.

```text
CREATE deferred intent
    ↓
prod already DISPATCHED(NONE) on same attempt
    ↓
already_issued / STALE_DISCARD
    ↓
no HELD(DISPATCH) — J-B NOT_VERIFIED
```

**Runs:** `145829`, `153247`, `195948`, `200519` (partial).

**Fix surface:** orchestrator script only (`run-joint-d1-c-orchestrator.ps1`). ADR frozen order:

```text
1. Live preempt: ATTEMPT_REQUESTED/WAKEUP(M03) BEFORE prod ICE_RESTART_DISPATCHED(NONE)
2. CREATE → CREATED + VALIDATION_FENCE_ARMED → BLOCK
3. NEG hard gate: DISPATCHED(NONE, attemptA) = 0 else ABORT_INVALID_EXERCISE
4. NEG → MUST HELD(DISPATCH) before any CLEAR
5. D1 ABSENT → EVALUATE → ADMITTED → RETRY → CLEAR DROP → RELEASE → EXECUTED + REPROBE_PASS
```

**Stop:** retuning CREATED wait times (ADR: `200519` — same attempt already dispatched).

### Class 2 — Ownership wipe (opens J-X grill; may need production seam)

`201352` ownership audit (`R16_OWNERSHIP_AUDIT.txt`) — **read-only, no fix authorized**.

```text
DEBUG_CREATE_DEFERRED_INTENT → R16 CREATED + FENCE + BLOCK
    ↓
ICE_DISCONNECTED → EDGE_STARTED
    ↓
clearMediaActionDeferral()   // silently nulls iceRestartIntentId
    ↓
MEDIA_NOT_READY re-defer (ROUTE_CONVERGED wakeup)
    ↓
NEGOTIATION_CAN_EXECUTE intentId=NONE   // R16 gone — no DRAIN_ATTEMPT
```

**Grill question (J-X, not yet normative):**

> May `MEDIA_NOT_READY` / `EDGE_STARTED` `clearMediaActionDeferral()` supersede an already CREATED+FENCED NEGOTIATION deferred intent without auditable expire?

**Field+code answer for 201352:** YES — it currently does.

**Normative inclination:** NO — negotiation intent identity must not be silently wiped by media-domain admission.

**Code seam:** `ConferenceEdgeRecoveryController` ~2351-2364 (`beginRecovery` / `EDGE_STARTED` path).

This is **not** a D1 delivery bug. It is a **cross-domain deferred intent ownership** question.

---

## Gate matrix (aggregate)

| Gate | Status | Notes |
|------|--------|-------|
| J-A D1 retry chain | **PARTIAL** | PASS in `150808`, `153247`, `195948`, `200519`; FAIL when prod dispatch suppressed |
| J-B C drain chain | **OPEN** | Never `EXECUTED` + `REPROBE_PASS` in field |
| J-C D1→C purity | **PASS** | All 10 runs |
| J-D C→D1 purity | **PASS** | All 10 runs |
| J-E correlation | **MOSTLY PASS** | One fence break (`153247`) |
| J-F completion safety | **PASS** | Hard gate — **always** |
| J-G consistency | **PASS (soft)** | Partial exercise expected |

---

## Phase-3 authorization checklist

### Phase-3 Authorization Preconditions

Harness / orchestrator readiness (Orchestrator v2, 2026-08-04):

- [x] D1 FIELD_VERIFIED
- [x] C FIELD_VERIFIED
- [x] CompletionPolicy fence verified
- [x] isDispatchBlocked wiring verified
- [x] Orchestrator O0 classification (`ORCHESTRATOR_V2_CLASSIFICATION.txt`)
- [x] O1 invalid exercise abort (`DISPATCHED_WITHOUT_INTENT` → no J-B scoring)
- [x] O2 obligation-before-flap ordering (T-3..T+3 checkpoints)
- [x] O3 intent/fence correlation (`createdIntentId == canExecute == executed`)
- [x] **v2 static review gate** — `test-orchestrator-v2-classification.ps1` PASS (`logs/orch-v2-review-static` + `logs/orch-v2-review-jx-contract2`)
- [x] **Joint Evidence Classifier** — sole final verdict (`classify-joint-d1-c.ps1` / ADR §E.16.0)
- [x] **Attempt E16-Joint-1** — field executed ([run card](./e16-joint-1-clean-field-run.md)); final = compliant supersede abort
- [ ] `CLEAN_ATTEMPT_ESTABLISHED` (primary acceptance — still false after E16-J1)
- [ ] `PASS_JOINT_D1_C` artifact (secondary / stretch)

**Status framing:** Old analyzer `FAIL_C_CHAIN` labels on 20260801 runs are **gate/provisional**. Under §E.16.0 reclassification, the 10 archived M02 runs are `FAIL_OWNERSHIP_WIPE` / `SILENT_SLOT_LOSS` — **no clean attempt yet**. E16-J1 is **not** a wipe; it is an invalid exercise cut after HELD.

Phase-3 flips from `NOT AUTHORIZED` when **one** v2 field run achieves:

- [ ] **J-A PASS** — `ABSENT → EVALUATE → ADMITTED → RETRY` (EXHAUSTED not required)
- [ ] **J-B PASS** — literal `REPROBE_PASS` + `EXECUTED` on same `intentId` (not `DISPATCHED` alone)
- [ ] **J-C..J-F PASS** — already consistently passing except J-E watch
- [ ] **O0 `PASS_JOINT_D1_C`** — not `ABORT_*` / not `FAIL_CLEAN_JOINT` / not `FAIL_OWNERSHIP_WIPE`
- [ ] **O3 correlation** — `createdIntentId == canExecuteIntentId == executedIntentId` and fence chain match
- [ ] **Harness on device** — `isDispatchBlocked` wiring deployed; adb commands verified
- [ ] **Report archived** — `JOINT_D1_C_REPORT.txt` + `ORCHESTRATOR_V2_CLASSIFICATION.txt` in `logs/joint-d1-c-*`

**Unlocks on PASS:** PR5-3 / UVCP design migration baseline (not WiFi soak; not R4-impl).

---

## Recommended next actions (ordered)

### 1. Orchestrator v2 (no production code) — **DONE (2026-08-04)**

- O0: `ORCHESTRATOR_V2_CLASSIFICATION.txt` with strict enum (`ABORT_INVALID_EXERCISE` | `ABORT_ORCHESTRATION` | `FAIL_CLEAN_JOINT` gate-level | `FAIL_OWNERSHIP_WIPE` | `PASS_JOINT_D1_C`)
- O0 priority (frozen): `orchestration_abort` → `obligation_missing` → `ownership_wipe` → `J-A..J-F` (never score J-B before tiers 1–3)
- O1: pre-flap hard gate — `DISPATCHED(NONE)` without intent → `ABORT_INVALID_EXERCISE` / `DISPATCHED_WITHOUT_INTENT`
- O2: frozen timeline T-3..T+3 with runtime `VERIFY_OBLIGATION` (intentId + fence + blocked=true); `WAITING_FOR_D1_ARMED` if D1 not armed
- O3: end-to-end intent/fence correlation before `PASS_JOINT_D1_C`
- **Next:** v2 static review gate (dry-run), then single clean field attempt

### 1b. v2 static review gate (before field) — **PASS (2026-08-04)**

**Goal:** prove the experiment can produce valid evidence — not `PASS_JOINT_D1_C` on first try.

```text
Orchestrator v2
    ├─ syntax                 PASS
    ├─ static classification  PASS
    ├─ adb lifecycle          PASS
    └─ field discipline       AUTHORIZED → Attempt E16-Joint-1
```

Run (no flap):

```powershell
.\scripts\test-orchestrator-v2-classification.ps1
```

Expect per scenario:

| Scenario | `result` | `reason` | Must NOT be |
|----------|----------|----------|-------------|
| `WAITING_FOR_D1_ARMED` | `ABORT_ORCHESTRATION` | `WAITING_FOR_D1_ARMED` | `FAIL_CLEAN_JOINT` / `J_B_FAIL` |
| `MISSING_OBLIGATION` | `ABORT_ORCHESTRATION` | `TIMEOUT_WAITING_PREREQUISITE` | `FAIL_CLEAN_JOINT` / `J_B_FAIL` |

### 1c. Attempt E16-Joint-1 — **EXECUTED (2026-08-04)**

**LogDir:** `logs/joint-d1-c-e16-j1-20260804-150753`

| Layer | Result |
|-------|--------|
| Old provisional Orchestrator | `FAIL_OWNERSHIP_WIPE` / `INTENT_FENCED` (**misclassification**) |
| **Final Joint Evidence Classifier** | `ABORT_INVALID_EXERCISE` / `LIFECYCLE_CUT_AFTER_HELD` / `AUTHORITY_COMPLIANT_SUPERSEDE` |
| ownershipCompliance | **PASS** |
| jb | `NOT_EXERCISED` |
| cleanAttemptEstablished | **false** |

Full narrative: [e16-joint-classification-review.md](./e16-joint-classification-review.md)

### 1d. J-X classification contract correction — **DONE (2026-08-04)**

- ADR §E.16.0 + CONTEXT terms frozen
- Single classifier owns finalOutcome; Orchestrator/Analyzer demoted
- Historical 10-run reclass: all `SILENT_SLOT_LOSS`; **anyCleanAttempt=NO**
- Admission next: [e16-clean-attempt-admission-contract.md](./e16-clean-attempt-admission-contract.md) (Execute-before-edge)

### 2. J-X ownership grill (may authorize small seam)

- Input: `R16_OWNERSHIP_AUDIT.txt` + `EdgeRecoveryModels.DeferredIntentDomain`
- Question: may MEDIA domain observation clear NEGOTIATION slot without `expireDeferredIceRestartIntent` audit?
- Outcomes: (A) orchestrator avoids flap during C window only; (B) production seam preserves NEGOTIATION identity across MEDIA re-defer
- Note: E16-J1 does **not** reopen this as wipe; 201352 silent loss remains the Class-2 anchor

### 3. Clean Attempt Admission — **FROZEN** (2026-08-04); field **gated**

- Contract: [e16-clean-attempt-admission-contract.md](./e16-clean-attempt-admission-contract.md)
- Next authorized shape: **Execute-before-edge only** (HELD→flap-first forbidden)
- Primary success: `CLEAN_ATTEMPT_ESTABLISHED` (not necessarily `PASS_JOINT_D1_C`)
- Production seams remain frozen; classifier remains sole final verdict
- Run card + orchestrator Shape A gate: **DONE** (`SHAPE_PRECONDITION_NOT_MET` if EXECUTED missing before flap)`n- Field: **one** attempt when devices ready; primary = ``CLEAN_ATTEMPT_ESTABLISHED``

### 4. Explicitly deferred

- D1 code changes (CLOSED)
- PR5-3 / UVCP migration (BLOCKED)
- WiFi soak charter (BLOCKED until joint PASS — otherwise attribution collapses)
- R4 design review (P1 parallel; not on §E.16 critical path)

---

## Relationship to WiFi user pain

§E.16 does **not** prove WiFi recovery works end-to-end. It proves:

```text
D1 delivery ownership  +  C negotiation drain ownership
        do not corrupt each other
        inside the same recovery episode
        without premature completion close (J-F)
```

User-visible SYNCING / leave-rejoin is addressed by **transport/PRR/L.1.5** tracks, not §E.16.

Signal-path reports (`20260729`) predate D1 FIELD_VERIFIED (`20260801`). Do not use them to reopen D1.

---

## Frozen actions

```text
❌ Reopen D1 grill or write d1-recovery-offer-delivery-analysis.md
❌ Patch CompletionPolicy / UVCP "to fix joint"
❌ Treat SYNCING / OFFER_AWAITING_ANSWER as D1 failure
❌ Merge orchestration FAIL with J-X ownership FAIL in one narrative
❌ Unlock PR5-3 before J-B literal PASS
❌ Accept ADMIT_SUCCESSOR as Joint EDGE_START / Activation substitute
❌ Freeze Option A vs B without naming Activation producer
❌ Infer ActivationEvidence in classifier from admission/delivery/completion
❌ Borrow activation slice to fix recovery/transport/completion
❌ Field before UT T1-T3 + classifier validation
```

---

## References

| Artifact | Role |
|----------|------|
| ADR-0022 §E.15.15 | D1 CLOSED / FIELD_VERIFIED |
| ADR-0022 §E.16 | Joint regression spec + injection discipline |
| `logs/d1-ingress-miss-20260801-142717` | D1 standalone PASS |
| `logs/joint-d1-c-20260801-201352/R16_OWNERSHIP_AUDIT.txt` | J-X ownership finding |
| `scripts/run-joint-d1-c-orchestrator.ps1` | Field orchestrator v2 (O0–O3; `-DryRun`) |
| `scripts/test-orchestrator-v2-classification.ps1` | Static review gate (dry-run classification) |
| [e16-joint-1-clean-field-run.md](./e16-joint-1-clean-field-run.md) | Attempt E16-Joint-1 field protocol + decision tree |
| `Pr52cBlockReadinessWiringTest.kt` | Harness dispatch block wiring UT |
| `TalkbackCoordinator.kt` | `isDispatchBlocked` → `canDispatchRecoveryMediaAction` |

---

*Orchestration status only. Does not authorize production code change or field replay without explicit Phase-3 authorization.*