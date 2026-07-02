# Runtime Public API

> RO-0 产出。定义四个 Runtime 的**唯一合法读写面**；Coordinator 与业务代码不得绕过。
>
> 配套：`ownership-matrix.md`（含 Public API 列）、`runtime-contract.md`、`prd-runtime-ownership-refactor.md`

---

## 使用方式

### Runtime Boundary Review（PR 必查）

每条 PR 回答：

1. 是否**新增**对 `TalkbackSession` 可变字段的直接写？
2. 是否**新增**对全局 Map（`signalPeersByModule` 等）的决策读取？
3. 变更是否经对应 Runtime 的 **Public API**？
4. 若需例外：是否登记 `@OwnershipExempt` 且总数递减？

任一违规 → 请求修改，或拆到对应 RO issue。

### 符号

| 标记 | 含义 |
|------|------|
| **TARGET** | 目标 API（RO-2+ 引入或收敛） |
| **CURRENT** | 代码中已存在、允许继续使用 |
| **FORBIDDEN** | 禁止作为业务决策或突变入口 |
| **INTERNAL** | 仅 Runtime 实现类内部 |

---

## TalkbackSession 字段清单

`TalkbackSession` 是共享载体；字段按 Runtime 归属分类。**Direct Write = Forbidden** 表示 Coordinator 不得再直接赋值。

### Membership Runtime

| 字段 | 类型 | Public API（读） | Public API（写） | Direct Write |
|------|------|-----------------|-----------------|--------------|
| `groupMembers` | Fact | `canonicalRosterEndpoints()` | `applyRoster()` | **Forbidden** |
| `floorAuthorityModuleId` | Fact | `authorityModuleId()` | `applyAuthorityBinding()` | **Forbidden** |
| `floorAuthorityEpoch` | Fact | `floorAuthorityEpoch()` | `bumpFloorAuthorityEpoch()` | **Forbidden** |
| `rosterEpoch` | Fact | `rosterEpoch()` | `bumpRosterEpoch()` | **Forbidden** |
| `anchorEpoch` / `anchorModuleId` | Fact | `anchorSnapshot()` | `applyAnchorBinding()` | **Forbidden** |
| `membershipStateByModule` | Derived | `membershipState()` | `markOnline/Evicted/Suspect()` | **Forbidden** |
| `pendingInviteeEndpoints` | Fact | `pendingInvitees()` | `promoteInvitee()` | **Forbidden** |
| `memberModules` | Derived | `canonicalMemberModuleIds()` | （随 roster 同步） | **Forbidden** |
| `channelMemberSnapshot` | Fact | `channelMemberSnapshot` | `freezeChannelSnapshot()` | INTERNAL |
| `initiatorModuleId` | Fact | `initiatorModuleId` | session 创建时一次 | INTERNAL |

### Transport Runtime（signaling UDP）

| 字段 / 存储 | 类型 | Public API（读） | Public API（写） | Direct Write |
|-------------|------|-----------------|-----------------|--------------|
| `TransportRegistry` | Fact | `resolve(moduleId)` | `onVerifiedHello()` 等 | N/A（非 session 字段） |
| `signalPeersByModule` | Cache | — | `rememberSignalPeer()` INTERNAL | **Forbidden**（决策读） |
| `discoveredByModule` | Cache | — | 待删除 | **Forbidden** |
| `stateSync.presence` | Read Model | `presence(moduleId)` | HELLO/gossip 管道 | INTERNAL |

### Media Runtime

| 字段 | 类型 | Public API（读） | Public API（写） | Direct Write |
|------|------|-----------------|-----------------|--------------|
| `remotePeersByModule` | Cache | `meshPeer(moduleId)` | `bindMeshPeer()` | **Forbidden** |
| `meshCompletedModules` | Cache | `isMeshCompleted()` | `markMeshCompleted()` | **Forbidden** |
| `remotePeer` / `remote` | Cache | `primaryRemotePeer()` | unicast 建立路径 | INTERNAL |
| `mediaTopology` | Fact | `mediaTopology` | topology planner | INTERNAL |
| `participants`（GROUP） | Derived | `participantView()` | `onIceStateChanged()` | **Forbidden** |
| `participants.media` | Derived | Projector 只读 | `MediaRuntime.notify*` | **Forbidden** |

