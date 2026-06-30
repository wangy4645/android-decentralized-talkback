# Group Runtime Health Projection

*TopologySnapshot as ADR-0007 Read Model (v1)*

Group PTT 的「Syncing channel」、Meeting 后自愈、以及 ICE 已连但仍不可发流等现象，表明 Runtime 缺少一层**可验证的收敛投影**：无法回答「Planner 期望什么、实际有什么、卡在哪一层」。本 ADR 定义 **GroupRuntimeHealth** 只读投影及其日志形态 **TopologySnapshot**，作为 ADR-0007 Projection Emitter 在 Group PTT 域的 v1 契约。不引入新权威源、不暴露 Planner 内部阶段、v1 不改变 UI 行为。

**决定原因**：现场日志显示 Meeting 结束触发 `Group reconnect` 后 PTT 恢复，说明修复的是 **Group Runtime 收敛**，而非 Floor 或 Capture 局部状态。若继续堆 PTT / `pendingTransmit` 日志，无法 diff「修复前后拓扑差了什么」。须将 Mesh 从「隐式状态机」降级为**解释层**，将 Transmit Policy 提升为 **Readiness 唯一裁决层**，并以纯函数 Projector 满足 R32。

## Scope

**v1 仅覆盖 Group PTT Session。**

GroupRuntimeHealth 的职责是解释本机 Group Session 上：

```
Membership → Planner → Desired Links → Actual Links → Readiness → PTT
```

**不承担** Conference 或 Unicast 的诊断职责。

**Scope Exception — `CONFERENCE_END`：** Conference 不在本 ADR 诊断范围内，但其结束事件 **必须** 能触发 Group TopologySnapshot，因为该边界可能修改 `rosterEpoch`、触发 `Group reconnect` / `reconcileGroupMesh`，是合法的 Group Planner reset 信号。此为 **cross-domain trigger**，不是 Conference RuntimeHealth。

**Channel overlay：** `ChannelReadiness.BLOCKED`（如 Conference 占用 channel gate）不进入 `GroupTopologyReadiness` 四态；由 Channel 层 overlay 处理。快照可含 `channelGated` 字段供关联，但不得用它推导 Group readiness。

## Architecture Dependency

```
ADR-0001 Runtime Model
        │
        ▼
ADR-0007 Intent–Reality Consistency (R31–R33)
        │
        ▼
ADR-0008 Group Runtime Health Projection (R34, v1 read model)
```

## Diagnostic Stratification

GroupRuntimeHealth v1 分六层字段；**仅 Membership 与 Transmit 参与 readiness 推导**；Mesh 与 Floor 为诊断关联层。

| 层 | 角色 | 参与 readiness |
|----|------|----------------|
| Membership | 状态权威（本机收敛） | 是（门控 `MEMBERSHIP_PENDING`） |
| Mesh | 解释「为何 transmit 未满足」 | **否** |
| Transmit | **唯一 readiness 裁决** | 是 |
| Readiness | 由上层推导 | 导出 |
| Floor | 解释「OPERATIONAL 但无声」 | **否** |
| Channel overlay | gate 关联 | **否** |

> **Mesh State ≠ Transport State ≠ Transmit State。** ICE_CONNECTED 是 Physical 信号，不得单独推出 Logical OPERATIONAL（R33）。

## Normative Rules

### R34 — Local Convergence to Authority View

> The system does not require global membership consensus. It requires local convergence to the latest known authority view.

Authority HELLO 的 digest 仅作**本机收敛参考**，不代表全局一致性。不得因「未与所有 peer digest 一致」而永久锁死在 `MEMBERSHIP_PENDING`。

### Membership reconciled

```
membershipDigestAlignedWithAuthority :=
    local.topologyDigest == local.lastSeenAuthorityDigest

membershipReconciled :=
    sessionAccepted == true
    AND membershipDigestAlignedWithAuthority == true
    AND suspectPeers.isEmpty()
```

- `sessionAccepted == false` → **必须** 为 `MEMBERSHIP_PENDING`。
- `suspectPeers` 非空：允许进入 BUILDING 推导路径的**前置检查失败** → 仍为 `MEMBERSHIP_PENDING`（禁止 OPERATIONAL 的保险层）。

