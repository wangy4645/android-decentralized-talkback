# ADR-0056 Phase 1b-6 — Downstream Consistency Contract (S1–S8)

## Status

**CONTRACT PASS** (2026-08-18) · S1–S8 **8/8 PASS** · **1b-6 Impl PASS**
**Phase 1b COMPLETE / PASS** (unit) — [closeout](./0056-phase-1b-complete.md)

**Parent:** [Phase 1b mapping](./0056-phase-1b-contract-mapping.md) · [F1–F8 fixtures](./0056-phase-1b-contract-fixtures.md) · [CPP contract](./0056-cpp-conference-presence-projection-contract.md) · [1b-1 scope](./0056-phase-1b-1-topology-authority-facade-scope.md)

**Prerequisite:** F1–F8 PASS · G1–G8 PASS · M1–M8 PASS · T1–T8 PASS · **1b-5 Impl PASS**

**Purpose:** Phase 1b 最后一层 **consistency contract**。不扩展 topology 语义；只证明 **同一个 `ConferenceTopologySnapshot` 在 Presence / UI / Diagnostic 下游不会被重新解释成另一套事实**。

**Exit:** S1–S8 unit PASS → **1b-6 Contract PASS** → **1b-6 Implementation AUTHORIZED** → **Phase 1b COMPLETE**（仍不含 field）

---

## 1. Normative chain (frozen)

```text
ConferenceTopologyAuthority
        ↓ atomic publish
ConferenceTopologySnapshot          ← sole topology truth (1b-1 … 1b-5)
        ↓ bind (read-only)
 ┌──────┼──────────┐
 ↓      ↓          ↓
Presence  UI      Diagnostic
```

硬规则：

```text
Downstream consumes snapshot.
Downstream MUST NOT write topology.
Downstream MUST NOT merge fields from two snapshots.
Downstream MUST NOT infer topology from ICE / session observation on ANCHOR path.
Diagnostic MUST be snapshot → log (not ICE → inferred topology).
```

与 1b-5 CPP 分流一致：

```text
MESH   → MESH_SESSION_OBSERVATION      (session observation path; no authority anchor bind)
ANCHOR → ANCHOR_AUTHORITY_SNAPSHOT     (authority snapshot bind only)
```

---

## 2. Relationship to prior contracts

| Layer | Contract | Question answered |
| ----- | -------- | ----------------- |
| F1–F8 | Snapshot construction | 谁 compose / validate atomic snapshot |
| G1–G8 | Anchor admission decision | 谁决定 anchor / mode |
| M1–M8 | ActualMediaEdgeSet admission | 哪些 edge 合法 |
| T1–T8 | MESH / ANCHOR transition | mode 切换如何替换 edge set |
| **S1–S8** | **Downstream consistency** | **同一 snapshot 下游是否单一解释** |

**F7 → S4 extension:**

```text
F7  atomicity at contract construction
S4  atomicity at downstream consumption (no cross-generation field mixing in any consumer view)
```

---

## 3. Shared fixture roster

```text
members4 = {M01, M02, M03, M04}
members3 = {M01, M02, M03}
```

Anchor star (when `anchorId=M01`):

```text
actualMediaEdges = { M01→M02, M01→M03, M01→M04 }
```

Pure contract entry (no Coordinator):

```text
ConferenceTopologyConsistencyContract.bindDownstreamView(snapshot)
ConferenceTopologyConsistencyContract.validateAtomicConsistency(snapshot)
```

Diagnostic format (existing):

```text
ConferenceTopologySnapshotLog.format(snapshot)
```

---

## 4. Fixture categories

| Category | Fixtures | Validates |
| -------- | -------- | --------- |
| Presence bind | S1 | roster / anchor / epoch / generation 与 snapshot 一致 |
| UI / CPP lineage | S2 | UI 与 CPP 使用同一 `meshGeneration` / `anchorEpoch` |
| Diagnostic | S3 | MESH/ANCHOR 状态可完整表达；仅 snapshot → log |
| Atomic consumption | **S4** | 禁止跨 generation 字段混合（construction + consumption） |
| Failover freshness | S5 | failover 后 downstream 不显示旧 anchor |
| MESH invariants | S6 | MESH 不虚构 anchor / admitted edge |
| Immutability | S7 | downstream read 不 mutate snapshot |
| Stability | S8 | 同一 snapshot 重复 bind 结果稳定 |

---

## 5. S1 — Presence bind matches snapshot lineage

**Given (ANCHOR, HOST ≠ ANCHOR)**

```text
snapshot:
  hostModuleId   = M02
  anchorId       = M01
  anchorEpoch    = 100
  meshGeneration = 3
  rosterEpoch    = 7
  members        = members4
  topologyMode   = ANCHOR
  actualMediaEdges = M01-star
```

**Bind**

