# RO-M3 Recovery Write Matrix（审计草案）

**状态**：Draft（2026-07-10）— PR-A 已落地；R20–R24 + Gate-R1；WM-R5 Guardrail  
**目的**：冻结各事实域的 **Single Writer**，标出 #73 Edge Recovery 集成后可能 **越权写入** 的 callsite。  
**配套**：ADR-0021（Edge Recovery Lifecycle）、本文件 §4 T3 复现与 grep 模板。  
**原则**：PR-A 只恢复依赖方向；#73 先观测（RECOVERY_DECISION + S16）再改 Eligibility / Attempt。

---

## 0. 冻结不变量（WM-R1 – WM-R5）

| ID | 规则 |
|----|------|
| **WM-R1** | Recovery **must not** determine Rejoin Eligibility |
| **WM-R2** | Edge-scoped fact **must never** gate Conference-scoped decision |
| **WM-R3** | Recovery Commands may consume Conference Facts；Conference Facts **must not** consume Recovery Commands |
| **WM-R4** | **Membership Plane** emits `RejoinIntent` only；**Connectivity Plane** owns Recovery approval. Planes read facts; neither issues the other's commands (ADR-0021 R21) |
| **WM-R5** | **`FAILED_MEDIA_RECOVERY` Guardrail（防护约束，非本轮根因）**：不得删除 `ConferenceSession`；不得触发 Lifecycle transition；不得改 Membership。只允许 `edge.state = FAILED*`。**本 soak：未违反**（R1-B = Host `MEETING_ENDED`）。见 ADR-0021 **R24** |

### 双平面（冻结 2026-07-09）

```text
Membership Plane              Connectivity Plane
----------------              ------------------
JOIN / LEAVE / REJOIN         ICE / DTLS / PC
MEMBERSHIP                    RECOVERY / REATTACH / ICE_RESTART
        │                              │
        └──── read facts only ─────────┘
```

反模式（B2 根因）：

```text
Rejoin (Membership) → RECOVERY_REATTACH (Connectivity)   ❌
```

依赖方向：

```text
Conference / Membership / Lifecycle  →  Edge Recovery     ✅
Edge Recovery / ICE / channel tombstone  →  Rejoin Hint     ❌ (PR-A 已拆)
Membership Plane  →  Recovery start                ❌ (R21)
Recovery Plane    →  Membership mutation            ❌ (R10)
```

**PR-A API 拆分：**

| API | 职责 | 允许读 Recovery Gate？ |
|-----|------|------------------------|
| `rememberRejoinableConference` | 写 Rejoin Hint | ❌ — 用 `conferenceLifecycleIsRejoinEligible()` |
| `sendConferenceRejoinIntentInternal` | joinMeeting / host retry | ❌ — 用 live host session |
| `sendRecoveryReattachInternal` | RecoveryController 回调 | ✅ — `isChannelCancelled` 仅此处 |

---

## 1. 事实域与 Single Writer

| 事实域 | 权威含义 | Single Writer（设计） | Recovery 允许写？ |
|--------|----------|----------------------|-------------------|
| **Conference Lifecycle** | ESTABLISHED / TERMINATED；channel 上是否仍有 live conference | Host lifecycle + `releaseConferenceRuntimeAfterRemoteTermination` / `hangupInternal` | **否** — 只能读 eligibility；终止由 lifecycle 主导 |
| **Session Identity** | `sessionId`、host `hostSessionId`、lineage | `meshCallInternal`（新建）、`acceptGroupInvite`（入会后绑定） | **否** — Recovery 不得 `sessions[id]=` 新建会 |
| **Membership** | JOINED / LEFT（committed） | `ConferenceParticipantManager` + leave/remove 路径 | **否** — eligibility 只读 LEFT 集合 |
| **Rejoin Hints** | `lastRejoinableConferenceByChannel`、`pendingRejoinByChannel` | `saveConferenceRejoinMemory` / `clearConferenceRejoinState` | **间接** — `sendConferenceRejoinInternal` 写 pending；终止须 `clear` |
| **Edge Recovery State** | per-edge FSM、attempt id、tombstone | `ConferenceEdgeRecoveryController` | **是** — 本 ADR 所有权 |
| **Media / ICE** | PC、ICE restart、mesh engine | `MediaSessionManager` / coordinator media 路径 | **执行 only** — 由 controller 回调触发，不自行决策 |
| **Authority Reachability** | UI「能否连上 host」 | **投影派生**（`isPeerMediaConnected(host)`）— 非独立写域 | **否** — 禁止为修 UI 改 lifecycle |
| **Runtime Projection** | phase、edgeRecovering、authorityReachable | `ConferenceRuntimeProjector` + `emitConferenceRuntimeProjection` | **否** — 只读 facts |

