# ADR-0056 Phase 1b-1 — TopologyAuthority Facade Scope / Ownership / Exit Gate

## Status

**FROZEN SCOPE** (2026-08-18)
**Implementation AUTHORIZED** (1b-1 only)
**Field NOT AUTHORIZED**

**Parent:** [Phase 1b mapping](./0056-phase-1b-contract-mapping.md) · [F1–F8 fixtures](./0056-phase-1b-contract-fixtures.md)

**Prerequisite:** Phase 1b Contract PASS (F1–F8, 8/8)

**Purpose:** 冻结 1b-1 的唯一目标——**不是“把 topology 做出来”**，而是证明生产链只能通过已冻结的 topology contract 产生 `ConferenceTopologySnapshot`。

**Out of scope (frozen):** 1b-2+ admission 语义细化 · Bus · Recovery · Health · field · MESH↔ANCHOR policy · partial star · AuthorizedTransition impl

---

## 1b-1 目标（一句话）

```text
single publish owner
    → compose → validate → atomic publish
    → immutable downstream read
    → no second path
```

---

## 1. 唯一 publish owner

### 1.1 Owner

```text
ConferenceTopologyAuthority
    = snapshot 的唯一写入口
```

允许：

```text
Coordinator / session layer
    → 触发 compose（输入 facts）
    → 调用 authority.publish(...)
```

禁止：

```text
Coordinator
    → 直接写 session.anchorId / meshGeneration / edge set
    → 绕过 authority 发布 snapshot
    → 第二个 publish API
```

Phase 1 最小落点（Matrix 已冻结）：**独立 class 可选**；可以是 Coordinator 内 facade，但 **API 边界必须单一**。

### 1.2 Ownership table

| Artifact | Writer | Reader |
| -------- | ------ | ------ |
| `ConferenceTopologySnapshot` | `ConferenceTopologyAuthority` only | Presence, CPP, diagnostics, future RecoveryEdgeProvider |
| Compose inputs (membership / anchor facts / admission inputs) | Upstream authorities / Coordinator gather | Authority (read-only consume) |
| ICE observations | Runtime | Admission evaluator only — **not** edge set writer |

---

## 2. Snapshot 生命周期

### 2.1 Publish pipeline（唯一合法路径）

```text
gather inputs
    ↓
ConferenceTopologyContract.compose* / authority internal compose
    ↓
ConferenceTopologyContract.validateSnapshot
    ↓
SnapshotValidity.Valid
    ↓
atomic publish(immutable ConferenceTopologySnapshot)
    ↓
consumers read current snapshot only
```

`*` 1b-1 可复用现有 `ConferenceTopologyContract`；1b-2 再替换/扩展 compose 语义。

### 2.2 Atomicity

一次 publish 必须同时包含（同文档 §5）：

```text
conferenceId
rosterEpoch
anchorEpoch
anchorId
meshGeneration
topologyMode
members
actualMediaEdges
hostModuleId (metadata only)
```

禁止：

```text
publish anchorId
    … later …
publish actualMediaEdges

publish new anchorEpoch
    + old actualMediaEdges   (F7 INVALID)
```

### 2.3 Downstream read model

```text
consumers MUST NOT mutate snapshot
consumers MUST NOT hold mutable references into authority internals
consumers read: authority.currentSnapshot() or equivalent immutable getter
```

CPP / Presence：**只读** snapshot 或由其派生的 presence snapshot；F8 已 contract PASS。

---

## 3. 禁止的第二路径

1b-1 实现与 inspection 必须确认不存在：

| Forbidden path | Rule |
| -------------- | ---- |
| ICE → `ActualMediaEdgeSet` | F3/F6；ICE 是 observation，admission 经 authority transaction |
| CPP / Presence → topology | F8；downstream only |
| `hostModuleId` → media authority | F2；host ≠ anchor 合法，star 仍以 anchorId 为中心 |
| Second publish path | 任何 bypass `ConferenceTopologyAuthority` 的 snapshot 写入 |
| Recovery → topology | Phase 2；1b-1 不碰 RecoveryController |
| Bus / timeout → topology | Matrix §14 禁止项 |

---

## 4. Exit Gate — 1b-1 PASS

### 4.1 必须全部满足