### Conference Runtime

| 字段 / 存储 | 类型 | Public API（读） | Public API（写） | Direct Write |
|-------------|------|-----------------|-----------------|--------------|
| `ConferenceParticipantManager` | — | `snapshot()` | 见下表 | N/A |
| `participants.invite` | Derived | `snapshot()` / Projector | `onInviteSent/Accepted()` | **Forbidden** |
| `leftMemberEndpoints` | Derived | `leftMembers()` | `applyPrune()` | **Forbidden**（session 副本废弃） |

### Floor Runtime（消费方，非 Owner）

| 消费 | 合法来源 | 禁止来源 |
|------|---------|---------|
| authority | Membership `authorityModuleId()` | participants, meshCompleted |
| endpoint | Membership roster row | signalPeers |
| transport | Transport `resolve(authority)` | signalPeers, discovered, remotePeers |

### UI / Session 壳（只读 disposition）

| 字段 | Owner | 说明 |
|------|-------|------|
| `disposition` | Session FSM | ADR-0001 |
| `ptt` / `floorOwner` | Floor FSM | Endpoint 级 |
| `traceId`, `lastActiveMs` | Session | 观测 |

---

## MembershipRuntime

**Owner**：`GroupMembershipSupport`（过渡）→ `MembershipRuntime` 门面（RO-2）

### 读 API

| 方法 | 状态 | 说明 |
|------|------|------|
| `canonicalRosterEndpoints(session)` | CURRENT | `GroupMembershipSupport` |
| `canonicalMemberModuleIds(session)` | CURRENT | |
| `canonicalMemberKeys(session)` | CURRENT | |
| `membershipState(session, moduleId)` | CURRENT | |
| `isEvicted(session, moduleId)` | CURRENT | |
| `isActiveMember(session, moduleId, ice)` | CURRENT | |
| `activeMemberModuleIds(session, iceFor)` | CURRENT | |
| `memberHashForSession(session)` | CURRENT | digest |
| `authorityModuleId(session)` | **TARGET** | RO-3 |
| `floorAuthorityEpoch(session)` | **TARGET** | RO-3 |

### 写 API

| 方法 | 状态 | 替代 Forbidden 操作 |
|------|------|---------------------|
| `applyGroupMembersList(session, members, origin)` | CURRENT | `session.groupMembers =` |
| `evictFromGroupRoster(session, moduleId, local)` | CURRENT | filter 直写 |
| `promoteToGroupRoster(session, remote, local)` | CURRENT | `groupMembers +` |
| `replaceGroupMemberEndpoint(session, moduleId, eid)` | CURRENT | HELLO rebind |
| `applyMembershipSnapshot(...)` | CURRENT | authority snapshot |
| `bumpRosterEpoch(session)` | CURRENT | |
| `markOnline/Evicted/Suspect(...)` | CURRENT | |
| `applyAuthorityBinding(session, id, reason)` | **TARGET** | 分散 `floorAuthorityModuleId =` |
| `bumpFloorAuthorityEpoch(session, reason)` | **TARGET** | Coordinator 私有函数上提 |

### Forbidden（Coordinator / 其他 Runtime）

```
session.groupMembers = ...
session.groupMembers.filter { ... }  // 除 Support 外
session.floorAuthorityModuleId = ...
session.rosterEpoch = ...            // 非 bump 路径
session.membershipStateByModule[...] = ...
```

---

## TransportRuntime

**Owner**：`TransportManager` + `TransportRegistry`（RO-4 Stub → RO-5 真实）

### 读 API

| 方法 | 状态 | 说明 |
|------|------|------|
| `TransportRegistry.resolve(moduleId)` | **TARGET** | Floor、dial、HELLO fanout |
| `TransportRegistry.bindingEpoch(moduleId)` | **TARGET** | 与 floorAuthorityEpoch 比对 |
| `resolvePeerForModule(moduleId)` | CURRENT | 待收敛到 Registry（RO-4） |

