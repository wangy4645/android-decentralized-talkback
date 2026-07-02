# Conference Membership vs Media Projection

Conference UI 曾用 mesh ICE 直连数作为「在会人数」，在 hub/star 拓扑下参与者少计一人；头像列表也曾直接消费完整 `memberViews`（邀请 + 接受 + 媒体混源）。根因是 **data-plane connectivity 与 control-plane membership 被 UI 直接解释**。

**决定原因**：Conference 需两层只读投影——roster（控制面）与 visible participants（展示面）。UI 不得从 Runtime Facts（invite/media/ICE）自行推导用户可见语义。

## 核心不变量

- **R41（双投影分离）**：
  - **Roster / `memberViews`**：`conferenceParticipantManager` → 谁属于会议（INVITED / ACCEPTED / LEFT 等）。
  - **Visible / `visibleParticipants`**：`ConferenceParticipantProjector` → 用户此刻应认为谁已在会。
  - **Mesh / `meshConnectedPeerCount`**：ICE 直连 → 诊断、transmit readiness；**不得**驱动 Conference UI。
- **R42（禁止混源）**：不得对 Conference UI 做 roster + mesh 或 roster + 本地 filter 混合计数。
- **R43（UI 投影原则）**：**UI MUST NOT interpret runtime facts directly.** 一切用户可见语义必须有专用 Projection（与 R32 一致）。
- **R44（Conference UI 唯一来源）**：**`ConferenceVisibleParticipants` is the canonical UI projection for conference participant presence.** 头像、人数、Speaking View、等待提示等 **必须且只能** 从此投影派生。

### Visible 规则（Projector 权威）

远端进入 `visibleParticipants` 当且仅当：

```text
invite == ACCEPTED
∧ 不在 leftMemberEndpoints（LEFT）
∧ media != NONE   // CONNECTING | CONNECTED | RECONNECTING | FAILED 均可
```

本机：`session.accepted` 时以 `VISIBLE_LOCAL` 计入。

`FAILED` **显示**（`VISIBLE_FAILED`），不隐藏。

## Snapshot 字段

| 字段 | 语义 | Conference UI |
|------|------|---------------|
| `memberViews` | Roster 投影 | 否（调试/Host） |
| `visibleParticipants` | `ConferenceParticipantViewState` | **是** |
| `visibleParticipantCount` | 可见人数（含本机） | **是** |
| `awaitingAdditionalParticipants` | 仍有未进入的邀请对象 | 等待提示 |
| `meshConnectedPeerCount` | ICE 直连 peer 数 | 否（诊断） |
| `connectedRemoteCount` | 同 `meshConnectedPeerCount`（遗留字段名） | 否 |

GROUP/UNICAST 继续使用 mesh 语义字段作链路诊断；Conference UI 只用 visible 字段。

## Considered Options

- **ViewModel `memberViews.filter(...)`**：业务规则散落 UI 层 → 拒绝（R43）。
- **`media == CONNECTED` only**：ICE 抖动导致头像闪灭 → 拒绝。
- **`acceptedRemoteCount` 驱动 UI**：ACCEPTED 但 media=NONE 仍显示 → 拒绝。

## Consequences

- `ConferenceParticipantProjector` + `ConferenceParticipantViewState` + `ConferenceParticipantDisplayState`。
- `TalkViewModel` / `MeetingFragment` 只消费 `visibleParticipants` / `visibleParticipantCount`。
- 单元测试覆盖 Projector 状态表；集成测试覆盖三节点 visible 一致。
