# Floor Routing Data Flow

> P0 产出。定义 Floor Resolver 的合法输入链与目标接口；**不含实现细节**（不写 signal/presence/HELLO 变量名进 Resolver）。

---

## 当前实现（违规态）

```
PTT Press (M03)
      │
      ▼
GroupFloorController.requestFloor()
      │
      ▼
TalkbackCoordinator.resolveFloorAuthorityRoute(session)
      │
      ├── authorityId      ← session.floorAuthorityModuleId     [Membership ✓]
      ├── endpoint         ← session.groupMembers[authority]      [Membership ✓]
      ├── epoch            ← session.floorAuthorityEpoch          [Membership ✓]
      └── transport        ← signalPeersByModule[authorityId]     [Cache ✗ VIOLATION]
              │
              ▼
      UDP FLOOR_REQUEST → peerTarget.host:port
              │
              ▼
      若 transport 污染 → 打到非 authority → NOT_AUTHORITY
```

---

## 目标 Data Flow

```
PTT Press
      │
      ▼
resolveAuthority(session)
      │  输入：Membership Snapshot
      │  输出：authorityModuleId, authorityEpoch
      │
      ▼
resolveEndpoint(session, authorityModuleId)
      │  输入：groupMembers（canonical roster row）
      │  输出：EndpointAddress（身份域，非 transport）
      │
      ▼
resolveSignalingTransport(authorityModuleId)
      │  输入：TransportRegistry ONLY
      │  输出：TransportBinding { host, port, bindingEpoch }
      │
      ▼
sendFloorRequest(transport, envelope)
```

**跨 Runtime 读取白名单**（仅此两项）：

| 层 | 来源 Runtime | 字段 |
|----|-------------|------|
| Authority | Membership | `floorAuthorityModuleId`, `floorAuthorityEpoch` |
| Endpoint 校验 | Membership | `groupMembers` 中 authority 行（可选：校验 endpointId 一致性） |
| Transport | Transport | `TransportRegistry.resolve(authorityModuleId)` |

**禁止读取**：`signalPeersByModule`, `discoveredByModule`, `remotePeersByModule`, `participants.*`, `meshCompletedModules`, ICE state, UI projection。

---

## TransportRegistry 目标接口

```kotlin
/**
 * Signaling-plane UDP transport bindings per module.
 * Floor, dial, and HELLO fanout read; ICE/media paths do NOT write here.
 */
interface TransportRegistry {
    fun resolve(moduleId: ModuleId): TransportBinding?
    fun bindingEpoch(moduleId: ModuleId): Long
}

data class TransportBinding(
    val moduleId: ModuleId,
    val host: String,
    val port: Int,
    val epoch: Long,
    val source: TransportSource
)

enum class TransportSource {
    VERIFIED_HELLO,
    GOSSIP_PRESENCE,
    STATIC_BOOTSTRAP
}
```

Resolver 依赖 `TransportRegistry`，不依赖 `SignalPeerMap` 或 `DiscoveredMap`。

---

## TransportRegistry 合法 Writer（目标）

```
TransportRegistry WRITE (唯一入口 TransportManager)
│
├── onVerifiedHello(moduleId, fromPeer)      ← 验签 HELLO 的 UDP 源
├── onGossipPresence(moduleId, host, port)   ← StateSync 验签 presence
└── onStaticBootstrap(moduleId, host, port)  ← 配置加载（一次性）
```

**显式禁止写入**：

- `handleSignal` 通用兜底
- GROUP_INVITE / GROUP_ACCEPT / GROUP_JOIN
- CONFERENCE_REJOIN / CONFERENCE_MESH_RECONCILE
- membership snapshot / counter-invite
- ICE connected / disconnected

这些路径可继续更新 `remotePeersByModule`（Media Runtime），**不得**触碰 TransportRegistry。

---

## Epoch 绑定（目标 Invariant F3）

```
bumpFloorAuthorityEpoch(reason):
  1. session.floorAuthorityEpoch++
  2. TransportRegistry.invalidate(authorityModuleId)   ← 必须
  3. （可选）remotePeersByModule.remove(authority)    ← Media 清理，与 Floor 正交
```

`bindingEpoch` 与 `floorAuthorityEpoch` 比较：transport binding 早于 authority epoch → fail-closed，不发包。

---

## FloorAuthorityRoute 目标签名

```kotlin
object FloorAuthorityRoute {
    fun resolve(
        authorityModuleId: ModuleId,
        authorityEndpoint: EndpointAddress,  // roster row
        authorityEpoch: Long,
        transport: TransportBinding?         // from Registry, not optional cache
    ): FloorAuthorityRouteResult
}
```

`TalkbackSession` 不再作为「拼装袋」传入；Membership 字段与 Transport 字段在 Coordinator 边界拆开，再传入 pure function。

---

## 与 ADR-0013 的关系

ADR-0013 决策 3 条输入含 `signalPeersByModule` — **需修订**：

- 保留：authority + roster + epoch
- 替换：`signalPeers` → `TransportRegistry.resolve(authority)`
- 增补 Invariant F3：epoch bump 必须失效 transport binding

---

## P1 / P2 实施顺序（引用，本阶段不实施）

| 阶段 | 工作 |
|------|------|
| P1 | 删除 Floor 对 `signalPeers` / `discovered` 的非法 Reader |
| P1 | 引入 `TransportManager`，迁移 HELLO + gossip 写入 |
| P1 | `rememberSignalPeer` 降级为 SignalLayer 内部，不再写 `discoveredByModule` |
| P2 | 改 `FloorAuthorityRoute.resolve` 签名，接 `TransportRegistry` |
| P2 | 红测：污染 `signalPeers[M01]=M02`，断言 Floor 仍走 Registry 打到 M01 |
| P2 | `bumpFloorAuthorityEpoch` 失效 Registry binding |
