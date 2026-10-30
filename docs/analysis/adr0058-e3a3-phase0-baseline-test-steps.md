# ADR-0058 E3a.3 Phase 0 — Baseline Repeatability 测试步骤

## 本轮目标

确认先前 AMBER 样本（M02→M01 `burst=10 / P99=129ms`）是否可重复。  
**不改** AP / IGMP / pacing / 媒体合同。仅用 probe v2 观测。

```text
拓扑:     M01 ↔ M02，wlan0，239.255.42.99:46999
变更:     NONE（baseline）
媒体引擎: NOT AUTHORIZED
M03/M04:  本 Phase 不参与（可保持在线，不跑 probe）
```

## 前置检查（已完成则跳过）

1. `adb devices` 可见 M01=`HTUBB21B09220661`、M02=`DSJ-2407041`
2. 两台已装含 E3a.2a 的 `talkback-app-debug.apk` + `android-board-talkback-debug-androidTest.apk`
3. 两台连同一 Wi‑Fi / 同一 L2；不要开 Talkback app（脚本会 force-stop）

## Round 执行（每轮双向 = 2 次 probe）

### Round N（N = 1..3）

| 步骤 | 动作 | 命令 / 产物 |
|------|------|-------------|
| 0 | 建 round 目录 + 采 NETWORK_FACTS | `logs/adr0058-e3a3-phase0-rN-<stamp>/NETWORK_FACTS.txt` |
| 1 | 正向 M01→M02 | `.\scripts\adr0058-underlay-mcast-probe-start-run.ps1 -Direction M01toM02 -SkipBuild -SkipInstall -Iface wlan0` |
| 2 | 等正向结束，确认有 `*-sender.json` / `*-receiver.json` | `logs/adr0058-underlay-mcast-probe-<stamp>/` |
| 3 | 反向 M02→M01 | 同上，`-Direction M02toM01` |
| 4 | E3a.2 双向裁定 | `.\scripts\adr0058-underlay-mcast-probe-e3a2-adjudicate.ps1 -ForwardLogDir <fwd> -ReverseLogDir <rev>` |
| 5 | 填入本轮对比表（见下） | 写入 round 目录 `PHASE0_ROUND_SUMMARY.txt` |

**中间禁止：** 改 AP、关/开组播、换 SSID、改设备距离/朝向、重启 AP（除非本轮作废重来）。

## 每方向必记指标

```text
delivery %                         [observability]
maxConsecutiveLoss                 [gate ≤5]
interArrivalMsP99                  [gate ≤120]
interArrivalGapOver120MsCount      [v2]
maxInterArrivalGapMs               [v2]
```

## Phase 0 状态

```text
STOP after Round 2 — 不跑 Round 3

理由: R1/R2 已确认同一形态可重复
      M01→M02 = 0%
      M02→M01 = GREEN

下一刀: E3a.3a Directional Failure Classification
        见 adr0058-e3a3a-directional-failure-classification.md
```

## Round 对比表

| Round | Dir | Delivery | Burst | P99 gap | >120ms# | maxGap | Verdict |
|-------|-----|----------|-------|---------|---------|--------|---------|
| R1 | M01→M02 | **0%** (0/3000) | 0 | n/a | 0 | n/a | **RED** (zero delivery; join OK) |
| R1 | M02→M01 | **98.6%** (2958/3000) | **3** | **98** | **3** | **197** | **GREEN** |
| R1 overall | — | — | — | — | — | — | **AMBER** |
| R2 | M01→M02 | **0%** (0/3000) | 0 | n/a | 0 | n/a | **RED** (zero delivery repeated) |
| R2 | M02→M01 | **96.3%** (2888/3000) | **3** | **100** | **7** | **199** | **GREEN** |
| R2 overall | — | — | — | — | — | — | **AMBER** |
| R3 | M01→M02 | — | — | — | — | — | **CANCELLED** (stop after R2) |
| R3 | M02→M01 | — | — | — | — | — | **CANCELLED** |

Round 1 artifacts: `logs/adr0058-e3a3-phase0-r1-20260903-085706/`  
Round 2 artifacts: `logs/adr0058-e3a3-phase0-r2-20260903-090154/`

## 退出条件

- burst / P99 **大幅波动** → 先前 AMBER 可能噪声，继续 Phase 0
- burst≥10 或 P99>120 **可重复** → 进入 Phase 2（一次只改一个 underlay 变量）
- 两侧连续 GREEN → 可修 M03 / 开三节点 fan-out（仍不开媒体引擎）

## 本机一键（Round 1 正向，已装包）

```powershell
cd d:\workspace\project\talkback\talkback
.\scripts\adr0058-underlay-mcast-probe-start-run.ps1 `
  -Direction M01toM02 -SkipBuild -SkipInstall -Iface wlan0
```
