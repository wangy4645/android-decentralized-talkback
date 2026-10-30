# ADR-0065 Patch-2C: Pairwise Activation Authority Contract

## Status

**CONTRACT FROZEN — IMPLEMENTATION COMPLETE — DESK UNIT PASS** (2026-09-20)

```text
Patch-2C Contract Review:           PASS — FROZEN
  C1 Grill:                         PASS
  C3 Grill:                         PASS
  P2C-I2a:                          FROZEN

Patch-2C Implementation Plan:         PASS — AUTHORIZED
Patch-2C Implementation:            COMPLETE
Patch-2C Desk verification:         PASS (unit + integration regression)

Patch-2A (lifecycle + reducer):     IMPLEMENTED — unit PASS
Patch-2B (observation → router):    IMPLEMENTED — unit PASS

Production Meeting policy:          UNCHANGED
M02↔M04 field issue:                OPEN / NOT FIELD-VERIFIED
Field verification (Appendix B):    REVIEW PASS
                                    ONE M02↔M04 RUN AUTHORIZED
                                    result NOT YET ADJUDICATED
```

**Next gate (sequential):**

```text
1. This contract + Grill C1/C3        →  PASS / FROZEN
2. Patch-2C Implementation Plan Review →  PASS
3. Patch-2C Implementation (code)     →  COMPLETE
4. Desk unit adjudication             →  PASS
5. Appendix B field verification      →  REVIEW PASS
                                       ONE M02↔M04 RUN AUTHORIZED
                                       result NOT YET ADJUDICATED
```

**Parents / siblings:**

- [ADR-0065 Patch-2 pairwise edge obligation reactivation](./0065-patch-2-pairwise-edge-obligation-reactivation-contract.md) — lifecycle owner, reducer, Q1–Q6
- [ADR-0064 group invite delivery satisfied invalidation](./0064-group-invite-delivery-satisfied-invalidation-contract.md) — GIDR satisfied validity (SI-Q2 / SI-Q6); **MUST NOT be bypassed**
- ADR-0065 Patch-2A / Patch-2B — **IMPLEMENTED**; this contract builds on their runtime seams only

**Field evidence (activation false-terminal):**

- Log bundle: `talkback/logs/adr0065-sync-stuck-repro-20260918-1705/`
- Symptom: M02↔M04 stuck `DIRECTORY_SYNC` / `BUILDING`; `meshMissingIce=M04`; GIDR `phase=SATISFIED gate=SuppressSatisfied`; ICE `CHECKING`
- Post-2B shape: obligation can reach `MEDIA_PENDING` while GIDR still suppresses; Activation still reads `signalingSatisfied` until 2C

---

## Summary

Patch-2C is the **first patch that changes production execution semantics** for GROUP pairwise mesh activation.

Patch-2A/2B established:

```text
observation facts → ObligationObservationRouter → ObligationReducer → lifecycle
```

Patch-2C establishes:

```text
lifecycle (authoritative) → Activation evaluation → GIDR AND → existing GIE execution
```

Patch-2C **MUST NOT**:

- introduce a new retry / scheduler / execution framework;
- delete `meshCompletedModules` or `signalingSatisfied`;
- expand bootstrap admission completeness into this patch;
- bypass ADR-0064 GIDR gates;
- let obligation lifecycle become a second direct writer of delivery validity.

---

## Problem statement

### In scope

```text
For an existing required pairwise edge on a canonical GROUP roster:
  - Activation MUST read PairwiseMeshObligationState, not signalingSatisfied
  - ACTIVATION_REQUIRED MUST be the sole offerer entry state for activation evaluation
  - IssueInvite MUST require GIDR PermitRetry (AND, not OR)
  - MEDIA_PENDING MUST have a legal reducer-owned exit under prolonged CHECKING
  - GIDR satisfied-validity MUST re-evaluate when obligation reopens establishment
```

### Out of scope (Patch-2C contract)

- `GroupBootstrapAdmissionSupport.peerAdmissionIncomplete` authority migration
- `meshCompletedModules` deletion or global legacy cleanup
- RecoveryController / WiFi recovery / conference recovery
- New ICE restart policy (existing L0 paths may continue in parallel)
- Field runs / Directed recovery / UI changes
- Delivery terminal timing ADR (`markMeshLinkCompleted` rewrite)
- Membership authority (Layer 1)