### Mesh layer (diagnostic only)

```
meshDesiredLinks :=
    planner.joinTargets ∪ planner.inviteTargets
    （对当前 topologyKind / 本机角色，由既有 GroupMeshPlanner + MediaTopology 计算）

meshSignaledPeers :=
    meshCompletedModules ∩ members

meshIceConnectedPeers :=
    { peer | isPeerMediaConnected(peer) } ∩ members

meshMissingSignal := meshDesiredLinks - meshSignaledPeers
meshMissingIce    := meshDesiredLinks - meshIceConnectedPeers
```

- `meshMissingSignal` / `meshMissingIce` **MUST NOT** 影响 `GroupTopologyReadiness` 推导。
- `meshMissing*` **MUST NOT** 用于 UI state mapping。
- Mesh layer provides **explanations only**, never decisions.

### Transmit layer (readiness authority)

```
transmitRequiredPeers := MediaTopology.transmitPeerIds(...)
transmitReadyPeers    := { peer in transmitRequiredPeers | isPeerMediaConnected(peer) }
transmitMissingPeers  := transmitRequiredPeers - transmitReadyPeers
```

**GroupTopologyReadiness** 在 `membershipReconciled == true` 前提下：

- `transmitMissingPeers.isEmpty()` → `OPERATIONAL`
- 否则 → `BUILDING`

### Floor layer (diagnostic only)

快照可含 `floorOwner`、`floorEpoch`、`floorVersion`，用于关联「OPERATIONAL 但无声」。**MUST NOT** 参与 `GroupTopologyReadiness` 推导。

v1 **不含** `pendingTransmit`、`captureActive`（避免第二套 readiness FSM）。

### Planner visibility

> **RuntimeHealth MUST NOT expose Planner internal phases**（如 PLANNING、RECONCILING）。

`PLANNER_SCHEDULED` 仅可作为 Snapshot **触发 reason**，不是 readiness 枚举值。

## GroupTopologyReadiness (internal, v1)

v1 内部四态（非 UI FSM）：

| 状态 | 条件 |
|------|------|
| `DISCOVERING` | 无 callable module，或无 Group session 绑定 |
| `MEMBERSHIP_PENDING` | `!membershipReconciled` |
| `BUILDING` | `membershipReconciled` 且 `transmitMissingPeers` 非空 |
| `OPERATIONAL` | `membershipReconciled` 且 `transmitMissingPeers` 为空 |

映射到现有 `ChannelReadiness`（v1 UI 不变）：

| GroupTopologyReadiness | ChannelReadiness |
|------------------------|------------------|
| `OPERATIONAL` | `READY` |
| `BUILDING` | `DIRECTORY_SYNC` |
| `MEMBERSHIP_PENDING` | `DIRECTORY_SYNC` |
| `DISCOVERING` | `DISCOVERING` / `CONNECTING`（按现有 dialable 逻辑） |

v1：**UI 仍读既有 `channelReadiness()`**；投影与 UI 可短暂不一致，记为 known gap，v2 再统一。

## TopologySnapshot

**TopologySnapshot** = 某一时刻 `GroupRuntimeHealth` 的序列化观测产物；日志 tag 建议 `TOPOLOGY_SNAPSHOT`。

### Schema versioning

```
schemaVersion: 1   // 首版冻结字段语义
```

- 同 major version 内：**允许 additive 字段**；**禁止**改变既有字段语义。
- 语义变更或删改字段：**必须** bump `schemaVersion`。

### v1 字段（schemaVersion 1）

**Identity：** `reason`, `schemaVersion`, `sessionId`, `localModuleId`, `topologyKind`

**Membership：** `members`, `rosterEpoch`, `memberHash`, `sessionAccepted`, `membershipDigestAligned`, `suspectPeers`, `membershipReconciled`

**Mesh (diagnostic):** `meshDesiredLinks`, `meshSignaledPeers`, `meshIceConnectedPeers`, `meshMissingSignal`, `meshMissingIce`

**Transmit (authority):** `transmitRequiredPeers`, `transmitReadyPeers`, `transmitMissingPeers`

