# Mutation Map

> P0 产出。所有共享 Runtime 状态的写入口扫描；`MULTI` = 多 Writer。
>
> 扫描范围：`talkback/android-board-talkback/src/main/java`

---

## `rememberSignalPeer(moduleId, peer)`

定义：`TalkbackCoordinator.kt:2079` — 同时写 `signalPeersByModule` + `discoveredByModule`。

```
rememberSignalPeer
│
├── handleSignal (L2227)          ← 所有验签通过的入站信令（兜底）
├── handleHello (L2263)           ← HELLO
├── tryCounterInviteConference (L2549)   ← Conference counter-invite
├── handleConferenceRejoin (L2643)       ← CONFERENCE_REJOIN
├── tryCounterInviteGroupMesh (L2728)    ← GROUP counter-invite
├── ensureCanonicalMemberPresent (L5175) ← GROUP_JOIN 路径补 invite
├── tryApplyMembershipSnapshotInvite (L5336) ← membership snapshot
├── handleConferenceMeshReconcile (L4764)  ← CONFERENCE_MESH_RECONCILE
├── tryReinviteGroupPeerPairwise (L6732)   ← 间接：先读 discovered 再 remember
├── tryHostReinviteConferencePeer (L7276)  ← 间接：先读 discovered 再 remember
└── testInjectGroupInvite (L1867)          ← 测试注入
```

**删除路径**：

```
signalPeersByModule.remove
├── onModuleUnreachable / prune (L1719)
└── stale cleanup loop (L7521-7524)
```

**问题**：任意协议包的 `(from.moduleId, fromPeer)` 都会覆盖该 module 的 transport 绑定。Floor 读此 Map → **一个 Weak Cache 被 10+ 协议共同写**。

---

## `discoveredByModule`

```
discoveredByModule WRITE
│
├── onPresenceChanged / gossip sweep (L704)     ← StateSync presence（验签后）
├── rememberSignalPeer (L2082)                  ← 与 signalPeers 同源（污染）
├── static peer import (L1932)                  ← 配置引导
└── remove on presence expire (L708-710)        ← 仅当 signalPeers 也无条目
```

**读取决策路径**（应禁止）：

```
discoveredByModule READ (decision)
├── resolvePeerForModule (L2162)     ← 第一优先级
├── broadcastToGroupMembers (L4541)    ← fallback dial
├── applyGroupJoin peer bind (L4632)
├── logFloorRouteDecision trace (L4847)
└── tryReinvite* 间接读再写 rememberSignalPeer
```

---

## `signalPeersByModule`

除 `rememberSignalPeer` 外无独立 Writer。

**读取决策路径**（应禁止）：

```
signalPeersByModule READ (decision)
├── resolveFloorAuthorityRoute (L4837)   ← Floor 发送目标 【VIOLATION】
├── resolvePeerForModule (L2163)         ← 第二优先级
├── helloTargets (L2094)                 ← HELLO fanout
├── isModuleDialable (L2035)             ← callable 判断
└── logFloorRouteDecision trace (L4846)
```

---

## `remotePeersByModule`（per-session）

**Writer 分散在 TalkbackCoordinator（20+ 处，MULTI）**：

| 触发场景 | 大致行号 | 说明 |
|---------|---------|------|
| placeCallInternal / unicast | L2192 | 单呼建立 |
| startGroupSession | L1835, L888, L903 | group 创建/恢复 |
| handleGroupAccept | L2373 | accept 绑定 caller |
| handleGroupJoin | L3015, L3250, L3266, L3313, L3334 | join 绑定 peer |
| GROUP mesh repair | L3379, L971, L1038, L1185 | mesh 修复 |
| CONFERENCE_MESH_RECONCILE | L4765 | host peer + reducer |
| tryApplyMembershipSnapshotInvite | L5337 | snapshot invite |
| membership publication / evict | L3988, L4078 | 清理 |
| ICE connected callback | L5858 区域 | media 绑定 |
| anchor handover fail | L6611 | 清理 failed anchor |
| test / inject paths | 若干 | |

