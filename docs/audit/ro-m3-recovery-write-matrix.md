# RO-M3 Recovery Write Matrix（审计草案）

**状态**：Draft（2026-07-10）— R20–R25 + Gate-R1/R24A；WM-R5/R6 Guardrail  
**目的**：冻结各事实域的 **Single Writer**，标出 #73 Edge Recovery 集成后可能 **越权写入** 的 callsite。  
**配套**：ADR-0021（Edge Recovery Lifecycle）、本文件 §4 T3 复现与 grep 模板。  
**原则**：PR-A 只恢复依赖方向；#73 先观测（RECOVERY_DECISION + S16）再改 Eligibility / Attempt。

---

## 0. 冻结不变量（WM-R1 – WM-R6）

| ID | 规则 |
|----|------|
| **WM-R1** | Recovery **must not** determine Rejoin Eligibility |
| **WM-R2** | Edge-scoped fact **must never** gate Conference-scoped decision |
| **WM-R3** | Recovery Commands may consume Conference Facts；Conference Facts **must not** consume Recovery Commands |
| **WM-R4** | **Membership Plane** emits `RejoinIntent` only；**Connectivity Plane** owns Recovery approval. Planes read facts; neither issues the other's commands (ADR-0021 R21) |
| **WM-R5** | **`FAILED_MEDIA_RECOVERY` Guardrail**：不得删除 `ConferenceSession`；不得触发 Lifecycle transition；不得改 Membership。只允许 `edge.state = FAILED*`。~~本 soak：未违反~~ → **P2-B soak（`c46a2ba0`）证伪**：participant M01 在 `FAILED_MEDIA_RECOVERY(M03)` 后 post-terminal prune 改了 canonical roster。**已由 ADR-0023 (R29) 落地为强制边界**（participant + post-terminal）。见 ADR-0021 **R24**、ADR-0023 **R29-C** |
| **WM-R6** | **False termination Guardrail（R25 P0）**：Connectivity facts（`NETWORK_LOSS` / `ICE_FAILED` / `TRANSPORT_LOST`）**不得**写入 `SESSION_CANCELLED`、`CONFERENCE_TERMINATED`、`channel_cancelled` / tombstone。只允许 `edge.state = RECOVERING` 或 `FAILED_MEDIA_RECOVERY*`。**R24A soak：tombstone 生命周期 bug** — 合法 `remote_hangup` tombstone 未在新 session clear，Recovery 误读。见 `docs/audit/r25-false-conference-termination.md` |

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
| **Reachability / Dispatch Gate** | `canDispatchRecoverySignal` 四维事实 | `EdgeReachabilitySnapshot` + `dispatchRecoveryReattachOutcome` | **间接** — gate 在 coordinator；controller 消费 `DEFERRED` → `RECOVERY_PENDING` |
| **Completion Re-evaluate** | reachability 跃迁后再决策 | `ConferenceEdgeRecoveryController`（P2-A 目标） | **是** — 待 P2-A 落地 |
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
| `removeConferenceParticipant` | — | — | 🟢 LEFT | — | 🟢 cancelEdge | — | **R29**：改签名带 `AuthorityMembershipMutationSource`；四写点（roster/memberModules/floor/mesh）为原子事务；participant 的 `LOCAL_RECOVERY_FAILURE` 不得进入此 API。见 ADR-0023 R29-A/B |
| `scheduleParticipantPrune` | — | — | 🟡 prune | — | — | — | **R26**：`RECOVERY_PRUNE_DEFERRED` while edge recovering |
| `cleanupUnhealthyConferenceSession` | — | — | 🟡 prune | — | — | — | **R26 v1**：defer via `canPruneConferenceParticipant`; post-terminal prune = R26 v2 |
| `clearConferenceRejoinState` | — | — | — | 🟢 | — | — | T3：终止后须 clear，否则 zombie rejoin |
| `sendConferenceRejoinIntentInternal` | — | — | — | 🟢 pendingRejoin | — | — | Conference rejoin intent；不读 Recovery |
| `dispatchRecoveryReattachOutcome` | — | — | — | 🟡 pendingRejoin | 🟢 DEFERRED→PENDING / drop if cancelled | — | R28 gate；Recovery reattach only |
| `handleConferenceRejoin` | — | — | 🟡 re-invite | — | 🟡 onReattachAccepted | 🟡 sendInvites | Host 接受 reattach |
| `ConferenceEdgeRecoveryController.onRequestReattach` → 上项 | — | — | — | 🟡 | 🟢 | — | 控制面；gate 后可能 DEFERRED |
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

