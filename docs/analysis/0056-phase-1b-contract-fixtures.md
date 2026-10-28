# ADR-0056 Phase 1b — Contract Fixtures (F1–F8)

## Status

**CONTRACT PASS** (2026-08-18) · F1–F8 **8/8 PASS**
**Phase 1b:** COMPLETE / PASS (unit) — [closeout](./0056-phase-1b-complete.md)
**Field NOT AUTHORIZED**

**Parent:** [Phase 1b mapping](./0056-phase-1b-contract-mapping.md) · [1b-1 scope](./0056-phase-1b-1-topology-authority-facade-scope.md) · [Phase 0.5](./0056-phase-05-contract-mapping.md) · [CPP contract](./0056-cpp-conference-presence-projection-contract.md)

**Purpose:** 冻结 F1–F8 纯 contract fixture 的输入、断言与失败语义。测试骨架：`ConferenceTopologyPhase1bContractFixturesTest.kt` + `ConferenceTopologyContract.kt`（纯函数，非 Coordinator）。

**Exit:** F1–F8 PASS → Phase 1b Contract PASS → `PHASE 1b IMPLEMENTATION AUTHORIZED`（仍分 1b-1 … 1b-6，禁止一次性合并）。

---

## Fixture categories

| Category | Fixtures | Validates |
| -------- | -------- | --------- |
| Snapshot construction | F1, F2, F4, F5 | 单 transaction 产出完整 snapshot 字段 |
| Admission invariants | F3, F6, F7 | ICE ≠ admitted；transient ICE 不改 topology；半拓扑 INVALID |
| Downstream boundary | F8 | Presence/CPP 只消费 snapshot，不反向写 topology |

---

## Shared fixture roster

```text
members = {M01, M02, M03, M04}
```

Anchor star (when `anchorId=M01`):

```text
actualMediaEdges = {
    M01→M02,
    M01→M03,
    M01→M04
}
```

---

## F1 — 4p cold-start Anchor

**Inputs**

```text
topologyMode     = ANCHOR
hostModuleId     = M01
anchorId         = M01
anchorEpoch      = 100
meshGeneration   = 1
members          = {M01,M02,M03,M04}
admissionPolicy  = threshold≥4 satisfied
```

**Compose (single transaction)**

```text
ConferenceTopologyContract.composeAnchorAdmission(...)
```

**Assert**

```text
snapshot.topologyMode == ANCHOR
snapshot.anchorId == M01
snapshot.members.size == 4
snapshot.actualMediaEdges.size == 3
∀ e ∈ edges: e.anchorModuleId == M01
∀ remote ∈ {M02,M03,M04}: edge(M01,remote) ∈ edges
validateSnapshot(snapshot) == Valid
```

**Must NOT:** relay started outside snapshot; peer-pair edges present.

---

## F2 — HOST ≠ ANCHOR (第一优先级)

**Inputs**

```text
hostModuleId     = M02
anchorId         = M01
anchorEpoch      = 100
meshGeneration   = 1
members          = {M01,M02,M03,M04}
topologyMode     = ANCHOR
```

**Assert (canonical topology — not a special branch)**

```text
hostModuleId == M02
snapshot.anchorId == M01
hostModuleId != snapshot.anchorId

snapshot.members == {M01,M02,M03,M04}

actualMediaEdges == {
    M01→M02,
    M01→M03,
    M01→M04
}

validateSnapshot(snapshot) == Valid
```

**Must NOT:** host treated as media authority; star centered on M02; reduced member set.

**Field note:** Contract fixture uses HOST=M02 / ANCHOR=M01 as normative case. Run A (`267e47cc`) had ANCHOR=M02; Run B had HOST=ANCHOR=M01. Fixture ≠ replay of either run; it freezes required semantics.

---

## F3 — ICE connected but not admitted

**Topology snapshot (fixed)**

```text
anchorId = M01
topologyMode = ANCHOR
actualMediaEdges = M01-star only
```

**Runtime observation**

```text
IceEdgeObservation(local=M02, remote=M03, iceConnected=true)
```

**Assert forward**

```text
mediaEdgeForObservation(obs, snapshot) == null
isAdmittedByTopology(obs, snapshot) == false
obs.iceConnected == true   // observation allowed
M02-M03 ∉ actualMediaEdges
```

**Assert reverse (admitted + reconnecting)**

```text
edge = M01→M02 ∈ actualMediaEdges
obs = IceEdgeObservation(M01, M02, iceConnected=false, iceReconnecting=true)

edgeStillAdmitted(edge, snapshot) == true
meshGeneration unchanged
```

---

## F4 — Topology change bumps meshGeneration

**Given**

```text
G0: edges = M01-star, meshGeneration = 5
```

**When**

```text
member M05 admitted → new star edges include M01→M05
```

**Assert**

```text
requiredMeshGenerationAfterEdgeChange(G0.edges, G1.edges, G0.meshGeneration) == 6
validateSnapshot(G1) == Valid
anchorEpoch unchanged
```

