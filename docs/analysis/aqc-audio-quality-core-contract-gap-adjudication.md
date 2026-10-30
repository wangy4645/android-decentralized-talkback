# AQC — Audio Quality Core：契约与实现差距裁决

**状态：** `ACCEPTED — CONTRACT + GAP ONLY`（2026-10-13）  
**授权：** **不授权** 代码改造、不授权新的 B8/Catalog/P1 场测刀  
**父路径：** [ADR-0058](../adr/0058-conference-multicast-media-group.md) 组播生产路径 **保留、不重开**  
**工作模式：** 从「继续修场测刀」切换为「建立产品级音质模块与听感 SLO」

```text
AQC
Decision:     ACCEPTED — CONTRACT + GAP ONLY
Does NOT:     implement · commit · push · reopen catalog / B8-D1 / P1 RX
Does NOT:     attribute all noise to TX, or all choppiness to missing PLC
Next:         Phase 1 code — see aqc-minimal-implementation-authorization.md
```

---

## 1. 裁决

现有实现已经部分满足**入场条件**（catalog 物化、游标恢复、本场 FGS 常驻），但**没有**满足产品音质契约：

| 契约问题 | 仓库事实（定点） | 结论 |
|----------|------------------|------|
| 每次消费都产出 20 ms PCM | `resolveBufferedSlot == null` 直接 `return`，不写 AudioTrack；`EqualWeightMixer.mix(empty)` 得到空 `samples`，`AndroidAudioTrackPlayoutSeam.write` 对空块 `return false` | **无**「永不空 tick」 |
| 缺帧有真实 PLC/CN | `PlcController` 只返回 `PLC_SYNTHESIS` / `SILENCE_GAP`；`decodeMixablePcmForPlayoutPull` **仅**在 `DECODE_FRAME` 时解码 | **PLC 不生成 PCM** |
| TX 编码前有权威处理与限幅 | 生产默认 `ProcessLocalMicFrameSource`（APM 前）；`encode(merged)` 无目标电平/限幅；`PostApmLocalMicFrameSource` 存在但未接到 TX | **无** TxProcessing 权威帧 |
| 混音响度不随本 tick 人数跳变 | `EqualWeightMixer`：`summed[i] /= n`，`n = participants.size` | **响度随活跃来源数变化** |

以上三件事实足以定义模块差距，**不足以**把场测全部杂音归因于 TX、把全部断续归因于缺 PLC。归因必须发生在模块边界上的可重复输入之后。

**AUDIO 继续 FAIL**，直到模块指标与设备听感同时达标。  
**B8-D1 / Catalog / 现有 B8-A/B 恢复：冻结。**  
**P1 灭屏 RX：独立缺陷，未关闭。**

---

## 2. 冻结范围

独立工作项：**Audio Quality Core（AQC）**。三个模块独立接口，**必须**由同一音频时钟与 PCM 帧契约串联。

| 保留 | 冻结 / 不重开 |
|------|----------------|
| ADR-0058 组播生产路径、wire、Top-K 资格策略 | Catalog 物化、B8-D1 soak、B8 resolve 诊断拨盘 |
| B8-A/B 已合入的游标恢复（不回调参） | 新的 B8 场测刀或 soak/skip 调参 |
| P1 灭屏 RX 作为独立缺陷记录 | 把 P1 并入 AUDIO 因果 |

本文件只冻结契约、测量定义与最小差异。**不**把草案接口写成仓库里已有的类名。

---

## 3. 统一帧与时钟（AQC 基础契约）

| 项 | 冻结值 | 现有仓库 |
|----|--------|----------|
| 采样率 / 声道 / 位深 | 48 kHz、mono、s16 | `MediaMixConstants.SAMPLE_RATE_HZ`、`OpusCodecConstants` 已是 48 k / mono / 960 @ 20 ms |
| AQC 帧长 | **20 ms = 960 samples** | 编码/解码/混音/AEC reverse 已按 960 |
| 采集内部帧 | 允许 10 ms，转换必须在明确边界 | `ConferencePcmFormat.CANONICAL` = **10 ms**；`Profile01ShadowMulticastTransmitSeam` 用 `halfFrame` 拼 20 ms |
| PCM 类型 | AQC 只认一种 20 ms 帧 | **两套** `PcmFrame`：`webrtc.conferenceaudio`（带 format）与 `conference.runtime`（带 `usableForMix`） |
| 时钟 | 同一 playout tick 驱动 `pull` / `mix` / `write` | `Profile01ShadowPlayoutClockSeam` tick → resolve → mix → `playout`；resolve 失败则整拍中断 |

转换规则：10 ms → 20 ms **只**允许发生在 TxProcessing 入口之前的 assembler 边界；混音与播放消费面不得再出现 10 ms 半帧。

---

## 4. 定点盘点：现有实现 → 契约 → 最小差异

盘点树：`talkback/android-board-talkback`（文档与主接线）。场测包所在 `talkback-morning-5628b71-signer` 在 TX V/level 上多一刀能量门，**不改变**下表三件核心差距。

