# ADR-0065 Patch-2C: Activation Authority Implementation Plan

## Status

**IMPLEMENTATION COMPLETE — DESK UNIT PASS** (2026-09-20)

```text
ADR-0065 Patch-2C Contract:        CONTRACT FROZEN
  C1 Grill:                        PASS
  C3 Grill:                        PASS
  P2C-I1..I4 + P2C-I2a:            FROZEN

Patch-2C Implementation Plan:      PASS — AUTHORIZED
Patch-2C Implementation:           COMPLETE
Patch-2C Desk verification:        PASS (unit + integration regression)

Patch-2A / Patch-2B:               IMPLEMENTED — unit PASS
M02↔M04 field issue:               OPEN / NOT FIELD-VERIFIED
Production Meeting:                UNCHANGED
Field verification (Appendix B):   REVIEW PASS
                                   ONE M02↔M04 RUN AUTHORIZED
                                   result NOT YET ADJUDICATED
```

**Contract:** [0065-patch-2c-activation-authority-contract.md](./0065-patch-2c-activation-authority-contract.md)  
**Parents:** [0065 Patch-2 obligation reactivation](./0065-patch-2-pairwise-edge-obligation-reactivation-contract.md) · [0064 GIDR satisfied invalidation](./0064-group-invite-delivery-satisfied-invalidation-contract.md)

**Purpose:** Specify exact seams, file touch list, and test mapping for Patch-2C. **Implementation complete; desk unit adjudication PASS (2026-09-20).**

---

## 0. Frozen execution chain (post-2C target)

```text
authoritative observation
        ↓
ObligationObservationRouter
        ↓
ObligationReducer
        ↓
ACTIVATION_REQUIRED
        ↓
evaluateSatisfiedValidity()  [C3-G2 read-only obligation scope]
        ↓
suppress validity ≠ VALID (delivery fact retained)
        ↓
deliveryPhase → INVALIDATED_RETRY_ELIGIBLE
        ↓
reconciliationGate → PermitRetry
        ↓
Activation Layer A (lifecycle + offerer + gates)
        ↓
dispatch Layer B (GIDR PermitRetry AND)
        ↓
dispatchGroupInviteAfterEngineProvision()  [existing GIE]
```

---

## 1. Implementation sequence (frozen order)

| Step | Scope | Depends on |
|------|-------|------------|
| **0** | Contract tests skeleton (`Adr0065Patch2CActivationAuthorityTest`) | — |
| **1** | C3-G2: `ObligationEstablishmentScope` + `evaluateSatisfiedValidity` | Step 0 |
| **2** | Layer A: `PairwiseMeshAdmissionActivationSupport` lifecycle matrix | Step 1 (for P2C-A6/A7 unit paths) |
| **3** | Layer B: GIDR AND at `issuePairwiseMeshAdmissionInvite` | Step 1 |
| **4** | P2C-I2a: incarnation alignment in Activation evaluate | Steps 2–3 |
| **5** | C1: `ReevaluationQueueItem` + drain (replace `Set<edgeKey>`) | Step 2 |
| **6** | Coordinator wiring: drain tick + Layer B | Steps 3–5 |
| **7** | P2C-A1..A8 fixtures + regression | All |

**Do not implement Step 3 before Step 1.** Layer B without C3-G2 recreates bypass or deadlock.

---

## 2. C3-G2 — exact code location (PIN #1)

### Rule

C3-G2 **MUST** live only inside satisfied-validity evaluation. **Forbidden:**

```text
if (obligation.lifecycle == ACTIVATION_REQUIRED) gate = PermitRetry   ❌  // GIDR caller
if (ACTIVATION_REQUIRED) IssueInvite                                   ❌  // Activation bypass
Coordinator special-case PermitRetry                                   ❌
```

### Allowed call graph (unchanged shape)