**When no edge change**

```text
requiredMeshGenerationAfterEdgeChange(same, same, n) == n
```

---

## F5 — Anchor failover

**Old**

```text
anchorId=M01, anchorEpoch=10, meshGeneration=31
edges=M01-star
```

**New (single publish)**

```text
anchorId=M02, anchorEpoch=11, meshGeneration=32
edges=M02-star
```

**Assert**

```text
isStale(old, new) == true
validateSnapshot(new) == Valid
∀ e ∈ new.edges: e.anchorModuleId == M02
old edges invalid for admission under new anchorEpoch
```

---

## F6 — Transient ICE does not rewrite topology

**Given**

```text
snapshot S with edge M01→M02 admitted, meshGeneration=7
```

**Events (runtime only)**

```text
ICE restart on M01→M02
candidate refresh
signal retry
```

**Assert**

```text
topologyUnchangedByIceTransient(S, events) == true
requiredMeshGenerationAfterEdgeChange(S.edges, S.edges, 7) == 7
edgeStillAdmitted(M01→M02, S) == true
```

---

## F7 — Atomic snapshot (含 INVALID 构造)

**Valid**

```text
new anchor + new edges from same compose transaction → Valid
```

**Invalid (must reject)**

```text
anchorId       = M02
anchorEpoch    = 11
meshGeneration = 32
actualMediaEdges = M01-star {M01→M02, M01→M03, M01→M04}
```

**Assert**

```text
validateSnapshot(invalid) == Invalid("edge anchor mismatch")
```

**Other invalid patterns (documented; optional sub-cases)**

```text
new anchorEpoch + old meshGeneration + new edges mixed from two transactions
anchorId not in members
remote not in members
ANCHOR mode with empty actualMediaEdges while |members|>1
```

Phase 1b 价值：半拓扑在 contract 层不可接受，而非 runtime 后再 patch。

---

## F8 — CPP downstream consumption

**Given topology snapshots T1 → T2**

```text
T1: anchorEpoch=10, meshGeneration=1, M01-star, producer=M01
T2: same anchorId, anchorEpoch=10, meshGeneration=2
    actualMediaEdges changed by a separately valid topology transaction
    (e.g. member admit → new star edge per F4 — NOT an unchanged-edge bump)
```

**Disambiguation (F4 vs F8):** `meshGeneration=2` on T2 must **not** be explained as “same ActualMediaEdgeSet, generation++ anyway”. Unchanged edge set ⇒ generation unchanged (F4). F8 only tests downstream read of two distinct valid snapshots.

**Current unit test note:** skeleton uses same edge set + `meshGeneration=2` to assert CPP immutability/read-only only; a stricter T2 should adopt an F4-valid edge change before 1b-6 field gate.

**Chain**

```text
T → ConferencePresenceSnapshot (from admitted edges + observations)
  → ConferencePresenceProjector.compose
  → ConferencePresenceProjection
```

**Assert**

```text
compose uses T fields only (anchorEpoch, meshGeneration, producer)
changing T changes projection counts/mediaRelation
compose does not mutate T (immutable inputs)
CPP does not expose topology write API
```

**Must NOT:** CPP compose adds/removes ActualMediaEdgeSet members.

---

## Test file mapping

| Fixture | Test method |
| ------- | ----------- |
| F1 | `f1_fourParticipantColdStartAnchor_producesCanonicalStarSnapshot` |
| F2 | `f2_hostNotAnchor_producesSameCanonicalTopologyNotSpecialBranch` |
| F3 | `f3_iceConnectedPeerPair_notAdmitted_reverseAdmittedSurvivesReconnecting` |
| F4 | `f4_edgeSetChange_bumpsMeshGeneration_onlyOnChange` |
| F5 | `f5_anchorFailover_bumpsEpochAndGeneration_invalidatesOld` |
| F6 | `f6_transientIce_doesNotRewriteTopologyOrGeneration` |
| F7 | `f7_atomicSnapshot_validCompose_invalidHalfTopologyRejected` |
| F8 | `f8_cppFollowsTopologySnapshot_doesNotMutateTopology` |

**Code location**

```text
Contract types + pure functions:
  android-board-talkback/.../ConferenceTopologyContract.kt

Tests:
  android-board-talkback/src/test/.../ConferenceTopologyPhase1bContractFixturesTest.kt
```

---

## Gate

```text
F1–F8 unit PASS
        ↓
Phase 1b Contract PASS
        ↓
PHASE 1b IMPLEMENTATION AUTHORIZED
        ↓
1b-1 … 1b-6 (sequential; no monolithic 1b PR)
```

```text
Phase 1b Contract Fixtures   PASS (F1–F8, 8/8)
Phase 1b                     COMPLETE / PASS (unit; not field)
Phase 1b Field               NOT AUTHORIZED
```
