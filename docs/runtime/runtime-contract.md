# Runtime Contract

> P0 产出。约束代码的契约表；PR review 可对照「Business Read / Mutation」列直接挡违规改动。
>
> 状态：`TARGET` = 目标契约；`CURRENT` = 当前实现（违规处标注 `VIOLATION`）。

---

## Contract 模板

```
State: <name>
Owner: <Runtime module>
Type: Fact | Derived Fact | Cache
Business Read: Allowed | Forbidden | Owner-only
Mutation: <唯一 Writer 或 FORBIDDEN>
Lifetime: Session | Process | Ephemeral
Consistency: Strong | Eventual | Weak
Persistence: No
Decision Use: Allowed | Forbidden
```

---

## Membership Runtime

### `groupMembers`

```
Owner: GroupMembershipSupport (MembershipReducer)
Type: Fact
Business Read: Allowed (Floor, Mesh, Conference, UI)
Mutation: GroupMembershipSupport.applyGroupMembersList 及专用 mutator ONLY
Lifetime: Session
Consistency: Strong
Decision Use: Allowed
CURRENT VIOLATION: TalkbackCoordinator 直接赋值 (L1830, L4060)
```

### `floorAuthorityModuleId`

```
Owner: Membership / Anchor Election
Type: Fact
Business Read: Allowed
Mutation: 单一 applyAuthorityBinding() 入口（待引入）
Lifetime: Session
Consistency: Strong
Decision Use: Allowed
CURRENT VIOLATION: TalkbackCoordinator 6+ 分散赋值
```

### `floorAuthorityEpoch`

```
Owner: Membership (bound to rosterEpoch bump)
Type: Fact (generation stamp)
Business Read: Allowed (Floor trace only)
Mutation: bumpFloorAuthorityEpoch ONLY
Lifetime: Session
Consistency: Strong
Decision Use: Allowed (staleness gate)
CURRENT VIOLATION: bump 只清 remotePeers[authority]，未失效 signaling transport
```

### `rosterEpoch` / `membershipStateByModule`

```
Owner: GroupMembershipSupport
Type: Fact / Derived Fact
Business Read: Allowed
Mutation: GroupMembershipSupport ONLY
Decision Use: Allowed (snapshot staleness)
Status: CURRENT 基本合规
```

### `leftMemberEndpoints`

```
Owner: Membership (Conference: ConferenceParticipantManager)
Type: Derived Fact
Business Read: Allowed (Rejoin, projection)
Mutation: applyPrune / evictInvitee / MembershipReducer ONLY
Lifetime: Session
Consistency: Strong
CURRENT VIOLATION: 双存储 (TalkbackSession + ConferenceParticipantManager)
```

---

## Transport Runtime（目标态）

### `TransportRegistry` / `signalingTransport`

```
Owner: TransportManager (待引入)
Type: Fact (for signaling UDP)
Business Read: Allowed (Floor, dial, HELLO fanout)
Mutation: 仅以下事件：
  - verified HELLO (fromPeer)
  - gossip presence (StateSync, 验签后)
  - static peer bootstrap (config load)
Business Read FORBIDDEN from: ConferenceReducer, ICE callbacks, arbitrary signal handlers
Lifetime: Process (per moduleId binding)
Consistency: Eventual
Decision Use: Allowed (Floor transport resolution)
Status: NOT IMPLEMENTED — 当前由 signalPeers + discovered 代替
```

### `signalPeersByModule`

```
Owner: SignalLayer (internal)
Type: Cache
Business Read: NONE
Mutation: rememberSignalPeer (SignalLayer internal)
Lifetime: Ephemeral
Consistency: Weak
Decision Use: Forbidden
CURRENT VIOLATION: FloorAuthorityRoute.resolve 读取 (ADR-0013)
CURRENT VIOLATION: resolvePeerForModule 读取
```

### `discoveredByModule`（当前实现）