### 写 API

| 方法 | 状态 | 说明 |
|------|------|------|
| `onVerifiedHello(moduleId, fromPeer)` | **TARGET** | 验签 HELLO |
| `onGossipPresence(moduleId, host, port)` | **TARGET** | StateSync 验签后 |
| `onStaticBootstrap(moduleId, host, port)` | **TARGET** | 配置加载 |
| `invalidate(moduleId)` | **TARGET** | roster/authority epoch bump |
| `rememberSignalPeer(moduleId, peer)` | CURRENT → INTERNAL | RO-5 后仅 SignalLayer 内部 |

### Forbidden

```
signalPeersByModule[...] = ...       // Floor 决策读
discoveredByModule[...] = ...        // 经 rememberSignalPeer 同源写
resolveFloorAuthorityRoute 读 signalPeers
handleSignal 通用路径写 transport 绑定（TARGET 禁止）
GROUP_INVITE / CONFERENCE_REJOIN 写 Registry
ICE callback 写 Registry
```

### Stub 阶段（RO-4）

```kotlin
class DelegatingTransportRegistry(
    private val legacy: (ModuleId) -> PeerTarget?
) : TransportRegistry {
    override fun resolve(moduleId: ModuleId) =
        legacy(moduleId)?.toBinding(moduleId)
}
```

行为与今日一致；调用方已依赖接口，RO-5 只换实现。

---

## MediaRuntime

**Owner**：待提取 `MediaRuntime`（RO-7）；过渡期 Coordinator + qosMonitor

### 读 API

| 方法 | 状态 | 说明 |
|------|------|------|
| `session.remotePeersByModule[moduleId]` | CURRENT | 仅 ICE send / mesh engine |
| `meshCompletedModules.contains(moduleId)` | CURRENT | invite gating |
| `qosMonitor.snapshot(moduleId)?.iceState` | CURRENT | ICE 只读 |
| `isMeshCompleted(session, moduleId)` | **TARGET** | 门面 |

### 写 API

| 方法 | 状态 | 说明 |
|------|------|------|
| `markMeshLinkCompleted(session, moduleId)` | CURRENT | Coordinator 私有 |
| `bindMeshPeer(session, moduleId, peer)` | **TARGET** | 收敛 20+ remotePeers 写 |
| `onIceStateChanged(session, moduleId, iceState)` | **TARGET** | 唯一写 participants.media |
| `clearMeshPeer(session, moduleId)` | **TARGET** | evict / handover |

### Forbidden

```
meshParticipant(...).media = ...     // Coordinator ICE 回调直写
session.remotePeersByModule[...] =   // 非 MediaRuntime 门面
participants.media 用于 Floor 决策
meshCompletedModules 用于 Floor authority
```

---

## ConferenceRuntime

**Owner**：`ConferenceParticipantManager` + `ConferenceMembershipReducer`

### 读 API

| 方法 | 状态 | 说明 |
|------|------|------|
| `snapshot(sessionId, localModuleId)` | CURRENT | UI / visible count |
| `roster(sessionId)` | CURRENT | |
| `acceptedRemoteCount(sessionId)` | CURRENT | |
| `leftMemberEndpoints(sessionId)` | CURRENT | Rejoin（RO-8 唯一存储） |
| `ConferenceParticipantProjector.project(...)` | CURRENT | 只读投影 |

### 写 API（Manager）

| 方法 | 状态 | 说明 |
|------|------|------|
| `onInviteSent(sessionId, moduleId, ...)` | CURRENT | invite=INVITING |
| `onInviteAccepted(sessionId, moduleId)` | CURRENT | invite+CONNECTING |
| `onMediaConnected(sessionId, moduleId)` | CURRENT | media=CONNECTED |
| `updateMediaState(sessionId, moduleId, media)` | CURRENT | RO-7 收归 MediaRuntime 调用 |
| `updateInviteState(sessionId, moduleId, invite)` | CURRENT | |
| `applyPrune(sessionId, moduleId)` | CURRENT | |
| `evictInvitee(sessionId, moduleId, invite)` | CURRENT | |
| `addToRoster` / `replaceRoster` | CURRENT | |

