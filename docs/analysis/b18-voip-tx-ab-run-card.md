# B-18 voip_tx vs MIC (M02) — Field Run Card

**Status:** AUTHORIZED  
**Change:** 仅 M02（`Build.MODEL=g80l_221_v1_fhd`）`AudioSource.MIC`；其余机仍 `VOICE_COMMUNICATION`。  
**冻结：** AEC/NS/AGC/gain/VAD/Top-K/mixer/encoder/reverse；无第二路录音；**不裁决 M03 HiSi path**。

---

## 问句（唯一）

> 离开 `voip_tx` 后，M02 `APM_IN` 是否从 −70 dB 级回到 −40～−50，并恢复 V / Top-K / 可听性？

---

## 对照

| 轮次 | 目录 | M02 |
|------|------|-----|
| B-16 基线 | `logs/b16-adm-route-20260929-091742` | `VOICE_COMMUNICATION` → **voip_tx** → APM_IN ≈ −75.5 |
| B-18 本轮 | `logs/b18-voip-tx-ab-<ts>/` | **MIC** → 期望非 voip_tx |

---

## 设备

| Label | 角色 |
|-------|------|
| **M02** | **主裁决对象** |
| M01 | 旁证（仍 VOICE_COMMUNICATION） |
| M03 | 旁听；本轮不裁决 HiSi |
| M04 | 旁听 |

---

## 测试步骤

1. 四机已装 B-18 包并拉起。  
2. 助手已开 logcat（**禁止 `logcat -c`**）。  
3. 四机进同一 Meeting，等到 media plane。  
4. **M02 近讲 2–3 分钟**（主证据）；M01 可短讲作对照。  
5. 主观记：M02 是否仍小/断续。  
6. 说 **好了** → 停抓 + 裁决。

---

## 必查字段

| 字段 | 期望 |
|------|------|
| `B18_CAPTURE_SOURCE` | M02：`source=1`（MIC）`m02Board=true` |
| `client audio source=` | M02：`MIC`（非 VOICE_COMMUNICATION） |
| `dumpsys media.audio_policy` | M02：`voip_tx` **不应** curActive=1；应为 `primary input` 等 |
| `APM_IN` med | M02：−40～−50 = YES；仍 −70 级 = NO |
| V=1 / Top-K none | 随电平是否恢复 |
| HW AEC/NS | 仍请求 true（不作为本轮变量） |

**ENV_INVALID：** M02 仍停在 `voip_tx` active，或无 `B18_CAPTURE_SOURCE` / 无 APM_IN。

---

## 裁决

| 结果 | 含义 |
|------|------|
| YES | RCA：`voip_tx` path → capture 矮 → V/Top-K 下游 |
| NO | `voip_tx` 不是充分因果；下一层再看 AGC/effect，不回头改应用层 |
| ENV_INVALID | path 没切开，本轮不作因果裁决 |
