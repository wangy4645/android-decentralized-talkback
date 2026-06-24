# Distributed Talkgroup Membership

> 版本：**V1.2**（Membership 基线 · Final Approved）  
> 日期：2026-06-18  
> 评审：V1.2 三项修订已纳入；Proceed with P0 **Membership Stabilization**  
> 关联：[`会议组呼状态机重构方案.md`](会议组呼状态机重构方案.md)、[`PTT媒体面架构-选举锚点方案.md`](PTT媒体面架构-选举锚点方案.md)、[`现场测试方案与执行手册.md`](现场测试方案与执行手册.md)  
> 现场证据：`logs-m01-offline-20260618-165102`

## 0. 架构定位

Talkback 分层（自底向上）：

```text
Discovery Layer          — HELLO、gossip、dialablePeers
Membership Layer         — 本方案（Weakly Consistent Membership）
Topology Layer           — Mesh / Anchor、AnchorElection
Media Layer              — WebRTC、ProgramAudioBus
Floor Layer              — Floor CAS、仲裁
Application Layer        — PTT UI、Conference
```

**模型定性**：**Weakly Consistent Membership**（类似 Discord Voice + MANET PTT），允许短时间视图不一致，通过 Digest + RESYNC 在数秒～十几秒内收敛。不是 Zoom 式强中心 roster。

**设计原则**：不推翻 Anchor、Floor、ProgramAudioBus、Conference、Failover；补 GROUP 缺失的 Membership 生命周期。

---

## 1. 背景

M01 下线现场：

- M02：`Anchor failover epoch=101` 已触发，UI 长期 **Syncing**
- M02：周期性 `Pairwise re-invited M01`（对已离线节点）
- M03：仅 HELLO，无 GROUP 收敛

**根因不是 Mesh vs Anchor**，而是 **Membership 层缺失**（Conference 有 roster 治理，GROUP 没有）。

---

## 2. 阶段划分

| 阶段 | 名称 | 内容 |
|------|------|------|
| **Phase 0** | Membership Stabilization | Reachability、SUSPECT/EVICTED、rosterEpoch、control/media 拆分、failover 不 prune |
| **Phase 1** | Topology Synchronization | HELLO Digest、GROUP_RESYNC_REQUEST → Anchor 重发 INVITE |
| **Phase 2** | Anchor Optimization | `CHANNEL_ANCHOR_THRESHOLD=99` 全 Mesh A/B；通过后恢复 Anchor |
| **Phase 3** | HalfHot / SFU 预留 | 启用 `meshGeneration`；Conference 半热备等 |
| **Phase 4** | CTA Refactoring | `GroupMembershipManager` → `MembershipProvider` |

CTA **延迟到 Phase 4**；P0 逻辑落在 `TalkbackCoordinator` + 小函数。

---

## 3. 目标与非目标

### 3.1 目标（P0 验收）

| # | 场景 | 通过标准 |
|---|------|----------|
| A | M01 讲话 | M02、M03 都能听 |
| B | M02 讲话 | M01、M03 都能听 |
| C | M01 拔电/Stop | M02/M03 **不长期 Syncing**；EVICT 前（≤30s）若 ICE 已死则 readiness 不等待 M01 |
| D | M01 恢复上线 | 自动重新加入 talkgroup |

### 3.2 非目标

- Phase 4 前引入完整 CTA
- TOPOLOGY_PULL / SNAPSHOT（16+ 节点后再议）
- HELLO 携带全量 roster（避免 8×8×300B 广播风暴）

---

## 4. 成员视图（核心模型）

**Discovery 与 Membership 解耦**：`dialablePeers` **仅**用于 Bootstrap、Invite 目标、AnchorElection。**不得**参与 readiness / transmit / ProgramAudioBus / Floor。

```text
canonicalRoster
    └── membershipState: ONLINE | SUSPECT | EVICTED

activeMemberPredicate = ONLINE OR (SUSPECT AND ICE alive)

controlMembers      = { m | activeMemberPredicate(m) }
mediaMembers        = { m | activeMemberPredicate(m) }   // P0 与 control 同集；Phase 3 可拆 relay 语义
transmitMembers     = mediaMembers 经拓扑策略展开后的 module 集合
dialablePeers       = Discovery 层（独立）
```

