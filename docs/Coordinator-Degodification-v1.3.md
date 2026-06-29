# Coordinator 去神化（De-godification）演进规划

> 版本：**v1.3**（Approved · 双人架构评审收口）
> 日期：2026-06-24
> 关联：[`GROUP-Membership-Debt-Remediation-v1.0.md`](GROUP-Membership-Debt-Remediation-v1.0.md)（Membership V1.2 基线）、[`会议组呼状态机重构方案.md`](会议组呼状态机重构方案.md)、[`PTT冷启动加速方案.md`](PTT冷启动加速方案.md)
> 现状证据：`TalkbackCoordinator.kt` 6295 行 / 348 方法

## 0. 主线定性（最重要）

本规划的真正目标**不是做 Conference 功能**，而是：

> 把系统从 **shared mutable state**（多个局部状态机互相猜测同一份可变 session 状态）迁移到 **view-driven architecture**（ownership + 不可变 snapshot + intent）。

Conference 只是**第一块试验田**。衡量第一刀成功的标志不是"支持多少人会议"，而是：

> **Coordinator 从此不再拥有 Conference participant 的任何可变状态。**

最近三个月修的四个大坑——Membership authority、Session BUSY yield、Floor SSOT、Conference prune——本质是同一个病：**SSOT 没有落到代码结构里**。本规划用结构（而非纪律）来根治。

## 1. 两条主线原则

1. **读写边界先行**：风险不在"谁能写数据"，而在"谁能依赖内部结构"。Gate 优先约束**直接读**，其次才是写。
2. **观测面（view）先行**：任何被拆出的子系统，对外只暴露**不可变 snapshot + intent 出口**；调用方不得逐字段 query，不得持有内部引用。接口（缝）必须在第一刀就定义，**延迟定义接口 = 连环返工**。

## 2. 四刀路线（PR-SPLIT-1 ~ 4）

| PR | 抽取目标 | 主要风险类型 | 核心 Gate |
|----|----------|--------------|-----------|
| **PR-SPLIT-1** | `ConferenceParticipantManager` | lifecycle complexity | 写侧零引用 + ReachabilityView 立缝 |
| **PR-SPLIT-2** | `MembershipManager` | **fan-in + split-brain（最高风险）** | **读侧零直读** |
| **PR-SPLIT-3** | `FloorManager` | fan-out（媒体耦合） | 依赖 1/2 的 view contract 成熟后再动 |
| **PR-SPLIT-4** | Coordinator → Facade | — | 自然结果，非目标 |

风险矩阵（决定 Gate 形态）：

| 模块 | 风险类型 | Gate 重心 |
|------|----------|-----------|
| Floor | fan-out（媒体复杂） | 写侧 + 输出隔离 |
| Conference | lifecycle complexity | 写侧 + lifecycle 护栏 |
| Membership | fan-in + split-brain | **读侧（消费方一律走 snapshot）** |

> 排序结论：**PR-SPLIT-2（Membership）比 PR-SPLIT-3（Floor）更危险**，因为 fan-in 高、任一消费方私留旧引用即 split-brain。Floor 的复杂度是 fan-out（媒体），但它是下游、被读得少，反而后做更稳。

## 3. PR-SPLIT-1 — Extract ConferenceParticipantManager

PR 名（强调性质，而非功能）：

```text
PR-SPLIT-1  Extract ConferenceParticipantManager (behavior preserving)
```

### 3.1 Manager 拥有的状态（per conference session）

```text
participants
inviteState
mediaState
everConnected
```

### 3.2 明确排除（关键纠偏）

`meshCompletedModules` **不进** Conference ownership。理由：

- 它挂在 `TalkbackSession`（见 `TalkbackSession.kt:34`），是 **GROUP + CONFERENCE 共享的拓扑收敛事实**；
- `completeGroupMesh(session)` 在大量组呼路径调用（Coordinator 内多处）；
- 把"拓扑收敛状态"误当"会话成员状态" = 结构性错误，未来必然 split-brain。

