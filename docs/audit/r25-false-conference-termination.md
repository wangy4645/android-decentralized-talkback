# R25 — False Conference Termination（审计）

**状态**：Draft（2026-07-10）— Gate-R1-R24A soak 逆向写入链完成  
**Soak**：`logs-gate-r1-r24a-20260710-104756` · M01 · session `40f26355` · channel `CH-01`  
**配套**：ADR-0021 §R25、Write Matrix WM-R6

---

## 1. 结论（先读）

**不是**「WiFi 断线直接写了 `channel_cancelled`」。

**是**「上一场的 **合法** `remote_hangup` tombstone（120s TTL）在新 session 仍活跃时，Recovery 消费者把它当成 **当前 session 已终止**」。

```text
10:51:01.615  cancelChannel(CH-01) reason=remote_hangup     ← 写入（合法，上一场 0baccf23）
10:51:29.965  SESSION_CREATED 40f26355 acceptGroupInvite    ← 未 clear tombstone
10:51:42.577  ICE DISCONNECTED (M01 WiFi off)               ← 触发
10:51:42.587  isChannelCancelled(CH-01)=true               ← 误读
              → RECOVERY_DECISION SESSION_CANCELLED / CONFERENCE_TERMINATED
              → RECOVERY_EVENT_DROPPED
```

PR-A 已从 **Rejoin hint** 路径移除 `isChannelCancelled` 门控；**Recovery 路径仍消费 channel tombstone** — 与用户假设一致。

---

## 2. 写入链清单（grep 结果）

### 2.1 `cancelChannel` — 唯一写入点

| # | Writer | Caller | Reason | 合法？ |
|---|--------|--------|--------|--------|
| W1 | `ConferenceEdgeRecoveryController.cancelChannel` | `TalkbackCoordinator.releaseConferenceRuntimeAfterRemoteTermination` | `remote_hangup` | ✓ lifecycle |
| W2 | ↑ | ↑ | `remote_meeting_ended` | ✓ lifecycle |
| W3 | ↑ | `TalkbackCoordinator.hangupInternal` | `local_hangup` | ✓ lifecycle |

**存储**：`cancelledChannels[channelId] = now + tombstoneTtlMs`（默认 **120_000 ms**）  
**文件**：`ConferenceEdgeRecoveryController.kt:308-314`

**无** `markChannelCancelled` / `channelCancellationTokens` / 其他写入 API。

### 2.2 `cancelSession` — 唯一写入点

| Writer | Caller | Reason |
|--------|--------|--------|
| `ConferenceEdgeRecoveryController.cancelSession` | `hangupInternal` | `local_hangup` |

Session tombstone 与 channel tombstone **独立**；soak 中 M01 在 `10:51:42` 触发的是 **channel** 侧（`isChannelCancelled`）。

### 2.3 `releaseConferenceRuntimeAfterRemoteTermination` 调用栈（W1 soak 实例）

```text
handleSignal
  → handleHangup                    TalkbackCoordinator.kt:4655
    → releaseConferenceRuntimeAfterRemoteTermination(ch, "remote_hangup")  :4670
      → cancelChannel(channelId, reason)                                   :595
      → log CONFERENCE_TERMINATED ch=… clearRejoinState=true               :604
```

Soak 日志（M01）：

```text
10:51:01.615 CONFERENCE_LIFECYCLE_TIMELINE event=REMOTE_TERMINATION ch=CH-01
             writer=releaseConferenceRuntimeAfterRemoteTermination cause=remote_hangup
10:51:01.615 CONFERENCE_TERMINATED ch=CH-01 reason=remote_hangup clearRejoinState=true
10:51:01.769 [56732af2|0baccf23-…] Remote hangup
```

---

## 3. 消费者链（Recovery 侧 — PR-A 未覆盖）

```text
cancelChannel()  [W1/W2/W3]
       ↓
cancelledChannels[channelId]  (TTL 120s)
       ↓
isChannelCancelled(channelId)
       ↓
┌──────────────────────────────────────────────────────────────┐
│ C1  onIceStateChanged (gate)          :82  → RECOVERY_DECISION│
│ C2  buildEdgeRecoveryEligibility       :2192 → conferenceTerminated│
│ C3  sendRecoveryReattachInternal       :3315 → RECOVERY_EVENT_DROPPED│
└──────────────────────────────────────────────────────────────┘
```

**Rejoin 路径（PR-A 已修）**：`conferenceLifecycleIsRejoinEligible` **不**读 `isChannelCancelled`。  
集成测试：`conference_channelTombstone_doesNotBlockRejoinHintWhenNewHostAlive`。

**Recovery 路径（R25 命中）**：C1/C2/C3 仍读 channel tombstone。

### 3.1 `terminationReason=CONFERENCE_TERMINATED` 来源

Recovery **不写入** termination；仅 **映射** eligibility：

