# AQC — 最小实现授权

**状态：** `AUTHORIZED — PHASE 1 CODE`（2026-10-13）  
**父裁决：** [aqc-audio-quality-core-contract-gap-adjudication.md](./aqc-audio-quality-core-contract-gap-adjudication.md)  
**不授权：** Catalog / B8-D1 / P1 场测 · commit / push · 重写媒体栈

```text
AQC-IMPL
Decision:     AUTHORIZED — PHASE 1 (desk + feature flags)
Phase 1:      TxProcessing · MembershipStableMixer · Playout PCM synth · continuous tick fallback
Phase 2:      Wire flags on device field path (separate run card)
Phase 3:      Fixed RTP/PCM vectors + SLO gate (desk harness)
Does NOT:     reopen B8 soak/skip · mix-side A1 掩盖 TX
```

---

## Phase 1 授权范围（已落地 · 2026-10-13）

| 交付 | 路径 | 状态 |
|------|------|------|
| 运行时开关 | `core/conference/aqc/AqcRuntime.kt` | 默认 **关** |
| TxProcessing | `TxProcessingChain.kt` + TX seam 接线 | POST-APM + 限幅（`txProcessingEnabled`） |
| ConferenceMix | `MembershipStableMixer.kt` + pipeline | Top-K 席位数作分母（`membershipMixEnabled`） |
| AdaptivePlayout（最小） | `PlayoutPcmSynthesizer.kt` + playout clock | PLC/CN + resolve 空时 CN（`continuousPlayoutEnabled`） |
| Desk 测试 | `AqcModuleDeskTest.kt` | 已写；全量 `testDebugUnitTest` 受仓库其他用例编译失败阻塞 |

接线策略：`AqcRuntime` 三分开关 `tx` / `membershipMix` / `continuousPlayout`。

场测开启示例（应用启动或场测 harness，**未默认打开**）：

```kotlin
AqcRuntime.configureForDesk(tx = true, membershipMix = true, continuousPlayout = true)
```

---

## Phase 2（PREP 已授权 · 见跑卡）

正文：[aqc-phase2-validation-run-card.md](./aqc-phase2-validation-run-card.md)

| 项 | 状态 |
|----|------|
| 跑卡 / 语料 / 指标定义 | 已写 |
| 默认开启 flags | **禁止**；仅 `debug.talkback.aqc.*` 可选 |
| Desk D0–D5 | `AqcPhase2DeskTest` |
| 探索场测 | **门闩**：desk + compile PASS 后才可；**不得 AUDIO PASS** |
| Phase 3 向量自动化 | 未授权 |

---

## Phase 3（未授权）

- 固定输入向量包（单路/双路/丢包/乱序）
- 自动化 SLO 门禁脚本挂 CI（可选）

---

## 冻结（延续）

ADR-0058 路径保留。B8 恢复逻辑不删不改参。P1 分轨。

不 commit / 不 push。
