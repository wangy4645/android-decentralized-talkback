# Group Session Identity Consistency

*Canonical endpoint binding per moduleId within an active Group session*

现场日志（M03 新装冷启动 PTT）表明：ICE 已 CONNECTED、authority 已 GRANT、transmit 门控可能已满足，但远端仍无声。指纹为 `GRANT_DROPPED reason=ROSTER_MISS requesterKey=M03-E01`，而 mesh / invite 层使用 `M03-E03`。根因不是 mesh、ICE 或 floor 局部 bug，而是 **同一 module 在 Group session 不同层持有不同 endpoint identity**（Identity Drift）。

本 ADR 定义 Group PTT 域的 **canonical endpoint binding** 不变量及收敛规则。Meeting 结束后的「自愈」是 forced identity reconcile（roster 重建 + endpoint 对齐 + `rosterEpoch` bump），不是 magic reset。

**决定原因**：ADR-0008 的 TopologySnapshot 已能观测 membership / transmit 未收敛，但未规定 identity 如何保持单一。在 identity 分裂时继续堆 mesh 或 PTT 日志无法消除 `ROSTER_MISS`。须在 **HELLO ingress** 建立 canonical binding，floor / grant 层仅做 moduleId 级解析兜底，不得替代 binding。

## Scope

**v1 仅覆盖 active Group PTT session 内的 endpoint identity。**

- 包含：`session.groupMembers`、floor `requesterKey` / `ownerKey`、mesh invite target、membership snapshot 中的 endpoint 列表。
- 不包含：Callable Roster / Endpoint Directory（ADR-0006）的全局目录语义；Conference 内 identity（独立 ADR）。
- **Cross-domain trigger**：Conference 结束触发的 `releaseConferenceChannelForGroupPtt` 可引发 Group reconcile；本 ADR 规定 reconcile 后 identity 须满足 R35，不定义 Conference 自身 identity。

## Architecture Dependency

```
ADR-0002 Channel Session Membership
        │
        ▼
ADR-0006 Callable Gate & Endpoint Directory
        │
        ▼
ADR-0007 Intent–Reality Consistency (R31–R33)
        │
        ├── ADR-0008 Group Runtime Health Projection (diagnostic read model)
        │
        └── ADR-0009 Group Session Identity Consistency (R35–R38)
```

ADR-0008 的 `membershipReconciled` 与 `transmitMissingPeers` 在 identity 分裂时可能给出误导性「已连接」诊断；R35 是 membership 收敛的**前置条件**；R38 是本地 runtime 读取 canonical 的**前置条件**（floor / media 不得直接以 legacy `session.local` 为 SSOT）。

## Problem Statement (Observed)

| 层 | M03 现场 identity |
|----|-------------------|
| Floor / roster / grant | `M03-E01` |
| ICE / mesh invite | `M03-E03` |
| Membership digest | M01/M02 `memberHash=889465109` vs M03 `-528664596`（未对齐） |

序列：

1. M01 早期 invite payload：`members=["M01-E01","M03-E01"]`（占位 endpoint）。
2. M03 实际 HELLO / accept 使用 `M03-E03`。
3. M03 PTT → authority GRANT `requester=M03-E01`；M02 `GRANT_DROPPED ROSTER_MISS`。
4. Meeting 结束 → full reconcile → grant 变为 `requester=M03-E03` → PTT 正常。

### Phase 2（R35–R37 落地后复测）

R35 生效后 `ROSTER_MISS` 消失、`GRANT_OBSERVED resolved=OK`，但冷启动首次 PTT 仍出现：

| 层 | M03 复测 identity |
|----|-------------------|
| Canonical roster / grant owner | `M03-E03` |
| Legacy `TalkbackSession.local` | `M03-E01`（`val`，创建后不变） |
| Floor request payload | `requester=M03-E01` |
| Local runtime | `ownerIsLocal=false`，全程 `captureOFF`，无 `FLOOR_RELEASE` |
| Remote UI | `floorOwner=M03-E03` 卡住 ~1–2 分钟直至 mesh / snapshot 恢复 |

结论：Identity Drift 从 **Remote**（远端认不出你，R35 已修）收敛为 **Local Runtime**（自己认不出自己，R38 待修）。

## Normative Rules

### R35 — Single Active Endpoint Binding

> Within an active Group session scope, each `moduleId` MUST map to exactly one canonical `endpointId` in `session.groupMembers` and in all floor payloads emitted or consumed by that session.

- **Replace, not merge**：当验签 HELLO 或 Group accept 表明 `moduleId` 已存在但 `endpointId` 不同时，**替换** roster 中该 module 的 `EndpointAddress`，禁止并存 `M03-E01` 与 `M03-E03`。
- **Ingress 点**：canonical binding 的权威写入发生在 **HELLO ingress**（及 authority membership snapshot apply），不得在 grant 处理时静默「猜」endpoint 并永久保留双 identity。
- **Bump**：replace 后 **MUST** `bumpRosterEpoch` 并传播 membership snapshot（authority 侧），使 digest 与 floor 视图一致。

