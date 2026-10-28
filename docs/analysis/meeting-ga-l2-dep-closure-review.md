# L2（`faf8884`）依赖闭包审查 — 方案 A 静态分析

**状态：** Slice-M 物化探测已完成 — 见 `meeting-ga-l2-slice-m-probe-result.md`（**未** compile PASS）  
**日期：** 2026-10-09  
**依据裁定：** L2 不单独 PR；先识别 L3 类型依赖最小切片；不搬代码、不改生产、不 cherry-pick、不 push  
**程序边界：** #203/L1 独立 · `d06f17c` 延后 · 不整包 `1ab10ae` · 听感 `UNKNOWN` · 四机不启动

---

## 1. 结论摘要

| 事实 | 含义 |
|------|------|
| post-L1（`413d4ff`）上 **零** `com.talkback.core.conference.*` 生产文件 | L2 不是「几个类型文件」的补丁，而是 **在整块 Meeting multicast 栈之上** 加锁与 shadow 时钟 |
| `faf8884` 自带 3 个生产文件 + 强依赖 GA 栈 | 方案 A 的「最小切片」≈ **conference 包内 ~63 个 GA 生产 `.kt` + 3 个 L2 文件**（见 §4），约为 `1ab10ae` 该包 228 文件的 **~29%** |
| `faf8884` 的 `ConferenceSessionMediaWiring.kt` **不**引用 `ConferenceSessionMediaCoordinatorDelegate` | L2 **编译闭包不必包含** Coordinator 委托面；**运行时** Meeting 仍通过 delegate 接 shadow（GA 接线，非 L2 commit 内） |
| 静态 BFS **不能**替代一次 `compileDebugKotlin` | KDoc/歧义短名可能多收（如 `TalkbackCoordinator`）；也可能漏收（如 `SessionMediaWiringHarness` 仅测试需要） |

**对分层的调整（相对原 L2=单 commit）：**  
L2 在 `main` 上的可审查单元应定义为 **「L2 补丁 + 已裁定的 GA 引入切片（Introducer slice）」** 同一 PR 或严格序贯的两步，且 **不能** 表述为「只合 `faf8884` 五个 path」。

---

## 2. 编译错误起点的直接缺失符号（L1 + 仅 `faf8884`）

来自已执行探测：`substrate/l1-ops` + cherry-pick `faf8884` → `compileDebugKotlin` **FAIL**（见 `meeting-ga-l2-compile-probe.md`）。

### 2.1 `Profile01ShadowPlayoutClockSeam.kt` 直接 import

| 符号 | 定义位置（`1ab10ae`） | 说明 |
|------|------------------------|------|
| `ConferenceSessionMediaBridge` | `session/ConferenceSessionMediaBridge.kt` | 薄门面；**不** import `TalkbackCoordinator` |
| `ConferenceSessionMediaWiring` | L2 提交内（`faf8884`） | 锁与 pipeline 主体 |
| `AbsoluteMediaPlayoutClock` | `transport/AbsoluteMediaPlayoutClock.kt` | 20ms 绝对播放时钟 |
| `PipelinePlayoutResult` | `transport/ConferenceMulticastMediaPipeline.kt`（及相关 transport 类型） | playout 结果枚举/密封类 |
| `AudibleOwnershipState` | `session/integration/cutover/AudibleOwnershipState.kt` | RC1 可听归属 |
| `ReplacementCutoverRc1` | `session/integration/cutover/ReplacementCutoverRc1.kt` | cutover 熔断 OBS |

### 2.2 `Profile01ShadowRuntimeObservability.kt` 直接 import

| 符号 | 定义位置（`1ab10ae`） | 说明 |
|------|------------------------|------|
| `MemberBindingFact` | `session/ConferenceSessionMediaTypes.kt` | 成员绑定事实 |
| `SessionMediaRuntimeSnapshot` | 同上 | OBS 快照；**无** AudioTrack |

同文件还通过标识符引用 `MeetingProductMediaShadow.LOG_TAG`（无 import）→ `session/integration/MeetingProductMediaShadow.kt`。

### 2.3 `ConferenceSessionMediaWiring.kt`（`faf8884`）直接 import（节选）

| 包/符号 | 定义位置（`1ab10ae`） |
|---------|------------------------|
| `MediaKeyContextFact`, `SourceAuthorizationFact` | `authority/*` |
| `ConferenceMediaExecutionOrchestrator`, `MediaGroupEndpointBinding`, `TransportHandle` | `runtime/*` |
| `ConferenceMulticastRealMediaAssembly`, `PipelineAdmitResult`, `RelativeMediaTimeline`, … | `transport/*` |
| `WireIngressResult` | `wire/WireTypes.kt` / ingress |
| `Profile01ShadowRuntimeObservability` | L2 提交内 |
| cutover `audiblePlayoutSeam` 等 | `session/integration/cutover/*` |

同文件体内还有 **未 import 的同级类型**（静态分析必须计入）：

