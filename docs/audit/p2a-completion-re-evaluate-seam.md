# P2-A — Completion Re-evaluate Seam（规格草案）

**状态**：Draft（2026-07-10）— 待 `/grill` 冻结后进入实现  
**前置**：R28 gate 已落地（`baf393b`）；G-R28-D soak PASS（`logs-s13b-reattach-reachability-20260710-161257`）  
**配套**：ADR-0022 R28-A/C、R28-D；`s13b-recovery-reattach-reachability.md` §13

**本阶段只冻结 seam 与 re-evaluate 义务，不冻结 re-evaluate 后的动作（→ P2-B）。**

---

## 1. 问题陈述

R28 已证明 **「什么时候不能做」**：

```text
routeConverged=false → RECOVERY_REATTACH_DEFERRED → RECOVERY_WAITING(WAITING_FOR_ROUTE)
```

R28 soak 仍 **timeout**，但失败语义已从 **错误动作** 变为 **缺 completion continuation**：

```text
旧：routeConverged=false → SENT → host 无 INBOUND → timeout
新：routeConverged=false → DEFERRED → RECOVERY_PENDING → （无 re-evaluate）→ timeout
```

P2-A 回答：**reachability 跃迁后，谁 MUST 重新 evaluate？**

---

## 2. 冻结契约（normative intent）

当 Recovery Edge 同时满足：

```text
phase == RECOVERY_PENDING
AND
ReachabilitySnapshot 发生 material transition
```

**Conference Edge Recovery Controller MUST invoke completion re-evaluate** for that `(sessionId, remoteModuleId)`.

Re-evaluate **MUST** emit exactly one Recovery Completion Decision（ADR-0022 R28-C）:

```text
1. role-allowed completion action   ← P2-B 决定具体动作
2. WAITING(reason)
3. SUPERSEDED(nextAttemptId)
4. CANCELLED(reason)
```

### 2.1 Material transition（v1 最小集，待 grill）

| From (waiting reason) | To (fact change) | 示例触发源 |
|----------------------|------------------|------------|
| `WAITING_FOR_ROUTE` | `routeConverged=true` | mesh ICE CONNECTED → `qosMonitor.isGroupConnected` |
| `WAITING_FOR_LINK` | `linkReady=true` | channel readiness READY |
| `WAITING_FOR_DISCOVERY` | `peerDiscovered=true` | HELLO / discovery |
| `WAITING_FOR_AUTHORITY` | `authorityReachable=true` | host ICE + runtime projection |

**Frozen NOT in P2-A：** 具体选 dispatch / ICE restart / WAITING_FOR_INBOUND（→ P2-B）。

### 2.2 Explicit non-goals

```text
❌ routeConverged → coordinator.resend()
❌ ICE CONNECTED → auto REATTACH_REQUESTED
❌ 延长 attempt_timeout 掩盖缺 re-evaluate
```

---

## 3. Seam 边界（读写矩阵）

| 组件 | P2-A 职责 |
|------|-----------|
| **Fact writers**（Connectivity, Discovery, Runtime） | 只写事实；**不**决定 recovery 动作 |
| **TalkbackCoordinator** | 检测 fact 变化；**转发** `onReachabilityChanged(sessionId, remoteModuleId, snapshot)` 给 controller |
| **ConferenceEdgeRecoveryController** | **唯一** completion re-evaluate 决策者 |
| **dispatchRecoveryReattachOutcome** | P2-B 可能被 re-evaluate **调用**；P2-A 不直接改其行为 |

依赖方向（延续 WM-R3）：

```text
Reachability facts → Controller re-evaluate → Completion Decision
Controller → Coordinator callbacks（执行面）
```

---

## 4. Grill 开放问题（实现前必须闭合）

### G1 — 触发源粒度

- 仅 ICE CONNECTED？
- HELLO 恢复是否足够触发 `peerDiscovered` / `linkReady` re-evaluate？
- 同一 attempt 内多次跃迁：debounce 还是每次 evaluate？

### G2 — RECOVERY_PENDING vs attempt watchdog

- Re-evaluate 是否 **重置** attempt budget？
- 还是 **SUPERSEDED(nextAttemptId)** 开新 attempt？
- Watchdog 在 PENDING+WAITING 期间应 emit 周期性 `RECOVERY_WAITING` 还是静默？

### G3 — 双端 asymmetry

- M01 participant edge（initiatesReattach=true）route 恢复 → re-evaluate dispatch
- M02 host edge（initiatesReattach=false）M01 离线 → re-evaluate 允许什么？仅 `WAITING_FOR_INBOUND`？

### G4 — ICE CONNECTED 与 routeConverged 同义性

- v1 `routeConverged = isGroupConnected` — ICE 恢复即 route 收敛
- 若 ICE CONNECTED 触发 `onIceConnected` → RECOVERED **先于** re-evaluate，是否抢跑 completion？
- 需明确：**media recovery 完成** vs **control-plane reattach** 的优先级

### G5 — 日志契约

P2-A 最小 marker：

```text
RECOVERY_REEVALUATE session=… edge=… trigger=REACHABILITY_CHANGED from=WAITING_FOR_ROUTE to=routeConverged=true
RECOVERY_DECISION … (existing)
RECOVERY_WAITING … (existing)
```

---

## 5. Soak gate（G-P2-A）

| ID | Pass criterion |
|----|----------------|
| **G-P2-A1** | WiFi 恢复后 15s 内出现 `RECOVERY_REEVALUATE` 或新的 `RECOVERY_DECISION` / `RECOVERY_WAITING`（非静默 timeout） |
| **G-P2-A2** | 无 interval：`RECOVERY_PENDING` + reachability 已跃迁 + 零 completion decision > debounce |
| **G-P2-A3** | 仍 **允许** 无 `RECOVERY_REATTACH_SENT`（动作属 P2-B） |

Soak 目录命名：`logs-p2a-reevaluate-<yyyyMMdd-HHmmss>`

Grep：

```bash
rg "RECOVERY_(REEVALUATE|WAITING|REATTACH_DEFERRED|DECISION|EDGE_STARTED|FAILED_MEDIA_RECOVERY)" M01.txt M02.txt
```

---

## 6. 实现顺序（建议）

| 步 | 内容 | 验证 |
|----|------|------|
| 1 | Grill G1–G4 → 更新 ADR-0022 §P2-A 为 Accepted | 文档 |
| 2 | `onReachabilityChanged` API + no-op re-evaluate stub | 单元测试：跃迁 → 调用一次 |
| 3 | Coordinator 接线（ICE / channel readiness / discovery） | 集成测试 |
| 4 | Re-evaluate emit `RECOVERY_REEVALUATE` + decision | G-P2-A soak |
| 5 | **P2-B** — 决策树 + 动作 | G-S13-E soak |

---

## 7. 参考

- ADR-0022 — R28-A/C, R28-D, implementation notes §4–5
- `logs-s13b-reattach-reachability-20260710-161257` — R28 gate soak
- `ConferenceEdgeRecoveryController.kt` — `RECOVERY_PENDING`, `beginRecovery`
- `TalkbackCoordinator.kt` — `buildRecoveryEdgeReachabilitySnapshot`, `onMeshIceStateChanged`
