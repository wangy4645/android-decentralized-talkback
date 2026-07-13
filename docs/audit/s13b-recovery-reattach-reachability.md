# S13-B — Recovery Reattach Reachability（审计）

**状态**：Frozen + R28 增补（2026-07-10）— #73-B Phase 1 调查规格  
**Soak 基准（R26A）**：`logs-gate-r1-r26a-20260710-114335` · session `0cfa4c0a`  
**Soak 基准（R28）**：`logs-s13b-reattach-reachability-20260710-161257` · session `86bbbc2b`  
**配套**：ADR-0021 S13/S18、ADR-0022 R28、Write Matrix §7、`p2a-completion-re-evaluate-seam.md`

**本 audit 范围：** 仅 `RECOVERY_REATTACH` 信令链。  
**显式不在范围：** `RECOVERY_EDGE_STARTED`、`RECOVERY_DECISION`、`FAILED_MEDIA_RECOVERY`、ICE restart、Bootstrap defer（后者见 H3）。

---

## 1. 结论（先读）

**两层状态（2026-07-10 冻结）：**

| 层 | 状态 |
|----|------|
| **R28-D** reachability gate | ✅ G-R28-D PASS |
| **S13** completion 闭环 | ❌ 待 P2-A/B |

**R26A 结论（仍有效）：** Recovery 生命周期已启动，Reattach 子协议未进入 host 接收阶段。

**R28 结论（新增）：** 失败语义已从 **错误动作** 变为 **缺 completion continuation**：

```text
旧（R26A / pre-R28）：routeConverged=false → SENT → host 无 INBOUND → timeout
新（R28 gate）：      routeConverged=false → DEFERRED → WAITING_FOR_ROUTE → timeout（无 re-evaluate）
```

Reattach 子协议矩阵（session `86bbbc2b`，R28 soak）：

| 阶段 | R26A | R28 soak |
|------|------|----------|
| Edge recovery lifecycle | ✅ | ✅ |
| S13-A：Reattach 发送侧意图 | ✅ `requested` | ❌ 无 SENT（gate 阻止） |
| **G-R28-D** | — | ✅ DEFERRED + WAITING_FOR_ROUTE |
| S13-B：host 收到 Reattach | ❌ | ❌ |
| S13-E：`RECOVERY_EDGE_RECOVERED` | ❌ | ❌ → `FAILED_MEDIA_RECOVERY` |

**H1a-2 降级：** pre-R28 的「A3 有、B1 无」在本轮 **未复现**（零 SENT）。根因从 transport 未送达 → **gate 正确阻止 + 无 P2-A re-evaluate**。

**冻结分层：**

```text
#73-A（已闭环）
  Eligibility      ← R25
  Ownership        ← R26
  Terminal 投影    ← R24-A

#73-B
  Reattach Reachability Gate  ← R28 ✅（什么时候不能做）
  Completion Re-evaluate      ← P2-A（什么时候必须重新决定）
  Recovery Protocol           ← P2-B（决定做什么）
  Media Recovery
  Completion
```

**不在本阶段讨论：** R26 v2（FAILED 后 roster）、R27（Poor Network）、Recovery Completion 产品语义。

---

## 2. 问题定义

> Edge Recovery 能启动、能持有 ownership、能 timeout，但 **Reattach 信令未到达 host**，因此子协议链（accept → ICE restart → CONNECTED → RECOVERED）未开始。

核心句（#73-B Phase 1）：

> **Recovery Reattach Reachability** — 从 `RecoveryReattach` dispatch 到 host `handleConferenceRejoin` 之间，信令是否经过 Coordinator → Transport → RF/Mesh 并到达接收端。

---

## 3. R26A soak 时间线（session `0cfa4c0a`）

```text
11:46:31  M01 WiFi off — ICE DISCONNECTED（M01 视角：M02/M03 边）
11:46:35  M02 host：M01 ICE DISCONNECTED — RECOVERY_EDGE_STARTED path
11:46:36  M01：RECOVERY_REATTACH requested → M02（attempt=4）
11:46:43  M01：RECOVERY_REATTACH requested → M02（attempt=6，重试）
11:46:38  M02 host：M01 edge RECOVERY_EDGE_STARTED，policy=ICE_RESTART_ONLY
11:46:58  M02 host：FAILED_MEDIA_RECOVERY(M01) reason=attempt_timeout
11:47:02  M02 host：post-window unhealthy prune（R26 v2 范围，非本 audit）
11:48:02  HELLO from M01 — WiFi 恢复，但 recovery attempt 已结束
11:48:39  M01：CONFERENCE lifecycle IDLE gen=2 → GROUP gen=3（离会后重建）
```

**关键事实：**