### 3.5 Membership prune vs Edge Recovery window — **R26 已证实并修复（PR-R26A）**

```text
RECOVERY_EDGE_STARTED(P)
    ↓ (R25A 前)
scheduleParticipantPrune(FAILED) → removeConferenceParticipant → cancelEdge(member_left)
    ↓ (R26A)
RECOVERY_PRUNE_DEFERRED → recovery runs full attempt budget
```

Post-terminal `cleanupUnhealthyConferenceSession` prune **after** `FAILED_MEDIA_RECOVERY` is
**out of R26 v1 scope** (Membership survival semantics — R26 v2 / separate ADR).

**R26 v2 已由 ADR-0023 (R29) 兑现（2026-07-13）**：P2-B soak（`c46a2ba0`）证明 participant M01 在 `FAILED_MEDIA_RECOVERY(M03)` 后走 post-terminal prune，改了 canonical roster + memberModules + floor + mesh（authority-only mutation transaction 越权）。R29 冻结：membership mutation 为 authority-owned 原子事务；`LOCAL_RECOVERY_FAILURE` 仅为 recovery fact，不得 mutate membership，也不得隐式终止 edge obligation（耦合 R28-F）。

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

# R28 gate + completion waiting（ADR-0022 — 协议状态，非 debug）
adb logcat -d | rg "RECOVERY_(WAITING|REATTACH_DEFERRED|REATTACH_SENT|REATTACH_INBOUND|EDGE_RECOVERED|FAILED_MEDIA_RECOVERY)"
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
| `10:15:06` | **R1-A / R24** | `FAILED_MEDIA_RECOVERY` → owner vacuum → **R24-A 已修**（recovery 跑完怎么办） |
| `10:17:09–12` | **R1-B（后果，非根因）** | Host Leave → MISSING；**WM-R5 本轮未违反** |

#### Gate-R1-R24A soak 结论（`logs-gate-r1-r24a-20260710-104756`，M03 host，M01 WiFi）

| 阶段 | 判定 | 说明 |
|------|------|------|
| M03→M02 recovery timeout | **R24-A OK** | `edgeRecoveryFailed=true` + `conferenceDegraded=true` + `phase=ACTIVE` |
| M01 WiFi loss `40f26355` | **R25 / P0** | tombstone from prior `remote_hangup` → Recovery blocked。详见 `docs/audit/r25-false-conference-termination.md` |
| M02 `visible=3` vs M03 `visible=2` | **P2** | Host roster 正确；participant projection 滞后 — 不阻塞 R25 |

#### Gate-R1-R26A soak 结论（`logs-gate-r1-r26a-20260710-114335-manual`，M02 host，M01 WiFi）

| 阶段 | 判定 | 说明 |
|------|------|------|
| Recovery window | **R26 PASS** | `RECOVERY_PRUNE_DEFERRED` ×2；无 `pruning peer`；roster=3 至 `attempt_timeout` |
| Post-terminal | **R26 v2 待决策** | `11:47:02` `Pruning unhealthy conference peer M01`（post-window，不属 Phase A fail） |
| S13 闭环 | **FAIL** | 无 `RECOVERY_EDGE_RECOVERED`；participant 离线时 reattach 无法完成 — 与 R26 正交 |

#### Gate-R28-D soak 结论（`logs-s13b-reattach-reachability-20260710-161257`，M02 host，M01 WiFi）

| 阶段 | 判定 | 说明 |
|------|------|------|
| R28 gate | **PASS** | M01：`RECOVERY_REATTACH_DEFERRED` + `WAITING_FOR_ROUTE` ×2；**零** `RECOVERY_REATTACH_SENT` while `routeConverged=false` |
| 失败语义 | **已分类** | 从「错误动作（SENT 未达 host）」→「缺 completion continuation（DEFERRED 后无 re-evaluate）」 |
| S13 闭环 | **仍 FAIL** | M01/M02 `FAILED_MEDIA_RECOVERY attempt_timeout`；M01 HELLO 恢复 ~16:11:14 **晚于** timeout — **P2-A** 范围 |
| 两层状态 | **冻结** | R28-D ✅ · S13 completion ❌ → 见 `docs/audit/p2a-completion-re-evaluate-seam.md` |

