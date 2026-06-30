# Intent–Reality Consistency

*Asynchronous Completion Validation*

Talkback 的分布式运行时中，大量工作以异步方式完成（信令往返、ICE、SDP、定时器、重连）。共同风险不是某一子系统（Floor、Mesh、Conference）本身，而是 **Late Completion**：过去发起的操作在延迟后完成时，世界已经改变。本 ADR 建立 Runtime 一致性流水线与三条公理（R31–R33），规定异步完成事件何时有资格修改 **Runtime Facts**，以及 Facts 如何单向投影到 Digest、Presence 与 UI。

**决定原因**：孤儿 Floor、Syncing 卡住、Conference 误结束、Resume 扫错 Session 等近期问题，表面子系统不同，本质均为 **Physical 或信令层 completion 在未验证 Intent 的情况下修改了 Facts，或 Projection 反向写了 Facts**。须在 Owner commit 边界统一 Prevent，而非在各处用 Auto-Release 或 UI 补救。

## Runtime Consistency Pipeline

```
Intent
   │  (creates OperationToken)
   ▼
Physical Execution          ← ICE / DTLS / SDP / AudioTrack / Socket
   │                          （可完成；不得直接推出 Logical READY）
   ▼
Owner.commit(completion)    ← R31 validity + owner authority + version check
   │
   ├── invalid → Discard（不写任何 Fact；Physical → cleanup 或 re-validate）
   │
   ▼
Runtime Facts (Reality)     ← R32：仅对应 Owner 可写
   │
   ▼
Projection Emitters         ← 只读 Facts；不得 mutate Facts
   │
   ▼
Digest / Presence / UI
```

| 层 | 受 R31？ | 示例 |
|----|----------|------|
| **Intent** | 产生 Token | `PTT_DOWN`、`MembershipChange`、`JoinPlan` |
| **Physical Execution** | 否 | ICE、DTLS、SDP、AudioTrack |
| **Fact Mutation** | **是（唯一入口）** | Floor ownership、Membership、mesh actual、Session disposition |

**Physical completion 永远不是 Fact completion。** ICE 可 `CONNECTED`，但若绑定 Intent 已失效，不得推出 mesh ready、directory ready、participant connected 或 floor owner valid。

## 核心不变量

### R31 — Asynchronous Completion Validation

> Every asynchronous completion MUST validate that the intent on which it depends is still current before mutating any shared state. Otherwise the completion SHALL be discarded.

所有异步完成事件，在修改共享状态之前，必须验证其依赖的 Intent 仍有效；若 Intent 已失效，**必须丢弃，不得写入任何共享状态**。

**不得写入**（含但不限于）：`protocolFloorOwner`、membership facts、mesh actual、session disposition、以及任何会进入 digest/gossip 的对外 durable 字段。

**R31 挂载点**：各 **Owner 的 mutation API**（例如 `FloorFsm.commitGrant(completion, token)`）。ICE/SDP 回调不得直接 commit Facts，只能上报 completion 给 Owner。

**版本校验**：除 `OperationToken.validity` 外，completion 携带的 **domain version** 须与 token 绑定时的权威 version 一致，防止同 validity 下旧 version 漏网。

**禁止用 Timeout 合法化非法 Completion**：

```
Late Grant → 不得 Write Owner → Timeout → Release   （禁止）
Late Grant → Discard → 结束                         （正确）
```

Timeout **永远不能**使未通过 R31 的 completion 变合法。

### R32 — State Ownership

> Shared state SHALL only be mutated by the owner of that state.

R32 保护的是 **Runtime Fact**（事实），不是字段名。`protocolFloorOwner`、`memberViews`、`digest` 等是 **Representation**，不是 Owner。

| Runtime Fact | Owner |
|--------------|-------|
| Floor ownership | Floor FSM |
| Session membership | Membership Authority |
| Desired / actual mesh topology | Mesh Planner |
| Session disposition | Session Lifecycle FSM |
| Activity stack | Runtime Coordinator |

> **Projection emitters SHALL NOT mutate runtime facts.**

Digest、Presence、UI Snapshot 不是 Owner，不是 Facts，只是 Facts 的只读投影。

```
Reality → Projection Emitter → Digest / Presence / UI
```

禁止：

```
Completion → Digest →（反向影响）Floor / Membership
```

### R33 — Physical ≠ Logical

> Physical completion SHALL NOT directly promote logical state. Fact mutation requires Owner commit validated by R31.