```text
view = bindDownstreamView(snapshot)
read = ConferenceTopologyReadBind.presenceReadInput(snapshot)   // ANCHOR path only
```

**Assert**

```text
view.anchorId == snapshot.anchorId
view.anchorEpoch == snapshot.anchorEpoch
view.meshGeneration == snapshot.meshGeneration
view.rosterEpoch == snapshot.rosterEpoch
view.members == snapshot.members

read.anchorId == snapshot.anchorId
read.anchorEpoch == snapshot.anchorEpoch
read.meshGeneration == snapshot.meshGeneration
read.hostModuleId == snapshot.hostModuleId
read.canonicalRoster == snapshot.members
read.producerModuleId == snapshot.anchorId
```

**Must NOT:** presence bind 使用 host 作为 anchor；roster 子集化；generation 来自 session 缓存。

---

## 6. S2 — UI and CPP share topology generation

**Given**

```text
T1: ANCHOR snapshot, meshGeneration=5, anchorEpoch=10
T2: same conference, meshGeneration=6 (F4-valid edge change), anchorEpoch=10
```

**Chain**

```text
snapshot → bindDownstreamView → view.meshGeneration
snapshot → presenceReadInput → ConferencePresenceFactsAdapter → ConferencePresenceProjector.compose
       → projection (joinedCount / connectedCount unchanged by generation alone)
UI render path reads projection built from same snapshot lineage fields
```

**Assert**

```text
view(T1).meshGeneration == 5
view(T2).meshGeneration == 6
presenceSnapshot(T1).meshGeneration == 5
presenceSnapshot(T2).meshGeneration == 6
uiLineageGeneration(T1) == view(T1).meshGeneration
uiLineageGeneration(T2) == view(T2).meshGeneration
```

**Must NOT:** UI 显示 generation N 而 CPP/presence snapshot 绑定 generation M。

---

## 7. S3 — Diagnostic expresses MESH/ANCHOR from snapshot only

**Given**

```text
MESH snapshot:
  topologyMode=MESH, anchorId=null, anchorEpoch=0, meshGeneration=2
  actualMediaEdges=∅, members=members3

ANCHOR snapshot:
  topologyMode=ANCHOR, anchorId=M01, anchorEpoch=100, meshGeneration=5
  actualMediaEdges=M01-star, members=members4
```

**Emit**

```text
line = ConferenceTopologySnapshotLog.format(snapshot)
```

**Assert (both modes)**

```text
line contains topologyMode=
line contains anchorId=
line contains anchorEpoch=
line contains meshGeneration=
line contains members=
line contains edges=          // size derivable; empty for MESH

diagnosticIsSnapshotOnly(line) == true
```

**Forbidden diagnostic pattern**

```text
ICE observation
      ↓
diagnostic infers topologyMode / anchorId / actualMediaEdges
```

**Must NOT appear in topology diagnostic line:**

```text
iceConnected, iceReconnecting, IceEdgeObservation, peer-pair admitted inference
```

---

## 8. S4 — Atomic snapshot at downstream consumption (highest priority)

### 8.1 Single-snapshot cross-generation mix (extends F7)

**Invalid snapshot (must reject before any downstream bind)**

```text
anchorId         = M02          // generation T2 anchor identity
anchorEpoch      = 11           // T2 lineage
meshGeneration   = 31           // stale T1 generation
actualMediaEdges = M01-star     // T1 edge set
topologyMode     = ANCHOR
members          = members4
```

**Assert**

```text
validateAtomicConsistency(invalid) == Invalid
bindDownstreamView(invalid) == Rejected
ConferenceTopologyReadBind.presenceReadInput(invalid) MUST NOT be invoked
```

**Other documented invalid mixes (sub-cases)**

```text
MESH + non-empty actualMediaEdges
MESH + anchorId != null
MESH + anchorEpoch != 0
ANCHOR + anchorId=null
ANCHOR + incomplete star (T6 staged partial)
new anchorEpoch + old edge star centered on previous anchor
```

### 8.2 Cross-snapshot merge forbidden

**Given two valid snapshots S0, S1 (same conference, different generation)**

```text
attemptCrossSnapshotBind(S0, S1) == Invalid("cross-snapshot bind forbidden")
```

**Must NOT exist downstream API:**

```text
anchorId       ← S1
actualMediaEdges ← S0
meshGeneration ← S0
```

### 8.3 Replacement is whole-snapshot

**When authority publishes S1 replacing S0:**

```text
downstream view derived entirely from S1 OR rejected
never a hybrid view
```

---

## 9. S5 — Failover: downstream drops stale anchor

**Old**

```text
S0: anchorId=M01, anchorEpoch=10, meshGeneration=31, edges=M01-star
```

**New (single publish, F5-valid)**