1. M01 在 **offline** 期间本地打出 `RECOVERY_REATTACH requested`；M02 **零条** inbound / accepted。
2. M02 对 **M01 边** 为 `initiatesReattach=false`（host 等 participant 发起 reattach，不主动发向 M01）。
3. M01 发的是 **M01→M02（host link）** reattach，不是 host→participant。
4. WiFi 恢复（HELLO）发生在 **attempt_timeout 之后** — 本轮 attempt 不可能成功，与 generation 错窗无关（H2 为 P3）。

---

## 4. S13-B Reattach 矩阵（冻结 marker）

调查 **只断言状态 / 日志契约**，不 gate 未冻结字符串。

| ID | Marker / 状态 | 含义 | R26A soak |
|----|---------------|------|-----------|
| **S13-A1** | `RECOVERY_REATTACH requested` / `RECOVERY_REATTACH_REQUESTED` | Coordinator **决定** dispatch | ✅ M01 |
| **S13-A2** | `RECOVERY_REATTACH_ENQUEUED` | 已交给 `signalingChannel.send`（调用前/入口） | 🔍 probe 已落地，待 soak |
| **S13-A3** | `RECOVERY_REATTACH_SENT` | Transport **确认**送出（`send()` 成功返回） | 🔍 probe 已落地，待 soak |
| **S13-A3′** | `RECOVERY_REATTACH_SEND_FAILED` | Transport / Coordinator 发送失败 | 🔍 probe 已落地，待 soak |
| **S13-B1** | `RECOVERY_REATTACH_INBOUND` | Host `handleConferenceRejoin` 入口 | 🔍 probe 已落地，待 soak |
| **S13-B2** | `RECOVERY_REATTACH accepted` / `RECOVERY_REATTACH_ACCEPTED` | Host 接受 + controller notified | ❌ |
| **S13-C** | ICE restart issued after accept | Controller → media | ❌ |
| **S13-D** | Recovery edge ICE CONNECTED | 数据面 | ❌ |
| **S13-E** | `RECOVERY_EDGE_RECOVERED` | Edge terminal success | ❌ | ❌ |
| **G-R28-D1** | `RECOVERY_REATTACH_DEFERRED` + `WAITING_FOR_ROUTE` | Gate：route 未收敛不 dispatch | — | ✅ M01 |
| **G-R28-D2** | `RECOVERY_REATTACH_SENT` while `routeConverged=false` | Gate 违反 | — | ❌（零条） |

**判定规则：**

```text
A1 无                         → 未触发 reattach dispatch
A1 有，A2 无                  → dispatch 未进入 send 路径
A2 有，A3/A3′ 无              → H1a-1：Coordinator → signalingChannel 未成功
A3 有，B1 无                  → H1a-2：Transport → RF/Mesh 未送达 host（或 H1b 入口前丢）
B1 有，B2 无                  → host handler / lineage / membership 拒绝
B2 有，C 无                   → Recovery protocol（controller → media）
C 有，E 无                    → media / bootstrap completion
```

**H1a 子拆分（冻结）：**

| 子 ID | 链路段 | 矩阵停点 |
|-------|--------|----------|
| **H1a-1** | Coordinator → `signalingChannel.send()` | A2 有，A3 无 |
| **H1a-2** | `signalingChannel` → RF / Mesh → peer | A3 有，B1 无 |

---

## 5. 假设优先级（冻结）

| 优先级 | ID | 假设 | 说明 |
|--------|-----|------|------|
| **P0** | **H1a** | Reattach 信令未真正离开发送端 | `requested` ≠ `sent`；拆为 H1a-1 / H1a-2 |
| **P1** | **H1b** | A3 成立但 host 无 INBOUND | Handler / routing / 静默丢弃 |
| **P1.5** | **H3** | Reattach 协议模型 + Bootstrap 无 handoff | 见 §5.1 — **即使 H1a 成立，H3 仍可能阻塞 completion** |
| **P3** | **H2** | PC generation 错窗 | HELLO/gen3 **晚于** attempt 结束；非 Reattach reachability 第一根因 |

### H1a — 信令未真正发出（H1a-1 / H1a-2）

现有 log `RECOVERY_REATTACH requested` 来自 `dispatchConferenceRejoinSignal` **在** `sendSignal()` 调用**之后**（`TalkbackCoordinator.kt:3355–3383`）。

但 `sendSignal` 使用 `runCatching { signalingChannel.send(...) }.onFailure { log("Signal send failed…") }`（`:8495–8500`）：

- 失败时 **无 reattach 专用 marker**
- `dispatchConferenceRejoinSignal` **仍 return true**
- 与 `requested` 组合会 **误判为已发出**

**H1a-1：** `send()` 未成功或未调用（A2→A3 断）。  
**H1a-2：** `send()` 返回成功但 RF/Mesh 未送达（A3→B1 断）。

### H1b — Transport 确认发出但 host 未收到

若 probe 证实 **S13-A3**，则查：

