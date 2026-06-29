# Talkback Runtime Model

Talkback 将本端通信运行时拆为三层所有权：**资源属 Module**（Activity 栈、Media、ICE、Capture）→ **身份属 Endpoint**（Floor Owner、PTT、信令主体）→ **业务对象属 Session**（lifecycle、disposition、membership）。Activity 栈决定前台执行上下文，不直接驱动媒体；所有媒体/UI/QoS 模块只读 `Session.disposition`。

**决定原因**：Module 级 uplink/ICE/Capture 与 Endpoint 级 Floor 身份并存；Unicast 抢占 Group、未来 Emergency 多级抢占、以及异步恢复无法用「per-endpoint 栈 + Session 布尔挂起」表达。Per-Module 单栈避免「两栈各认为 ACTIVE、Mixer 只能选一个」的双真相问题。

## 核心不变量

- **R1（引用）**：Activity Frame 只保存 `sessionId`，绝不持有 `TalkbackSession`。恢复时 `SessionManager.find(sessionId)`；不存在则丢弃该帧并继续 pop。
- **R2（读写）**：媒体、UI、QoS、同步快照只读 `Session.disposition`；Activity 栈不直接操作媒体。
- **R3（恢复）**：恢复只能由当前栈顶活动发起；须验证 Session 仍存在，且 Preemption Token 仍有效（持有者 = 栈顶）。
- **R5（单活跃活动）**：同一 Module 任意时刻至多一个 Active 前台活动；`activeCaptureEndpoint` 必须等于栈顶 Frame 的 `actingEndpointId`（Idle 时均为空）。

## Activity Frame

```
ActivityFrame(
  activityType,
  sessionId?,
  actingEndpointId,   // 身份：谁在用媒体/Floor
  requestedBy,        // 谁发起的请求；V1 可与 acting 相同；系统/Watchdog 场景可分离
  preemptReason,
  autoResume
)
```

## Session Disposition 状态机

```
ACTIVE ──suspend──▶ SUSPENDED ──resume──▶ RESUMING ──mediaReady──▶ ACTIVE
   │                                              │
   └──────────── terminating ────────────────────▶ TERMINATING ──▶ TERMINATED
```

Floor Authority 在 Group PTT 内可另有 `YIELDED` 等子态，与 Session 级 disposition 正交。

## V1 → V2 渐进对齐

| 目标 | V1 现状 |
|------|---------|
| Per-Module `ActivityFrame` 栈 | 栈概念缺失；`suspendedForUnicast` 布尔 |
| `actingEndpointId` / `requestedBy` | 隐式于当前操作 Endpoint |
| `SessionDisposition.RESUMING` | 无；挂起/恢复视为同步 |
| `PreemptionToken` | 无 |

V1 行为可保持不变；接口与字段先预留。

## Considered Options

- **Per-Endpoint Activity 栈**：与 Module 级 Capture/ICE 资源冲突，易产生双真相；拒绝。
- **改名为 Module Runtime**：易误解 Floor 也为 Module 级；采用 Runtime Model + 所有权三分法；接受。
- **Suspended 直接回 Active**：媒体未就绪即对外宣称恢复；拒绝。
- **Session 强引用进 Frame**：生命周期分叉；拒绝（R1）。

## Preempt Reason 与 Endpoint Priority

正交、不共享枚举与 FSM，仅共享 `actingEndpointId` 作为关联键。映射单向：Preempt Reason 可触发本端 Floor 副作用；Endpoint Priority（分布式 Floor 裁决）不驱动 Activity 栈。Floor 抢麦成功不必 push Frame；push Frame 不必经过 Floor（如 Unicast）。

## Consequences

- 分散的 `prepareForUnicastOutgoing` / `isBusy` 等应收敛为 Module 级 Foreground Activity Admission API。
- 单呼路径 `channelManager.join` 迁出 Runtime 门控，仅作 Session Origin 副作用。
- Emergency 等多级抢占靠栈 + Token 表达，无需改 disposition 枚举。
