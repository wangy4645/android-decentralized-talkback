# ADR-0056 Phase 2-0 — Recovery Binding Contract (R1–R8)

## Status

**Phase 2-3 Binding PASS** · **Phase 2-4 Contract REVIEW** — [0056-phase-2-4-authorized-transition-contract.md](./0056-phase-2-4-authorized-transition-contract.md)

**Parent:** [ADR-0056](../adr/0056-conference-topology-first.md) · [Phase 0.5 mapping](./0056-phase-05-contract-mapping.md) · [Phase 1b COMPLETE](./0056-phase-1b-complete.md)

**Prerequisite:** Phase 1b COMPLETE / PASS (unit)

**Purpose:** Freeze **recovery eligibility as a projection of topology truth**. Close ADR-0056 finding **C**: RecoveryEdgeSet MUST be derived from `ActualMediaEdgeSet(currentGeneration)`, never from roster.

**Exit:** R1–R8 unit PASS → Phase 2-0 Contract PASS → Phase 2-1 Implementation AUTHORIZED (Provider only; Controller still frozen)

---

## 1. Architect freeze (Phase 2 sequencing)

Phase 2-0 answers **what Recovery is allowed to bind**, not how Recovery retries.

```text
Phase 1b = topology truth          CLOSED
Phase 2   = recovery binding       THIS TRACK
```

Recommended implementation order after this contract:

```text
Phase 2-0  Recovery Binding Contract          ← this document
Phase 2-1  RecoveryEdgeProvider               (stateless f(Snapshot); emits AdmittedRecoveryTarget)
Phase 2-2  (folded into 2-1)                  do NOT split Target as a second runtime owner
Phase 2-3  Bind existing Recovery Controller  (eligibility input only; R28 untouched)
Phase 2-4  generation / failover transition   (AuthorizedTransition produced by TopologyAuthority)
Phase 2-5  Health integration
```

**Do not split 2-1 / 2-2.** `RecoveryEdgeProvider` is the sole producer of `AdmittedRecoveryTarget`. A second “Target authority” would recreate roster-inference.

**Do not start 2-3 until 2-1 is signed.** Wiring Controller first would skip finding C.

Still frozen:

```text
WiFi recovery main chain
RNA / completion predicate
ADR-0055 implementation
Q11 / #196 edge-level exceptions
ConferenceEdgeRecoveryController lifecycle / retry / watchdog
AudioBus / Health / field
```

---

## 2. Normative chain

```text
ConferenceTopologySnapshot
        ↓
ActualMediaEdgeSet(currentGeneration)
        ↓
RecoveryEdgeProvider          f(Snapshot[, previous, AuthorizedTransition])
        ↓
AdmittedRecoveryTarget
        ↓
(Phase 2-3) existing Recovery obligation / ConferenceEdgeKey
```

Forbidden:

```text
roster → RecoveryEdgeSet
ICE connected → RecoveryEdgeSet
session.peer recently seen → RecoveryEdgeSet
```

---

## 3. Core invariant (INV-056 admission validity)

```text
AdmittedRecoveryTarget valid iff

  mediaEdge ∈ ActualMediaEdgeSet(currentGeneration)

  OR

  mediaEdge ∈ AuthorizedTransition(
      fromGeneration,
      currentGeneration
  ).affectedEdges
```

`AuthorizedTransition` in **2-0 is a typed contract input**, not a runtime policy:

```text
NO wall-clock grace
NO retry
NO completion
NO ICE-derived transition
NO wildcard “all old edges”
```

Default for 2-0 / 2-1:

```text
authorizedTransitions = ∅
```

Therefore R5 / R6 (generation change / failover) **invalidate old targets** unless an explicit transition is supplied (R7).

---

## 4. Types (contract only)

```text
AdmittedRecoveryTarget
  conferenceId
  mediaEdge            // topology MediaEdge, not ConferenceEdgeKey
  meshGeneration       // generation that currently authorizes this target
  anchorEpoch
  source               // CURRENT_GENERATION | AUTHORIZED_TRANSITION

AuthorizedTransition
  fromMeshGeneration
  toMeshGeneration     // MUST equal snapshot.meshGeneration
  fromAnchorEpoch
  toAnchorEpoch
  affectedEdges        // explicit, non-empty, no wildcard
                       // MUST ⊆ previous.actualMediaEdges when previous is present

RecoveryTargetSnapshot
  conferenceId
  rosterEpoch / anchorEpoch / meshGeneration   // copied from topology snapshot
  targets: Set<AdmittedRecoveryTarget>

RecoveryTargetDiff (optional in 2-0; required later for Q6-B)
  added / removed / retained
```

`ConferenceEdgeKey` binding is **Phase 2-3**. 2-0 does not call Recovery Controller.

---

## 5. MESH / ANCHOR