```text
signalingChannel → RF mesh / InMemorySignalingHub
    ↓
handleSignal → CONFERENCE_REJOIN → handleConferenceRejoin
```

Host 入口：`TalkbackCoordinator.kt:2852` → `:3436 handleConferenceRejoin`。

静默丢弃点（grep 重点）：

- `Conference rejoin ignored: no host session`
- `RECOVERY_REATTACH denied: … lineage`
- `Conference rejoin denied: … was not a prior member`
- `resolvePeerForModule … not reachable`（发送端 `:3335–3337`）

### 5.1 H3 — Reattach 协议模型 + Bootstrap handoff（P1.5）

**Protocol smell（R26A 已观测，非 H1 证伪后置）：**

```text
Host 对 participant 边：initiatesReattach=false
    → Recovery 假设「Participant 负责发出第一条 Reattach」
Participant 断 WiFi：
    → 恰恰是无法发出 Reattach 的一方
```

因此 **Reattach reachability 与 recovery completion 在模型上耦合于离线节点** — 这不一定是 implementation bug，但是 **协议层风险**，需在 H1a/H1b 矩阵填完后仍单独评估。

**Bootstrap 维度：** `Deferring full conference mesh until host link is stable` 与 Edge Recovery completion 可能 **无 ownership handoff** — 影响 S13-C+，不替代 S13-B 调查。

---

## 6. 代码路径（只读锚点）

### 6.1 发送链（participant / 任意 initiator）

```text
ConferenceEdgeRecoveryController.onRequestReattach
  → TalkbackCoordinator callback
  → sendRecoveryReattachInternal(channelId, authority, hostSessionId)     :3312
      → isSessionCancelled? → RECOVERY_EVENT_DROPPED
      → dispatchConferenceRejoinSignal(..., RECOVERY_REATTACH)           :3321
          → resolvePeerForModule(authority) ?: "Conference rejoin skipped"
          → sendSignal(peer, CONFERENCE_REJOIN envelope)                 :3355
          → log "RECOVERY_REATTACH requested by …"                       :3379
          → return true   ← 不反映 send 成败
```

### 6.2 接收链（host）

```text
handleSignal
  → SignalType.CONFERENCE_REJOIN → handleConferenceRejoin               :2852, :3436
      → validateRecoveryReattachLineage (RECOVERY only)
      → sendConferenceInvitesInternal(rejoin=true)
      → conferenceEdgeRecoveryController.onRecoveryReattachAccepted
      → log "RECOVERY_REATTACH accepted …"                              :3507
```

### 6.3 Host 侧 M01 边 recovery（不发 reattach）

```text
notifyConferenceEdgeIceState
  initiatesReattach = !isConferenceHostSession && remote == hostId
Host 观察 participant 断网：
  policy=ICE_RESTART_ONLY, initiatesReattach=false
  → 等 participant 侧 RECOVERY_REATTACH，host 不主动发向 M01
```

---

## 7. 待补 log 契约（Probe PR — **已落地**）

**Scope：** 只加 marker，**不改 recovery 行为**。

| Marker | 写入点 | 字段建议 |
|--------|--------|----------|
| `RECOVERY_REATTACH_ENQUEUED` | `sendSignal` 调用**前** | `session`, `ch`, `from`, `to`, `epoch`, `peerReachable`, `transportReady` |
| `RECOVERY_REATTACH_SENT` | `sendSignal` **成功返回**后 | `session`, `ch`, `to`, `nonce`, `peerReachable`, `transportReady` |
| `RECOVERY_REATTACH_SEND_FAILED` | `sendSignal` onFailure | `session`, `ch`, `to`, `err`, `peerReachable`, `transportReady` |
| `RECOVERY_REATTACH_INBOUND` | `handleConferenceRejoin` 入口（decode 后） | `session`, `ch`, `from`, `intent`, `epoch` |

**`peerReachable` / `transportReady` 语义（冻结）：**

| 字段 | 含义 | 避免歧义 |
|------|------|----------|
| `peerReachable` | `resolvePeerForModule(to)` 成功 / discovery 认为 authority 可达 | ≠ A3 `SENT` |
| `transportReady` | signaling channel / mesh transport 处于可发送状态 | ≠「已写入本地队列」 alone |

**`SENT` 必须明确表示：** `signalingChannel.send()` **返回成功**（情况 A），**不**隐含 mesh 路由已建立（情况 B）或仅本地排队（情况 C）。

现有 generic `Signal send failed type=…` **不足以** 区分 recovery vs 其他信令。

---

## 8. Grep 模板

### 8.1 现有 log（无 probe）

