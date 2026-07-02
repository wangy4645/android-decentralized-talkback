# Conference Mesh Reconcile vs Formal Invite

`GROUP_INVITE` 在 Conference 路径上曾承载两种互斥语义：带 SDP 的正式邀请，以及 Host 广播的 mesh 重规划触发器（`sdp=""`, `rejoin=true`）。后者被 `handleGroupInvite` 落入 pending / rejoin-auto-accept 流程，导致 `SessionDescription is NULL` 与 pending 污染（2026-07 现场 regression）。

**决定原因**：控制面 reconcile 信号与数据面 invite 生命周期必须分离；隐式字段组合（`sdp==""`）不足以长期区分语义，但短期须用三元判定堵住回归。

## 核心不变量

- **R-A1（语义隔离）**：`CONFERENCE_MESH_RECONCILE` 控制信号 **MUST NOT** create/mutate `TalkbackSession`、**MUST NOT** enter pending invite queue、**MUST NOT** trigger accept/reject flow。
- **R-A2（入口强分流）**：`handleGroupInvite()` 在 parse 后 **第一时间** classify；reconcile 路由至 `routeConferenceMeshReconcileInvite()` 并 `return`，不得进入 counter-invite、pending、`acceptGroupInvite` 等 invite pipeline。
- **R-A3（识别条件）**：

```text
isConferenceMeshReconcileInvite :=
    sessionType == CONFERENCE
    AND rejoin == true
    AND sdp.isNullOrEmpty()
    AND membershipSnapshot == null
```

正式 Conference invite（含 rejoin invite）**必须**携带非空 SDP offer。

## 与 GROUP 先例对齐

GROUP 已用 `membershipSnapshot != null` 区分 snapshot-only `GROUP_INVITE` 与正式 mesh invite。Conference reconcile 采用同等模式：控制信号在入口剥离，不依赖 accept 层兜底。

## Considered Options

- **在 `acceptPendingConferenceInvite` / `canAcceptGroupInvite` 校验 sdp**：已进入 Session Lifecycle Domain，过晚；拒绝。
- **新增 `SignalType.CONFERENCE_MESH_RECONCILE`**：最清晰；列为后续迁移（Implementation Slice B）。
- **仅依赖 `sdp==""`**：与未来 payload reuse 冲突；拒绝（R-A3 三元判定）。

## Consequences

- `routeConferenceMeshReconcileInvite`：无 accepted session 时 log `ignored` 并无副作用。
- 有 accepted session 时仅调用 `reconcileConferenceMesh` + mesh repair 调度。
- 回归测试：`conferenceMeshReconcile_doesNotPollutePendingInvite`。
- 长期：payload 增加显式 `inviteKind: INVITE | RECONCILE | MEMBERSHIP_SNAPSHOT`，或独立 SignalType。
