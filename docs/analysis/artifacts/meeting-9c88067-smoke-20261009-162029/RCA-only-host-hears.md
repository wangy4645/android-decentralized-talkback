# RCA — 只有 Host（M01）能听到声音（2026-10-09 烟测）

**Tip:** `9c88067`  
**Log:** 本目录 `M01.log`…`M04.log`  
**听感裁决：** 仍不开放（本文件只做机制归因）

---

## 现象

现场：仅 Host 能听到对方；叶节点听不到。

---

## 结论（机制）

**可听路径卡在 ANCHOR（WebRTC），Multicast 自动切流从未成功。**  
叶节点 ICE 通了但 **没有 `remote_track_attached`**，故扬声器无远端音；Host 有多路 remote track，故只有 Host 听得到。

这不是「没发包」——Shadow 组播 RX/TX/playout metrics 都在涨，但 `audioTrackOwner=SHADOW_METRICS_ONLY`（不可听）。

---

## 证据对照

| 信号 | M01 (Host) | 叶（log 文件 M02/M03/M04） |
|------|------------|---------------------------|
| `AUTO_CUTOVER_SUCCEEDED` | **0** | **0** |
| `MULTICAST_ACTIVE` | **0** | **0** |
| `audioTrackOwner` | `SHADOW_METRICS_ONLY` | `SHADOW_METRICS_ONLY` |
| `remote_track_attached` | **6** | **0** |
| `receive-path sync` | 12（含 remote_track） | 2（仅 `ice_connected`） |
| Shadow `ingressAccepted` / `successfulPlayoutWrites` | 很高 | 很高（metrics only） |

Cutover 仅在入会瞬间 DEFER：

```text
AUTO_CUTOVER_DEFERRED
  detail=LOCAL_TX_BINDING_NOT_READY,REMOTE_MULTICAST_RX_SOURCE_NOT_READY
  → REMOTE_MULTICAST_RX_SOURCE_NOT_READY
```

之后未见 `AUTO_CUTOVER_SUCCEEDED`（就绪条件后来即使 catalog/RX 有数据，也未再切成功）。

---

## 根因链（短）

```text
RC1 自动切流未成功
  → 扬声器仍归 ANCHOR/WebRTC
  → Host 收到多路 remote_track → 能听
  → 叶节点无 remote_track_attached → 不能听
  → Shadow 组播有包但 SHADOW_METRICS_ONLY → 不能补救可听
```

**问题出在「可听切流 / 叶端 ANCHOR 收轨」这一层，不是 P1–P3 desk 混音逻辑本身，也不是 Scheme B L1/L2 分层。**

---

## 设备 ID 标注修正（log 侧）

| log 文件 | adb serial | 运行时 `localModuleId` |
|----------|------------|------------------------|
| M01.log | HTUBB21B09220661 | **M01** |
| M02.log | 192.168.31.206:5555 | **M04** |
| M03.log | 192.168.31.115:5555 | **M02** |
| M04.log | 192.168.31.148:5555 | **M03** |

（角色按 `localModuleId` 理解；Host=M01 不变。）

---

## 与 10/8 下午「改好过」的对照（聊天历史）

| | 10/8 MC2/MC3（修好后） | 今日 `9c88067` 烟测 |
|--|------------------------|---------------------|
| 现场 | 四机 `AUTO_CUTOVER_SUCCEEDED` → `MULTICAST_ACTIVE`；能互听（后另有延时/杂音） | 仅 Host 能听 |
| `audioTrackOwner` | `MULTICAST_PRODUCTION` | 全程 `SHADOW_METRICS_ONLY` |
| Cutover | SUCCEEDED | 仅开局 `DEFERRED`（`REMOTE_MULTICAST_RX_SOURCE_NOT_READY`）后不再成功 |
| 根因 | 已修：**远程 member ready 也要再 `maybeAttempt`** | **同一 bug 又回来了** |

10/8 授权 **「GO Meeting 钩子」** 去掉了：

```text
if (moduleId != null && moduleId != localModuleId) return
```

修好后远程 catalog 一有条目就会二次 cutover。  
该修复在 **当前脏工作树** `ConferenceSessionMediaCoordinatorDelegate.kt` 里仍在；**不在** 今日安装的 tip **`9c88067`**（P1–P3 desk merge）里——`9c88067` 仍是带 `moduleId != local` early-return 的旧钩子。

→ **不是新 RCA**；是 **装错基线**：用冻结 desk tip 覆盖了 10/8 已场证的 cutover 钩子包。

## 建议下一步（待授权）

1. **用含钩子修复的工作树重新编包装四机**（或只把该 Delegate 一刀合进装包包），复测应再出现 `AUTO_CUTOVER_SUCCEEDED`。  
2. 把该钩子修 **提交进可追溯 tip**，避免再被 `9c88067` 覆盖丢掉。  
3. 未授权前：不重开 P1–P3 / 不补叶子 mesh。