实现：`ConferenceAuditTimelineLog.kt` + `TalkbackCoordinator` 挂钩；`RECOVERY_DECISION` 在 `ConferenceEdgeRecoveryController`。R28：`EdgeReachabilitySnapshot` + `dispatchRecoveryReattachOutcome`（`baf393b`）。

---

## 6. 本阶段禁止项

- 不 merge #73 行为修复（Eligibility / Rejoin≠Reattach）直到 `RECOVERY_DECISION` 证实假设
- **R24 v1 = Strategy A only**（已落地）；禁止 Strategy B handoff 未证明前上线
- **禁止 P0-A″**：`hostIce=FAILED` + debounce → 强制 `ACTIVE+degraded`（会掩盖 R25 false termination）
- **R25A 已落地**：Recovery 解耦 `isChannelCancelled`；**不做** `clearChannelCancellation`（留 R25B）
- **R26A 已落地**：Recovery window Membership guard；**R26 v2 / R27 冻结**
- **R28 gate 已落地**：禁止 `routeConverged=false` 时 dispatch reattach；**禁止**用 `resend()` patch 替代 P2-A re-evaluate
- **R25 根因未明前**：禁止再改 CONNECTING / ACTIVE 投影规则（CONNECTING 可能是正确后果）

---

## 7. 执行顺序（冻结 2026-07-10）

| 步 | 内容 | 验证 |
|----|------|------|
| 1–5 | PR-A / RECOVERY_DECISION / Phase A/B | 已完成 |
| **6** | **R24 Strategy A** | **已落地**（`53fe19f`）；recovery timeout → `ACTIVE+conferenceDegraded` |
| **7** | **PR-R25A** — Recovery 解耦 channel tombstone | **已落地**；S17 PASS |
| **8** | **PR-R26A** — Recovery ownership window guard | **已落地** `ab73ad8`；S18 PASS |
| **9** | **R28 reachability gate** — `EdgeReachabilitySnapshot` + DEFERRED/WAITING | **已落地** `baf393b`；G-R28-D PASS |
| 10 | **P2-A** — completion re-evaluate seam (R28-E/F/G) | **Frozen** — `p2a-completion-re-evaluate-seam.md` Accepted; code pending |
| 11 | **P2-B** — re-evaluate 动作决策树 | S13-E / INBOUND |
| 12 | **R25B session-scoped CancellationToken** | tombstone key = `(channelId, sessionId, generation)` |
| 13 | **WM-R6** 断言/门禁 | Connectivity loss 不 tombstone channel（Recovery 侧已解耦） |
| 14 | **WM-R5** Guardrail | `FAILED_MEDIA_RECOVERY` 后无 lifecycle 越权 |
| — | **R26 v2** post-terminal roster semantics | **由 ADR-0023 (R29) 兑现（2026-07-13）** — participant + post-terminal membership mutation boundary |
| — | Host Link Bootstrap / R24-B | 冻结 |
| — | Issue1 Host LIVE | 冻结 |
| — | M02 `visible=3` vs host `visible=2` | **P2**（roster ∩ presence） |

---

## 8. 参考文件

- `docs/audit/s13b-recovery-reattach-reachability.md` — #73-B Phase 1：Reattach 信令可达性 + S13-B 矩阵 + G-R28-D
- `docs/audit/p2a-completion-re-evaluate-seam.md` — P2-A re-evaluate seam（Draft）
- `docs/audit/r25-false-conference-termination.md` — R25 写入链 + 升级矩阵
- `android-board-talkback/.../ConferenceEdgeRecoveryController.kt`
- `android-board-talkback/.../TalkbackCoordinator.kt`（`conferenceEdgeRecoveryController` lazy 块）
- `docs/adr/0021-conference-edge-recovery-lifecycle.md`
- `talkback-app/.../TalkViewModel.kt`（`joinMeeting`）
