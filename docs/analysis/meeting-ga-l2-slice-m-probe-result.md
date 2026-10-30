# Slice-M 物化探测结果（§5 GO）

**状态：** 完成 — **未达成** `compileDebugKotlin` PASS（在授权边界内停止）  
**日期：** 2026-10-09  
**Worktree：** `talkback-l2-slice-m-probe` @ `413d4ff`（`substrate/l1-ops`）  
**未 push / 未 PR / 未改生产逻辑**

---

## 裁定对照

| 授权项 | 执行情况 |
|--------|----------|
| L2 三文件 @ `faf8884` | ✅ 已物化 |
| 从缺失符号最小扩闭包 | ✅ 编译驱动 3 轮后第 4 轮停止 |
| 排除 `ConferenceSessionMediaCoordinatorDelegate`、`TalkbackCoordinator` | ✅ 物化集中 **无** 二者 |
| 仅 `compileDebugKotlin` | ✅ |
| 不升级为 Slice-F | ✅ 未整包 `1ab10ae` |
| 不修改实现 / 不改 `build.gradle` | ✅（亦导致下述硬停止） |

---

## 1. 结果摘要

**在「L2=`faf8884` 布线 + 自 `1ab10ae` 回灌依赖 `.kt`、不改 Gradle」前提下，本次探测未能得到 BUILD SUCCESSFUL。**

| 指标 | 值 |
|------|-----|
| 物化生产 `.kt` 合计 | **66**（3 L2 + **63** GA @ `1ab10ae`） |
| 占 `1ab10ae` conference 包（228） | **~29%** |
| 编译轮次（驱动新增） | bootstrap 17 + round 1–3 增量 |
| 完整清单 | `artifacts/l2-closure/slice-m-probe-log.md` |

**含义（按你的通过标准 §3）：**  
闭包已扩张到 **完整 multicast 执行子图**（authority、wire/SRTP、transport pipeline、runtime decode/mix/jitter/TopK、cutover 等），属于 **大范围 Meeting 媒体运行时能力**，虽 **未** 引入 Coordinator 委托类，但已触及「明显 GA 运行时」边界 — **不宜在未重新裁定的情况下继续自动扩文件或升级为 Slice-F**。

---

## 2. 停止原因（硬失败，按优先级）

### 2.1 构建依赖：`concentus` 不在 L1 Gradle 闭包

- `ConcentusOpusDecoderSeam.kt` 需要 `io.github.jaredmdobson.concentus.*`。
- **`413d4ff`** 的 `android-board-talkback/build.gradle.kts` **无** `concentus` 依赖；**`1ab10ae`** 才加入。
- 在「只物化 `.kt`、不改 build」授权下，**无法**消除 `OpusDecoder` / `io` 未解析错误。

### 2.2 API 版本错配：`ConferenceSessionMediaBridge`（`1ab10ae`）× `ConferenceSessionMediaWiring`（`faf8884`）

- `faf8884` 树中 **不存在** `ConferenceSessionMediaBridge.kt`（该文件随 **`1ab10ae`** 引入）。
- `Profile01ShadowPlayoutClockSeam`（L2）静态依赖 Bridge；bootstrap 从 `1ab10ae` 回灌 Bridge。
- **`1ab10ae` Bridge** 调用 `wiring.currentMediaKeyEpoch` / `rotateMediaKeyEpoch`；**`faf8884` Wiring** 无这两处 API（已在 `1ab10ae` Wiring 上补齐）。
- 另：**`1ab10ae` `ConferenceMulticastMediaPipeline`** 引用 `maybeLogIngressFunnel`，与 **仅锁增量** 的 `faf8884` Wiring 历史也不一致。

→ 这不是「再补几个类型文件」能解决的，而是 **L2 提交与 GA baseline 提交在符号历史上前后交错**（`faf8884` 父提交为 `d06f17c`，Bridge/完整栈在 `1ab10ae`）。**在锁定 `faf8884` 三文件原文时，不能同时无冲突地回灌「最新」Bridge/Pipeline。**

### 2.3 政策边界（你的停止条件 #3）

- 已物化 **63** 个 GA conference 源文件，覆盖 **产品级 multicast 媒体管线**，远超「几个 L3 类型引入件」的直觉。
- **未** 含 `TalkbackCoordinator` / `ConferenceSessionMediaCoordinatorDelegate`，但 **已含** `MeetingProductMediaShadow`、真实装配与 decode/playout 接缝类型。
- 按裁定：**停止自动扩张**，回报边界，**不** 自行改为 Slice-F 或改代码「抹平」错配。

---

## 3. Slice-M 起始集 vs 编译驱动增量

| 阶段 | 文件数 | 说明 |
|------|--------|------|
| L2 @ `faf8884` | 3 | 固定种子 |
| §2 bootstrap | 17 | 直接 import / 符号属主（Bridge、Types、Catalog、Shadow、cutover 种子、authority/runtime/transport/wire 主干） |
| compile round 1 | +26 | 如 `MediaExecutionPipeline`、`ConferenceWireIngress`、`AndroidAudioTrackPlayoutSeam` 等 |
| compile round 2 | +17 | jitter、SRTP、socket binder、TopK、PromotionLiveEdgeFence 等 |
| compile round 3 | +3 | `SourceBindingGate`、`SrtpReplayAdmission`、`WireDemux` |
| round 4 | — | 未解析 `Opus*`（Maven）+ Bridge/Wiring 方法错配 |

每一行路径与「 unresolved 符号」理由见 **`slice-m-probe-log.md`** 表格。

---

## 4. 与「通过标准」的对照

| # | 标准 | 本次 |
|---|------|------|
| 1 | `compileDebugKotlin` 成功且无清单外文件 | **未满足**（失败 + 见 §2） |
| 2 | 每文件有明确依赖理由 | **满足**（日志表） |
| 3 | 扩张到 GA 接线 / Coordinator / 大范围 Meeting | **已触及 GA 媒体子图广度** → **停止** |

---

## 5. 探测方法复现

```powershell
# 主仓库脚本（只读 git + 指定 worktree）
git -C talkback worktree add talkback-l2-slice-m-probe origin/substrate/l1-ops
Copy-Item talkback/local.properties talkback-l2-slice-m-probe/
python talkback/docs/analysis/artifacts/l2-closure/slice_m_probe.py talkback-l2-slice-m-probe
```

脚本要点：`BUILD SUCCESSFUL` 判定、`git checkout 1ab10ae -- <path>`、排除 Coordinator 相关路径、编译驱动补文件。

---

## 6. 建议的后续裁定（非本次执行）

1. **#203 / L1**：不变；与本探测独立。  
2. **`d06f17c`**：仍延后。  
3. **L2 PR 组织**：在锁定 `faf8884` 前提下，需 **书面选择** 之一：  
   - **(i)** 允许 **build 引入切片**（至少 `concentus` 一行）+ **同一历史平面** 的 Bridge/Pipeline/Wiring（通常意味着 **不可拆的 GA 引入单元** 大于 Slice-M 期望）；或  
   - **(ii)** 对 L2 做 **历史重基**（使 Bridge/Wiring/Pipeline 与 `faf8884` 同提交平面）— 属新 authoring，需单独授权；或  
   - **(iii)** 接受「**不存在** 在 post-L1 上仅 L2+极小切片的可编译闭包」，合并单元上移。  
4. **听感 / 四机 / GA**：**不**因本探测作任何推断。

---

## 7. 不变项

- P1–P3 **CLOSED** · 听感 **UNKNOWN** · 无字段活动。