```text
S1: anchorId=M02, anchorEpoch=11, meshGeneration=32, edges=M02-star
```

**Assert**

```text
isStaleSnapshot(S0, S1) == true
bindDownstreamView(S1).anchorId == M02
bindDownstreamView(S1).anchorEpoch == 11
presenceReadInput(S1).anchorId == M02
selectPresenceSnapshot(conferenceId, currentAnchorEpoch=S1, candidates) prefers S1 lineage
downstream MUST NOT display M01 as current anchor after S1 bind
```

---

## 10. S6 — MESH: no fabricated anchor or media edge

**Given**

```text
mesh = composeMeshSnapshot(..., members3, meshGeneration=2)
```

**Assert**

```text
validateAtomicConsistency(mesh) == Valid
view = bindDownstreamView(mesh)
view.topologyMode == MESH
view.anchorId == null
view.anchorEpoch == 0
view.actualMediaEdgeCount == 0
view.presenceReadPath == MESH_SESSION_OBSERVATION

validateMeshDownstreamInvariants(view) == Valid
presenceReadInput(mesh) MUST throw or be unreachable on MESH authority path
```

**Must NOT:**

```text
MESH downstream view with anchorId=M01 (stale carry-over)
MESH downstream view with actualMediaEdgeCount > 0
MESH authority bind → ANCHOR_AUTHORITY_SNAPSHOT
```

---

## 11. S7 — Immutable snapshot under downstream consumption

**Given valid ANCHOR snapshot S**

```text
before = S
(after, _) = readWithoutMutatingSnapshot(S) {
    bindDownstreamView(S)
    ConferenceTopologyReadBind.presenceReadInput(S)
    ConferenceTopologySnapshotLog.format(S)
    ConferencePresenceProjector.compose(... snapshot built from S fields ...)
}
```

**Assert**

```text
before == after
S.members unchanged
S.actualMediaEdges unchanged
```

**Must NOT:** any downstream consumer mutates `members`, `actualMediaEdges`, or lineage fields in place.

---

## 12. S8 — Repeated read stability

**Given valid snapshot S (ANCHOR or MESH)**

```text
view1 = bindDownstreamView(S)
view2 = bindDownstreamView(S)
view3 = bindDownstreamView(S)
line1 = diagnosticFromSnapshot(S)
line2 = diagnosticFromSnapshot(S)
```

**Assert**

```text
view1 == view2 == view3
line1 == line2
repeatedBindStable(S, iterations=5) == true
```

---

## 13. Explicit non-scope (1b-6)

```text
Recovery
ICE admission
AudioBus
Health
field / 4p integration gate
new topology semantics
runtime repair for diagnostic gaps
Coordinator wiring (1b-6 impl phase only)
```

尤其：**不为 S3 顺手加 runtime repair**。

---

## 14. Test file mapping

| Fixture | Test method |
| ------- | ----------- |
| S1 | `s1_presenceBind_matchesSnapshotLineage_hostNotAnchor` |
| S2 | `s2_uiAndCpp_shareTopologyGeneration` |
| S3 | `s3_diagnostic_snapshotOnly_meshAndAnchor` |
| S4 | `s4_atomicDownstream_rejectsCrossGenerationMix_andCrossSnapshotMerge` |
| S5 | `s5_failover_downstreamUsesNewAnchor_notStale` |
| S6 | `s6_meshDownstream_noFabricatedAnchorOrEdges` |
| S7 | `s7_downstreamRead_doesNotMutateSnapshot` |
| S8 | `s8_repeatedBind_stableResults` |

**Code location**

```text
Contract:
  android-board-talkback/.../ConferenceTopologyConsistencyContract.kt

Tests:
  android-board-talkback/src/test/.../ConferenceTopologyConsistencyPhase1b6FixturesTest.kt
```

---

## 15. Gate

```text
F1–F8 PASS
G1–G8 PASS
M1–M8 PASS
T1–T8 PASS
        ↓
S1–S8 PASS
        ↓
1b-6 Contract PASS
        ↓
1b-6 Implementation AUTHORIZED
        ↓
Phase 1b COMPLETE (unit/projection scope; field still NOT AUTHORIZED)
```

Phase 1b 最终证明的封闭链：

```text
Roster
  ↓
Anchor admission
  ↓
Topology mode
  ↓
ActualMediaEdgeSet
  ↓
Atomic Snapshot
  ↓
Presence / UI / Diagnostic
```

**Recovery 仍在这条链之外** — Phase 2 以 `ActualMediaEdgeSet(currentGeneration)` 为唯一事实源接入。

---

## 16. Current door

```text
PHASE 1b — COMPLETE / PASS

1b-1 … 1b-6     PASS
S1–S8           PASS

Phase 2         NOT STARTED
Recovery        FROZEN
Bus             FROZEN
Health          FROZEN
Field           FROZEN
```
