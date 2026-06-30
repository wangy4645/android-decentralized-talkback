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
        └── ADR-0009 Group Session Identity Consistency (R35–R37, v1 invariant)
```

ADR-0008 的 `membershipReconciled` 与 `transmitMissingPeers` 在 identity 分裂时可能给出误导性「已连接」诊断；R35 是 membership 收敛的**前置条件**。

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

TopologySnapshot（ADR-0008）v2 **MAY** 增加 `identityDriftPeers: [moduleId]`；v1 实现可先用结构化 log `IDENTITY_REBOUND`。

## Considered Options

- **仅 grant fallback（moduleId resolve）**：掩盖 drift，Meeting 仍成唯一 reset；拒绝作为主方案。
- **仅 mesh / ICE 重连**：现场 ICE 已 CONNECTED；拒绝。
- **全局 Endpoint Directory 覆盖 groupMembers**：混淆 ADR-0006 门与形与 session roster；拒绝。
- **HELLO ingress replace + rosterEpoch + grant moduleId resolve + PTT gating（接受）**。

## Consequences

- 修改点：`TalkbackCoordinator` HELLO 处理、`GroupMembershipSupport`（replace helper）、`GroupFloorController.resolveFloorOwner`、PTT admission / floor request 路径。
- 回归测试：三机 cold M03 join → 首次 PTT → 断言无 `ROSTER_MISS`，grant `requesterKey` 与 mesh peer endpoint 一致。
- ADR-0008 TopologySnapshot 现场复测：修复前后对比 `membershipDigestAligned` 与 `ROSTER_MISS` 消失。
- CONTEXT.md 增补 Canonical Endpoint Binding、Identity Drift 术语。

## Implementation Slices (non-normative)

| 顺序 | 切片 | 性质 |
|------|------|------|
| 1 | HELLO ingress canonical replace + rosterEpoch | Root fix |
| 2 | Floor grant resolve by moduleId | Compatibility |
| 3 | Block floor request until identity stable | Guard |
| 4 | Integration regression (cold M03 first PTT) | Lock-down |

每个切片独立 PR / issue，按序合并。
