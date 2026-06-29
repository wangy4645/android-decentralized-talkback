# Floor 获权超时（Arbitration Safety Rule）

Group PTT 中，本端获权后若在超时内无法开启上行（`localUplinkGrant` 未就绪），须主动 `FLOOR_RELEASE` 让权，避免「沉默霸麦」与双持麦窗口。此为 Floor 抢占一致性协议的一部分，不是普通性能调参。失权方向仍为零窗口立即停采集（见 ADR-0003）。

**Interim 默认**：`acquireReleaseTimeoutMs = 500`（可配）。标定收敛后按 R14 公式替换，不得用调大 timeout 补偿冷启动（R15）。

## 时间语义

```
T0 = GRANT_APPLIED     （信令收敛写入 protocolFloorOwner 的时刻）
T1 = captureON         （localUplinkGrant 实际为 true）
Δ  = T1 - T0           （sinceGrant，已由 PttTimingLog 记录）
```

## 核心不变量

- **R12（计时起点）**：获权超时从 `GRANT_APPLIED` 起算，禁止从 `PTT_DOWN` 或网络往返起算。
- **R13（标定分布）**：timeout 标定使用 **cold-start 子分布** 的 P95：`P95_cold(sinceGrant)`。
- **R14（有效超时）**：`effective_timeout = clamp(max(500ms, P95_cold × 1.5), 500ms, 1000ms)`。
- **R15（禁止 timeout 补偿冷启）**：冷启动性能须通过 pre-warm pipeline 解决；不得因设备慢而突破 1000ms 上限或无限放宽 timeout。
- **R16（V2 唯一自动让权触发器）**：获权后 `localUplinkGrant` 迟迟未就绪时，`FLOOR_RELEASE` 在 V2 只能由 timeout 自动触发；health signal 不得触发。用户松 PTT、正常 `FLOOR_RELEASE` 信令等显式操作不受此限。
- **R17（confidence 观测边界）**：confidence 信号（AudioTrack 失败、ICE 断开、mic 权限拒等）仅用于 UI 指示、telemetry、回放分析与 timeout 参数调优；`failure → mark degraded → still wait timeout`。

## 控制面分层

| 层 | V2 职责 |
|----|---------|
| **Safety Gate** | timeout-only 释放 `FLOOR_RELEASE`（获权方向） |
| **Health Signal** | confidence 观测，无副作用 |
| **V3+（未启用）** | early release 仅当 failure 进入全网可排序、可重放的事件流后方可评估；需新 ADR |

拒绝「确定性失败即时让权 + 其余 timeout」双路径释放：event-driven 与 time-driven 释放顺序不可跨节点对齐，易导致重复/丢失 `FLOOR_RELEASE` 与状态抖动。

## 不对称收敛（与 ADR-0003 一致）

| 方向 | 规则 |
|------|------|
| **获权**（protocol=local, uplink 未就绪） | 有界等待 → 超时 `FLOOR_RELEASE` + UI 采集失败 |
| **失权**（protocol=remote, uplink 仍开） | 零窗口 `stopCapture`，禁止宽限 |

协议态（`protocolFloorOwner`）为跨端权威；执行态（`localUplinkGrant`）单调跟随，不得超前（R11，ADR-0003）。

## 标定方案

每台目标 Android 板：

| 维度 | 要求 |
|------|------|
| cold-start 样本 | ≥ 20 次 |
| warm-start 样本 | ≥ 20 次 |
| 组呼规模 | 3 modules（含本端） |
| 网络 | LAN + mesh 正常负载 |

**验收指标**（协议正确性，非仅性能）：

- `timeout_violation_rate ≤ 0.1%`（获权后超时仍未 captureON 却未让权）
- `false_yield_rate ≈ 0`（误让权：captureON 已成功却触发超时 release）

warm 分布仅作回归对比；**R14 只消费 cold P95**。

## 落地阶段（代码在 ADR 之后）

1. **Phase 1 — 观测**：保留 `sinceGrant` / `PttTimingLog`；输出 P95 报告；**不改** `FLOOR_RELEASE` 行为。
2. **Phase 2 — 参数**：引入 `acquireReleaseTimeoutMs = 500` 配置；**不接入** FSM 决策。
3. **Phase 3 — 决策**：超时触发 `FLOOR_RELEASE`；接入 R14 clamp；替换硬编码 500。

## Considered Options

- **先代码后 ADR**：阈值变补丁、ADR 变事后解释；拒绝。
- **固定 300ms / 无限等待**：误让权或沉默霸麦；拒绝。
- **用调大 timeout 解决冷启**：违背 R15，上限失控；拒绝。
- **混合释放（failure 即时让权 + timeout）**：双路径 release ordering 不可排序；拒绝（R16）。
- **Interim 500ms + 标定收敛（C，B 作默认）**：接受。

## Consequences

- `PttTimingLog`（`GRANT_APPLIED` → `captureON` / `sinceGrant`）为 Phase 1 基准数据源。
- 预热（pre-warm pipeline）单独立项，与 timeout 参数解耦。
- V3 可评估 event-time hybrid clock（有序事件 + 时间双轨），前提：failure 信号可全网一致排序与重放；不削弱 R12–R17。