### 写 API（Reducer — 唯一 membership 事件缝）

| 事件 | 可写范围 | 禁止 |
|------|---------|------|
| `PeerLeft` | roster, left tombstone | invite/media |
| `SnapshotCorrected` | roster, left tombstone | **invite/media**（ADR-0012） |
| `PeerReactivated` | roster, invite, media（真实重入） | — |

```kotlin
conferenceMembershipReducer.apply(sessionId, local, event)
```

### Forbidden

```
Coordinator 直写 meshParticipant().invite
Coordinator 直写 meshParticipant().media   // → MediaRuntime
session.leftMemberEndpoints[...] =         // → Manager only
Conference 路径写 signalPeers / Registry
```

---

## FloorRuntime（纯函数，无可变状态）

**Owner**：`FloorAuthorityRoute` + `GroupFloorController`

### 读 API（仅消费其他 Runtime）

| 输入 | 来源 |
|------|------|
| `authorityModuleId` | Membership |
| `authorityEndpoint` | Membership roster |
| `authorityEpoch` | Membership |
| `transport` | TransportRegistry |

### 写 API

| 方法 | 说明 |
|------|------|
| `FloorAuthorityRoute.resolve(...)` | 纯函数，无副作用 |
| `GroupFloorController.request/grant/deny` | 信令编排 |

### Forbidden

```
resolve 内读 TalkbackSession 全局 Map
resolve 内读 signalPeers / discovered / remotePeers
```

---

## TalkbackCoordinator：编排规则

Coordinator **允许**：

- 收信令 → 调用对应 Runtime Public API
- 编排多 Runtime 顺序（如 accept → membership → mesh）
- 读 **只读投影**（snapshot、canonicalRoster）供 UI

Coordinator **禁止**（逐步 RO 消除）：

| 模式 | 目标 RO |
|------|---------|
| `session.groupMembers =` | RO-2 |
| `session.floorAuthorityModuleId =` | RO-3 |
| `resolveFloorAuthorityRoute` 读 signalPeers | RO-4/5/6 |
| `meshParticipant().media =` | RO-7 |
| `rememberSignalPeer` 影响 Floor 决策 | RO-5 |

**North Star**：Coordinator 构造函数注入四个 Runtime 门面，无 `TalkbackSession` 可变字段直写。

```
TalkbackCoordinator
    ├── membership: MembershipRuntime
    ├── transport: TransportRegistry
    ├── media: MediaRuntime
    └── conference: ConferenceRuntime  // Manager + Reducer
```

---

## 全局 Map 速查（非 session 字段）

| Map | Owner | 合法读 | 合法写 | Direct Access |
|-----|-------|--------|--------|---------------|
| `signalPeersByModule` | SignalLayer | INTERNAL | `rememberSignalPeer` | **Forbidden** 业务读 |
| `discoveredByModule` | （废弃） | — | — | **Forbidden** |
| `staticPeers` | Config | dial fallback | 启动加载 | INTERNAL |

---

## 与 RO 路线图对照

| RO | 本文件对应节 |
|----|-------------|
| RO-0 | 全文（本文档） |
| RO-1 | Forbidden 表 → OwnershipContractTest 规则源 |
| RO-2 | MembershipRuntime 写 API |
| RO-3 | `applyAuthorityBinding` |
| RO-4 | TransportRegistry Stub |
| RO-5 | TransportRuntime 写 API |
| RO-6 | FloorRuntime 纯函数输入 |
| RO-7 | MediaRuntime `onIceStateChanged` |

---

## References

- `docs/runtime/ownership-matrix.md`
- `docs/runtime/runtime-contract.md`
- `docs/runtime/mutation-map.md`
- `docs/runtime/floor-routing-data-flow.md`
- `docs/prd-runtime-ownership-refactor.md`