| 符号 | 定义位置 |
|------|----------|
| `ConferenceSessionMediaFact` | `ConferenceSessionMediaTypes.kt` |
| `SourceBindingCatalog` | `session/SourceBindingCatalog.kt` |

---

## 3. 锚点类型追踪（是否 GA 接线 / Coordinator / 运行时副作用）

### 3.1 `ConferenceSessionMediaBridge`

- **角色：** Coordinator 与 `ConferenceSessionMediaWiring` 之间的 **无状态委托**（start/install/teardown/stop）。
- **TalkbackCoordinator：** 仅 KDoc 提及；**无**对 `TalkbackCoordinator` 的编译依赖。
- **副作用：** 无网络/无 AudioTrack；调用方若在真实 Meeting 中 invoke，则进入 multicast wiring（见下）。

### 3.2 `MeetingProductMediaShadow`

- **角色：** Phase A shadow 钩子与 OBS；`enabled` **默认 `true`**。
- **Coordinator：** KDoc 写明由 **Meeting 生命周期** 驱动 `ConferenceSessionMediaCoordinatorDelegate`；**L2 三文件不 import delegate**。
- **副作用：** 捕获异常 → `ReplacementCutoverRc1.onReplacementRuntimeFatal`；**不**直接开 AudioTrack（shadow 走 `createShadow` 类路径，见 assembly 文档字符串）。
- **是否整包 Meeting runtime：** 单独编译进 APK **不会**自动挂接 Coordinator；**但若** 与 GA 同批合入且 delegate 已接线，则 shadow 路径可被激活 — 审查切片时应把 **delegate + coordinator 调用点** 标为 **「GA 运行时接线（非 L2 diff 内）」**，避免 L2 PR 悄悄带上。

### 3.3 `SessionMediaRuntimeSnapshot` / `MemberBindingFact`

- **角色：** 控制面事实与只读 OBS 结构。
- **副作用：** 无 I/O；L2 OBS 日志仅 `Log.i`。

### 3.4 `ConferenceSessionMediaWiring`（L2 增量）

- **角色：** RCA5-A — `withSessionPipelineLock` 序列化 shadow ingress / playout 与 mix 周期。
- **依赖栈：** 完整 **multicast 产品装配**（`ConferenceMulticastRealMediaAssembly` → orchestrator → pipeline → wire ingress）。
- **副作用（代码能力层面，非「已接线」）：** 可构造 **真实** Opus 解码 + **真实** `AndroidAudioTrackPlayoutSeam`（见 `ConferenceMulticastRealMediaAssembly`）；是否可听仍取决于 **谁** 创建 assembly、是否 shadow 工厂 — **听感不评判**。

### 3.5 `ConferenceSessionMediaCoordinatorDelegate`（闭包争议项）

- **在 GA 中：** 引用 `Profile01ShadowPlayoutClockSeam`、未在 `faf8884` 中的 `Profile01ShadowMulticastReceiveSeam` 等。
- **对 L2-only 编译：** `faf8884` 版 `ConferenceSessionMediaWiring` **无** delegate 引用 → **建议列为「GA 接线层，默认不纳入 L2 编译切片」**；若探测失败再单独裁定是否被其他 GA 文件牵入。

---

## 4. 最小生产文件清单（静态 tiered 闭包）

**方法：** 以 `faf8884` 三文件为种子，在 `1ab10ae` 的 `com.talkback.core.conference/**` 上做 import + conference 内类型标识符 BFS，再向外一层 `com.talkback.*` import 扩张（**不**扫全模块歧义短名，避免 551 文件爆炸）。

**产物机器可读列表：**  
`docs/analysis/artifacts/l2-closure/closure-tiered.txt`（**68** 个 GA 回灌路径 + 脚本统计 **phase_a = 66** conference 闭包含 L2 种子重复计数）。

### 4.1 分桶（便于审查，非合入顺序）

| 桶 | 约文件数 | 路径模式 | GA 生产接线？ | Coordinator？ | 运行时备注 |
|----|----------|----------|---------------|---------------|------------|
| **L2 补丁** | 3 | `ConferenceSessionMediaWiring.kt`（`faf8884` 内容）、`Profile01ShadowPlayoutClockSeam.kt`、`Profile01ShadowRuntimeObservability.kt` | 锁 + OBS | 无 | 能力：pipeline 锁 |
| **Session 门面/事实** | ~8 | `ConferenceSessionMediaBridge.kt`, `ConferenceSessionMediaTypes.kt`, `SourceBindingCatalog.kt`, `SessionBindingIngressResolver.kt`, `SessionMediaLiveEdgeAligner.kt`, … | 是 | Bridge 仅门面 | 事实与 catalog |
| **Shadow / cutover** | ~10 | `MeetingProductMediaShadow.kt`, `cutover/*` | Phase A shadow | delegate **未**在 L2 diff | `enabled` 默认 true |
| **Transport + wire** | ~20 | `transport/*`, `wire/*` | **Multicast GA 核心** | 无 | 套接字、SRTP、ingress |
| **Runtime 执行** | ~25 | `runtime/*`（orchestrator、jitter、decode、mix、TopK、…） | **解码/混音/播放接缝** | 无 | 含 `AndroidAudioTrackPlayoutSeam` 等 **能力** |
| **Authority** | 3 | `authority/*` | 准入事实 | 无 | 与 pipeline admit 相关 |