```text
GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate()
    → deliveryPhase()
        → suppressesDeliveryRetry()
            → evaluateSatisfiedValidity(..., obligationEstablishmentScope)
    → map phase → ReconciliationGate
```

### File and function

| File | Change |
|------|--------|
| `GroupInviteDeliverySatisfiedScopeSupport.kt` | Add `ObligationEstablishmentScope`; extend `evaluateSatisfiedValidity` |
| `GroupInviteDeliverySatisfiedScopeSupport.kt` | `suppressesDeliveryRetry` passes scope through (no new logic) |
| `GroupInviteDeliveryRecoverySupport.kt` | **No gate logic change** — only thread scope into `deliveryPhase` / `suppressesDeliveryRetry` call sites |

### New read-only input

```kotlin
data class ObligationEstablishmentScope(
    val edgeKey: PairwiseMeshEdgeKey,
    val lifecycle: PairwiseMeshObligationState,
    val evaluationPcGeneration: Long?,
)
```

**GIDR may read only:** `edgeKey`, `lifecycle`, `evaluationPcGeneration` (the last mirrors existing `currentPcGeneration` at eval time).

**GIDR must NOT read:** reducer reason, raw ICE/QoS, membership churn, `meshMissingIce`.

### Scope builder (single factory)

| File | Function |
|------|----------|
| `PairwiseMeshObligationBoundaries.kt` or new `PairwiseMeshObligationEstablishmentScopeSupport.kt` | `establishmentScope(session, remoteModuleId, currentPcGeneration): ObligationEstablishmentScope?` |

Callers of `deliveryPhase` / `evaluateReconciliationGate` pass scope from factory — **no obligation logic inside GIDR enum mapping**.

### C3-G2 validity rule (append after existing VALID preconditions)

Insert in `evaluateSatisfiedValidity`, **after** `completed == authoritative` and **after** edgeObservation STALE_EDGE checks, **before** `return VALID`:

```text
IF obligationEstablishmentScope?.lifecycle == ACTIVATION_REQUIRED
   AND completedBinding == authoritativeBinding   // incarnation match — P2C-I2a
THEN return SatisfiedValidity.STALE_EDGE           // or dedicated enum value; MUST NOT return VALID
```

Historical delivery terminal on attempt **unchanged**. Only suppress scope fails.

**Mapping:** `STALE_EDGE` (or equivalent non-VALID) → `suppressesDeliveryRetry == false` → `deliveryPhase == INVALIDATED_RETRY_ELIGIBLE` when `hasHistoricalDeliverySuccess` → `PermitRetry`.

### Call sites to thread scope (audit list)

| Caller | File |
|--------|------|
| `deliveryRetryInvitees` | `TalkbackCoordinator.kt` |
| `tryReinviteGroupPeerPairwise` | `TalkbackCoordinator.kt` |
| Layer B AND helper (new) | `TalkbackCoordinator.kt` |
| Unit tests | `Adr0064PatchA1GidrScopeTest.kt` (extend, do not break Cases A–E) |

---

## 3. P2C-I2a — independent tests (PIN #2)

**New file:** `Adr0065Patch2CP2cI2aIncarnationScopeTest.kt`

| ID | Fixture | Expect |
|----|---------|--------|
| **I2a-1** | `completed(gen3)`, `authoritative(gen4)`, `ACTIVATION_REQUIRED`, would-be VALID without 2C rule | `evaluateSatisfiedValidity` ≠ VALID; **C3-G2 lift does not apply** (already `STALE_BINDING` from gen mismatch OR lift blocked by P2C-I2a) |
| **I2a-2** | `completed == authoritative(gen4)`, `ACTIVATION_REQUIRED`, historical SATISFIED | validity ≠ VALID → `PermitRetry` via phase chain (**C3-G2 lift**) |
| **I2a-3** | GIDR `PermitRetry` (e.g. STALE_BINDING path) but obligation `ACTIVATION_REQUIRED` on stale gen / obligation missing | Activation `Deferred(STALE_OBLIGATION_INCARNATION)` — **no IssueInvite** |
| **I2a-4** | `SuppressActive` + `ACTIVATION_REQUIRED` | gate `SuppressActive`; Activation eligible but Layer B blocks |