---

## 2. 写入矩阵（Callsite × 域）

图例：**🟢** 允许 · **🟡** 需审计（可能越界） · **🔴** 明确越权风险

| Callsite | Lifecycle | Session | Membership | Rejoin Hints | Edge FSM | Media | 备注 |
|----------|-----------|---------|------------|--------------|----------|-------|------|
| `meshCallInternal` | 🟢 创建 ESTABLISHED | 🟢 新 UUID session | 🟢 sync roster | — | — | 🟢 invite/mesh | Host 建会唯一入口之一 |
| `acceptGroupInvite` | 🟢 参与方入会 | 🟢 绑定 host sessionId | 🟢 onInviteAccepted | — | — | 🟢 mesh | 参与方不得换 sessionId |
| `hangupInternal` | 🟢 LOCAL 终止 | 🟢 remove session | 🟢 dispose | 🟢 clear rejoin | 🟢 cancelChannel/Session | 🟢 release media | 本地挂断权威 |
| `releaseConferenceRuntimeAfterRemoteTermination` | 🟢 REMOTE 终止 | — | — | 🟢 clearRejoin | 🟢 cancelChannel | 🟡 条件 release channel | 须先于 recovery 完成 |
| `removeConferenceParticipant` | — | — | 🟢 LEFT | — | 🟢 cancelEdge | — | member_left 应阻断 recovery |
| `clearConferenceRejoinState` | — | — | — | 🟢 | — | — | T3：终止后须 clear，否则 zombie rejoin |
| `sendConferenceRejoinIntentInternal` | — | — | — | 🟢 pendingRejoin | — | — | Conference rejoin intent；不读 Recovery |
| `sendRecoveryReattachInternal` | — | — | — | 🟡 pendingRejoin | 🟢 drop if cancelled | — | Recovery reattach only |
| `handleConferenceRejoin` | — | — | 🟡 re-invite | — | 🟡 onReattachAccepted | 🟡 sendInvites | Host 接受 reattach |
| `ConferenceEdgeRecoveryController.onRequestReattach` → 上项 | — | — | — | 🟡 | 🟢 | — | 控制面先于媒体 |
| `onIceRestart` → `attemptConferencePeerIceRestart` | — | — | — | — | 🟢 | 🟢 | 有界 ICE restart |
| `joinMeeting` / `openMeetingScreen` | 🟡 可能触发新 mesh | 🟡 ensureChannelSession | — | 🟡 requestRejoin | 🟡 UI 触发 recovery | — | **T4**：UI 不应成为 recovery 主触发 |
| `acceptGroupJoin` RECOVERY_REATTACH | — | — | 🟡 mark active | — | 🟡 onReattachAccepted | 🟡 ICE restart | 与 handleConferenceRejoin 双入口 |

---

## 3. 标红：疑似越权链（待 Timeline 证实）

### 3.1 Recovery 在 TERMINATED 后仍写 Rejoin / Reattach — **T3 根因已证实（PR-A 修复）**

```text
Prior hangup → cancelChannel(CH-01) tombstone
  ↓ (PR-A 前)
rememberRejoinableConference → isChannelCancelled → skip hint
  ↓
joinMeeting → new conference → 新 sessionId
```

**PR-A 修复：** hint 与 intent 路径改读 `conferenceLifecycleIsRejoinEligible` / live host session；Recovery gate 仅保留在 `sendRecoveryReattachInternal`。

### 3.2 Recovery 链触碰 Session / Lifecycle

| 链路 | 风险 |
|------|------|
| `onReattachAccepted` → ICE restart → `runOnCoordinatorSync` | 已修死锁；仍须确认不 create session |
| `handleConferenceRejoin` → `sendConferenceInvitesInternal(rejoin=true)` | 在 host session 上复活成员，**不得** fork 新 host session |
| `member_left` 后 `cancelEdge` | T3/T4 仍失败 → 可能有 **其他入口** 未 cancel（如 channel 级未 TERMINATED） |

### 3.3 Authority 与 Recovery 混淆