| 视图 | 定义 | 用途 |
|------|------|------|
| **canonicalRoster** | `groupMembers`，含 SUSPECT，不含 EVICTED | INVITE payload.members、Floor 恢复窗口 |
| **controlMembers** | ONLINE，或 SUSPECT 且 ICE alive | `isChannelMediaReady`、PTT 门控、RESYNC 决策 |
| **mediaMembers** | 与 controlMembers 相同（P0） | ProgramAudioBus、floor 音频同步 |
| **transmitMembers** | 拓扑层从 mediaMembers 派生 | `isSessionTransmitReady`、ICE 要求集合 |
| **dialablePeers** | HELLO/发现 | bootstrap、invite、选举 **仅此** |

**V1.2 修订**：controlMembers 含 SUSPECT(ICE alive)，避免 HELLO 短暂丢失但 ICE 仍通时 UI Syncing 与媒体不一致。

```kotlin
fun isIceAlive(moduleId: String): Boolean  // CONNECTED | COMPLETED

fun isActiveMember(session, moduleId): Boolean  // ONLINE || (SUSPECT && isIceAlive)

fun controlMemberModuleIds(session): Set<ModuleId>  // == mediaMemberModuleIds P0
fun mediaMemberModuleIds(session): Set<ModuleId>
fun canonicalMemberModuleIds(session): Set<ModuleId>
```

---

## 5. Reachability 状态机

| 状态 | 进入 | canonicalRoster | control / media | 退出 |
|------|------|-----------------|-----------------|------|
| **ONLINE** | 默认 / 恢复 | 保留 | 计入 | → SUSPECT |
| **SUSPECT** | 见下方超时公式 | 保留 | ICE 活则**计入** | → ONLINE / → EVICTED |
| **EVICTED** | SUSPECT 超时 | **移除** | 不计 | 仅新 INVITE 加入 |

### 5.1 超时可配置（默认）

```text
进入 SUSPECT：
  ICE CLOSED/FAILED/DISCONNECTED 持续 ≥ groupMemberSuspectIceMs
  或 HELLO 缺失 ≥ groupMemberSuspectHelloMs（默认 3 × helloIntervalMs）

SUSPECT → EVICTED：
  默认 groupMemberEvictSuspectMs = 27_000（25~30s，进入 SUSPECT 后计时）
```

推荐公式（与 HELLO/ICE 联动）：

```text
groupMemberSuspectHelloMs = 3 × helloIntervalMs        // 例：9s
groupMemberSuspectIceMs   = 2 × iceDisconnectedGraceMs // 例：10s
groupMemberEvictSuspectMs = 27_000                       // 可调 25_000~30_000
```

**V1.2 修订**：EVICT 放宽至 25~30s，减少 WiFi 切 AP、短暂断网误踢；M01 真下线时 ICE 先断，controlMembers 立即不再等待 M01，readiness 不必等 EVICT 完成。

---

## 6. Epoch 与 Digest

### 6.1 rosterEpoch

- 类型：`Long`，初始 `1`，**单调 +1**
- 触发：**仅 EVICTED**（`removeGroupMember`）；禁止 failover 直接 prune 触发
- **不用**现有 `rosterEpochMs`（时间戳）

### 6.2 anchorEpoch

- 已有；failover / handover 递增

### 6.3 meshGeneration（预留，P0 不用）

```kotlin
data class TopologyDigest(
    val rosterEpoch: Long,
    val anchorEpoch: Long,
    val meshGeneration: Long = 0L  // P0 恒为 0；Phase 3 HalfHot/SFU 启用
)
```

P0：`rosterEpoch` 或 `anchorEpoch` 变化 → remesh；不维护 meshGeneration 递增。

### 6.4 memberHash（Phase 1）

**必须基于 canonicalRoster**（不能含 controlMembers / 本地 SUSPECT 差异）：

```text
memberHash = hash32(
    channelId +
    rosterEpoch +
    sorted(canonicalRoster moduleIds)
)
```