**Readiness (derived):** `groupTopologyReadiness`, `mappedChannelReadiness`, `convergenceAgeMs`

**Floor (diagnostic):** `floorOwner`, `floorEpoch`, `floorVersion`

**Channel overlay:** `channelGated`

### Snapshot emission (normative)

TopologySnapshot **MUST** emit on:

1. `membershipEpoch` / `rosterEpoch` 变化（`MEMBERSHIP_CHANGED`）
2. Planner schedule 边界（`PLANNER_SCHEDULED`）
3. `GroupTopologyReadiness` 迁移（`READINESS_CHANGED`）
4. Reconnection / rejoin（`RECONNECT`）
5. PTT blocked by non-operational transmit（`PTT_BLOCKED`）
6. App 启动后首次 Group session 绑定（`APP_START`）
7. **`CONFERENCE_END`**（Scope Exception，Group reset trigger）
8. ICE 状态变化（`ICE_STATE_CHANGED`，可选节流）
9. Mesh offer（`MESH_OFFERED`）

**Stall detector — `PERIODIC_BUILDING`：**

仅在同时满足时 emit：

- `groupTopologyReadiness == BUILDING`
- `convergenceAgeMs > BUILDING_STALL_THRESHOLD`
- 距上次 `PERIODIC_BUILDING` 已超过 `PERIODIC_WINDOW`

这不是普通 heartbeat，而是 **stall detector**。

### convergenceAgeMs

```
convergenceAgeMs := now - lastConvergenceAnchorMs

lastConvergenceAnchorMs := 最近一次下列事件之一：
  - membershipEpoch / rosterEpoch 变化
  - planner schedule（reconcile 边界）
```

用于量化「Syncing 卡了多久」，支持现场与性能分析。

## Projection Execution Model (R32)

```
TalkbackCoordinator
    ├── emitSnapshot(reason) at lifecycle boundaries only
    └── MUST NOT compute topology / readiness inline for projection

GroupRuntimeHealthProjector
    ├── pure function: Facts snapshot → GroupRuntimeHealth (immutable)
    ├── MUST be side-effect free
    └── MUST NOT schedule mesh or mutate session

TopologySnapshotLogger
    ├── serializes projection (schemaVersion 1)
    ├── logs TOPOLOGY_SNAPSHOT
    └── MAY throttle PERIODIC_BUILDING
```

- TopologySnapshot is an **observation artifact** and **MUST NOT** feed back into control decisions.
- Coordinator **MUST NOT** derive readiness for projection purposes outside the Projector（v1 UI 例外见上 known gap）。

## Considered Options

- **仅加 PTT / DEBUG-transmit 日志**：无法 diff Meeting 前后；拒绝。
- **6 态 GroupTopologyReadiness 含 PLANNING**：易长成第二套 UI FSM；拒绝。
- **mesh 层参与 readiness**：与 R33 及现场 ICE_CONNECTED+Syncing 矛盾；拒绝。
- **全局 membership 共识**：网络分区下死锁；拒绝。
- **纯函数 Projector + Snapshot Logger + schemaVersion（接受）**。

## Consequences

- 新增 `GroupRuntimeHealthProjector`、`TopologySnapshotLogger`；Coordinator 挂触发点。
- 单元测试以 fixture Session + QoS 快照覆盖四态推导、mesh/transmit 分层、R34 membership。
- v1 不改变 UI；诊断依赖 log 解析或后续工具。
- 现场 bug（M03 Syncing、Meeting 自愈）须在快照合入后复测，用 before/after `missingLinks` diff 定位收敛入口缺口。
- CONTEXT.md 增补 GroupRuntimeHealth、TopologySnapshot、GroupTopologyReadiness 术语。

## Follow-up Work（非规范性）

| 优先级 | 项 |
|--------|-----|
| P0 | 实现 Projector + Logger + 触发点竖切 |
| P1 | 现场复测：M03 新装 → Syncing → Meeting → 对比快照 |
| P2 | UI 改读 Projector（消除 known gap） |
| P3 | ConferenceRuntimeHealth（独立 ADR） |