**Activation incarnation gate (Layer A extension):**

In `PairwiseMeshAdmissionActivationSupport.evaluate`, when `lifecycle == ACTIVATION_REQUIRED`:

```text
require establishmentScope.evaluationPcGeneration == currentPcGeneration
require obligation still exists for edge
else Deferred(STALE_OBLIGATION_INCARNATION)
```

Uses same `ObligationEstablishmentScope` factory — **does not re-read ICE/QoS**.

---

## 4. Layer A / Layer B — two-level AND (PIN #4)

### Layer A — `PairwiseMeshAdmissionActivationSupport.evaluate`

Replace `signalingSatisfied` gates with lifecycle matrix (contract C2):

| lifecycle | offerer | Result |
|-----------|---------|--------|
| `PENDING` | yes | NoAction or `Deferred(TOPOLOGY_NOT_READY)` |
| `ACTIVATION_REQUIRED` | yes | eligible (subject to peerEdgeReady, cooldown, !signalingInFlight, !iceConnected, P2C-I2a) |
| `SIGNALING_IN_FLIGHT` | yes | NoAction |
| `MEDIA_PENDING` | yes | NoAction |
| `SATISFIED` | yes | NoAction |
| any | answerer | `Deferred(ANSWERER_AWAITING_OFFER)` |

Also update:

- `shouldUsePairwiseAdmissionInvite` → `lifecycle == ACTIVATION_REQUIRED` + offerer
- `isAnswererAwaitingPairwiseOffer` → answerer + lifecycle not terminal satisfied
- `unsatisfiedOffererPeerIds` → `lifecycle == ACTIVATION_REQUIRED`

**Remove:** `if (obligation.signalingSatisfied) return NoAction` as activation authority.

### Layer B — dispatch seam

**File:** `TalkbackCoordinator.kt`

**New private helper:**

```text
pairwiseActivationGidrPermitsRetry(session, remoteModuleId): Boolean
  → build edgeObservation (existing gidrEdgeObservationForPeer)
  → build obligationEstablishmentScope
  → GroupInviteDeliveryRecoverySupport.evaluateReconciliationGate(..., scope)
  → return gate == PermitRetry
```

**Enforce at:**

```text
issuePairwiseMeshAdmissionInvite()
  → before dispatchGroupInviteAfterEngineProvision:
       if (!pairwiseActivationGidrPermitsRetry(...)) return 0
```

**Also:** when `evaluate` returns `IssueInvite`, path already goes through `issuePairwiseMeshAdmissionInvite` — single Layer B choke point.

**Forbidden:** duplicating `if (ACTIVATION_REQUIRED) PermitRetry` without calling `evaluateReconciliationGate`.

---

## 5. C1 — ReevaluationQueueItem and drain lifecycle (PIN #3)

### Queue item (frozen fields only)

```kotlin
data class ReevaluationQueueItem(
    val sessionId: String,
    val edgeKey: String,           // PairwiseMeshEdgeKey.storageKey()
    val pcGeneration: Long,
    val enqueuedAtMs: Long,        // diagnostic only
)
```

**Forbidden on item:** `retryCount`, `nextAttemptAt`, `phase`, `reasonHistory`.

### Session storage migration

| Today (2B) | 2C |
|------------|-----|
| `pendingPairwiseObligationReevaluationEdgeKeys: MutableSet<String>` | `pendingPairwiseObligationReevaluationQueue: MutableList<ReevaluationQueueItem>` or `LinkedHashSet` with data class equality |

Replace enqueue in `PairwiseMeshObligationObservationRouter` (MeshMissingIce diagnostic) and membership prune in `PairwiseMeshObligationMembershipBoundary`.