**V1.2 修订**：memberHash **不含 anchorEpoch**。Roster 成员集合变化用 hash + rosterEpoch；Anchor 切换单独靠 HelloPayload 已有 `anchorEpoch` / `primaryModuleId` 与 `resolveSplitBrainFromHello` 处理，避免同一 roster 因 failover 触发多余 RESYNC 风暴。

---

## 7. Phase 0 — Membership Stabilization

### 7.1 reconcileGroupMembership（统一入口）

触发：ICE 变化、HELLO tick、failover 后 `scheduleReconcile`、周期性 mesh retry。

```text
评估各 member → 更新 ONLINE/SUSPECT/EVICTED
EVICTED → removeGroupMember → rosterEpoch++
SUSPECT → 不 remove canonicalRoster
```

### 7.2 removeGroupMember（仅 EVICTED）

1. 从 `groupMembers` / `memberModules` 移除
2. release engine / remotePeers / meshCompleted
3. `bumpRosterEpoch("member_evicted")`
4. `completeGroupMesh` / `updateSessionReceivePlayback`

### 7.3 handleAnchorFailover — **不 prune roster**

```kotlin
// 仅对 failed anchor：
markMembershipSuspect(failedAnchorId)
releaseGroupEngine(failedAnchorId)
switchAnchor(newAnchor); bump anchorEpoch
if (local == newAnchor) completeGroupMesh()
else offerGroupMeshJoin(newAnchor)
scheduleReconcile("anchor_failover")  // 不 removeGroupMember
```

**禁止** failover 时 `pruneUnreachableFromRoster()` 或 `rosterEpoch++`（避免 M03 被误踢、roster 震荡）。

failed anchor 的 EVICT 交给 SUSPECT 定时器。

### 7.4 resolveSplitBrainFromHello — member remesh

`payload.anchorEpoch > session.anchorEpoch` 且本地非新 anchor：

```kotlin
applyRemoteAnchorView(...)
offerGroupMeshJoin(session, session.anchorModuleId!!)
```

### 7.5 读点替换

| 读点 | 新逻辑 |
|------|--------|
| `isSessionTransmitReady` GROUP | `transmitMembers`（来自 mediaMembers + 拓扑） |
| `ProgramAudioBus` fan-out | `mediaMembers` |
| `pairwiseReconnect` | 跳过 EVICTED；**不对** failed anchor 无限 re-invite（SUSPECT/EVICT 后停止） |
| `missingPeers` invite | `dialable - canonicalRoster`（discovery 仅用于补 invite） |

### 7.6 bumpRosterEpoch 唯一入口

```kotlin
bumpRosterEpoch(session, reason)  // 仅 member_evicted | applyGroupRoster_authority
```

---

## 8. Phase 1 — Topology Synchronization

### 8.1 HELLO 携带 TopologyDigest

在现有 `HelloPayload`（已有 anchorEpoch/primary/backup/channelId）上增加：

```json
{ "rosterEpoch": 12, "meshGeneration": 0, "memberHash": 2882345678 }
```

**不在 HELLO 带全量 roster**（Digest-only，不一致再 RESYNC）。

### 8.2 发现不一致

```kotlin
if (remoteDigest != localDigest(session)) {
    sendGroupResyncRequest(to = session.anchorModuleId ?: resolveBootstrapPrimary())
}
```

**禁止**默认发给 `payload.moduleId`（可能是无权威的 member）。

### 8.3 GROUP_RESYNC_REQUEST

Anchor / bootstrap primary 收到后：

1. 对 requester 重发 `GROUP_INVITE`（snapshot = payload.members + epochs）
2. 必要时 `completeGroupMesh`
3. **不**新建独立 SNAPSHOT 协议；INVITE 即 snapshot

---

## 9. Phase 2 — Anchor Optimization（诊断）

```kotlin
const val CHANNEL_ANCHOR_THRESHOLD_MODULES = 99  // 临时：强制 MESH
```

| Mesh A/B 结果 | 结论 |
|---------------|------|
| 四通 + M01 下线恢复 | P0 正确；再测 Anchor |
| 仍失败 | 继续 Phase 0 |

