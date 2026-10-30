# ADR-0056 Phase 2-4 — AuthorizedTransition Contract (AT1–AT8)

## Status

**Phase 2-4 CONTRACT PASS** (2026-08-18) · AT1–AT8 **8/8 PASS**
**Phase 2-4 Impl PASS** (unit) · Health / Field / R28 **FROZEN**

**Parent:** [ADR-0056](../adr/0056-conference-topology-first.md) · [Phase 2-0 binding](./0056-phase-2-0-recovery-binding-contract.md) · [Phase 1b COMPLETE](./0056-phase-1b-complete.md)

**Prerequisite:** Phase 2-3 Recovery Binding Integration Gate PASS

**Purpose:** Freeze **when** a legal `AuthorizedTransition` exists, and **which** displaced edges it may carry. This is a topology generation/epoch fact — not Recovery policy.

**Fixture IDs:** AT1–AT8 (user T1–T8). Not 1b-5 MESH/ANCHOR T1–T8.

**Exit:** AT1–AT8 unit PASS → Phase 2-4 Contract PASS → Implementation AUTHORIZED (TopologyAuthority emit only; R28 still frozen)

---

## 1. What this is / is not

```text
AuthorizedTransition
≠ grace period
≠ retry window
≠ ICE reconnect
≠ completion policy
≠ Recovery-inferred handoff
```

It is only:

```text
old Snapshot  →  new Snapshot
                     +
              AuthorizedTransition
                     ↓
              RecoveryEdgeProvider
```

Producer (after 2-4 impl): **ConferenceTopologyAuthority** (same transaction as the new snapshot).

Consumer: **RecoveryEdgeProvider** (already; explicit input). Recovery Controller MUST NOT compose transitions.

---

## 2. Compose rule (frozen)

```text
displacedEdges = previous.actualMediaEdges \ current.actualMediaEdges
```

```text
compose(previous, current) =

  None
    if previous is null
    OR same conference with no generation/epoch/edge identity change
    OR displacedEdges = ∅
       (generation may still bump; current-gen targets cover remaining edges)

  AuthorizedTransition
    if displacedEdges ≠ ∅
    AND current.meshGeneration > previous.meshGeneration
    with:
      fromMeshGeneration = previous.meshGeneration
      toMeshGeneration   = current.meshGeneration
      fromAnchorEpoch    = previous.anchorEpoch
      toAnchorEpoch      = current.anchorEpoch
      affectedEdges      = displacedEdges
```

Empty `affectedEdges` is **not** a transition. It is forbidden as a wildcard (AT5 / R7).

---

## 3. Validate (already in 2-0; 2-4 restates)

```text
affectedEdges ≠ ∅
affectedEdges ≠ wildcard / ALL
affectedEdges ⊆ previous.actualMediaEdges
toMeshGeneration == current.meshGeneration
fromMeshGeneration == previous.meshGeneration
fromMeshGeneration < toMeshGeneration
toAnchorEpoch == current.anchorEpoch
fromAnchorEpoch == previous.anchorEpoch
```

Unauthorized old edge (not in previous set, or not in `affectedEdges`) MUST NOT become a recovery target.

---

## 4. Fixtures AT1–AT8

Shared: `members4={M01,M02,M03,M04}`, M01-star / M02-star as in 1b/2-0.

### AT1 (T1) — no topology change → no transition

Same ANCHOR snapshot (or identical edges + gen + epoch). `compose == None`.

### AT2 (T2) — generation change binds old/new generation

4p M01-star gen=31 → 3p M01-star gen=32 (M04 dropped).  
`compose` emits transition `fromMeshGeneration=31`, `toMeshGeneration=32`.  
`affectedEdges == {M01→M04}`.

### AT3 (T3) — anchor failover binds old/new epoch

S0: M01, epoch=10, gen=31, M01-star  
S1: failover M02, epoch=11, gen=32, M02-star  
`fromAnchorEpoch=10`, `toAnchorEpoch=11`.  
`affectedEdges == M01-star` (all identities displaced).

### AT4 (T4) — affectedEdges from previous ActualMediaEdgeSet only

Every edge in `compose.affectedEdges` ∈ `previous.actualMediaEdges`.  
Peer-pair `M02→M03` not admitted into `affectedEdges`.

### AT5 (T5) — no wildcard

`affectedEdges = ∅` → Invalid.  
No `ALL` / omitted-set token.

### AT6 (T6) — unauthorized old edge does not enter recovery

Failover without listing `M01→M03` in `affectedEdges`: that MediaEdge is not a CURRENT or TRANSITION target.  
Provider.project(S1, S0, {partial transition}) does not admit unlisted displaced edges.

### AT7 (T7) — toGeneration == new snapshot generation

`toMeshGeneration ≠ current.meshGeneration` → Invalid.  
Legal compose always equals `current.meshGeneration`.

### AT8 (T8) — TopologyAuthority contract produces; Recovery does not infer

`compose(S0,S1)` is `ConferenceAuthorizedTransitionContract` only.  
`RecoveryEdgeProvider.project(S1, S0)` with `authorizedTransitions=∅` has **no** `AUTHORIZED_TRANSITION` sources.  
ICE / wall-clock / retry MUST NOT appear as compose inputs.

---

## 5. Explicit non-scope (2-4 impl later; not this contract drop)

```text
TopologyAuthority.publish emits transition     ← 2-4 impl
Coordinator passes compose output to Provider  ← 2-4 impl
Q6-B EDGE_REMOVED_BY_TOPOLOGY close path       ← not 2-4 contract
R28 / RNA / completion / Health / field
WiFi recovery main chain
```

---

## 6. Test mapping

| User | Fixture | Test method |
| ---- | ------- | ----------- |
| T1 | AT1 | `at1_noTopologyChange_noTransition` |
| T2 | AT2 | `at2_generationChange_bindsOldAndNewGeneration` |
| T3 | AT3 | `at3_anchorFailover_bindsOldAndNewEpoch` |
| T4 | AT4 | `at4_affectedEdges_subsetOfPreviousActualMediaEdgeSet` |
| T5 | AT5 | `at5_emptyAffectedEdges_rejectedAsWildcard` |
| T6 | AT6 | `at6_unlistedDisplacedEdge_notRecoveryTarget` |
| T7 | AT7 | `at7_toGeneration_mustEqualCurrentSnapshot` |
| T8 | AT8 | `at8_provider_doesNotInferTransition` |

```text
Contract:  .../ConferenceAuthorizedTransitionContract.kt
Tests:     .../ConferenceAuthorizedTransitionPhase24FixturesTest.kt
```

---

## 7. Gate

```text
AT1–AT8 PASS
        ↓
Phase 2-4 Contract PASS
        ↓
Phase 2-4 Impl AUTHORIZED
        (Authority emit + Coordinator pass-through only)
R28 / Health / Field still FROZEN
```

---

## 8. Current door

```text
Phase 2-3 Binding    PASS
Phase 2-4 Contract   PASS
Phase 2-4 Impl       PASS (unit)

R28 / RNA / Completion / Health / Field   FROZEN
```