处置：`meshCompletedModules` 留在 **session / topology layer**，Manager 作为**输入**读取，不持有、不写入。

### 3.3 接口契约

```kotlin
interface ConferenceParticipantManager {
    fun onInviteAccepted(sessionId: String, moduleId: ModuleId)
    fun onMediaConnected(sessionId: String, moduleId: ModuleId)
    fun onLateJoin(sessionId: String, moduleId: ModuleId)
    fun applyPrune(sessionId: String, moduleId: ModuleId)   // 只执行，不判定
    fun snapshot(sessionId: String): ConferenceSnapshot
}
```

命名纪律：是 `applyPrune()`（应用外部已决定的驱逐），**不是** `pruneDeadPeer()`（自行判定）。**判定权属于 Membership/reachability 层，Manager 只 apply。** 严禁在 Manager 内长出 `peerDeadTimeout` / `iceClosedTimeout` / `pruneIfDisconnected()`——那会形成第三套 prune 策略，半年后必然与 Membership / UI 三方不一致。

Coordinator 只能：

```kotlin
participantMgr.onInviteAccepted(...)
participantMgr.onMediaConnected(...)
val snap = participantMgr.snapshot(sessionId)
```

Coordinator 禁止：

```kotlin
participants[...] = ...
conferenceRoster.remove(...)
onlineCount--
```

### 3.4 验收 Gate

| Gate | 内容 | 检验方式 |
|------|------|----------|
| **Gate1（写侧）** | Coordinator 对 participant 状态写引用 = 0 | grep：`participantStates[` / `conferenceParticipants[` / `groupMembers.remove(` / `conferenceRoster.remove(` 全 0 |
| **Gate2（ownership）** | Manager 拥有 `participants/inviteState/mediaState/everConnected`；Coordinator 只拿 `snapshot()` | code review + grep |
| **Gate3（回归）** | 三条集成测试 baseline 绿 → 拆后仍绿 | CI |

> 已删除：行数 `<6000` gate（Goodhart's law；行数是观测值，写进报告即可，不当及格线）。

Gate3 测试集（前两条已存在于 `ConferencePruneIntegrationTest.kt`）：

```text
conferencePrune_doesNotRemoveInvitedOnlyPeerWithStaleIce   (existing)
conferenceLateJoin_restoresRosterAfterMeshLinkAccepted     (existing)
conferenceRosterSplit_lateJoinRestoresThreeMembers         (new, members=2/3 split)
```

## 4. ReachabilityView —— 第一刀必须立的缝

`ConferenceParticipantManager` 把 reachability 当**输入**读，且 reachability 的持有者将在 PR-SPLIT-2 被抽成 `MembershipManager`。若第一刀直接读 Coordinator 具体字段，PR-SPLIT-2 一动就回头砸第一刀。故第一天定缝：

```kotlin
interface ReachabilityView {
    fun snapshot(sessionId: String): ReachabilitySnapshot
}

data class ReachabilitySnapshot(
    val online: Set<ModuleId>,
    val suspect: Set<ModuleId>,
    val evicted: Set<ModuleId>
)
```

设计要点：

- 暴露**不可变集合**，不提供逐 module query（防止调用方形成隐式内部依赖）；
- PR-SPLIT-1：Coordinator 实现 `ReachabilityView`；
- PR-SPLIT-2：`MembershipManager` 接管实现，**`ConferenceParticipantManager` 全程零改动**。

线程约束：Manager 与 view 均运行在 Coordinator 现有的**单线程 executor** 上，snapshot 不可变，**不引入第二个锁域**。

## 5. 护栏体系（三层，当前缺中间层）

```text
Invariant Layer          ← 当前缺失，本规划补齐
    ↓
Characterization Tests   ← 部分缺失，PR-SPLIT-1 前补齐
    ↓
Scenario Tests           ← 已有（集成测试）
```

当前测试覆盖的是"场景"，不是"规则空间"。"behavior preserving" 必须以 **FSM invariants** 为锚，否则不可证伪。

需补齐的 invariant（participant lifecycle）：

