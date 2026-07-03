# Conference Host-Owned Lifetime

Conference Session 的生命周期必须由 **Host 显式处置** 决定，不能由当前参与人数、connected mesh 或 GROUP channel reclaim 隐式终止。2026-07 现场：所有参与者离开后 Host 未操作，会议仍被结束——根因是 `isLiveConferenceSession()` 将「无 connected remote」判为 stale，并喂给 `endStaleConferenceBlockingGroup()` 做 GROUP reclaim。

**决定原因**：Conference 是 **Host-Owned Session**（创建时即允许 `soloConference`），Rejoin 依赖 Host Session 持续存在。引入 Solo TTL 会把产品策略混入领域语义，且与 Rejoin 契约冲突。

**Status**: accepted（2026-07-03，Architecture Owner）

## 与既有 ADR 关系

- **ADR-0010**：`visibleParticipantCount` / `awaitingAdditionalParticipants` 是 UI 投影；Solo Host 应显示等待态，**不得**因 visible=1 推断 Session 已终止。
- **ADR-0012**：participant join/leave 经 Membership Transition；leave **不得**等价于 Conference terminate。
- **ADR-0008**：`CONFERENCE_END` 可触发 Group Topology reset；该事件 **仅** 在 Host 显式结束或 Host leave 后发出，不得因 roster 为空或 mesh 不健康而发出。

## 核心不变量

- **R-L1（Host-Owned Lifetime）**：Conference Session **SHALL** remain alive until:
  - Host explicitly ends the conference (`hangup` / End Meeting), or
  - Host leaves the conference (`leaveConference` / Leave Meeting).
- **R-L2（Participant 不终止 Session）**：Participant join/leave **MUST NOT** terminate the Conference Session on any node.
- **R-L3（无 Solo TTL）**：**MUST NOT** auto-end a Conference Session because connected peer count is zero, visible participant count is one, or a timer elapsed while Host remains in the meeting.
- **R-L4（Exists ≠ Operational）**：
  - **Conference Session Exists** — Host has not ended/left; Session object + channel mode CONFERENCE remain.
  - **Conference Session Operational** — at least one remote peer has connected media (or other transmit-ready criteria). Solo Host: Exists=true, Operational=false 是合法且预期的。
- **R-L5（Group Reclaim 不得销毁 Host Conference）**：`endStaleConferenceBlockingGroup()` 及同类 GROUP reclaim **MUST NOT** call `hangupInternal()` on a Conference Session where `initiatorModuleId == localModuleId` and Host has not ended/left. Channel resource recovery 与 Application Session 是不同层级。

## 拒绝的选项

| 选项 | 拒绝原因 |
|------|----------|
| Solo 30s/60s TTL 后 auto-end | 产品策略混入领域语义；Rejoin 窗口不可解释 |
| `connectedRemotes == 0` ⇒ not live ⇒ reclaim | 将 Operational 与 Exists 混为一谈 |
| 继续增强 `isLiveConferenceSession()` 单函数 | 名称已承载 Alive + Healthy 双重语义，应拆分 |

## 实现指引（非规范性，Issue 跟踪）

1. 引入 `conferenceSessionExists(session)` 与 `conferenceSessionOperational(session)`，替代在 reclaim 路径中使用 `isLiveConferenceSession()`。
2. `endStaleConferenceBlockingGroup()`：Host Conference 硬护栏（R-L5）。
3. 审计并修正 `handleCallReject` solo-mesh teardown、`sessionIdleTimeout` 等路径，确保不违反 R-L1–R-L3。
4. 红灯集成测试：`conference_hostRemainsAliveAfterAllParticipantsLeave`。

## Consequences

- Solo Host 可无限等待 re-invite / rejoin；UI 用 `awaitingAdditionalParticipants`，不是 `conferenceEndReason = REMOTE_ENDED`。
- GROUP PTT 在 Host 仍占 CONFERENCE 时继续被 gate 挡住（现有行为）；用户须 End/Leave Meeting 后才恢复 GROUP——这是 ChannelMode 互斥，不是 bug。
- Meeting→PTT 性能与 Warm Transition 不在本 ADR 范围；见 Issue Recovery Metrics / Warm Transition（P1/P2）。