**明确不在「L2 编译切片」优先集（除非探测证明必需）：**

- `com/talkback/app/TalkbackCoordinator.kt`（静态 phase C 误收；**无** conference 文件 import 它）
- `talkback-app/**`、native、build.gradle 变更（`1ab10ae` 全量含，本切片 **未** 展开）
- `session/integration/Profile01HostLocalSessionFactProjection.kt` 等 **Profile01 接线**（非 L2 commit 内）

**可能漏项（测试闭包）：**

- `session/SessionMediaWiringHarness.kt` — `Profile01ShadowRca5ConcurrencyReproTest` 依赖；**仅 JVM 测试**，非生产 APK 行为。

### 4.2 与整包 L3 对比

| 范围 | 生产 `.kt` 约数 |
|------|----------------|
| `1ab10ae` 新增 `core/conference` | **228** |
| 本审查 tiered 闭包（含 L2 三文件） | **~66–71**（conference 为主 + 极少量 `com.talkback.core.session` / `core.network` 若探测牵入） |
| `1ab10ae` 全提交 | **~519** 路径级（inventory） |

---

## 5. 可复现本地探测方案（**未授权执行**）

### 5.1 目标

验证 tiered 清单是否 **充分且必要** 地使下列目标编译通过：

- **主判据：** `compileDebugKotlin`（`android-board-talkback` 模块）
- **次判据（可选）：** `:android-board-talkback:testDebugUnitTest --tests Profile01ShadowRca5ConcurrencyReproTest`

**禁止表述：** 编译通过 = GA 通过 / 听感改善。

### 5.2 环境

- 基线：`origin/substrate/l1-ops` @ `413d4ff`（或 post-L0 `main` + 等价 L1 cherry-picks）
- 独立 worktree（建议名 `talkback-l2-closure-probe`），**不 push**

### 5.3 步骤（仅设计）

```text
1. git worktree add ../talkback-l2-closure-probe origin/substrate/l1-ops
2. 从 1ab10ae 物化闭包清单（逐路径）：
      git checkout 1ab10ae -- <path1> <path2> ...
   注意：三份 L2 文件必须用 faf8884 版本覆盖（与 1ab10ae 上同路径可能已演进）：
      git show faf8884:<path> > <path>   # 或 checkout faf8884 -- 仅这三路径
3. 不修改 build.gradle / 不 cherry-pick 整提交
4. ./gradlew :android-board-talkback:compileDebugKotlin
5. 若 FAIL：记录 e: 行；将缺失符号映射到 1ab10ae 路径；与 closure-tiered.txt  diff → 迭代清单
6. 若 PASS：对清单做「必要性」抽检（从叶子文件尝试移除，再编译）— 仅在下一次授权下进行
```

### 5.4 成功 / 失败判据

| 结果 | 判据 |
|------|------|
| **成功（闭包充分）** | 步骤 4 exit 0；且无「清单外」手工新增文件 |
| **失败（闭包不足）** | 出现 `Unresolved reference` / `Cannot infer` → 归档为新缺失符号表，**不得**用整包 `1ab10ae` 填坑 |
| **失败（闭包过大）** | PASS 但清单含 `TalkbackCoordinator` 或 `talkback-app` → 回退并收紧 BFS 规则 |
| **测试次判据** | Repro 测试 PASS 仅说明并发锁 desk；**≠** 现场听感 |

### 5.5 建议迭代顺序（若授权探测）

1. **Slice-M（最小）：** §2 直接符号对应文件 + `SourceBindingCatalog` + `MeetingProductMediaShadow` + L2 三文件  
2. **Slice-F（完整 tiered）：** `closure-tiered.txt` 全部 conference 路径  
3. **Slice-F + harness：** 加 `SessionMediaWiringHarness.kt` + repro 测试 → 次判据  

---

## 6. 对 #203 / L1 / `d06f17c` 的关系

- **#203：** 无变更；仍取决于 L1 逐行审查。
- **`d06f17c`：** 不在本闭包内；**不能**假定合并后可减少 L2 引入切片。
- **下一步人类闸门：** 审阅 §4 清单与分桶副作用 → **书面授权** 是否执行 §5 物化探测。

---

## 7. 分析产物

| 路径 | 内容 |
|------|------|
| `artifacts/l2-closure/closure-tiered.txt` | tiered BFS 路径列表 + 统计 |
| `artifacts/l2-closure/_closure_bfs_tiered.py` | 可复跑静态脚本（只读 git） |
| `meeting-ga-l2-compile-probe.md` | 已执行：L1+仅 `faf8884` 编译 FAIL |
| `artifacts/pr203-review/*` | L1 审查包（独立） |