### R36 — Floor Resolution by moduleId

> Floor grant observation and snapshot apply MUST resolve `requesterKey` / `ownerKey` to the canonical roster endpoint for that `moduleId` when exact key match fails.

- `GroupFloorController.resolveFloorOwner` 当前为 exact key lookup；v1 **SHOULD** 增加：`requesterKey` → parse `moduleId` → `groupMembers.find { it.moduleId == moduleId }`。
- 此为 **compatibility patch**：缓解 race 窗口，**MUST NOT** 替代 R35；若 binding 长期不稳定，仍须 gating（R37）。
- Authority 侧 emit grant 时 **SHOULD** 使用 canonical endpoint key，避免发出 stale `M03-E01`。

### R37 — No Floor Request Under Unstable Identity

> No local floor REQUEST MUST be sent while identity mapping for any active roster member is unstable or membership digest is not aligned with authority.

- **Unstable** 定义（v1）：`session.groupMembers` 中某 remote `moduleId` 的 endpoint 与 Endpoint Directory 最新验签 HELLO 不一致；或本机 `memberHash` 与 `lastSeenAuthorityDigest` 不一致（复用 ADR-0008 R34 信号）。
- UI「Syncing」在 v1 仍可由 `channelReadiness()` 驱动；但 PTT 门控 **MUST** 与 identity stable 挂钩，防止 orphan grant。
- ICE_CONNECTED **≠** identity stable。

### R38 — Local Runtime Identity Consistency

> During an active Group session, the local runtime identity SHALL remain identical to the current canonical local member identity. Canonical identity changes MUST be reflected in runtime reads before any subsequent Floor or Media operation.

> **Local runtime identity MUST NOT be owned by `TalkbackSession`.**

- **Derived view, not stored state**：Runtime 侧 local identity 是 canonical membership 的**只读派生视图**，不得在 Floor / Media / Coordinator 中通过 `session.local = …` 或等价 setter 写入。
- **Canonical local member**：`session.groupMembers` 中 `moduleId == localModuleId` 的 `EndpointAddress`；由 **Canonical Identity Owner**（见下）维护。
- **IdentityResolver**：唯一 runtime 读取入口（`local(session)`、`isLocal(session, endpoint)`）；Floor request、grant local 判定、release、capture **MUST** 经 Resolver 读取，**MUST NOT** 以 legacy `session.local` 为 SSOT。
- **Legacy bootstrap**：`TalkbackSession.local`（`val`）仅为会话创建时的 bootstrap identity；标注为 legacy，未来退役；在 R38 落地前不得作为 canonical 写入目标。
- **Compatibility guard**（过渡）：`IdentityResolver.isLocal` 在 canonical key 不匹配时 **MAY** fallback 到 `moduleId` 相等并打 `LOCAL_IDENTITY_DRIFT_COMPAT`；此路径 **MUST NOT** 成为 authoritative identity 定义；日志长期归零后删除 fallback。
- **Recovery**（非 correctness）：`localRequestedFloor && PTT_UP && owner still local after timeout` 时 **MAY** emit release；不得替代 Resolver 正确性。

### Canonical Identity Owner

> **Who may change canonical identity?** Only the Membership Authority write path — not Floor, Media, Session bootstrap, or Coordinator ad-hoc mutation.

| 写入路径 | 函数 / 入口 | 行为 |
|----------|-------------|------|
| HELLO ingress | `applyCanonicalEndpointBindingFromHello` → `replaceGroupMemberEndpoint` | 单 module replace + `bumpRosterEpoch` |
| Membership snapshot | `applyMembershipSnapshot` | Authority 全量对齐 `groupMembers` + `rosterEpoch` |
| Group accept | `promoteInviteeToCanonicalRoster` | 新成员加入（非 replace） |
| Evict | `removeGroupMember` | 移除 + bump |

- 上述路径 **MUST NOT** 同步修改 `TalkbackSession.local`；canonical 变更后，下游 **MUST** 通过 `IdentityResolver` 在下一次 read 时看到新 identity（无缓存、无 setter）。
- HELLO、snapshot、accept、reconcile 等 **MUST** 最终汇入 Owner，不得出现第二 canonical 写入入口。

### IdentityResolver (normative contract)

```
CanonicalIdentityOwner (groupMembers)
        │
        ▼
IdentityResolver.local(session)      → EndpointAddress
IdentityResolver.isLocal(session, e) → Boolean (+ COMPAT log if needed)
IdentityResolver.localKey(session)     → String
```

- `local(session)`：`groupMembers.find { moduleId == localModuleId }`；若缺失 **MAY** fallback 到 legacy `session.local`（仅 bootstrap 窗口）。
- `isLocal(session, endpoint)`：先 `endpoint.key == local(session).key`；compat 时 `endpoint.moduleId == localModuleId` + `LOCAL_IDENTITY_DRIFT_COMPAT sessionId=… expected=… actual=…`。
- P0 接管：floor request identity、grant `ownerIsLocal`、release、capture / uplink。**不**改 Conference / Unicast / 全局 `session.local` 引用（P1 迁移清单）。

