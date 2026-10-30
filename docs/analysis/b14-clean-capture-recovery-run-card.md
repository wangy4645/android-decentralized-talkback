# B-14 Clean Capture Recovery / Repro — Field Run Card

**Status:** AUTHORIZED  
**Package:** 已装四机干净包（B-13 第二路 `PRE_HW` AudioRecord **不启动**）  
**目的：** 确认 B13 退化是否随第二路录音消失。  
**不做：** 不加 tap；不改 AEC/NS/AGC、route、VAD、Top-K、Gate、timeout。

---

## 对照基线

| 轮次 | 目录 | 预期角色 |
|------|------|----------|
| B-12 正常 | `logs/b12-adm-debug-20260928-174648` | M01 `APM_IN` med ≈ −40.5；Top-K none ≈ 34% |
| B-13 差 | `logs/b13-end-dump-20260928-180046`（PRE 启动后） | M01 降至 −65/−72；none 75–79% |
| B-14 本轮 | `logs/b14-clean-capture-<ts>/` | 干净生产单路 capture |

---

## 设备

| Label | Wireless ADB | 备注 |
|-------|--------------|------|
| M01 | `192.168.31.46:5555` | 需有 `APM_IN` + `TX_CANONICAL` |
| M02 | `192.168.31.165:5555` | 首选 media plane |
| M03 | `192.168.31.148:5555` | 需有 `APM_IN` + `TX_CANONICAL` |
| M04 | `192.168.31.206:5555` | 旁听；异常可剔除 |

身份以 `moduleId=` / USB serial 为准。

---

## 前置（硬）

```text
四机同一 Meeting；已进 media plane（能听远端）
只用生产唯一 AudioRecord（无 PRE_HW 第二路）
不改音量/距离策略以外的任何音频参数
测前确认 log 里无 PRE_HW started
```

---

## 测试步骤（你测）

1. 确认四机已装本轮干净包并已拉起。  
2. 四机加入**同一** Meeting，等到都能听远端（media plane）。  
3. 告知助手「开始」或直接开讲（logcat 已开则继续录）。  
4. **有效近讲 2–3 分钟**：至少一人（优先 M02；复证 M01/M03）连续说短句；其余尽量安静。  
5. 主观记：是否接近 B12 清晰度；有无破音/扩叉/听不见。  
6. 说 **好了** → 助手停抓 + 裁决。

---

## 抓包（已开 / 格式）

```text
talkback/logs/b14-clean-capture-<yyyyMMdd-HHmmss>/
  M01-logcat.txt … M04-logcat.txt
  run-meta.txt
```

```text
adb -s <ip:5555> logcat -v threadtime -s Talkback:I MEETING_MEDIA_SHADOW:I *:S
```

**有效场判据（缺任一则 ENV_INVALID）：**

- 说话机有 `point=APM_IN` + `rmsDbFS=`
- 说话机有 `TX_CANONICAL`（或等价 canonical voice 行）
- Meeting funnel / Top-K 行在走（非纯信令）

**负向检查：** 全程不应出现 `PRE_HW` / `point=PRE_HW` started。

---

## 裁决指标（仅现有线）

| 指标 | 来源 |
|------|------|
| `APM_IN` rmsDbFS med/min/max | `A12_APM_AB` / CapturePCM |
| `TX_CANONICAL` rms / V | `TX_CANONICAL_VOICE` |
| V=1 占比 | canonical / voice 行 |
| Top-K `none` 占比 | Top-K / funnel |
| mix clip / starve | `A3_MIX_INPUT` 等现有 gate |

---

## 裁决

| 结果 | 含义 |
|------|------|
| 接近 B12（M01 `APM_IN` 约 −40 档；none 不高） | 第二路录音与退化**高度相关**；B13 不再作 A/B 测量；下一刀查干净路径下 capture/input 为何变弱 |
| 仍 −60～−70 dBFS | B13 副作用**不是主因**；RCA 留在 capture/platform input；再考虑只读诊断 |
| 介于两者 / 只出现一次 | 先看能否稳定复现；不单次定性 |

**禁止：** 根据本轮坏环境改音频参数；把相关升格为已证因果。