### Drain object

**New file:** `PairwiseMeshObligationReevaluationDrain.kt`

```text
drainSession(session, currentPcGenerationByRemote, membershipConvergenceAllowed, nowMs):
  while queue non-empty (or one item per tick — impl choice: ONE item per convergence tick per session):
    item = dequeue head
    if item.sessionId != session.id → drop
    if obligation missing → drop
    if edge not required → drop
    if item.pcGeneration != currentPcGeneration(remote) → drop
    if !membershipConvergenceAllowed → drop
    else:
      read ICE state from qos snapshot
      if negotiating (CHECKING/NEW):
        route MediaIceChecking(elapsedMs)
      else if lost:
        route MediaIceLost (if applicable)
      // consume item — always, pass or drop
```

**Grace not elapsed:** route `MediaIceChecking(elapsed < grace)` → reducer noop → **consume, no re-enqueue**.

**Grace elapsed:** route `MediaIceChecking(elapsed >= grace)` → reducer may set `ACTIVATION_REQUIRED` → **consume**.

**Forbidden drain outputs:** GIDR calls, Activation, IssueInvite, ICE restart.

### Drain invocation seams (no new scheduler)

| Seam | File | Trigger |
|------|------|---------|
| `onGroupConvergenceBoundary` | `TalkbackCoordinator.kt` | after `routePairwiseMeshMissingIceDiagnostics` |
| Post-`PairwiseMeshAdmissionSupport.reconcile` | optional single call from coordinator reconcile paths | membership stable |

**Membership convergence gate:** use existing health projection:

```text
membershipConvergenceAllowed :=
  health.membershipReconciled == true
  AND health.groupTopologyReadiness != MEMBERSHIP_PENDING
```

(Exact predicate frozen at impl review if health field names differ.)

### `checkingGraceMs` vs `iceNegotiationTimeoutMs`

| Constant | Owner | Role |
|----------|-------|------|
| `PairwiseMeshObligationReducer.Config.checkingGraceMs` (10s default) | reducer / drain | `MEDIA_PENDING → ACTIVATION_REQUIRED` |
| `TalkbackCoordinatorConfig.iceNegotiationTimeoutMs` | L0 `tryRecoverStuckCheckingPeer` | ICE restart offer — **parallel, not substitute** |

Document in plan test: drain grace and L0 timeout are independent knobs.

---

## 6. Legacy authority cut list (C4 narrow)

### MUST cut (2C)

| Location | Change |
|----------|--------|
| `PairwiseMeshAdmissionActivationSupport.evaluate` | lifecycle matrix |
| `shouldUsePairwiseAdmissionInvite` | lifecycle |
| `isAnswererAwaitingPairwiseOffer` | lifecycle |
| `unsatisfiedOffererPeerIds` | lifecycle |
| `TalkbackCoordinator.evaluatePairwiseMeshAdmissionActivation` | uses updated evaluate |
| `TalkbackCoordinator.issuePairwiseMeshAdmissionInvite` | Layer B + shouldUse lifecycle |

### MUST NOT cut (2C)

| Location | Reason |
|----------|--------|
| `GroupBootstrapAdmissionSupport.peerAdmissionIncomplete` | bootstrap domain — contract defer |
| `PairwiseMeshAdmissionSupport.shouldDeferMeshCompletionAfterSnapshot` | follow-on audit |
| `markMeshLinkCompleted` / `meshCompletedModules` writes | compat projection |
| `reconcile()` `signalingSatisfied` refresh | compat |
| `GroupRuntimeHealthProjector` meshSignaledPeers | diagnostic |
| Legacy / topology observation adapters | diagnostic |

### Compat field behavior

`signalingSatisfied` may remain `true` when `meshCompletedModules` contains peer. Activation **MUST ignore** for IssueInvite (P2C-A6).

---

## 7. P2C-A1..A8 → seam → test mapping (PIN #4 + contract regression)