```bash
# Reattach + edge lifecycle（分开读）
rg "RECOVERY_REATTACH|RECOVERY_EDGE_|FAILED_MEDIA_RECOVERY" M01.txt M02.txt

# Host 是否收到 reattach
rg "RECOVERY_REATTACH (requested|accepted|denied|ignored)" M02.txt

# Transport 失败（generic）
rg "Signal send failed|rejoin skipped|TRANSPORT_NOT_READY|INVITE_DISPATCH" M01.txt M02.txt

# 矩阵 E 级
rg "RECOVERY_EDGE_RECOVERED" *.txt
```

### 8.2 R28 gate + completion waiting

```bash
rg "RECOVERY_(WAITING|REATTACH_DEFERRED|REATTACH_SENT|REATTACH_INBOUND|EDGE_RECOVERED|FAILED_MEDIA_RECOVERY)" *.txt
```

### 8.3 Probe 落地后（legacy send chain）

```bash
rg "RECOVERY_REATTACH_(ENQUEUED|SENT|SEND_FAILED|INBOUND)" *.txt
```

---

## 9. 对照 soak 协议（#73-B Phase 1b）

**目的：** 区分「一直 offline」vs「窗口内 transport 恢复」。

| 步骤 | 动作 |
|------|------|
| 1 | M02 host 三方会，M01/M03 入会，ACTIVE |
| 2 | M01 **关 WiFi 20–30s** |
| 3 | M01 **开 WiFi**（尽量在 host `attempt_timeout` ~15s **之前**） |
| 4 | 60s 内不操作 UI，抓三机 logcat |
| 5 | 填 §4 矩阵 A1→E |

**Log 目录命名：** `logs-s13b-reattach-reachability-<yyyyMMdd-HHmmss>`

**Pass（Phase 1b 调查，非产品 pass）：** 矩阵至少推进到 **B1 或 B2**，以定位 H1a-1 / H1a-2 / H1b。

---

## 10. 与 #73-A 冻结项的关系

| 冻结项 | 本 audit |
|--------|----------|
| R25 Eligibility | 不 revisit；soak 无 `SESSION_CANCELLED` |
| R26 Ownership | 不 revisit；window 内 roster 稳定（S18 PASS） |
| R24-A Terminal | 不 revisit；timeout → ACTIVE+degraded |
| R26 v2 post-terminal prune | **显式 out of scope** |
| R27 Poor Network | **显式 out of scope** |

---

## 11. 执行顺序（#73-B）

| 步 | 内容 | 状态 |
|----|------|------|
| 1 | 本 audit 冻结 | **Done** |
| 2 | Probe PR | **Done** |
| 3 | R26A soak + 矩阵 | **Done** |
| 4 | R28 gate + G-R28-D soak | **Done** `baf393b` / `logs-…-161257` |
| 5 | P2-A re-evaluate seam | **Draft** — `p2a-completion-re-evaluate-seam.md` |
| 6 | P2-B 决策树 + S13-E soak | 待 P2-A |
| 7 | H3 协议评估 / H2 / S13-C+ | 后置 |

---

## 12. 参考文件

- `docs/adr/0021-conference-edge-recovery-lifecycle.md` — S13/S18、R26
- `docs/adr/0022-recovery-completion-ownership.md` — R28 gate、P2-A/B
- `docs/audit/ro-m3-recovery-write-matrix.md` — Gate-R1-R26A、Gate-R28-D
- `docs/audit/p2a-completion-re-evaluate-seam.md` — P2-A 规格
- `docs/audit/r25-false-conference-termination.md` — 分层写法参考
- `TalkbackCoordinator.kt` — `dispatchRecoveryReattachOutcome`, `handleConferenceRejoin`
- `ConferenceEdgeRecoveryController.kt` — `onRequestReattach`, `RECOVERY_REATTACH_*` markers
- `EdgeReachabilitySnapshot.kt` — R28 gate facts

---

## 13. R28 soak 时间线（session `86bbbc2b`，`logs-s13b-…-161257`）

```text
16:10:40  M01：RECOVERY_EDGE_STARTED(M02) attempt=3 initiatesReattach=true
16:10:40  M01：RECOVERY_REATTACH_DEFERRED reason=WAITING_FOR_ROUTE routeConverged=false
16:10:47  M01：ICE_FAILED → attempt=5 → DEFERRED again
16:10:55  M02：FAILED_MEDIA_RECOVERY(M01) attempt_timeout
16:11:00  M01：FAILED_MEDIA_RECOVERY(M02) attempt_timeout
16:11:14  M02：HELLO from M01 — WiFi 恢复，但无 re-evaluate
16:12:22  M01：RECOVERY_EDGE_CANCELLED(M02) local_hangup
```

**判定：**

- **G-R28-D PASS** — 全程无 `RECOVERY_REATTACH_SENT`
- **S13-E FAIL** — WiFi 恢复晚于 attempt budget；缺 P2-A `onReachabilityChanged → re-evaluate`
- **禁止下一步：** `routeConverged → resend()` patch（见 ADR-0022 R28-E）
