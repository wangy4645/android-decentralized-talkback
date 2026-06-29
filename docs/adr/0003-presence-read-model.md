# Presence 双层读模型

Presence 不是第三套 Domain 系统，而是 Session 域与 Module Runtime 的只读投影层。分 `ModulePresenceSnapshot`（高频、本端执行态）与 `SessionPresenceSnapshot`（中频、协议态），供 UI / Admission / Monitor 统一读取，避免逻辑散落拼装。

**决定原因**：Floor、ICE、Mixer、Stack、Membership 各自维护权威态时，UI 若四处订阅会导致语义扩散与时间尺度冲突（ICE ms 级 vs roster 秒级）。合并为单一 Snapshot 会职责污染；双层投影各从其权威源生成。

## 核心不变量

- **R9（只读）**：Presence Snapshot 纯投影，不参与任何决策，无副作用。
- **R10（分源）**：Module Presence 与 Session Presence 来自不同权威源，禁止交叉写入。Module 侧写入 Mixer/采集闸门/ICE/Stack；Session 侧写入 SessionManager/信令收敛。
- **R5（经 Module Presence）**：`activeCaptureEndpoint == stackTop.actingEndpointId` 仅在 ModulePresenceSnapshot 上校验。
- **R11（执行不超前）**：`localUplinkGrant` 不得在 `protocolFloorOwner` 授予本端之前为 true（现有 `INVARIANT_F1_BREAK` 的正式化）。
- **不对称收敛**：获权有界等待、失权零窗口；获权超时语义、标定与 `effective_timeout` 见 ADR-0004（R12–R15）。

## 结构

```
SessionPresenceSnapshot（per Session）
    membership, protocolFloorOwner, epoch, disposition
              │
              ▼
ModulePresenceSnapshot（per Module）
    localUplinkGrant, activeCaptureEndpoint, iceByPeer, stackTop, speaking
              │
              ▼
        UI / Admission / Monitor（只读）
```

## Floor 双层字段

| 层 | 字段 | 权威源 | 读者 |
|----|------|--------|------|
| Session | `protocolFloorOwner` | `session.floor.owner()` / 信令 | UI 谁在说话、对端可见态 |
| Module | `localUplinkGrant` + `activeCaptureEndpoint` | 采集闸门 + Mixer + AudioTrack | 采集路由、R5 |

获权：`protocolFloorOwner == local` 且 `!localUplinkGrant` → UI「夺权中…」。失权：收到他人 `FLOOR_GRANTED` / `FLOOR_PREEMPTED` → 同步 `stopCapture`，无宽限。

## Considered Options

- **A：保持散落**：Admission/UI 拼装条件爆炸；拒绝。
- **B：单一 PresenceSnapshot**：Module 与 Session 时间尺度与 floor 语义冲突；拒绝。
- **两层同名 floorOwner**：靠注释区分，易与 INVARIANT_F1 混淆；拒绝，改用 `protocolFloorOwner` / `localUplinkGrant`。
- **B+：双层 Snapshot + 分名字段**：接受。

## Consequences

- `TalkbackSessionSnapshot.floorOwnerKey` 重构目标名为 `protocolFloorOwnerKey`；Module 层新增 `localUplinkGrant` 投影字段。
- 获权超时从 `GRANT_APPLIED` 起算、标定与三阶段落地见 `docs/adr/0004-floor-acquire-timeout.md`。
- Conference 等无 Floor 场景：Session Snapshot 省略 floor 字段即可。