```text
MESH
  ActualMediaEdgeSet = ∅
  → RecoveryTargetSnapshot.targets = ∅

ANCHOR
  targets(CURRENT_GENERATION) = ActualMediaEdgeSet
  |targets| == |ActualMediaEdgeSet| when authorizedTransitions = ∅
```

MESH MUST NOT invent peer-pair recovery targets from roster.

---

## 6. Provider MUST / MUST NOT

**MUST**

- Read a single immutable snapshot (plus optional previous + explicit transitions)
- Copy lineage fields from that snapshot (no cross-generation mix — S4)
- Emit current-generation targets only from `actualMediaEdges`

**MUST NOT**

- Mutate topology snapshot
- Admit ICE-only observations
- Admit roster pairs absent from `ActualMediaEdgeSet`
- Create PeerConnection / retry / watchdog / SUPERSEDE / health
- Infer `AuthorizedTransition` from time or ICE

---

## 7. Fixtures R1–R8

Shared roster: `{M01,M02,M03,M04}`. Anchor star when `anchorId=M01`: `{M01→M02, M01→M03, M01→M04}`.

Priority: **R2 / R3 / R5 / R6**.

### R1 — admitted current-generation edge is a recovery target

```text
snapshot ANCHOR, gen=5, M01-star
project(snapshot) → 3 targets
∀ e ∈ actualMediaEdges: e is eligible
|targets| == |actualMediaEdges|
```

### R2 — roster member pair not in ActualMediaEdgeSet is NOT recoverable

```text
members contain M02,M03
edge M02-M03 ∉ actualMediaEdges
isEligible(M02-M03) == false
```

### R3 — ICE connected but not admitted is NOT recoverable

```text
IceEdgeObservation(M02,M03, iceConnected=true)
isAdmittedByTopology == false
isEligible == false
```

### R4 — admitted edge reconnecting remains a recovery target

```text
e = M01→M02 ∈ actualMediaEdges
IceEdgeObservation(M01,M02, iceConnected=false, iceReconnecting=true)
edgeStillAdmitted == true
isEligible(e) == true
meshGeneration unchanged
```

### R5 — generation change invalidates old targets (no transition)

```text
S0 gen=31, M01-star
S1 gen=32, M01-star + same edges still valid as NEW generation targets
old target bound to gen=31 isEligible(S1) == false
authorizedTransitions = ∅
```

### R6 — Anchor failover: old anchor edges are not recovery targets

```text
S0 anchor=M01 epoch=10 gen=31 edges=M01-star
S1 failover M02 epoch=11 gen=32 edges=M02-star
isEligible(M01→M03, S1) == false
isEligible(M02→M01, S1) == true
```

### R7 — explicit AuthorizedTransition may keep a legitimate handoff edge

```text
transition:
  fromGen=31 → toGen=32
  fromEpoch=10 → toEpoch=11
  affectedEdges = {M01→M02}     // ⊆ S0.actualMediaEdges
project(S1, previous=S0, transitions={transition})
  M02-star edges eligible as CURRENT_GENERATION
  M01→M02 eligible as AUTHORIZED_TRANSITION
  M01→M03 NOT eligible (not in affectedEdges)
wildcard / empty affectedEdges → Invalid
toGen ≠ S1.meshGeneration → Invalid
```

No wall-clock, retry, or completion in this fixture.

### R8 — provider is read-only

```text
before == after project(snapshot)
snapshot.actualMediaEdges unchanged
```

---

## 8. Test mapping

| Fixture | Test method |
| ------- | ----------- |
| R1 | `r1_currentGenerationAdmittedEdge_isRecoveryTarget` |
| R2 | `r2_rosterPairAbsentFromActualMediaEdgeSet_notRecoverable` |
| R3 | `r3_iceConnectedNotAdmitted_notRecoverable` |
| R4 | `r4_admittedEdgeReconnecting_stillRecoveryTarget` |
| R5 | `r5_generationChange_withoutTransition_invalidatesOldTargets` |
| R6 | `r6_anchorFailover_oldAnchorEdges_notRecoveryTargets` |
| R7 | `r7_explicitAuthorizedTransition_allowsListedHandoffEdge` |
| R8 | `r8_provider_doesNotMutateTopologySnapshot` |

```text
Contract:  .../ConferenceRecoveryBindingContract.kt
Tests:     .../ConferenceRecoveryBindingPhase20FixturesTest.kt
```

---

## 9. Gate

```text
R1–R8 PASS
        ↓
Phase 2-0 Contract PASS
        ↓
Phase 2-1 RecoveryEdgeProvider AUTHORIZED
        ↓
Controller / Health / Field still NOT AUTHORIZED
```

---

## 10. Current door

```text
Phase 1b             COMPLETE / PASS

Phase 2-0 Contract   PASS
Phase 2-1 Provider   PASS
Phase 2-3 Binding    PASS (unit C1–C7; Controller consumes projection)
Phase 2-4 Transition NOT AUTHORIZED
Health               FROZEN
Field                FROZEN
R28 lifecycle        FROZEN
```
