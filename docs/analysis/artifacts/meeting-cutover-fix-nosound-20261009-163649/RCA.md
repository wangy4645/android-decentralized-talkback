# RCA — cutover 钩子包后四机完全无声（2026-10-09 16:36）

**APK SHA256:** `0CB41D959D3FE1917632A5C99F4B60F778611851674CB2A1A2E73EC73821B43E`  
**Session:** `5ec79f50-d3fe-4a85-8a93-9603ba160e3a`

## 一句话

**Cutover 钩子修好了（四机都进了 `MULTICAST_ACTIVE`），但组播混音几乎一直是空写；同时 ANCHOR 已释放 → 没有任何后备可听路径 → 完全无声。**

这和 10/8「只有 M01 能听」**不是同一失败点**；更接近 10/8 MC2/MC3 cutover 成功后的 **播放漏斗饿死 / resolved 冻死**。

## 证据

| 项 | 四机结果 |
|----|----------|
| `AUTO_CUTOVER_SUCCEEDED` | **有**（钩子生效） |
| `MULTICAST_ACTIVE` | **有** |
| `owner=MULTICAST_PRODUCTION` | **有** |
| `A3_MIX_INPUT` 稳态 | `realFrames=0` / `plcFrames=0` / `mixedFrames=0` |
| funnel | `target` 前进（~23k），`resolved` 冻在旧 slot（~20k）→ **lag 数千 slot** |
| ANCHOR | cutover 已 `CUTOVER_RELEASE_ANCHOR` → WebRTC 可听面关掉 |

M01 仅见极短一窗 `mixedFrames=10560 rms=-68.4`，随后全 0。

`PLAYBACK_DIAG NO_FLOOR_OWNER` 出现在会前/频道面，是 **PTT Floor** 合同，不是本次 Meeting 无声主因。

## 因果链

```text
远程 member ready → maybeAttempt（钩子 OK）
  → AUTO_CUTOVER_SUCCEEDED / MULTICAST_ACTIVE
  → 释放 ANCHOR（Host 也不能再靠星型听）
  → 组播 playout：target 跑、resolved 冻 / 缓冲对不上
  → mix realFrames=0 → AudioTrack 空写
  → 主观：四机完全没声音
```

## 和上一场对比

| | `9c88067`（无钩子） | 本包（有钩子） |
|--|---------------------|----------------|
| Cutover | 失败，停在 shadow | **成功** |
| Host 听感 | 仍可靠 ANCHOR 听到 | ANCHOR 已放 → **也没声** |
| 叶听感 | 没声（预期） | 没声（mix 空） |

## 建议（待授权）

1. **不要回退钩子**（否则又回「只有 Host 能听」）。  
2. 下一刀对准 **playout funnel：resolved 冻死 / slot lag / 空 mix**（与 10/8 MC2/F9.3 同类），不是再改 cutover。  
3. 可选应急：仅现场先确认是否仍在会、是否有人在讲；需要对照包再说。