```kotlin
// ConferenceEdgeRecoveryController.kt — onIceStateChanged early return
trigger = SESSION_CANCELLED
terminationReason = CONFERENCE_TERMINATED   // 硬编码映射，非独立事实写入
rejectReason = session_or_channel_cancelled

// beginRecovery eligibility gate
eligibility.conferenceTerminated → RecoveryTerminationReason.CONFERENCE_TERMINATED
```

`buildEdgeRecoveryEligibility`：

```kotlin
conferenceTerminated = channelId != null &&
    conferenceEdgeRecoveryController.isChannelCancelled(channelId)
```

---

## 4. R25 升级矩阵（Escalation Matrix）

| 输入事件 | 允许写入 | 禁止写入 |
|----------|----------|----------|
| `ICE_DISCONNECTED` | `edge.state = RECOVERING` | `SESSION_CANCELLED` |
| `ICE_FAILED` | `edge.state = FAILED_MEDIA_RECOVERY` | `CONFERENCE_TERMINATED` |
| `WIFI_OFF` / transport loss | presence offline；`edge.state = RECOVERING` | `channel_cancelled` |
| `remote_hangup` / `MEETING_ENDED` | `cancelChannel` + lifecycle teardown | — |
| `local_hangup` | `cancelChannel` + `cancelSession` | — |
| **新 SESSION_CREATED（同 channel）** | **clear channel tombstone**（缺失 → R25） | 继承上一场 tombstone |
| Host explicit hangup | `CONFERENCE_TERMINATED` | — |

**WM-R6 细化**：connectivity 事件不得 **发起** tombstone；但 R25 还暴露 **tombstone 生命周期** 缺陷 — 合法 lifecycle tombstone 不得污染下一场 active session 的 Recovery eligibility。

---

## 5. Soak 时间线（M01，CH-01）

| 时间 | 事件 | Session |
|------|------|---------|
| 10:50:05 | `SESSION_CREATED` acceptGroupInvite | `0baccf23`（M02 host） |
| 10:51:01.615 | **W1** `remote_hangup` → `cancelChannel(CH-01)` | 结束 `0baccf23` |
| 10:51:29.965 | `SESSION_CREATED` acceptGroupInvite | **`40f26355`（M03 host）** — tombstone **未清** |
| 10:51:30.479 | `phase=ACTIVE` authority=true | 会中正常 |
| 10:51:42.577 | WiFi off → ICE DISCONNECTED | |
| 10:51:42.587 | **C1** RECOVERY_DECISION rejected | tombstone 仍有效（~41s / 120s） |
| 10:51:42.594 | 日志误报 `edge recovery active for M03` | Recovery 实际已 DROPPED |

Tombstone 预计过期：`10:51:01 + 120s ≈ 10:53:01` — 整个 `40f26355` 会中 Recovery 均被阻断。

---

## 6. 根因分类

| 层级 | 描述 |
|------|------|
| **直接原因** | `acceptGroupInvite` / 新 conference session 创建时未 `clearChannelCancellation` |
| **放大器** | channel-scoped tombstone（120s）跨 session 存活 |
| **消费者** | Recovery eligibility 仍绑定 `isChannelCancelled`（Rejoin 已解绑） |
| **表象** | WiFi → ICE DISCONNECTED → 像「网络 loss 被判 conference terminated」 |

**不是** R24-A 遗漏；Recovery 从未进入 attempt 阶段。

---

## 7. 修复方向（PR 拆分）

### PR-R25A（P0，已实施）— Recovery 与 channel tombstone 解耦

**不做** `SESSION_CREATED → clearChannelCancellation`（止血，非模型修复）。

修改 C1/C2/C3：

```text
conferenceTerminated := isSessionCancelled(sessionId)
```

删除 Recovery 路径对 `isChannelCancelled(channelId)` 的读取。

门禁：**S17** — `conference_s17_staleChannelTombstone_mustNotBlockRecovery`

### PR-R25B（P1）— session-scoped CancellationToken

```text
cancelledChannels[channelId]
    → CancellationToken(channelId, sessionId, generation, reason)
```

### PR-R25C（P2）— 评估 `clearChannelCancellation()` 是否仍需要

---

## 8. 下一步验证

- [x] TDD：stale channel tombstone 不阻断 Recovery
- [x] S17 集成测试
- [ ] Gate-R1-R25 soak：M01 WiFi off 后 `RECOVERY_DECISION approved=true`
- [ ] 可选：`cancelChannel` 写 audit `CHANNEL_TOMBSTONE_SET writer=… reason=… ttl=…`

---

## 9. 参考

- `ConferenceEdgeRecoveryController.kt` — tombstone + consumers  
- `TalkbackCoordinator.kt` — W1/W2/W3 callers；`acceptGroupInvite` :3654  
- `TalkbackCoordinatorIntegrationTest.kt` — `conference_channelTombstone_doesNotBlockRejoinHintWhenNewHostAlive`