## Canonical Binding Algorithm (v1)

```
onVerifiedHello(moduleId, endpointId):
    existing = session.groupMembers.find { it.moduleId == moduleId }
    if existing == null:
        // defer to invite / membership snapshot path
        return
    if existing.endpointId == endpointId:
        return
  // R35: replace
    replaceGroupMemberEndpoint(session, moduleId, endpointId)
    bumpRosterEpoch(session)
    if localIsAuthority:
        broadcastMembershipSnapshot()
    else:
        scheduleMembershipResync()
    emitTopologySnapshot(IDENTITY_REBOUND)
```

- `replaceGroupMemberEndpoint` **MUST** 移除旧 key、插入新 key，**禁止** 双条目。
- Pending invitee 使用 moduleId 索引；endpoint 更新后 **MUST** 刷新 pending 解析。

## Diagnostic Fingerprints

| 日志 | 含义 |
|------|------|
| `GRANT_DROPPED ROSTER_MISS` | Floor key ∉ canonical roster（identity drift） |
| `memberPresent=false` + grant | Authority roster 与 requester 不同步 |
| `SNAPSHOT_DEFER membership_not_converged` | Floor 层已感知 identity/membership 未收敛 |
| `Mesh join deferred: M03 invite still pending` | Mesh 层等待 identity/session 握手，非根因但常共生 |
| `LOCAL_IDENTITY_DRIFT_COMPAT` | Resolver compat：`session.local` ≠ canonical local；含 `sessionId`、`expected`、`actual` |
| `ownerIsLocal=false` + grant `owner==canonicalLocal` | Local runtime drift：未走 Resolver 或 legacy 直读 |
| `HOLDER_AUDIO_UNREACHABLE` + 本地无 `captureON` | 持权但本机未开麦（常与 local drift 共生） |

TopologySnapshot（ADR-0008）v2 **MAY** 增加 `identityDriftPeers: [moduleId]`；v1 实现可先用结构化 log `IDENTITY_REBOUND`。

## Considered Options

- **仅 grant fallback（moduleId resolve）**：掩盖 drift，Meeting 仍成唯一 reset；拒绝作为主方案。
- **仅 mesh / ICE 重连**：现场 ICE 已 CONNECTED；拒绝。
- **全局 Endpoint Directory 覆盖 groupMembers**：混淆 ADR-0006 门与形与 session roster；拒绝。
- **HELLO ingress replace + rosterEpoch + grant moduleId resolve + PTT gating（接受，slices 1–4）**。
- **Mutate `TalkbackSession.local` on canonical change**：破坏 R32 State Owner，多入口 setter；拒绝。
- **`ownerIsLocal` 散落 moduleId 比较**：复制 compat、掩盖 local drift；拒绝；compat 仅允许在 `IdentityResolver.isLocal` 一处。

## Consequences

- Slices 1–4 修改点：`TalkbackCoordinator` HELLO 处理、`GroupMembershipSupport`（replace helper）、`GroupFloorController.resolveFloorOwner`、PTT admission / floor request 路径。
- Slice 5–7 修改点：新增 `IdentityResolver`；Group PTT floor request / `applyFloorGrant` / release / capture 改经 Resolver；扩展 cold M03 回归（全程 `resolver.local == canonical`）。
- 回归测试：三机 cold M03 join → 首次 PTT → 无 `ROSTER_MISS`；`captureON` + `FLOOR_RELEASE`；理想无 `LOCAL_IDENTITY_DRIFT_COMPAT`。
- P1：`session.local` 静态引用扫描与逐步迁移；PTT_UP watchdog release；R37 扩展 local drift 检查。
- P2：legacy `session.local` 退役，全 runtime 唯一入口 `IdentityResolver.local(session)`。
- CONTEXT.md 增补 Runtime Local Identity、IdentityResolver 术语。

## Implementation Slices (non-normative)

| 顺序 | 切片 | 性质 |
|------|------|------|
| 1 | HELLO ingress canonical replace + rosterEpoch | Root fix (R35) |
| 2 | Floor grant resolve by moduleId | Compatibility (R36) |
| 3 | Block floor request until identity stable | Guard (R37) |
| 4 | Integration regression (cold M03 first PTT, no ROSTER_MISS) | Lock-down |
| 5 | ADR R38 + IdentityResolver + unit tests | Local runtime SSOT |
| 6 | Group PTT 四路径经 Resolver（request / grant / release / capture） | P0 wire-up |
| 7 | cold M03 回归：captureON、release、全程 canonical==resolver.local | Lock-down |

每个切片独立 PR / issue，按序合并。Slices 1–4 对应 issues #17–#20；5–7 对应 issues #21–#23。
