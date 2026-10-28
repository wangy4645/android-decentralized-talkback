# AQC Phase 2 — 验证准备与场测跑卡

**状态：** `AUTHORIZED — PREP + DESK ONLY`（2026-10-13）  
**父文档：** [aqc-minimal-implementation-authorization.md](./aqc-minimal-implementation-authorization.md) · [aqc-audio-quality-core-contract-gap-adjudication.md](./aqc-audio-quality-core-contract-gap-adjudication.md)  
**场测性质：** **探索性验证** — **不得**裁决 `AUDIO PASS`

```text
AQC-P2
Authorizes:   run card · corpus · metric definitions · desk isolation · fixed PCM vectors
Does NOT:     default-on flags · Catalog/B8/P1 reopen · commit/push · AUDIO PASS
Gate to field: desk flag isolation PASS + fixed-input vectors PASS + wiring audit PASS
```

---

## 0. 边界（开篇冻结）

1. Phase 1 **编译通过 ≠ SLO 达标**；默认关闭 ≠ 开启后行为已验证。
2. 本卡一次性覆盖最小必要验证，**不**拆成多轮无结论观察。
3. `0.89 FS` 限幅 **不是** 目标电平 AGC；稳定席位分母 **不是** 完整响度管理（**无 ramp = 已知限制**）。
4. 固定 RTP 向量包与 SLO 自动化属 Phase 3 — 首次真机仅探索。
5. P1 灭屏 RX **不**进入 AUDIO 因果。

---

## 1. Flag 默认值与生产接线审计（必过）

### 1.1 默认

| Flag | 默认 | 作用 |
|------|------|------|
| `AqcRuntime.txProcessingEnabled` | `false` | POST-APM tap + `TxProcessingChain` 后编码 |
| `AqcRuntime.membershipMixEnabled` | `false` | `MembershipStableMixer`（分母 = Top-K 席位数） |
| `AqcRuntime.continuousPlayoutEnabled` | `false` | PLC/CN 合成 + resolve 空时 CN write |

代码：`AqcRuntime.resetToProductionDefaults()` / 进程启动未调 `configure*` 时保持 false。

### 1.2 接线定点（2026-10-13 审计）

| Flag | 生效点 | 关闭时行为 |
|------|--------|------------|
| `txProcessingEnabled` | `Profile01ShadowMulticastTransmitSeam.armCaptureTap` → `PostApmLocalMicFrameSource`；`onCanonicalPcmFrame` → `TxProcessingChain.process20ms` 再 `encode` | `frameSource`（默认 ProcessLocal）直 encode |
| `membershipMixEnabled` | `ConferenceMulticastMediaPipeline.executeTimedMixCycle` → `MembershipStableMixer.mix20ms(..., topKMembers.size)` | `EqualWeightMixer.mix`（`/=` 本 tick PCM 路数） |
| `continuousPlayoutEnabled` | 同 pipeline：非 `DECODE_FRAME` 时 `PlayoutPcmSynthesizer`；`Profile01ShadowPlayoutClockSeam` resolve null → `playoutContinuousFallback` | pull 无 PCM 则该源不进混；resolve null **整拍不写** |

**接线陷阱（跑卡必记）：**

- TX tap 在 **arm** 时选定。场测须 **先设 flag，再 Start Service / 开始 capture**；中途改 flag 需停再起才会换 POST-APM。
- POST-APM 依赖 native `TalkbackApmPcmProduction`；若 JNI 无交付，开启 TX 后可能 **无包/静音** — 记为 ENV，不作 AUDIO PASS。
- `Orchestrator.executeTopKSlotMixCycle` 仍走 `EqualWeightMixer`（harness/旧路径）；**生产组播 playout** 走 `ConferenceMulticastMediaPipeline`。Phase 2 以 pipeline 路径为准。

### 1.3 探索开启方式（非默认）

仅当设备显式设置 debug 属性时开启（`TalkbackApp.onCreate` 调用 `AqcRuntime.applyOptionalDebugSystemProperties()`）：

```text
adb shell setprop debug.talkback.aqc.tx 1
adb shell setprop debug.talkback.aqc.mix 1
adb shell setprop debug.talkback.aqc.playout 1
```

未设置 → 保持全关。**禁止**把三 flag 写死为 true 进生产默认。

---

## 2. Desk 回归矩阵（真机前必过）

| ID | 验证项 | 方法 | PASS 条件 |
|----|--------|------|-----------|
| D0 | 默认关 | `AqcRuntime` 构造后三 flag false | 断言 |
| D1 | TX 隔离 | 关：热帧 encode 路径不调 chain（模块测：关时 desk 不强制）；开：`process20ms` 降峰 | `AqcPhase2DeskTest` |
| D2 | Mix 隔离 | 同 1 路 PCM：席位分母=2 的峰值 **&lt;** EqualWeight 分母=1 | 同上 |
| D3 | Playout 隔离 | PLC disposition → 960 samples；kind ≠ REAL | 同上 |
| D4 | 连续 fallback | `continuousFallbackFrame` 长度 960 | 同上 |
| D5 | 固定输入 · 人数跳变 | 1 路满幅 vs 同帧在席位=2 下 amplitude 约减半（稳定分母） | 同上 |
| D6 | 已知限制文档化 | ramp 缺失写入本卡 §5 | 人工勾选 |

模块测试：`com.talkback.core.conference.aqc.AqcPhase2DeskTest` + 既有 `AqcModuleDeskTest`。  
注：仓库全量 `testDebugUnitTest` 另有无关编译失败 — Phase 2 **以 AQC 包测试 + `compileDebugKotlin` 为准**，不阻塞在无关用例上。