```text
1b-1 PASS
  ├─ single publish owner
  ├─ atomic snapshot boundary
  ├─ immutable downstream read
  ├─ no ICE → edge admission write path
  ├─ no second publish path
  └─ F1–F8 contract tests remain PASS
```

### 4.2 Gate formula

```text
F1–F8 contract PASS
        +
single publish path inspection
        +
no ICE → ActualMediaEdgeSet admission path
        +
no downstream topology mutation
        ↓
1b-1 PASS
        ↓
1b-2 AUTHORIZED (admission semantics — not before 1b-1 PASS)
```

### 4.3 单元 / contract 测试（1b-1 交付）

| Check | Method |
| ----- | ------ |
| F1–F8 | `ConferenceTopologyPhase1bContractFixturesTest` — must stay green |
| Validate rejects half-topology | F7 cases via authority path (not only direct contract call) |
| Publish rejects invalid | authority.publish → does not install INVALID snapshot |
| Single current snapshot | after publish, readers see one immutable instance |
| No mutation API on snapshot | data class / copy not exposed to consumers for “fixups” |

### 4.4 Static / grep inspection（正式 gate 项）

Run against `android-board-talkback` production sources (`src/main`, exclude test). **FAIL if any match is a topology write outside authority.**

**A. Second publish / direct session topology write**

```text
grep targets (indicative — refine at 1b-1 impl PR):
  meshGeneration =
  anchorEpoch =
  anchorModuleId =          (assignment on session / digest publish path)
  actualMediaEdges
  ConferenceTopologySnapshot(
```

Rule: assignments that **publish** topology truth must live only in `ConferenceTopologyAuthority` (or its single facade file).

**B. ICE → admission**

```text
grep targets:
  ICE_CONNECTED
  iceConnected
  onIceConnected
  actualMediaEdges +
  ActualMediaEdgeSet +
```

Rule: no code path from ICE callback directly mutating or constructing admitted edge set without authority transaction.

**C. Downstream topology mutation**

```text
grep targets in CPP / Presence / ViewModel conference path:
  publishConferenceTopology
  actualMediaEdges =
  topologyMode =
```

Rule: CPP/Presence/UI must not write topology (F8).

**D. Host as authority**

```text
grep targets:
  hostModuleId == .* anchor
  if (isHost) { .* actualMediaEdges
  if (host) { .* topologyMode
```

Rule: host must not branch topology shape (F2).

Inspection recorded in PR / gate checklist with **file:line allowlist** for false positives (diagnostics only).

---

## 5. 1b-1 交付物（implementation 时）

| Deliverable | Notes |
| ----------- | ----- |
| `ConferenceTopologyAuthority` (facade) | single `publish` / `currentSnapshot` |
| Wire Coordinator → authority only | no parallel writers |
| Inspection checklist | grep results + allowlist |
| Tests | authority path for validate/publish; F1–F8 unchanged |

**NOT in 1b-1:**

```text
❌ Full anchor admission policy (1b-3)
❌ ActualMediaEdgeSet runtime admission from ICE (1b-4)
❌ 4p integration / field (1b-5 / 1b-6)
❌ ConferenceAudioBus
❌ RecoveryController / RecoveryEdgeProvider
❌ MESH mode production paths (unless read-only passthrough)
```

---

## 6. 与后续阶段边界

```text
1b-1 PASS
    → TopologyAuthority facade + atomic publish proven
1b-2
    → ConferenceTopologySnapshot type in production read path
1b-3
    → Anchor admission decision (threshold as input only)
1b-4
    → ActualMediaEdgeSet admission rules
1b-5
    → 4p admission integration
1b-6
    → 4p field gate
```

**Recovery binding** (`RecoveryMediaEdgeSet ⊆ ActualMediaEdgeSet`, AuthorizedTransition) remains **Phase 2** — not expanded into 1b-1 gate.

---

## 7. 当前门状态

```text
Phase 1b                       COMPLETE / PASS (unit)
                               closeout: 0056-phase-1b-complete.md
Phase 1b-1 Scope               FROZEN (this doc)
Phase 1b Field                 NOT AUTHORIZED
Phase 2 Recovery               NOT STARTED
Bus / Health / CPP contract    FROZEN
```