- `isConferenceAuthorityReachable` = host ICE connected（**非** lifecycle）。
- `edgeRecovering` / `mediaRecovering` 分属两套 controller（#72 media vs #73 edge）。
- **禁止**：为修 T2（authority=false → Connecting）去改 lifecycle 或强制 SOLO_HOST。

### 3.4 UI 触发 Recovery（T4）

`TalkViewModel.joinMeeting(reason=ui.openMeetingScreen)` → `requestConferenceRejoin` / `hasRejoinableConference`  
Recovery 应按 **ICE debounce + eligibility** 自动触发，不应依赖用户 reopen。

---

## 4. T3 复现步骤与日志 grep

### 4.1 手测步骤（M03 host）

1. M03 发起会议，M01、M02 加入，确认 ACTIVE。
2. M01、M02 **退出会议**（非 host hangup）。
3. M03 保持 host；M01 或 M02 再次 `openMeetingScreen` / join。
4. **失败特征**：无法回到原会；logcat 出现 **新 `sessionId`**，同 `channelId`。

### 4.2 Grep 模板（logcat / soak 目录）

```bash
# 生命周期时间线（新打点）
adb logcat -d | rg "CONFERENCE_LIFECYCLE_TIMELINE"

# 按 channel 串会话创建与终止
adb logcat -d | rg "CONFERENCE_LIFECYCLE_TIMELINE.*ch=<CHANNEL_ID>"

# 终止 vs 新建 session
adb logcat -d | rg "CONFERENCE_LIFECYCLE_TIMELINE.*(REMOTE_TERMINATION|LOCAL_HANGUP|SESSION_CREATED|REJOIN_STATE_CLEARED).*ch=<CHANNEL_ID>"

# 旧日志（仍有效）
adb logcat -d | rg "CONFERENCE_TERMINATED|JOIN_MEETING_TRACE|RECOVERY_REATTACH"

# Authority 跌落（T2）
adb logcat -d | rg "AUTHORITY_TIMELINE.*reachable=false"

# Edge recovery（已有标签，无需新前缀）
adb logcat -d | rg "RECOVERY_EDGE_|RECOVERY_REATTACH_|RECOVERY_EVENT_DROPPED"
```

### 4.3 T3 判定表

| 顺序 | 期望 | 若违反 → 怀疑 |
|------|------|----------------|
| 1 | `REMOTE_TERMINATION` 或 host 侧 `LOCAL_HANGUP` | 会未正确终止 |
| 2 | `REJOIN_STATE_CLEARED` 同 channel | zombie rejoin hint |
| 3 | 无 `RECOVERY_REATTACH` after `CONFERENCE_TERMINATED` | S14 类越权 |
| 4 | 重进为 **同 hostSessionId** 或明确 rejoin 成功 | 新 `SESSION_CREATED` = lifecycle 越界 |

---

## 5. Timeline 打点（最小集）

| Tag | 字段 | 写入点 |
|-----|------|--------|
| `CONFERENCE_LIFECYCLE_TIMELINE` | `event, ch, session, writer, cause` | meshCall、acceptInvite、hangup、remote termination、clearRejoin |
| `AUTHORITY_TIMELINE` | `session, ch, host, reachable, hostIce, writer, cause` | `emitConferenceRuntimeProjection`（仅 reachable **变化**） |
| Edge（已有） | `RECOVERY_*` | `ConferenceEdgeRecoveryController.onLog` |
| **RecoveryDecision** | `trigger, recoveryReason, terminationReason, policy, approved, attempt, edge` | `RECOVERY_DECISION` — Reason=策略来源，Trigger=事件；Policy 决策 phase 2 |
| **`CONFERENCE_RUNTIME_DECISION`** | `conferenceSessionPresent, conferenceSessionId, conferenceGeneration, authorityReachable, hostIce, hostEngine, hostConfEngine, meshIcePeers, edgeRecovering, phase` | `projectConferenceRuntimeState`（snapshot 路径；签名变化才打）— **Gate-R1-A** |
| **`CONFERENCE_RUNTIME_MISSING`** | `conferenceSessionPresent=false, peer, ice, reason` | mesh ICE CONNECTED 且无 CONFERENCE session — **Gate-R1-B** |

### Gate-R1（Issue2 A/B 判定，冻结）

ICE_CONNECTED 后 5s 内必须二选一：