**删除路径**：evict、epoch bump（仅 authority）、session end、prune。

**读取**：ICE send、mesh engine、`resolvePeerForModule` 不读此 Map（合规）；`broadcastToGroupMembers` 优先读此 Map。

---

## `groupMembers`

```
groupMembers WRITE
│
├── GroupMembershipSupport.applyGroupMembersList (L158)     ← 主入口
├── GroupMembershipSupport.evictFromGroupRoster (L227)
├── GroupMembershipSupport.promoteToGroupRoster (L237)
├── GroupMembershipSupport.replaceGroupMemberEndpoint (L257) ← HELLO rebind
├── GroupMembershipSupport.applyMembershipSnapshot
├── TalkbackCoordinator.startGroupSession (L1830)           ← MULTI 直写
└── TalkbackCoordinator.removeGroupMember (L4060)           ← MULTI 直写
```

---

## `floorAuthorityModuleId` / `floorAuthorityEpoch`

```
floorAuthorityModuleId WRITE
├── startGroupSession (L1829)
├── group local authority init (L1169)
├── handleGroupAccept payload (L4587)
├── membership snapshot restore (L7487)
└── payload encode read-back (L3959, L5474)

floorAuthorityEpoch WRITE
└── bumpFloorAuthorityEpoch ONLY (L4827)
    └── side effect: remotePeersByModule.remove(authority)  ← 未清 signalPeers
```

---

## `meshCompletedModules`

```
meshCompletedModules WRITE
├── markMeshLinkCompleted → add (L316)
├── onIceDisconnected / evict / prune → remove (L345, L379, L882, L965)
├── membership evict (L3989, L4079)
└── anchor handover fail (L6612)
```

Reader：mesh planner、invite pending 判断、playback gate — **不应用于 Floor**。

---

## `participants`（invite / media）

### CONFERENCE 路径

```
ConferenceParticipantManager
├── onInviteSent → invite=INVITING
├── onInviteAccepted → invite=ACCEPTED, media=CONNECTING
├── onMediaConnected → media=CONNECTED
├── updateMediaState / updateInviteState
└── applyPrune / evictInvitee → remove

ConferenceMembershipReducer
├── PeerLeft → tombstone
├── PeerReactivated → invite=ACCEPTED, media=CONNECTING  ← 仅真实事件
└── SnapshotCorrected → roster only（已修，不写 invite/media）

TalkbackCoordinator (VIOLATION)
├── ICE callback → meshParticipant().media (L5858, L6018, L7681)
├── evict path → invite (L4055-4062)
└── expire path → invite=EXPIRED (L7760)
```

### GROUP 路径

```
session.participant() getOrPut
├── syncParticipantsFromMembers
├── Coordinator ICE → media
└── Coordinator evict → invite
```

---

## `leftMemberEndpoints`

```
双存储（split-brain 风险）

TalkbackSession.leftMemberEndpoints
└── Coordinator 回退读取 (L5454, L2671)

ConferenceParticipantManager.leftMemberEndpoints
├── applyPrune (L161)
├── evictInvitee (L175)
└── ConferenceMembershipReducer.PeerLeft remove (L91, L116)
```

---

## 因果链：为何「修 Meeting 坏 PTT」

```
Conference Rejoin / Mesh Reconcile
        │
        ▼
rememberSignalPeer(hostId or rejoinerId, fromPeer)
        │
        ├── signalPeersByModule[M01] = M02_socket   （污染）
        └── discoveredByModule[M01] = M02_socket     （同源）
                │
                ▼
M03 PTT → resolveFloorAuthorityRoute
        → signalPeersByModule[M01]
        → UDP 打到 M02
        → M02 NOT_AUTHORITY
```

Meeting reducer 本身不读 Floor；但 **Meeting 信令路径是 signalPeers 的 Writer 之一**，间接改变了 Floor Resolver 输入。