### 4.1 AdaptivePlayout

**契约：** `pullPcm20ms(): PcmFrame` — 每次返回且只返回一帧 20 ms；真帧 / 有界 PLC / CN 可区分；恢复时无突变；禁止无限重复末帧。

**现有最近接口（不是该契约）：**

| 符号 | 路径 | 实际语义 |
|------|------|----------|
| `MediaExecutionPipeline.pullSlot` | `conference/runtime/MediaExecutionPipeline.kt` | 返回 `SlotPullDisposition`，**不返回 PCM** |
| `PlcController.onDeadlineLostSlot` | `conference/runtime/PlcController.kt` | 计数；注释：*Synthesis is counted only — no Opus/PCM* |
| `decodeMixablePcmForPlayoutPull` | `ConferenceMediaExecutionOrchestrator.kt` | `pullDisposition != DECODE_FRAME` → `null` |
| `resolveBufferedSlot` | `Profile01ShadowPlayoutClockSeam.kt` | `null` → **不调用 mix / 不写扬声器** |
| `AndroidAudioTrackPlayoutSeam.write` | 同 runtime | `samples.isEmpty()` → 失败，不补帧 |

**最小差异（未授权实现）：**

1. 在 **每源** 增加「消费必出 20 ms PCM」的适配层（可先包在现有 `pullSlot` + decode 之后），缺包时合成有界 PLC/衰减/CN，而不是 `null`。
2. 时钟在 resolve 失败或 mix 为空时，仍 `write` 一帧契约 PCM（CN 或衰减），并打 `kind=PLC|CN|SILENCE_POLICY|REAL`。
3. **不**再增加 B8 soak/cursor/skip 拨盘；游标逻辑冻结，只作为 AdaptivePlayout 的内部实现细节，直至该模块接管。

不能从本差距直接推出「场测全部断续 = 缺 PLC」。resolve 空、Top-K 空、RX 停收都会造成硬缝，须用固定 RTP 向量在模块边界拆开。

### 4.2 TxProcessing

**契约：** `process20ms(captureFrame): ProcessedTxFrame` — 明确 APM 前后所有权；目标电平与限幅作用于**实际编码**的 PCM；VAD 与该帧对齐；保留 in/out peak、RMS、clip、limiter 触发量。

**现有最近接口：**

| 符号 | 路径 | 实际语义 |
|------|------|----------|
| `LocalMicFrameSource` | `webrtc/LocalMicFrameSource.kt` | `acquire(sink)`，无 20 ms、无指标 |
| `ProcessLocalMicFrameSource` | 同上 | 生产默认；`WebRtcSharedFactory` SamplesReady = **APM 前** |
| `PostApmLocalMicFrameSource` | `webrtc/PostApmLocalMicFrameSource.kt` | 设计权威点；**TX 默认未注入** |
| `Profile01ShadowMulticastTransmitSeam.onCanonicalPcmFrame` | session/integration | 10+10 合并后 **直接** `opusEncoder.encode(merged)` |
| `ConcentusOpusEncoderSeam.encode` | runtime | 16 kbps VOIP；无限幅/目标电平/DTX |
| 场测树 `Rfc6464TxVoiceLevel` | morning-signer 同名 seam | 能量 VAD 与 **同一份 merged** 对齐（符合「同源」）；仍无 APM 后权威、无限幅 |

**最小差异（未授权实现）：**

1. 生产 TX `frameSource` 接到 **POST-APM**（或在 seam 内强制），并在文档/指标上标明所有权。
2. 在 `encode` 前增加目标电平 + 限幅，输出 `ProcessedTxFrame` 遥测（inPeak / outPeak / clipSamples / rms / limiterGain）。
3. VAD 只读处理后的权威 20 ms，禁止第二条 tap。
4. **不**用混音侧再衰减掩盖 TX 过热（A1 −6 dB 保持实验，不升生产）。

不能从本差距直接推出「场测全部杂音 = TX」。解码过热、1/N 泵音、回授在 AEC reverse 缺失时仍可能贡献。须在 TxProcessing 边界用固定 capture PCM 验收后再谈场测归因。

### 4.3 ConferenceMix

**契约：** `mix20ms(sourceFrames, membershipState): MixedPcmFrame` — 增益规则与「本 tick 成功解码人数」解耦；进出 / Top-K 有 ramp；缺失源与 PLC 源分列；输出峰值约束；可追踪每源贡献。

**现有最近接口：**

| 符号 | 路径 | 实际语义 |
|------|------|----------|
| `EqualWeightMixer.mix(List<PcmFrame>)` | `conference/runtime/EqualWeightMixer.kt` | 线性求和后 **`/= n`**，再 0.89 FS 峰值缩放 |
| `n` | 同上 | `participants.size` = **本 tick 实际拿到 PCM 的路数** |
| `MediaMixConstants.OUTPUT_PEAK_ABS_FS` | 0.89 | 峰值保护存在；空输入 → 空块 |
| Top-K / membership | orchestrator + resolver | 无 ramp；晋升有 live-edge fence（丢窗外帧） |
| A3 遥测 | 场测 A3_MIX_INPUT/OUTPUT | 有每源 REAL 贡献观测，**无** mix 增益计划 |

