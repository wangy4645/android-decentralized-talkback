# P2-A — Completion Re-evaluate Seam

**状态**：**Accepted**（2026-07-10）— frozen `/grill-with-docs` on branch `ro-m2-p1-recovery-reattach`  
**Normative：** ADR-0022 R28-E / R28-F / R28-G  
**前置：** R28 gate（`baf393b`）；G-R28-D soak PASS（`logs-s13b-reattach-reachability-20260710-161257`）

**P2-A 冻结 seam 与 re-evaluate 义务；P2-B 冻结 evaluation 输出动作。**

---

## 1. 两层状态（不变）

| 层 | 状态 |
|----|------|
| R28-D reachability gate | ✅ |
| S13 completion 闭环 | ❌ → P2-B |

```text
旧：routeConverged=false → SENT → host 无 INBOUND → timeout（错误动作）
新：routeConverged=false → DEFERRED → WAITING → timeout（缺 continuation）
P2-A 目标：capability 变化 → RECOVERY_REEVALUATE → explicit evaluation
```

---

## 2. 冻结决策摘要（Grill 2026-07-10）

### R28-E — Completion Re-evaluate Seam

```text
Media Edge Restored ≠ Recovery Edge Completed

RECOVERY_PENDING + controlPlaneStarted=false:
  ICE restoration MUST NOT → RECOVERED
  MUST → RECOVERY_REEVALUATE → completion evaluation

Exception: controlPlaneStarted=true → evaluation MAY → RECOVERED

Seam 对所有 edge 相同；role 差异仅在 evaluate 输出（P2-B）
```

### R28-F — Attempt Terminal vs Edge Obligation

```text
FAILED_MEDIA_RECOVERY = attempt terminal（A1：record 保留，不增 phase enum）

attempt_timeout → attempt terminal only → edge obligation 继续

Material transition after attempt terminal:
  MUST RECOVERY_REEVALUATE
  MAY SUPERSEDE（非 MUST）

watchdog budget ∈ attempt；REEVALUATE/WAITING 不 extend/pause

Watchdog 到期 → RECOVERY_FINAL_EVALUATION(ATTEMPT_TIMEOUT) → FAILED_MEDIA_RECOVERY
```

### R28-G — Capability Re-evaluation Contract

```text
Materiality owner = Coordinator
Fact writers MUST NOT invoke recovery evaluation directly

RecoveryCapabilitySignature {
  permittedActions: Set<RecoveryAction>
  waitingReason: WaitingReason?
}

Material ⇔ signature changes（capability changed，非任意网络事件）

Host + WAITING_FOR_INBOUND：route 单独恢复 ≠ material，除非 signature 真变

Authority fact：domain source（非 RuntimeProjector emit 反向输入）
```

---

## 3. 日志契约

| Marker | 职责 |
|--------|------|
| `RECOVERY_REEVALUATE` | capability 变化；controller 被叫醒 |
| `RECOVERY_FINAL_EVALUATION` | watchdog 最后一眼；attempt 结束前 |
| `RECOVERY_DECISION` | evaluation 输出 |
| `RECOVERY_WAITING` | 显式 wait（协议状态） |

`RECOVERY_REEVALUATE` 字段：`session`, `edge`, `attempt`, `trigger`, `capabilityBefore`, `capabilityAfter`, `controlPlaneStarted`

示例：

```text
RECOVERY_REEVALUATE session=… edge=M02 attempt=5 trigger=ROUTE_CONVERGED
  capabilityBefore=WAITING_FOR_ROUTE capabilityAfter=DISPATCH_REATTACH
  controlPlaneStarted=false
```

---

## 4. Coordinator hooks（v1）

| Hook | 可能改变 signature |
|------|-------------------|
| Mesh ICE state | route / dispatch |
| Channel readiness | link |
| Peer 0→1 callable | discovery |
| Authority reachability **fact** flip | completion |

**不挂：** 每条 HELLO、gossip 时间戳、ICE CHECKING、projection emit 本身

---

## 5. Soak gates

| ID | Pass |
|----|------|
| G-P2-A1 | WiFi 恢复 15s 内 `RECOVERY_REEVALUATE` 或新 decision/WAITING |
| G-P2-A2 | 无 material 变化后静默 > debounce |
| G-P2-A3 | 仍可无 `RECOVERY_REATTACH_SENT` |

```bash
rg "RECOVERY_(REEVALUATE|FINAL_EVALUATION|WAITING|DECISION|REATTACH_DEFERRED|FAILED_MEDIA_RECOVERY)" M01.txt M02.txt
```

目录：`logs-p2a-reevaluate-<yyyyMMdd-HHmmss>`

---

## 6. 实现顺序（P2-A code）

| 步 | 内容 |
|----|------|
| 1 | `RecoveryCapabilitySignature` + Coordinator materiality comparator |
| 2 | `onRecoveryReachabilityChanged` + `RECOVERY_REEVALUATE` log |
| 3 | Block `onIceConnected` → RECOVERED when `!controlPlaneStarted` |
| 4 | `RECOVERY_FINAL_EVALUATION` on watchdog |
| 5 | Re-evaluate path from `FAILED_MEDIA_RECOVERY` on material transition |
| 6 | G-P2-A soak |
| 7 | **P2-B** — decision tree + S13-E |

**Explicit non-goals (P2-A):** `routeConverged → resend()`; watchdog extend; debounce material re-evaluate

---

## 7. 参考

- ADR-0022 R28-E/F/G
- `logs-s13b-reattach-reachability-20260710-161257` — R28 gate soak
- `s13b-recovery-reattach-reachability.md` §13
- `ro-m3-recovery-write-matrix.md` — Gate-R28-D, step 10