---

## Frozen invariants (P2C-I1 … P2C-I4)

### P2C-I1 — ACTIVATION_REQUIRED origin

```text
ACTIVATION_REQUIRED can only originate from ObligationReducer
after authoritative observation facts and applicable grace/convergence rules.
```

Forbidden:

```text
ActivationSupport → set ACTIVATION_REQUIRED          ❌
GIDR PermitRetry → set ACTIVATION_REQUIRED         ❌
meshMissingIce → set ACTIVATION_REQUIRED             ❌
Reevaluation drain → set ACTIVATION_REQUIRED directly ❌
```

Reevaluation drain **routes observations**; reducer **owns** lifecycle transitions.

### P2C-I2 — GIDR × obligation (C3 — blocking)

```text
ACTIVATION_REQUIRED does not bypass GIDR.

ACTIVATION_REQUIRED does not directly invalidate GIDR satisfied.

It requires GIDR, when re-evaluating SuppressSatisfied, to treat
“current edge establishment obligation has reopened”
as an input to satisfied-validity scope.

Only ACTIVATION_REQUIRED produced by ObligationReducer
from legitimate observation facts
may cause an obsolete delivery success
to lose retry-suppression authority.
```

**Causal chain (frozen):**

```text
ICE / GPLB / Membership observation
        ↓
ObligationReducer
        ↓
ACTIVATION_REQUIRED
        ↓
GIDR satisfied-validity re-evaluation
        ↓
old GROUP_ACCEPT delivery success
no longer sufficient to SuppressSatisfied
        ↓
INVALIDATED_RETRY_ELIGIBLE (delivery phase)
        ↓
PermitRetry
```

**Explicit non-goals:**

```text
❌ “ACTIVATION_REQUIRED ⇒ GIDR satisfied automatically invalid”
❌ Activation reads lifecycle and skips GIDR
❌ ObligationReducer writes GIDR phase / gate directly
```

GIDR remains the **delivery suppress / permit owner**. Obligation remains the **topology intent owner**. Patch-2C adds a **read coupling** at GIDR validity evaluation time only.

### P2C-I3 — IssueInvite conjunction (execution formula)

```text
IssueInvite requires ALL of:

  obligation.lifecycle == ACTIVATION_REQUIRED
  ∧ local module is designated offerer on edge
  ∧ convergence / cooldown / budget / per-edge serialization gates pass
  ∧ signaling not in flight for this edge
  ∧ GIDR reconciliation gate == PermitRetry
```

Forbidden:

```text
lifecycle != SATISFIED → IssueInvite                    ❌
signalingSatisfied == false → IssueInvite               ❌
ACTIVATION_REQUIRED ∧ SuppressSatisfied → IssueInvite   ❌
```

Existing execution surface:

```text
PairwiseMeshAdmissionActivationSupport.evaluate()
  → issuePairwiseMeshAdmissionInvite()
  → dispatchGroupInviteAfterEngineProvision()   // GIE — unchanged mechanism
```

Patch-2C changes **eligibility**, not **execution machinery**.

### P2C-I4 — Reevaluation drain (C1 — blocking)

```text
Reevaluation drain is incarnation-scoped work consumption,
not a retry scheduler and not an execution mechanism.
```

`pendingPairwiseObligationReevaluationEdgeKeys` **MUST NOT** become a permanent timer queue or polling loop.

**Per drain cycle (frozen):**

```text
dequeue edge key (one consumption pass)
  → re-validate:
       edge still required
       obligation still exists
       pcGeneration still current
       membership not in churn window
  → if stale: discard queue item (no observation)
  → else: evaluate current incarnation
  → route applicable observation(s) through ObligationObservationRouter
  → consume queue item for this pass
```

**If lifecycle unchanged after observation:**

```text
CHECKING < grace
  → lifecycle unchanged
  → NO queue self-repoll
  → next convergence / health tick MAY enqueue again via diagnostic path
```

Forbidden:

```text
queue entry polls until ACTIVATION_REQUIRED     ❌
drain bypasses Router/Reducer                  ❌
drain emits IssueInvite                         ❌
```

---

## Contract violation invariant (17:02 deadlock shape)

There **MUST NOT** exist a stable state satisfying **all** of:

```text
edge required on canonical roster
∧ obligation.lifecycle == ACTIVATION_REQUIRED
∧ no signaling transaction in flight on edge
∧ local module is designated offerer
∧ convergence / cooldown / budget gates satisfied
∧ GIDR reconciliation gate permanently SuppressSatisfied
```

This state is a **contract violation**, not an acceptable steady state.

The 2026-09-18 M02↔M04 repro is an instance of this shape (pre-2C: blocked earlier at fake `signalingSatisfied`; post-2B-only: blocked at `MEDIA_PENDING` or post-grace `ACTIVATION_REQUIRED` + `SuppressSatisfied`).

---

## C1 — MEDIA_PENDING exit under prolonged CHECKING

### Current foundation (Patch-2A/2B — IMPLEMENTED)

Reducer already defines:

```text
MediaIceChecking(elapsedMs >= checkingGraceMs)
  + current in { MEDIA_PENDING, SATISFIED }
  → ACTIVATION_REQUIRED
```

Patch-2B gap: `MediaIceChecking` is not reliably re-emitted while ICE remains in `CHECKING`; diagnostic queue is not drained.

### Patch-2C obligation (Grill subject)

Introduce **ObligationReevaluationDrain** wiring (name TBD at impl plan) that:

1. Runs on existing convergence / health boundaries (no new scheduler framework)
2. Consumes `pendingPairwiseObligationReevaluationEdgeKeys` per P2C-I4
3. Emits `MediaIceChecking(elapsedMs)` from live QoS snapshot when ICE is negotiating
4. Lets reducer move `MEDIA_PENDING → ACTIVATION_REQUIRED` when grace exceeded

**Within grace (P2-E7 alignment):**

```text
MEDIA_PENDING + CHECKING + meshMissingIce diagnostic
  → lifecycle stays MEDIA_PENDING
  → no IssueInvite
  → GIDR may remain SuppressSatisfied
```

**After grace:**

```text
MEDIA_PENDING + CHECKING elapsed >= checkingGraceMs
  → reducer: ACTIVATION_REQUIRED
  → triggers P2C-I2 GIDR validity re-evaluation path
```

### C1 Grill questions (must PASS before implementation)

| ID | Question | Required answer |
|----|----------|-----------------|
| C1-G1 | What existing tick(s) invoke drain without creating a new scheduler? | Named seams only |
| C1-G2 | How is queue re-enqueue bounded without self-poll? | Diagnostic / convergence re-enqueue only |
| C1-G3 | What happens to queued work on incarnation fence? | Discard + dedupe reset policy |
| C1-G4 | `checkingGraceMs` vs `iceNegotiationTimeoutMs` relationship | Documented; not merged silently |

---

## C2 — Activation decision matrix (frozen)

| lifecycle | role | `ActivationSupport.evaluate` | Notes |
|-----------|------|-------------------------------|-------|
| `PENDING` | offerer | **NoAction** or `Deferred(TOPOLOGY_NOT_READY)` | MUST NOT IssueInvite without topology/convergence path |
| `ACTIVATION_REQUIRED` | offerer | **Eligible for evaluation** | Only primary activation entry state |
| `SIGNALING_IN_FLIGHT` | offerer | NoAction | |
| `MEDIA_PENDING` | offerer | NoAction | Wait for observation / reducer |
| `SATISFIED` | offerer | NoAction | |
| any | answerer | `Deferred(ANSWERER_AWAITING_OFFER)` / NoAction | Offerer executes |

**Reads to migrate (Patch-2C cut surface):**

```text
PairwiseMeshAdmissionActivationSupport.evaluate
PairwiseMeshAdmissionActivationSupport.shouldUsePairwiseAdmissionInvite
PairwiseMeshAdmissionActivationSupport.isAnswererAwaitingPairwiseOffer
PairwiseMeshAdmissionActivationSupport.unsatisfiedOffererPeerIds
Coordinator: evaluatePairwiseMeshAdmissionActivation
Coordinator: issuePairwiseMeshAdmissionInvite
```

