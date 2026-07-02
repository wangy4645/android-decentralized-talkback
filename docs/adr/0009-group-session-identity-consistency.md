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
        └── ADR-0009 Group Session Identity Consistency (R35–R39)
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

### Phase 3（R38 落地后，M02 发起方复测）

M03 冷启动路径改善后，M02 作为 Group 发起方出现 PTT / 收听失败。log 显示：

| 时间 | 事件 |
|------|------|
| 16:18:45 | M02 `meshCallInternal`：`members=M02,M03`（含 local） |
| 16:19:04 | `CANONICAL_MISMATCH`：local=`[M02,M03]`，authority=`[M01,M03]` |
| 16:19:04 | `snapshotApplied from=M01 members=2` → `members=M01,M03`（**local 从 roster 消失**） |
| 16:20:01 | M02 PTT：`requester=M02-E02`，`GRANT_DROPPED ROSTER_MISS` |

**已证实**：Consumer 侧 `applyMembershipSnapshot` → `applyGroupMembersList` 整表覆盖后 local ∉ roster。

**未证实（待调查）**：Producer 侧 — M01 的 canonical snapshot builder 为何发出不含 M02 的 roster。在回答该问题之前，**不得**将「snapshot apply 不得删除 local」等策略写入 normative 修复方案。

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

### R39 — Canonical Membership Completeness

> For every **accepted** Group session, the canonical roster (`session.groupMembers`, non-EVICTED) MUST contain exactly one `EndpointAddress` per participating `moduleId`, and **MUST** include the local module.

形式化：

```
∀ moduleId ∈ acceptedParticipants(session):
    ∃! endpoint : (moduleId, endpoint) ∈ canonicalRoster

localModuleId ∈ { m.moduleId | m ∈ canonicalRoster }
```

- **Runtime invariant, not a snapshot rule**：R39 约束 roster **最终必须成立的事实**；HELLO、membership snapshot、mesh 建组、evict 等仅为写入路径，不是 R39 本身。合法的 LEAVE/evict 后 local 可以不在 roster，但此时 session **MUST NOT** 再视为该 module 的 accepted active participant。
- **Producer vs Consumer**：违反 R39 可能源于 Authority 发出错误 snapshot（Producer），或 Consumer 错误应用（Consumer），或 Initiator 建组绕过 Authority 模型。诊断 **MUST** 区分二者，不得默认 Consumer apply 即为 bug。
- **与 R38 关系**：R39 是 R38 的前置条件；`local ∉ canonicalRoster` 时 IdentityResolver / Floor / Playback 的异常行为是 **非法 roster 的症状**，不是独立根因。
- **禁止过早修复策略**：在 Producer 根因未查清前，**MUST NOT** normatively 规定「snapshot apply 不得删除 local」等补丁；该命题仅可作为待验证假设。

#### R39 断言点（implementation，非 normative 修复）

| 侧 | 时机 | 目的 |
|----|------|------|
| **Producer** | Authority `serializeMembershipSnapshot` / broadcast 前 | 若此处违反 R39 → Authority canonical builder 错误 |
| **Consumer** | `applyMembershipSnapshot` / `applyGroupMembersList` 后 | 若此处违反 R39 → 收到非法 snapshot 或 apply 逻辑错误 |

指纹：`ROSTER_INVARIANT_BROKEN`（含 `sessionId`、`side=PRODUCER|CONSUMER`、`modules=…`、`localModuleId=…`）。

#### Roster Origin（诊断，非 SSOT）

每次 `groupMembers` 变更 **SHOULD** 记录 `rosterOrigin` 供现场归因（永久诊断字段，非控制面）：

| Origin | 含义 |
|--------|------|
| `LOCAL_BUILD` | Initiator `meshCallInternal` 初始 roster |
| `INVITE_METADATA` | Invitee `populateGroupSessionMetadata` |
| `AUTHORITY_SNAPSHOT` | `applyMembershipSnapshot` 整表对齐 |
| `HELLO_REBIND` | `replaceGroupMemberEndpoint` |
| `PROMOTE_INVITEE` | `promoteInviteeToCanonicalRoster` |
| `EVICTION` | `removeGroupMember` |

日志形态建议：`ROSTER_MUTATION origin=… modules=… sessionId=…`。

### R40 — Committed Projection Publication

> **Projection publication is commit-based rather than event-based.** Runtime events may mutate canonical membership multiple times within one authority mutation boundary; only the final committed state is eligible for publication.

> Membership snapshots MUST represent only the latest **committed** canonical membership state.

- **Facts → Projection**（ADR-0008）：Snapshot 是 canonical `groupMembers` 的投影，不是事件流中的第 N 条消息。
- **Deferred publication**：canonical 尚未 commit（R39 未满足）时 **MUST** defer；多个 pending 请求 **MAY** coalesce 为一次对最新 committed state 的 publish。
- **Commit boundary**：Authority mutation 边界内 **MUST** `markPublicationPending()`；离开边界前 **MUST** 调用一次 `flushPublicationIfReady()`。边界内允许多次 mutation，**MUST NOT** 每步 mutation 单独 publish。
- **PublicationController**：唯一 publish 门控；**MUST NOT** 在 gate 内 repair / promote / reconcile（R32）。
- **Pending state**：Runtime **SHALL** retain publication pending state（实现可用 generation 或等价机制，normative 不写死 bool）。

#### Publication Decision

| decision | 含义 |
|----------|------|
| `READY` | 可 publish |
| `DEFER` | 暂不可 publish；保留 pending |
| `IGNORE` | 非 authority 或非 GROUP；不 pending |

`DEFER` reason：`CANONICAL_INCOMPLETE`、`PARTICIPANT_PROMOTING`、`NOT_ACCEPTED`。

指纹：`MEMBER-PRODUCER`（含 `decision`、`reason`、`pendingGeneration`、`publishedGeneration`、`members`、`memberHash`、`rosterEpoch`）。

#### Commit Path（authority mutation boundaries）

| Mutation | markPending | flushBoundary |
|----------|-------------|---------------|
| `promoteInviteeToCanonicalRoster` | boundary | end |
| `replaceGroupMemberEndpoint` / HELLO rebound | boundary | end |
| `removeGroupMember` / evict | boundary | end |
| `becomeAuthority` / conference→group reconnect | boundary | end（待接线处须审计） |

**禁止**：直接 `broadcastMembershipSnapshot`；统一经 `MembershipPublicationController`。

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
| `ROSTER_INVARIANT_BROKEN` | R39 违反：`local ∉ roster` 或 module 重复；含 PRODUCER/CONSUMER 侧 |
| `ROSTER_MUTATION` | `groupMembers` 变更归因：`origin=… modules=…` |
| `CANONICAL_MISMATCH` + `snapshotApplied` | local 与 authority roster 分歧；Consumer 已执行覆盖 |
| `MEMBER-PRODUCER` | R40 publish 决策：`decision=READY|DEFER|IGNORE`、`reason=…`、`pendingGeneration` / `publishedGeneration` |

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
| 8 | ADR R39 + Producer/Consumer 双侧 R39 断言 | Invariant guard |
| 9 | Roster Origin 诊断（`ROSTER_MUTATION`） | Observability |
| 10 | R40 Committed Projection Publication + coalescing flush | Producer commit fix |
| 11 | M02 initiator + Mutation Storm 集成回归 | Lock-down |

每个切片独立 PR / issue，按序合并。Slices 1–4 对应 issues #17–#20；5–7 对应 issues #21–#23；8–9 为 R39 诊断；10 为 R40 Producer publication。