---

## 10. 不变量

1. P0：`controlMembers == mediaMembers`（均满足 `activeMemberPredicate`）⊆ `canonicalRoster`
2. `dialablePeers` 不参与 control/media/transmit（Discovery 独立）
3. EVICTED ∉ `groupMembers` / payload.members
4. `rosterEpoch` 只增；**仅 EVICT** 递增
5. failover **不** remove canonicalRoster、**不** bump rosterEpoch
6. `memberHash` = f(channelId, rosterEpoch, canonicalRoster)；anchor 变更走 `anchorEpoch` 字段
7. P0 `meshGeneration == 0`

---

## 11. 测试

### 11.1 单元

- SUSPECT + ICE alive ∈ controlMembers 且 ∈ mediaMembers
- dialable=false 但 ICE alive + 非 EVICTED：仍 ∈ controlMembers
- failover 不 bump rosterEpoch
- EVICT → rosterEpoch+1

### 11.2 集成

- 三节点 → M01 ICE dead → M02 readiness 不等待 M01（EVICT 前即可）
- digest 不一致 → RESYNC → INVITE

### 11.3 现场 TC-DTM（Distributed Talkgroup Membership）

| 编号 | 场景 | 标准 |
|------|------|------|
| TC-DTM-01 | A/B 四通 | 见 §3.1 |
| TC-DTM-02 | M01 拔电 | M02/M03 可 PTT，非长期 Syncing |
| TC-DTM-03 | M01 断网 10s 恢复 | 不 EVICT，不断音 |
| TC-DTM-04 | M01 断网 ≥35s | EVICT（~27s）后 M02+M03 正常；M01 回来自动 join |

---

## 12. 实施计划（代码）

### Phase 0 — Membership Stabilization（3~5 天）

| # | 任务 |
|---|------|
| 0.1 | `GroupMemberReachability` + session maps + 超时 config |
| 0.2 | `control/media/canonicalMemberModuleIds` + `isIceAlive` |
| 0.3 | `reconcileGroupMembership` + SUSPECT/EVICTED 定时器 |
| 0.4 | `removeGroupMember` / `applyGroupRoster`；`bumpRosterEpoch` 唯一入口 |
| 0.5 | `handleAnchorFailover` 改为不 prune；failed → SUSPECT |
| 0.6 | transmit/bus/readiness 读点替换 |
| 0.7 | `resolveSplitBrainFromHello` member remesh |
| 0.8 | 单元 + 集成测试 |

### Phase 1 — Topology Synchronization（1~2 天）

| # | 任务 |
|---|------|
| 1.1 | `TopologyDigest` + HelloPayload 扩展（meshGeneration=0） |
| 1.2 | `memberHash`（channelId + rosterEpoch + canonicalRoster） |
| 1.3 | `GROUP_RESYNC_REQUEST` → anchor；handler 重发 INVITE |

### Phase 2 — Anchor Optimization（1 天）

全 Mesh 诊断 + TC-DTM 现场 + 恢复阈值。

### Phase 3~4（后续）

meshGeneration 启用；`GroupMembershipManager`；CTA。

---

## 13. 评审纪要

### V1.1 → V1.2 最终修订（Approved）

| 项目 | V1.1 | **V1.2 基线** |
|------|------|---------------|
| controlMembers | 仅 ONLINE | **ONLINE + SUSPECT(ICE alive)** |
| memberHash | 含 anchorEpoch | **仅 channelId + rosterEpoch + canonicalRoster** |
| SUSPECT→EVICT | ~10~15s | **25~30s（默认 27s）** |

**Review Result: Final Approved — Talkback 2.x Membership 基线**

**Recommendation:** Proceed Phase 0 → Phase 1 → Phase 2 A/B → 现场 TC-DTM。

---

## 14. 文档映射（实施后）

- `现场测试方案与执行手册.md` — 增加 TC-DTM
- `architecture-review-summary.md` — Membership 层红线
- `PTT媒体面架构-选举锚点方案.md` — Phase 2 诊断阈值说明