| 结果 | 日志 | 含义 | 修法方向 |
|------|------|------|----------|
| **R1-A** | `CONFERENCE_RUNTIME_DECISION` | Session 还在；看 `authorityReachable` / `hostIce` / `edgeRecovering` | **P0 = R24**：Recovery completion ownership（非「改 UI」） |
| **R1-B** | `CONFERENCE_RUNTIME_MISSING` | Session 已没；媒体残留 mesh | 先分清 Host 终止 vs Recovery 越权；本 soak = Host Leave |

**禁止第三态**：ICE 已恢复 + 既无 DECISION 也无 MISSING。

脚本：`scripts/assert-gate-r1-runtime-alive.ps1`。Issue1（Host LIVE）冻结，不在本 gate 范围。

#### Gate-R1 soak 结论（`logs-gate-r1-20260710-101344`，M02 WiFi）

| 阶段 | 判定 | 说明 |
|------|------|------|
| `10:15:06` | **R1-A / P0** | `FAILED_MEDIA_RECOVERY` → `edgeRecovering=false` + `hostIce=FAILED` → `CONNECTING`；HELLO 恢复后仍 `Deferring … host link` — **owner vacuum**（ADR R24） |
| `10:17:09–12` | **R1-B（后果，非根因）** | Host `leaveConferenceInternal` → `MEETING_ENDED` → MISSING；**≠** Recovery 删 Session；**WM-R5 本轮未违反** |

实现：`ConferenceAuditTimelineLog.kt` + `TalkbackCoordinator` 挂钩；`RECOVERY_DECISION` 在 `ConferenceEdgeRecoveryController`。

---

## 6. 本阶段禁止项

- 不 merge #73 行为修复（Eligibility / Rejoin≠Reattach）直到第二轮 soak 用 `RECOVERY_DECISION` 证实假设
- 不加 S3「>10s 强制 restart」类 patch — **S3 = Host Link Bootstrap Optimization**，与 #73 脱钩
- **R24 v1 = Strategy A only**；禁止 `FAILED_MEDIA_RECOVERY → HostLinkBootstrap.resume()`（Strategy B 未证明 mid-call ownership / budget / 无 ping-pong 前不做）
- 不用 Projection 掩盖 `authority=false`；但 **允许** Projection 在 `edge.state=FAILED*` 时保持 `ACTIVE + degraded`（这是 R24-A 的合法输出，不是掩盖）
- 不把本 soak 的 R1-B 记成 Recovery 越权根因
- 不堆 Recovery 功能；先拿 `RECOVERY_DECISION` + S16 证明 Write Matrix 标红项；**下一步 P0 = 落地 R24 Strategy A**

---

## 7. 执行顺序（冻结 2026-07-10）

| 步 | 内容 | 验证 |
|----|------|------|
| 1 | **Commit PR-A** | S15A/B PASS |
| 2 | **RECOVERY_DECISION + S16** | soak 可见 `approved` / `terminationReason` |
| 3 | Recovery Eligibility + `TerminationReason` gate | S16 PASS；无 `USER_LEAVE → approved=true` |
| 4 | Rejoin ≠ `RECOVERY_REATTACH`；Attempt 生命周期 | B2 无声/Connecting 消失 |
| 5 | **Phase A/B**：USER_REJOIN 移出 Recovery 输入域；S17 | `JOIN_RESTORE_STARTED`；零 `USER_REJOIN approved=true` |
| **6** | **P0：R24 Strategy A（degraded residency）** — timeout 后 `ACTIVE+degraded`；禁 `CONNECTING` / 禁 bootstrap / 禁 auto ICE restart | **落地**：`conferenceDegraded` + `anyFailedMediaRecovery`；`ConferenceBootstrapDeferral` 跳过会中 residency；Gate-R1 soak 待复验 |
| 7 | **WM-R5** 断言/门禁（Guardrail） | `FAILED_MEDIA_RECOVERY` 后无 `sessions.remove` / Lifecycle |
| 8 | **Host Link Bootstrap / R24-B**（独立；须先证明 mid-call ownership） | 未开；防 Recovery↔bootstrap ping-pong |
| — | Issue1 Host LIVE（ADR-0020） | **冻结** |
| — | M03 `visible=3` Projection | **P2** |

---

## 8. 参考文件

- `android-board-talkback/.../ConferenceEdgeRecoveryController.kt`
- `android-board-talkback/.../TalkbackCoordinator.kt`（`conferenceEdgeRecoveryController` lazy 块）
- `docs/adr/0021-conference-edge-recovery-lifecycle.md`
- `talkback-app/.../TalkViewModel.kt`（`joinMeeting`）