```text
INV-1  invite TTL 不得影响 media alive
INV-2  BUSY yield 不得影响 roster correctness
INV-3  conference ↔ group 互斥必须可逆
INV-4  会议结束冷却期（15s）不得影响 membership state
INV-5  prune 判定仅来自 reachability 层；Manager 仅 applyPrune
```

执行铁律：**只能安全重构你能刻画的东西。** PR-SPLIT-1 动手前先做 coverage 审计（TTL / BUSY / 互斥 / 冷却），缺口先补 characterization test，再拆。

## 6. PR-SPLIT-2 — Extract MembershipManager（读侧隔离优先）

顺序：**read-side isolation first, write-side second。**

Membership 是全系统 fan-in 最高节点（Floor / Conference / Media / transmit readiness 均读它）。写侧最简单，读侧才致命。

| Gate | 内容 |
|------|------|
| **读侧（主）** | `MembershipManager` 之外，对 roster 内部结构的直接读引用 = 0；消费方一律走 snapshot / view（`controlMemberModuleIds()` / `mediaMemberModuleIds()` / `canonicalMemberModuleIds()` 已是函数缝，收敛到 view 即可） |
| **写侧（次）** | Coordinator 不再写 `canonicalRoster` / `memberHash` / `rosterEpoch` |
| **接管** | `MembershipManager` 实现 `ReachabilityView`，替换 PR-SPLIT-1 的临时实现 |

## 7. PR-SPLIT-3 / PR-SPLIT-4

- **PR-SPLIT-3（FloorManager）**：依赖 1/2 的 view contract 成熟后再动；Gate 重心在写侧 + 输出隔离（capture/playback/audio routing/PTT timing/UI speaking 只接收 Floor 的 intent，不读 Floor 内部）。
- **PR-SPLIT-4（Coordinator Facade）**：Coordinator 退化为 Command Router + Single-thread Executor Owner + Event Dispatcher。**这是自然结果，不是独立目标**——前三刀完成即达成。

## 8. 全局不变量

1. 拆分必须是**状态所有权转移**，不是方法搬家。判据：Manager 拥有自己的 `private` 状态，对外只 snapshot + intent。
2. prune **判定**在 reachability/membership 层；Conference 只 `applyPrune`（apply 不 decide）。
3. `meshCompletedModules` 属拓扑层，GROUP/CONFERENCE 共享，不进任何 per-session Manager 的 ownership。
4. 任何跨层依赖只能通过 view 接口（不可变 snapshot），禁止持有他层内部可变结构引用。
5. 所有 Manager 共用 Coordinator 单线程 executor，不新增锁域。

## 9. 执行顺序（PR-SPLIT-1 落地步骤）

```text
Step 0  coverage 审计：TTL / BUSY / 互斥 / 冷却 现有覆盖盘点
Step 1  补 characterization test 覆盖缺口 + 新增 conferenceRosterSplit_lateJoinRestoresThreeMembers（先在当前代码 baseline 跑绿）
Step 2  定义 ReachabilityView + ReachabilitySnapshot，Coordinator 实现
Step 3  抽取 ConferenceParticipantManager（behavior preserving），Coordinator 改为 delegate
Step 4  跑 Gate1/2/3，全绿即第一刀完成
```

## 10. 评审纪要

| 项 | 初版 | v1.3 收口 |
|----|------|-----------|
| 第一刀目标 | 做 Conference 功能 | 验证 ownership+snapshot+intent 拆分模型 |
| 行数 gate | `<6000` | 删除（Goodhart） |
| meshCompletedModules | 进 Conference ownership | 留拓扑层，作输入 |
| Conference Gate2 | 拥有字段 | 写侧零引用 + ownership |
| Membership Gate | 写侧 | **读侧零直读（fan-in 控制）** |
| view 接口 | 后补 | **第一刀必须立（ReachabilityView）** |
| 护栏 | 场景测试 | 补 Invariant Layer |
| 第二刀 | Floor | **Membership（风险更高，先隔离读侧）** |

**Review Result: Approved — 以本 v1.3 为执行基准。**