| ID | Scenario | Production seam | Test fixture |
|----|----------|-----------------|--------------|
| **P2C-A1** | GROUP_ACCEPT + CHECKING within grace | Delivery boundary + ICE boundary; Activation evaluate | `Adr0065Patch2CActivationAuthorityTest.a1_mediaPendingWithinGrace_gidrMaySuppress` |
| **P2C-A2** | MEDIA_PENDING + CHECKING beyond grace | `PairwiseMeshObligationReevaluationDrain` + Router | `...Test.a2_drainGraceExceeded_activationRequired` |
| **P2C-A3** | ACTIVATION_REQUIRED + historical VALID + same incarnation | `evaluateSatisfiedValidity` C3-G2 only | `Adr0064PatchA1GidrScopeTest` extend + `...Test.a3_gid rPermitRetryViaValidityNotBypass` |
| **P2C-A4** | ACTIVATION_REQUIRED + PermitRetry + gates | Layer A + Layer B + GIE mock/harness | `GroupPairwiseMeshAdmissionActivationTest` extend or new coordinator harness |
| **P2C-A5** | ACTIVATION_REQUIRED + SuppressSatisfied (without C3-G2 conditions) | Layer B blocks dispatch | `...Test.a5_activationRequiredSuppressSatisfied_noDispatch` |
| **P2C-A6** | signalingSatisfied=true, lifecycle=MEDIA_PENDING | Activation Layer A | `...Test.a6_compatSignalingSatisfiedIgnored` |
| **P2C-A7** | **17:02 deadlock invariant — MUST NOT occur** | Full chain: validity → phase → gate → Layer A → Layer B | `...Test.a7_1702DeadlockShape_reachesPermitRetryAndEligibleIssueInvite` |
| **P2C-A8** | Queue item stale incarnation | Drain drop policy | `...Test.a8_drainDropsStalePcGeneration` |

### P2C-A7 fixture detail (contract-level regression — mandatory)

Construct:

```text
edge required on canonical roster
obligation.lifecycle = ACTIVATION_REQUIRED
local = designated offerer
peerEdgeReady = true
cooldown elapsed
signalingInFlight = false
iceConnected = false
historical delivery terminal = GROUP_ACCEPT / SATISFIED
completedBinding == authoritativeBinding (same pcGeneration)
edgeObservation: CHECKING (within or beyond grace per sub-case)
```

**Assert:**

```text
evaluateSatisfiedValidity(...) != VALID
deliveryPhase == INVALIDATED_RETRY_ELIGIBLE
evaluateReconciliationGate == PermitRetry
Activation.evaluate → IssueInvite
pairwiseActivationGidrPermitsRetry == true
issuePairwiseMeshAdmissionInvite path would dispatch (mock GIE or counter)
```

This proves the 17:02 root cause shape has a **legal exit** under 2C — not field proof.

### P2C-A7 sub-case (within grace — still P2C-A1/E7)

Same fixture but `elapsed < checkingGraceMs`:

```text
lifecycle remains MEDIA_PENDING
GIDR may SuppressSatisfied
Activation NoAction
```

---

## 8. Files touched (expected diff surface)