```
Owner: 未定义（实际：Coordinator 全局 Map）
Type: Cache
Business Read: NONE (for decisions)
Mutation: gossip presence + rememberSignalPeer (VIOLATION: 与 signalPeers 同源)
Lifetime: Ephemeral
Consistency: Weak
Decision Use: Forbidden
CURRENT VIOLATION: resolvePeerForModule、mesh dial、Floor trace 读取
```

---

## Media Runtime

### `remotePeersByModule`

```
Owner: MediaRuntime / ICE session binding
Type: Cache (per-session ICE peer socket)
Business Read: Allowed (ICE send, mesh engine only)
Mutation: ICE connect/disconnect handlers, GROUP_JOIN/ACCEPT ONLY
Lifetime: Session
Consistency: Eventual
Decision Use: Forbidden
Status: ADR-0013 已禁止 Floor 读取 — CURRENT 基本合规 for Floor
CURRENT VIOLATION: 20+ 分散 Writer，无单一 MediaRuntime 入口
```

### `meshCompletedModules`

```
Owner: MediaRuntime
Type: Cache
Business Read: Allowed (mesh planner, invite gating)
Mutation: markMeshLinkCompleted / evict cleanup ONLY
Decision Use: Forbidden (must not gate Floor authority)
Status: CURRENT 合规 for Floor
```

### `participants.media`（GROUP）

```
Owner: MediaRuntime (ICE)
Type: Derived Fact
Business Read: Allowed (UI projection)
Mutation: ICE state projection ONLY
CURRENT VIOLATION: Coordinator meshParticipant().media 多处直写
```

### `participants.media`（CONFERENCE）

```
Owner: MediaRuntime (ICE) + ConferenceParticipantManager
Type: Derived Fact
Business Read: Allowed (ConferenceParticipantProjector)
Mutation: ConferenceParticipantManager.updateMediaState / onMediaConnected
CURRENT VIOLATION: Coordinator ICE 回调直写；Reducer reactivatePeerAfterRealEvent 写 CONNECTING
```

---

## Conference Runtime

### `participants.invite`

```
Owner: ConferenceParticipantManager + ConferenceMembershipReducer
Type: Derived Fact
Business Read: Allowed (Projector, visible count)
Mutation: Reducer 事件路径 + onInviteSent/Accepted ONLY
CURRENT VIOLATION: Coordinator 直写 invite (evict 路径 L4055-4062)
```

### Conference `roster`（manager 内）

```
Owner: ConferenceParticipantManager
Type: Fact (conference-scoped)
Business Read: Allowed
Mutation: addToRoster / replaceRoster / applyPrune ONLY
Status: CURRENT 基本合规
```

---

## Floor Resolver Contract（目标）

```
FloorAuthorityRoute.resolve 合法输入:
  1. authorityModuleId     ← Membership (session.floorAuthorityModuleId)
  2. authorityEndpoint     ← Membership (groupMembers row)
  3. authorityEpoch        ← Membership (session.floorAuthorityEpoch)
  4. signalingTransport    ← TransportRegistry.resolve(authorityModuleId)

禁止输入:
  - signalPeersByModule
  - discoveredByModule
  - remotePeersByModule
  - participants.*
  - meshCompletedModules
  - UI / Projector state
```

---

## PR Review 检查清单

- [ ] 新增对 `signalPeersByModule` 的读取？→ **拒绝**（Decision Use Forbidden）
- [ ] 新增 `rememberSignalPeer` 调用方？→ **需 Transport 评审**
- [ ] 修改 `groupMembers` 绕过 `GroupMembershipSupport`？→ **拒绝**
- [ ] Conference Reducer 写 `participants.media`？→ **仅 PeerReactivated 事件允许**
- [ ] `bumpFloorAuthorityEpoch` 未失效 authority transport？→ **拒绝**
- [ ] Floor / Meeting / Mesh 共用新全局 Map？→ **需 Ownership 表登记**