Physical 层不受 R31「禁止写 Fact」的替代解释所放宽：物理资源可完成、可 teardown、可在 **re-validate 当前 Intent 后** 由 Planner/Owner 决定 reuse，但 **不得**因 `ICE_CONNECTED` 等物理就绪跳过 epoch/token 校验而提交 Facts。

> Stale physical resources must not mutate shared facts. Physical resources may be reused only after re-validation against the current Intent.

## OperationToken

> **OperationToken does not introduce an additional version space. It provides lifecycle semantics over the existing authoritative version maintained by each domain.**

OperationToken 是 **Runtime 语义对象**，不是协议编号。各领域保留既有权威版本（如 `floorVersion`、`rosterEpoch`、`meshGeneration`）；Token 对其进行生命周期封装。

```
OperationToken {
  domain      // FLOOR | MEMBERSHIP | MESH | CONFERENCE | RESUME | …
  identity    // sessionId, moduleId, endpointId 等领域唯一对象
  version     // 映射该域已有权威 version；ADR 不统一命名
  validity    // VALID | INVALIDATED | COMPLETED（Runtime 本地，非协议字段）
}
```

生命周期（终态互斥）：

```
CREATE → VALID ──┬── COMPLETED
                 └── INVALIDATED
```

`validity` 是 R31 的校验面；`version` 来自各域权威机制，ADR 不规定 `epoch`/`seq` 的统一形状。

## Relationship with ADR-0004

ADR-0007 治理 **completion 是否有资格修改 Facts**；ADR-0004 治理 **Facts 已合法提交之后，Runtime 是否兑现**。

> **ADR-0004 applies only to validated completions.**

Floor 生命周期三阶段：

```
Intent → Asynchronous Completion ──[R31]── invalid → Discard
                              └── valid → Reality Mutation (protocolFloorOwner, …)
                                              → Runtime Execution (captureON / uplink)
                                              → [ADR-0004] success | timeout → FLOOR_RELEASE
```

- **ADR-0007**：Prevent（事前约束）
- **ADR-0004**：Execution Recovery（事后保障；仅适用于已通过 R31 的 grant）

获权超时（R12–R17）从 `GRANT_APPLIED` 起算，前提是 grant 已通过 Owner commit 与 R31。

## Architecture Dependency

```
ADR-0001 Runtime Model
        │
        ▼
ADR-0004 Floor Acquire Timeout
        │
        ▼
ADR-0007 Intent–Reality Consistency
        │
        ▼
ADR-0008 Group Runtime Health Projection (read model, v1)
```

- **ADR-0001**：Runtime 对象（Activity、Disposition、Authority）
- **ADR-0007**：Facts 如何安全演化（R31–R33、Pipeline、OperationToken）
- **ADR-0004**：Valid grant 进入 Reality 之后的恢复策略（Auto Release）

## Considered Options

- **Intent–Reality 仅作脚注，各 ADR 各管各的**：易与 0004 冲突；拒绝。
- **Late Grant 靠 Auto-Release 补救**：先污染再 release，UI 仍闪 Speaking；拒绝（违背 R31 Prevent）。
- **OperationToken 替代 floorVersion/rosterEpoch**：第二套编号；拒绝。
- **Digest 作为独立 Owner**：易绕过 R31 直写；拒绝。
- **Physical 完成即 Logical READY**：Zombie Connection；拒绝（R33）。
- **Owner commit + R31/R32/R33 + OperationToken 封装既有 version（接受）**。

## Follow-up Work（非规范性）

以下项 **减少** stale completion 的产生，但 **不是 R31 正确性所必需**。即使网络侧零 Cancel，只要 Owner commit 执行 R31，系统仍正确。

| 优先级 | 项 | 说明 |
|--------|-----|------|
| P1 | Authority-side invalid request rejection | Requester `PTT_UP` 后 authority 不再 grant 在途 request |
| P2 | Membership → MeshPlanner reconciliation | `MembershipChanged(epoch)` 为 mesh 唯一入口 |
| P3 | Participant read model / Speaking UI | UI 读统一投影，不单独修协议 |

**首刀实现建议**：Floor `FloorOperationToken` 竖切（Owner commit 边界 + 回归：Late Grant discard，不写 `protocolFloorOwner`/digest）。

## Consequences

- 所有异步 completion 入口须收敛到 `Owner.commit(...)` 或等价门面；禁止 handler 直写 Facts 或 digest。
- `FloorAcquireReleaseWatchdog`（ADR-0004）仅挂在 **validated grant** 之后。
- CONTEXT.md 增补 Late Completion、OperationToken、Runtime Fact、Projection Emitter 等术语。
- Mesh、Conference、Resume 迁移时复用本 Pipeline，不另发明一致性规则。