**最小差异（未授权实现）：**

1. 响度分母改为 **membership / 固定席位 / 1/√N(成员)** 之一（需单独短裁决），并对缺席与 PLC 位应用占位或 hold-gain，禁止 `n=成功解码数`。
2. 源加入/移除与 Top-K 变化做有界 ramp（例如 40–80 ms，与现有 `DECODER_WARMUP_MS=40` 对齐或显式改写）。
3. 保留峰值保护，但报告 limiter 触发，避免「靠削平换响度」。
4. **不**把 A1 场试 headroom 当成混音产品策略。

---

## 5. 串联：避免「换了名字的旧逻辑」

三个模块若各自为政，只会复制现有 seam。AQC 要求：

```text
capture 10ms ──assemble──► TxProcessing.process20ms
                              │
                              ├─ encode / RTP TX
                              └─ (metrics)

RTP / jitter / decode ──► AdaptivePlayout.pullPcm20ms  (每源每 tick 必有帧)
                              │
                              ▼
                         ConferenceMix.mix20ms(membership)
                              │
                              ▼
                         AudioTrack.write(960)   // 禁止因 resolve/empty 跳过
```

同一 `tickMediaTimeMs` 驱动 pull → mix → write。`SlotPullDisposition`、catalog、Top-K 资格仍属 ADR-0058，**不得**替代 `PcmFrame` 输出保证。

---

## 6. 首版听感 SLO（工程目标，待设备校准）

测量方法先冻结；数值可在第一轮可重复输入后修订，**修订必须写进本文件修订段**，不得在场测口头改门。

### 6.1 语音窗（RMS / clip 共用）

- **有效语音帧：** 20 ms 帧 `rmsDbfs ≥ −45`（静音/底噪不计入 RMS 目标）。
- **近讲有效窗：** 连续有效语音 ≥ 10 s 的并集；首版 desk 用固定语料回放，场测用同一读稿。
- **削波样本：** `|s| ≥ 32767`。另报 **limiter 触达**（缩放 ≠ 1）与 **0.89 FS 保护触达**，避免「clip% 达标但听感已限死」。
- **硬缝：** 某 20 ms 墙钟对应 **未向 AudioTrack 写入 960 samples**，或写入全零且事件标记为 `RESOLVE_NULL` / `EMPTY_MIX` / `WRITE_FAIL`（PLC/CN 有意输出除外）。

### 6.2 目标

| 维度 | 首版目标 | 测量边界 |
|------|----------|----------|
| 输出连续性 | 每个 playout 回调写出完整 960 samples | `AndroidAudioTrackPlayoutSeam.write` |
| 硬缝 | 正常网 + 约定丢包向量中，无 `resolve==null` / 空 mix 引起的整帧静音 | 输出 PCM + 事件时间线 |
| TX 削波 | 近讲有效语音窗削波样本比例 **≤ 2%** | TxProcessing **编码前**权威 PCM |
| TX 电平 | 近讲有效语音窗 RMS **约 −30～−40 dBFS** | 同设备、同距离、同语料 |
| 混音峰值 | 无 s16 溢出；峰值受控；limiter 触达需记录 | `MixedPcmFrame` |
| 人数切换 | 1/2/3 路固定输入电平切换，无主观阶跃 | desk 回放包络 + 听测 |
| 进出过渡 | 成员/Top-K 变化无突兀跳变 | 混音包络 |
| 主观 | 无明显刺耳、啸叫、硬缝、不可接受泵音 | 盲听 + 真机 |

### 6.3 固定输入集（先 desk，后设备）

单路语音、双路重叠、三路重叠、静音、短丢包、连续丢包、乱序、突发恢复。  
先 PCM/RTP 向量判定波形与 SLO；再一轮**有效**设备场测看耦合与听感。P1 不进入 AUDIO 因果。

---

## 7. 最少工作量推进（仍未授权写代码）

1. **本文件** = 契约 + 测量 + 差距（本步完成）。
2. 另裁「AQC 最小实现授权」时，每个模块只补上表差异，并加直接回归；禁止顺手重写媒体栈。
3. 先可重复输入，再一场有效设备测。
4. 不重跑 Catalog / D1；不把观察拆成无结论多轮。

---

## 8. 与场测门的关系

| Gate | 本裁决后 |
|------|----------|
| P0 Catalog | PASS，冻结 |
| B8-D1 声学因果 | **不支持**；停止该方向 |
| P1 M04 灭屏 RX | FAIL，分轨 |
| AUDIO | **FAIL**（产品模块未立、SLO 未验） |

场测指针：[B8-BRIGHTSCREEN-AB-VERDICT.md](../../../field-logs/lq-a-level-clip-20261010/b8-brightscreen-ab-field-20261012/B8-BRIGHTSCREEN-AB-VERDICT.md)

不 commit / 不 push。
