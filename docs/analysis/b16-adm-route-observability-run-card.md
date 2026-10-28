# B-16 WebRtcAudioRecordExternal Forward — Field Run Card

**Status:** AUTHORIZED  
**Package:** B-16 只读转发 `WebRtcAudioRecordExternal`（不改 AudioSource / MODE / speaker / AEC/NS / AGC；无第二路录音）  
**目的：** 补齐 B-15 观测缺口，对照 B-12 正常窗 vs 本轮低电平窗的实际 capture configuration。

---

## 对照

| 轮次 | 目录 | 角色 |
|------|------|------|
| B-12 | `logs/b12-adm-debug-20260928-174648` / `b12-adm-reinit-*` | 正常窗（M01 APM_IN ≈ −40） |
| B-14 | `logs/b14-clean-capture-20260929-085134` | 干净但低电平 |
| B-16 | `logs/b16-adm-route-<ts>/` | 本轮：含 AudioRecordExternal |

---

## 设备

| Label | Wireless ADB | 本轮 |
|-------|--------------|------|
| M01 | `192.168.31.46:5555` | 对照主对象 |
| M02 | `192.168.31.165:5555` | 对照 |
| M03 | `192.168.31.148:5555` | 对照 |
| M04 | `192.168.31.206:5555` | 旁听；不纳入裁决 |

---

## 硬约束

```text
禁止 logcat -c（保留 startRecording 启动窗）
先开抓包，再进 Meeting / 再触发 startRecording
不改音量策略以外的任何音频参数
```

---

## 测试步骤（你测）

1. 确认四机已装 B-16 包（助手已装则跳过）。  
2. 助手已开四机 logcat（**未清 buffer**）。  
3. 四机加入**同一** Meeting，等到能听远端（media plane）。  
4. **近讲 2–3 分钟**（优先 M01/M02/M03 各说一段短句亦可）。  
5. 主观记：是否断续 / 不清。  
6. 说 **好了** → 助手停抓并裁决。

---

## 抓包格式

```text
talkback/logs/b16-adm-route-<yyyyMMdd-HHmmss>/
  M01-logcat.txt … M04-logcat.txt
  run-meta.txt   # NO_LOGCAT_CLEAR=1
```

```text
adb -s <ip:5555> logcat -v threadtime -s Talkback:I MEETING_MEDIA_SHADOW:I *:S
# 不要加 logcat -c
```

**有效场判据：**

- 有 `WEBRTC_ADM tag=WebRtcAudioRecordExternal`（至少 session / active / config 一类）
- 说话机有 `point=APM_IN` + `rmsDbFS=`
- 无 `PRE_HW started`

**ENV_INVALID：** 全程无 `WebRtcAudioRecordExternal`，或启动窗被清掉导致只有稳态 APM、无 init/start 行。

---

## 裁决字段（本轮新增为主）

| 字段 | 期望来源 |
|------|----------|
| session ID / buffer | `WebRtcAudioRecordExternal` |
| active recording count | 同上 |
| AudioRecordingConfiguration / client source | 同上 |
| routedDevice | 同上 / verify |
| verifyAudioConfig PASS/FAILED | 同上 |
| HW AEC/NS used/enabled | `JavaAudioDeviceModule` / `WebRtcAudioEffectsExternal` |
| APM_IN med | `A12_APM_AB` |

---

## 裁决问句

> B-12 正常 vs 本轮低电平：实际 capture configuration / route / session / effect **是否发生可证实分叉？**

| 结果 | 含义 |
|------|------|
| 有分叉 | 沿该分叉继续（仍不猜参数） |
| 无分叉（字段齐全且一致） | 继续往更深 HAL/platform |
| 字段仍缺 | ENV_INVALID / 观测仍不够，先补齐再比 |
