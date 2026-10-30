# ADR-0056 Gate 3 / Gate 4 — 8→10 Capacity Gate Contract (K1–K8)

## Status

**CONTRACT PASS** (2026-08-18) · K1–K8 **8/8 PASS** · Implementation **PASS** · Closeout: [0056-capacity-gate-closeout.md](./0056-capacity-gate-closeout.md)

**Parent:** [ADR-0056](../adr/0056-conference-topology-first.md) Decision 4 · Gate 3 (8p) / Gate 4 (10p)

**Not this phase:** ADR-0056 Phase 3 (ConferenceHealth → UI) remains **FROZEN**.

**Prerequisite:** Phase 2-5 Health Implementation PASS

**Purpose:** Freeze when Conference may occupy MESH vs ANCHOR/SFU-lite at scale, and that capacity pressure must not invent edges.

**Exit:** K1–K8 unit PASS → Capacity Gate Contract PASS → Implementation NOT until signed

---

## 1. Naming (frozen)

```text
Phase 2-5       Health Projection        CLOSED
Gate 3 / Gate 4 Capacity Gate            THIS CONTRACT
ADR-0056 Phase 3                         Health → UI（仍冻结）
```

Do not call this Phase 3.

---

## 2. What this is / is not

```text
Capacity gate
    ↓
valid topology shape
    ↓
existing topology truth
```

Forbidden:

```text
Capacity pressure
    ↓
invent more edges
```

```text
NO capacity implementation
NO admission implementation
NO hardware validation
NO field
NO UI / R28 / RNA / completion
```

---

## 3. Scale shape (Decision 4)

```text
N ≤ 3   MESH valid; ActualMediaEdgeSet = ∅
N ≥ 4   ANCHOR / SFU-lite required; MESH rejected
N = 8   Gate 3 product capacity shape (star N−1)
N = 10  Gate 4 architecture-limit shape (star N−1)
N > 10  outside architecture (rejected by this contract)
```

Threshold **4** is policy input (already). This contract freezes: **4-person conference must not fall back to full mesh.**

Star:

```text
|ActualMediaEdgeSet| == N − 1
every edge incident on elected anchor
no peer-pair edges
```

Forbidden:

```text
N × (N − 1)
roster → RecoveryTarget → peer-pair
Health → modify admission
```

Recovery at scale:

```text
RecoveryTargetSet ⊆ ActualMediaEdgeSet(currentGeneration)
|RecoveryTargets| == O(N)   (star: == N−1 when no AuthorizedTransition)
```

Health at scale: same Phase 2-5 aggregation. In-flight must not block topology fact or MEDIA_USABLE when admitted media is usable.

---

## 4. Fixtures K1–K8

Shared: `anchor=M01`, `threshold=4`. Members `M01…MN`.

### K1 — N=4 ANCHOR required / MESH rejected

Policy `decide(N=4, threshold=4)` → Anchor.  
Capacity `evaluate(MESH snapshot, N=4)` → Rejected.

### K2 — N=3 MESH remains valid

Policy → Mesh.  
MESH snapshot: `ActualMediaEdgeSet = ∅`. Capacity admitted.

### K3 — N=8 star

ANCHOR snapshot, 8 members.  
`actualMediaEdges.size == 7`. Not `8*7`.

### K4 — N=8 recovery bound

`RecoveryEdgeProvider.project(snapshot)`:  
targets ⊆ ActualMediaEdgeSet; `|targets| == 7`; no M02–M03 peer-pair target.

### K5 — N=10 star

`actualMediaEdges.size == 9`.

### K6 — N=8 MESH rejected

`composeMeshSnapshot(members=8)` must not pass capacity evaluate.  
No `meshGeneration++` + peer edges as a workaround.

### K7 — Health at N=8

Admitted edge usable → `MEDIA_USABLE` / ONLINE.  
Recovery in-flight → topology edges unchanged; does not block MEDIA_USABLE.

### K8 — roster growth isolation

N=4 star → N=8 star via topology compose (not recovery).  
Recovery targets remain star-only.  
Health project does not add/remove edges.

---

## 5. Test mapping

| Fixture | Test method |
| ------- | ----------- |
| K1 | `k1_n4_meshRejected_anchorRequired` |
| K2 | `k2_n3_meshValid_emptyEdgeSet` |
| K3 | `k3_n8_starEdgeCountIsNMinus1` |
| K4 | `k4_n8_recoveryTargetsSubsetStar_on` |
| K5 | `k5_n10_starEdgeCountIsNMinus1` |
| K6 | `k6_n8_meshSnapshotRejected` |
| K7 | `k7_n8_healthMediaUsable_inflightDoesNotMutate` |
| K8 | `k8_rosterGrowth_noPeerPairFromRecoveryOrHealth` |

```text
Contract:  .../ConferenceCapacityGateContract.kt
Tests:     .../ConferenceCapacityGatePhaseKFixturesTest.kt
```

---

## 6. Gate

```text
K1–K8 PASS
        ↓
Capacity Gate Contract PASS
        ↓
Gate implementation NOT AUTHORIZED until signed
```

---

## 7. Current door

```text
Phase 2-5 Health     PASS / CLOSED
8→10 Capacity Gate   CLOSED (0056-capacity-gate-closeout.md)
Gate 3 / Gate 4 field NOT AUTHORIZED
ADR-0056 Phase 3 UI  FROZEN
R28 / RNA / Completion FROZEN
```