| File | Action |
|------|--------|
| `GroupInviteDeliverySatisfiedScopeSupport.kt` | C3-G2 validity rule + scope type |
| `GroupInviteDeliveryRecoverySupport.kt` | Thread scope parameter only |
| `PairwiseMeshAdmissionActivationSupport.kt` | Layer A lifecycle matrix + I2a defer |
| `PairwiseMeshObligationReevaluationDrain.kt` | **NEW** — C1 drain |
| `ReevaluationQueueItem.kt` or inline in drain file | **NEW** — queue item |
| `TalkbackSession.kt` | Replace edgeKey set with queue items |
| `PairwiseMeshObligationObservationRouter.kt` | Enqueue `ReevaluationQueueItem` |
| `PairwiseMeshObligationMembershipBoundary` | Prune queue by required edges |
| `PairwiseMeshAdmissionSupport.kt` | Clear queue on session reset |
| `TalkbackCoordinator.kt` | Layer B AND, drain tick, scope threading to GIDR |
| `Adr0065Patch2CActivationAuthorityTest.kt` | **NEW** — P2C-A1..A8 |
| `Adr0065Patch2CP2cI2aIncarnationScopeTest.kt` | **NEW** — I2a-1..4 |
| `Adr0064PatchA1GidrScopeTest.kt` | Extend — no regression Cases A–E |
| `PairwiseMeshAdmissionActivationSupportTest.kt` | Update lifecycle expectations |
| `Adr0065Patch2BObservationRouterTest.kt` | Update queue type assertions |
| `0065-patch-2c-activation-authority-contract.md` | Status: CONTRACT FROZEN (Grill PASS) |

**Not touched:** RecoveryController, bootstrap peerAdmissionIncomplete, field harness, `meshCompletedModules` deletion.

---

## 9. Out of scope (reconfirm)

- Field run M02↔M04
- Production Meeting policy change
- New retry scheduler / recovery framework
- GIDR / GROUP_ACCEPT terminal rewrite
- Bootstrap admission completeness migration
- `shouldDeferMeshCompletionAfterSnapshot` authority switch

---

## 10. Implementation Plan Review checklist

Reviewer MUST confirm:

| # | Question | Pass criteria |
|---|----------|---------------|
| 1 | C3-G2 only in `evaluateSatisfiedValidity`? | No caller bypass |
| 2 | P2C-I2a tests independent? | I2a-1..4 named |
| 3 | Queue item minimal? | No retryCount / nextAttemptAt |
| 4 | P2C-A7 is a test, not prose? | Fixture spec §7 |
| 5 | Layer B single choke point? | `issuePairwiseMeshAdmissionInvite` |
| 6 | Bootstrap untouched? | C4 defer list |
| 7 | 2A/2B regressions? | Patch-2B tests updated for queue type |

**Implementation Plan Review:** PASS (2026-09-20)  
**Implementation:** COMPLETE

---

## 11. Desk adjudication (2026-09-20)

```text
Implementation:       COMPLETE
Desk verification:    PASS
Field verification:   NOT AUTHORIZED / PENDING
```

**Desk evidence (high-risk shapes):**

| Evidence | Result |
|----------|--------|
| P2C-A7 (17:02 deadlock invariant) | PASS |
| P2C-I2a-1..4 (incarnation fence) | PASS |
| Patch-2A / Patch-2B regression | PASS |
| Activation unit (`PairwiseMeshAdmissionActivationSupportTest`, `Adr0065Patch2C*`) | PASS |
| 17:02-shape integration (`GroupPairwiseMeshAdmissionActivationTest`) | PASS |
| Bootstrap regression (`GroupBootstrapSignalingInFlightGapTest`) | PASS |

**Field verification (authorized, result pending):**

```text
Appendix B Review:      PASS (B1–B5)
Field execution:        ONE M02↔M04 RUN AUTHORIZED
Field result:           NOT YET ADJUDICATED
Procedure:              docs/analysis/adr0065-appendix-b-field-procedure.md
Adjudication:           docs/analysis/adr0065-appendix-b-adjudication.md
Target chain:
  MEDIA_PENDING → grace/reevaluation → ACTIVATION_REQUIRED
  → GIDR PermitRetry → existing GIE → ICE CONNECTED → SATISFIED → READY
Scope: M02↔M04 single valid 17:02-class run only
On PASS: close field verification; no confirmation re-run
On FAIL: STOP; this log is the only RCA input; no second field run
On INCONCLUSIVE: STOP invalid run; record reason before any further attempt
Production Meeting: UNCHANGED
8–10 / soak / Layer 1 / Meeting: NOT AUTHORIZED
```