---

## 3. 指标定义（场测 log 必抽）

### 3.1 TX（编码前权威 PCM）

开启 `tx` 后日志相位：`AQC_TX_METRICS`（见 observability）

| 字段 | 含义 |
|------|------|
| `tapPoint` | `POST_APM`（flag 开时） |
| `inPeak` / `outPeak` | 处理前后峰值 abs |
| `inClip` / `outClip` | `|s|≥29000` 样本数 |
| `inRmsDb` / `outRmsDb` | RMS dBFS |
| `limiterScale` / `limiterApplied` | 是否触发 0.89 FS 限幅 |

**语音窗（与契约裁决一致）：** 帧 `rmsDbfs ≥ −45` 才计入近讲 RMS/clip 比例。

### 3.2 播放

| 事件 | 含义 |
|------|------|
| `AQC_PLAYOUT_FALLBACK` | resolve 空仍写入 CN（960） |
| `SourceMixInputKind.PLC`（A3 既有） | 合成进混（与 REAL 区分） |
| 无 `successfulWrites` 增长且无 FALLBACK | **无输出硬缝**（flag 关时的旧行为） |

**禁止：** 把 CN/PLC 帧的非零能量当成「连续真语音 PASS」。

### 3.3 混音

继续用 `A3_MIX_OUTPUT` / `A3_MIX_INPUT`；附加口径：

- 人数切换听感对照：**固定输入 desk** 为主；场测记 1/2/3 人主观与 `mixParticipantCount`。
- **无 ramp**：进出 Top-K 的突兀跳变 **不** 判为 Phase 2 失败，记 **KNOWN_LIMIT**。

### 3.4 明确不是成功标准的东西

- 仅 A3 混音 RMS 改善、无 TX 编码前指标。
- clip%≤2% 但 limiter 长期贴顶（听感已压死）。
- starve 标签变好但 FALLBACK 全是 CN。

---

## 4. 固定语料与场测脚本（探索）

**仅在 §1–§2 PASS 后执行。**

### 4.1 固定

| 项 | 值 |
|----|-----|
| 语料 | 与历史亮屏 A 相同读稿；30 s 读 + 10 s 停 ×3 |
| 距离 / 音量 | 近讲固定距离；系统音量与昨日 A 一致，测中不调 |
| 四机 | M01 USB 发言主测；M02/M03/M04 接收；SHA 与安装包记录 |
| 目录 | `field-logs/lq-a-level-clip-20261010/aqc-phase2-explore-<stamp>/` |
| 对照 | OFF 一场（三 flag 0）+ ON 一场（三 prop=1）；**同日同脚本** |

### 4.2 脚本（每臂 ≥5 min）

1. `logcat -c`；设 prop（ON 臂）或确认 unset（OFF 臂）；**强制停 App 再启 Service**（使 TX arm 读到 flag）。
2. 亮屏读稿 2 min → 灭屏 1 min（记台号，建议 M04）→ 亮屏 2 min。
3. 听感便签：刺耳 / 泵音 / 硬缝 / 小 / 杂 — 分列，不与 P1 合并。
4. 停抓；跑分析：TX `AQC_TX_METRICS`、M01 A3 clip/RMS、M04 FALLBACK vs REAL、HB `msSinceLastDatagram`（P1 旁注）。

### 4.3 探索裁决口径（非 AUDIO PASS）

| 结果 | 记录 |
|------|------|
| OFF 与历史基线同量级 | ENV 可比 |
| ON 后 TX outClip↓ 且 in 热 | TxProcessing **方向正确**（非 SLO PASS） |
| ON 后仍空 tick / 无 FALLBACK | continuous **未生效或接线失败** |
| ON 后听感明显 CN 沙沙 | 连续性契约有，音质未过 — **预期限制** |
| M04 灭屏 HB 长空窗 | **P1**，标污染，不作 AQC 失败主因 |
| 任何 AUDIO PASS 措辞 | **禁止** |

---

## 5. 已知限制（必须写进场测笔记）

1. 无目标电平 AGC — 只有峰值限幅。  
2. 无成员进出 ramp。  
3. PLC 为衰减末帧 / CN，非 Opus FEC/NetEq。  
4. 固定 RTP 向量自动化未就绪（Phase 3）。  
5. POST-APM 依赖补丁 AAR/JNI。

---

## 6. 执行顺序与本轮结果（2026-10-13）

| 步骤 | 结果 |
|------|------|
| ① 接线审计 §1.2 | **PASS** — 三 flag 各控独立接线；默认 false |
| ② Desk D0–D5 | **PASS** — `./gradlew :android-board-talkback:testDebugUnitTest -PaqcDeskOnly --tests "com.talkback.core.conference.aqc.*"` → **12/12** |
| ③ `compileDebugKotlin`（board + app） | **PASS** |
| ④ 探索场测 §4 | **无效探索（2026-10-13）** — 裸编 `talkback/` 包无声；见 `field-logs/.../aqc-phase2-explore-20261013-103409/VERDICT.md`；**不对 AQC 作结论**；空写另立解码/播放故障轨 |

Desk 隔离：`-PaqcDeskOnly` 将 test 源限定为 `com.talkback.core.conference.aqc`（避免无关破损用例挡编译）。全量 unitTest 健康恢复后应去掉该属性再跑回归。

**Desk 注记：** 0.89 FS 限幅顶与 clip 阈值 `|s|≥29000` 同量级 — 限幅后 `outClip` 可不降；断言看 `limiterApplied` + `outPeak < inPeak`，勿误判「限幅无效」。

不 commit / 不 push。