**Explicitly deferred from 2C cut:**

```text
GroupBootstrapAdmissionSupport.peerAdmissionIncomplete   // bootstrap domain
PairwiseMeshAdmissionSupport.shouldDeferMeshCompletionAfterSnapshot  // audit / follow-on
```

---

## C3 — GIDR × obligation AND (blocking — highest priority)

### Failure mode if C3 is wrong

```text
MEDIA_PENDING
    ↓ grace (C1)
ACTIVATION_REQUIRED
    ↓
GIDR = SuppressSatisfied
    ↓
permanent stall            ← 17:02 class dead lock
```

C1 without C3 only moves the stall from `MEDIA_PENDING` to `ACTIVATION_REQUIRED`.

### Frozen AND table

| obligation.lifecycle | GIDR gate | Activation evaluation | Wire dispatch |
|---------------------|-----------|----------------------|---------------|
| `ACTIVATION_REQUIRED` | `PermitRetry` | eligible | **IssueInvite allowed** |
| `ACTIVATION_REQUIRED` | `SuppressActive` | eligible | **No dispatch** |
| `ACTIVATION_REQUIRED` | `SuppressSatisfied` | eligible | **No dispatch** |
| `MEDIA_PENDING` | any | NoAction | No dispatch |
| `SATISFIED` | any | NoAction | No dispatch |

### GIDR validity re-evaluation (P2C-I2 detail)

When `GroupInviteDeliveryRecoverySupport` / `GroupInviteDeliverySatisfiedScopeSupport` evaluates suppress:

```text
Inputs (existing):
  completed binding on attempt
  authoritative binding on current pcGeneration
  edgeObservation (ICE, binding state)

Input (new — read-only):
  obligation.lifecycle on same edge

Rule (frozen intent):
  If obligation.lifecycle == ACTIVATION_REQUIRED
  AND suppress would otherwise be VALID solely because
  historical GROUP_ACCEPT delivery success matches current binding incarnation,
  THEN satisfied-validity scope MUST NOT treat that as sufficient to suppress retry.

  Map to delivery phase INVALIDATED_RETRY_ELIGIBLE
  → reconciliation gate PermitRetry.
```

**While `MEDIA_PENDING` within CHECKING grace:** historical delivery success **MAY** still suppress (ADR-0064 Case E / P2-E7).

**Implementation location:** GIDR satisfied-validity evaluation only. **NOT** in ActivationSupport. **NOT** in ObligationReducer writing GIDR state.

### C3 Grill questions (must PASS before implementation)

| ID | Question | Required answer |
|----|----------|-----------------|
| C3-G1 | Exact API seam for obligation read in GIDR validity | Named function + read-only |
| C3-G2 | Proof this preserves ADR-0064 SI-Q2 (delivery fact not rewritten) | Delivery fact retained; suppress scope narrowed |
| C3-G3 | Proof Activation cannot dispatch without GIDR PermitRetry | Coordinator AND audit |
| C3-G4 | P2-E7 case still suppresses within grace | Test fixture named |
| C3-G5 | 17:02 replay reaches PermitRetry after grace + ACTIVATION_REQUIRED | Contract test or trace table |

---

## C4 — Legacy authority cut scope (frozen narrow)

### MUST cut in Patch-2C

Activation / pairwise re-activation authority reads of `signalingSatisfied` and `meshCompletedModules` as **execution gates** — listed under C2.

### MUST retain as compatibility / diagnostic projection

```text
TalkbackSession.meshCompletedModules mutations (markMeshLinkCompleted)
PairwiseMeshAdmissionObligation.signalingSatisfied field + reconcile refresh
GroupRuntimeHealthProjector meshSignaledPeers
Legacy runtime / topology observation adapters
Coordinator non-pairwise-activation meshCompleted reads (audit list at impl plan)
```

### MUST NOT cut in Patch-2C

```text
GroupBootstrapAdmissionSupport.peerAdmissionIncomplete
```

