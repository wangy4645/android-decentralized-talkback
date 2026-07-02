# Runtime Ownership Matrix

> P0 产出 + RO-0 增补 Public API 列。基于 `TalkbackCoordinator.kt`、`TalkbackSession.kt`、`GroupMembershipSupport.kt`、`ConferenceParticipantManager.kt`、`ConferenceMembershipReducer.kt` 静态扫描（2026-07-02）。
>
> **图例**：`MULTI` = 多个 Writer，标红为 regression 高风险。  
> **Public API 详情**：`runtime-public-api.md`

## Facts vs Cache 分类

| 状态 | 类型 | 能否作为业务决策依据 |
|------|------|---------------------|
| `groupMembers` | **Fact** | 是 |
| `floorAuthorityModuleId` | **Fact** | 是 |
| `floorAuthorityEpoch` | **Fact**（世代戳） | 是 |
| `rosterEpoch` / `anchorEpoch` | **Fact**（世代戳） | 是 |
| `participants.invite` | **Derived Fact** | 是（仅 Owner 写） |
| `participants.media` | **Derived Fact** | 是（仅 Owner 写） |
| `membershipStateByModule` | **Derived Fact** | 是（Membership） |
| `signalPeersByModule` | **Cache** | **否** |
| `discoveredByModule`（当前实现） | **Cache** | **否** |
| `remotePeersByModule` | **Cache** | **否**（ICE 会话级） |
| `meshCompletedModules` | **Cache** | **否** |
| `leftMemberEndpoints` | **Derived Fact**（软离开） | 是（仅 Membership） |

---

## 四个 Runtime 边界

```
Membership Runtime          Transport Runtime (signaling)     Media Runtime              Conference Runtime
├── groupMembers            ├── signalingTransport (*)        ├── remotePeersByModule    ├── participant.invite
├── floorAuthorityId        └── transportEpoch (待引入)       ├── meshCompletedModules   ├── participant.media
├── rosterEpoch / anchorEpoch                                   ├── iceState (qosMonitor)  └── leftMemberEndpoints
├── membershipStateByModule                                     └── mediaTopology
└── pendingInviteeEndpoints

(*) 目标态：TransportRegistry；当前未实现，由 signalPeers + discovered 混用代替
```

Floor Resolver **目标态**只允许跨 Runtime 读取：

1. `authorityId` ← Membership
2. `signalingTransport(authorityId)` ← Transport Registry

---

## 完整 Ownership 表

| State | Owner | Public API（写） | Public API（读） | Direct Write | Writer（当前） | Lifetime | Consistency | 风险 |
|-------|-------|-----------------|-----------------|--------------|---------------|----------|-------------|------|
| `groupMembers` | Membership | `applyGroupMembersList` / `evict` / `promote` | `canonicalRosterEndpoints` | **Forbidden** | Support + Coordinator 直写 2 处 | Session | Strong | `MULTI` |
| `floorAuthorityModuleId` | Membership | `applyAuthorityBinding` (TARGET) | `authorityModuleId` (TARGET) | **Forbidden** | Coordinator 6+ 处 | Session | Strong | `MULTI` |
| `floorAuthorityEpoch` | Membership | `bumpFloorAuthorityEpoch` | `floorAuthorityEpoch` | **Forbidden** | bump 仅清 remotePeers | Session | Strong | 未清 transport |
| `rosterEpoch` | Membership | `bumpRosterEpoch` | `rosterEpoch` | **Forbidden** | Support only | Session | Strong | 低 |
| `membershipStateByModule` | Membership | `markOnline/Evicted/Suspect` | `membershipState` | **Forbidden** | Support | Session | Strong | 低 |
| `pendingInviteeEndpoints` | Membership | `promoteToGroupRoster` | `pendingInvitees` | **Forbidden** | Support + Coordinator | Session | Strong | 中 |
| `participants`（GROUP） | Media | `onIceStateChanged` (TARGET) | Projector | **Forbidden** | ICE + Coordinator | Session | Eventual | `MULTI` |
| `participants`（CONFERENCE） | Conference | Manager + Reducer 事件 | `snapshot` / Projector | **Forbidden** | Manager + Reducer + ICE | Session | Eventual | `MULTI` media |
| `leftMemberEndpoints`（session） | —（废弃） | — | — | **Forbidden** | Coordinator | Session | Strong | 双存储 |
| `leftMemberEndpoints`（manager） | Conference | `applyPrune` / `evictInvitee` | `leftMemberEndpoints` | **Forbidden** | Manager + Reducer | Session | Strong | split-brain |
| `signalingTransport` | Transport | `onVerifiedHello` 等 (TARGET) | `TransportRegistry.resolve` | N/A | 未实现；signalPeers 代替 | Process | Eventual | Cache 当 Fact |
| `signalPeersByModule` | SignalLayer | `rememberSignalPeer` INTERNAL | — | **Forbidden** 决策读 | 10+ 协议 | Process | Weak | **Cache 当 Fact** |
| `discoveredByModule` | （废弃） | — | — | **Forbidden** | gossip + rememberSignalPeer | Process | Weak | 同源污染 |
| `remotePeersByModule` | Media | `bindMeshPeer` (TARGET) | `meshPeer` / ICE send | **Forbidden** | Coordinator 20+ 处 | Session | Eventual | `MULTI` |
| `meshCompletedModules` | Media | `markMeshCompleted` | `isMeshCompleted` | **Forbidden** | Coordinator 6 处 | Session | Eventual | 低 |
| `stateSync.presence` | Presence（ADR-0003） | HELLO / gossip 管道 | `presence` | INTERNAL | stateSync | Process | Eventual | → Registry 候选 |

---

## 多 Writer 标红汇总

| Map / 字段 | Writer 数量 | 主要冲突 |
|-----------|------------|---------|
| `signalPeersByModule` | **12+** | 任意信令包 → Floor 路由输入被污染 |
| `discoveredByModule` | **3 路径** | gossip + rememberSignalPeer 同源 |
| `remotePeersByModule` | **20+** | Mesh/Conference/Group 各写各的 |
| `groupMembers` | **2** | Support vs Coordinator 直写 |
| `participants.media` | **2+** | ICE 回调 vs ConferenceReducer |
| `leftMemberEndpoints` | **2 存储** | `TalkbackSession` vs `ConferenceParticipantManager` |

---

## ADR-0013 与现实的差距

ADR-0013 声明 Floor 输入为 `groupMembers + signalPeers[authority]`，并将 `signalPeers` 列为 resolver 合法输入。

按本矩阵分类，`signalPeersByModule` 是 **Weak Cache**，不应作为 Fact。这是 M03 PTT regression 的文档层根因：Invariant 写了一半。