Rationale: bootstrap first-join completeness is a separate admission definition; conflating it with pairwise re-activation risks spreading `GROUP_ACCEPT → MEDIA_PENDING` semantics into bootstrap readiness timing.

---

## Relationship to Patch-2A / Patch-2B (IMPLEMENTED)

| Layer | Patch-2A/2B (done) | Patch-2C (this contract) |
|-------|-------------------|--------------------------|
| Lifecycle writer | ObligationReducer only | unchanged |
| Runtime input | Observation → Router | unchanged |
| Reconcile | merge preserves lifecycle | unchanged |
| Activation reads | still `signalingSatisfied` (legacy) | **→ lifecycle** |
| GIDR suppress | independent of obligation | **→ validity re-eval reads obligation** |
| IssueInvite | blocked by fake satisfied | **→ ACTIVATION_REQUIRED ∧ PermitRetry** |

---

## Acceptance cases (Patch-2C — contract level)

| ID | Scenario | Expect |
|----|----------|--------|
| **P2C-A1** | `GROUP_ACCEPT` + CHECKING; within grace | lifecycle `MEDIA_PENDING`; Activation NoAction; GIDR may SuppressSatisfied |
| **P2C-A2** | `MEDIA_PENDING` + CHECKING beyond grace | reducer → `ACTIVATION_REQUIRED` via drain + `MediaIceChecking` |
| **P2C-A3** | `ACTIVATION_REQUIRED` + historical delivery VALID + obligation reopened | GIDR validity re-eval → `PermitRetry` (not bypass) |
| **P2C-A4** | `ACTIVATION_REQUIRED` + `PermitRetry` + offerer + gates pass | IssueInvite via existing GIE |
| **P2C-A5** | `ACTIVATION_REQUIRED` + `SuppressSatisfied` | **No IssueInvite** (AND enforced) |
| **P2C-A6** | `signalingSatisfied=true` compat + lifecycle `MEDIA_PENDING` | Activation **NoAction** (lifecycle wins) |
| **P2C-A7** | Stable deadlock invariant (see above) | **MUST NOT occur** |
| **P2C-A8** | Queue drain on fenced incarnation | queue item discarded; no stale observation resurrection |

Parent Patch-2 cases P2-E3/E4/E5/E6 remain applicable; 2C adds execution closure on top.

---

## Implementation boundary

```text
Contract + Grill C1/C3:           FROZEN / PASS
Implementation Plan:              PASS — AUTHORIZED
Implementation:                   COMPLETE
Desk verification:                PASS (2026-09-20)

Plan: docs/adr/0065-patch-2c-activation-implementation-plan.md

Desk evidence (high-risk shapes):
  P2C-A7                         PASS
  P2C-I2a-1..4                   PASS
  Patch-2A / Patch-2B regression PASS
  Activation unit                PASS
  17:02-shape integration        PASS
  Bootstrap regression           PASS

Remaining verification:
  Field (Appendix B):             REVIEW PASS
                                  ONE M02↔M04 RUN AUTHORIZED
                                  result NOT YET ADJUDICATED
  Production Meeting:             UNCHANGED
  M02↔M04 single field run:       OPEN / NOT FIELD-VERIFIED
    On FAIL → RCA from field evidence; no insurance re-run
```

---

## Grill adjudication (Patch-2C contract)

| Gate | Status | Blocker? |
|------|--------|----------|
| **C1** Reevaluation drain semantics | **PASS — FROZEN** | no |
| **C2** Activation matrix | Frozen in this contract | no |
| **C3** GIDR × obligation AND + validity re-eval | **PASS — FROZEN** | no |
| **C4** Legacy cut scope | Frozen narrow | no |
| **P2C-I1..I4** | Frozen | — |
| **P2C-I2a** | Frozen (Grill 2026-09-20) | — |
| **Deadlock invariant** | Frozen | — |

**Contract:** FROZEN  
**Implementation Plan:** PASS — AUTHORIZED ([0065-patch-2c-activation-implementation-plan.md](./0065-patch-2c-activation-implementation-plan.md))  
**Implementation:** COMPLETE  
**Desk verification:** PASS (unit + integration regression)  
**Field verification:** NOT AUTHORIZED / PENDING
