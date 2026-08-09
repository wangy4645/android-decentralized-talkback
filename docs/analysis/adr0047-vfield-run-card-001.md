# ADR-0047 V-field' Post-Merge Observation Run Card 001

**Status:** **AUTHORIZED** (post-merge passive observation) · **informational only** · **NOT a merge gate**  
**Date:** 2026-08-09  
**Parent ADR:** [0047-ordinary-recovery-post-defer-evaluability-contract.md](../adr/0047-ordinary-recovery-post-defer-evaluability-contract.md)  
**Merged:** PR #148 (`434313c`)  
**Desk gate:** V-desk' D1–D7 — `OrdinaryPostDeferEvaluabilityContractTest` (pre-merge PASS)

```text
Purpose:
  post-merge observational evidence for ordinary recovery
  post-defer evaluability attribution (ADR-0047)

Not purpose:
  Sync recovery validation
  terminal convergence validation
  media reconnect latency proof
  watchdog timeout behavior study
  amend ADR / reopen implementation / block future work
```

---

## 1. Observation objective

Verify only:

```text
ordinary recovery episode
        |
        v
post-defer evaluability attribution exists
```

Do **not** validate:

```text
✗ Sync disappears faster
✗ recovery succeeds faster
✗ media reconnect latency
✗ watchdog timeout behavior
✗ UI ONLINE timing
```

Success criterion (field):

```text
RECOVERY_ATTEMPT_OPENED
        +
intent bound
        +
defer-exit
        +
manifested
        =>
ordinary recovery obligation remained evaluable after defer
```

---

## 2. Observation entry conditions

**Allow (passive / natural):**

```text
ordinary recovery episode
        +
negotiation defer path (if present)
        +
defer-exit
```

**Do not manufacture:**

```text
✗ WiFi flap for evidence farming
✗ Directed #5
✗ FAILED_MEDIA injection
✗ successor admission (ADMIT_SUCCESSOR) episodes
✗ forced Sync UI transitions
```

---

## 3. Topology (field convention)

| Role | Module | Serial |
|------|--------|--------|
| Peer | M01 | `HTUBB21B09220661` |
| Host / observer | M02 | `2d73067a` |
| DUT / peer | M03 | `MDX0220416001963` |

SSID: **`happy`** only

---

## 4. Evidence matrix

| Phase | Expected evidence |
|-------|-------------------|
| **Open** | `ORDINARY_POST_DEFER_EVALUABILITY_INTENT_BOUND` |
| **Defer** | existing negotiation defer evidence (e.g. `deferredReason=NEGOTIATION_SETTLING`, `ICE_RESTART_DEFERRED`) |
| **Defer-exit** | defer-exit category identifiable (`deferExitCategory=` on manifest log, or `NEGOTIATION_BUDGET_EXHAUSTED` / `NEGOTIATION_INTENT_CLOSE_*`) |
| **Manifest** | `ORDINARY_POST_DEFER_EVALUABILITY_MANIFESTED` |
| **Residency** | `ORDINARY_EPISODE_EVALUABILITY_ARMED` / `_PENDING` / `_RETAINED` and/or `RECOVERY_WATCHDOG_STARTED` (existing clock connection — not timeout proof) |

**Episode correlation (K4'):** log lines must bind `obligationGen` + `attempt` for the same edge.

---

## 5. Pass conditions

```text
PASS (informational coverage):
  ≥1 natural ordinary episode with:
    INTENT_BOUND
    + MANIFESTED (≤ defer-exit class)
    + (EVALUABILITY_* OR existing attempt-clock armed post-manifest)
```

**Pass does not require:**

```text
SUCCESS / FAILED_MEDIA
Sync cleared
shorter Sync duration
membership convergence
```

---

## 6. Fail / anomaly record (narrow)

Record only when:

```text
obligationOpen=true
        +
post-defer phase
        +
missing ordinary attribution manifest
        on ordinary recovery episode
```

**Do not equate:**

```text
Sync stuck  =  ADR-0047 field fail
```

Routing reminder:

```text
SYNCING projection     → ADR-0044 (faithful while obligation open)
recovery attribution   → ADR-0047 (this card)
successor convergence  → ADR-0046 (sibling — separate)
```

---

## 7. ADR-0046 separation check (per sample)

Each field sample MUST confirm:

```text
successorTerminalConvergenceContractBound = false
        (no SUCCESSOR_TERMINAL_CONVERGENCE_CONTRACT_BOUND on same episode)
```

If `ADMIT_SUCCESSOR` / successor contract markers dominate → **not** an ADR-0047 V-field' sample; route to ADR-0046 track.

---

## 8. Observation disposition

```text
V-field':
  informational only

Does not:
  block merge (already merged)
  amend ADR-0047 normative text
  reopen Design / Runtime grill
  authorize new runtime workstream
```

Escalate only if:

```text
post-merge natural ordinary episode
  + intent bound at open
  + defer-exit occurred
  + manifest still absent (S4'-class hollow)
```

→ new evidence note; **no casual patch**; protected domains still require new ADR.

---

## 9. 手机操作步骤（现场怎么做）

角色约定：

| 手机 | 角色 |
|------|------|
| M01 | 成员机 |
| M02 | 主持人 / 组会机 |
| M03 | 成员机 |

WiFi 一律连 **`happy`**（不要连 `happy_5G`）。

---

### 9.1 装包确认（开测前做一次）

1. **M01、M03**：确认已装上本轮 debug 包（电脑侧已 `adb install` 过即可）。
2. **M02**：打开「文件管理 / 下载」→ 进入 `Download/talkback/` → 点 `talkback-app-debug.apk` 安装（覆盖安装即可）。
3. 三台都打开 Talkback App，确认能进到主界面。

---

### 9.2 建会（稳定基线）

1. 三台 WiFi 都连上 **`happy`**，信号正常。
2. 在 **M02** 上创建 / 进入会议（按平时建会流程）。
3. **M01、M03** 加入同一场会议。
4. 等三台互相都能正常通话（听得到、界面成员正常），再告诉电脑侧「可以开始抓 log」。
5. 电脑侧执行抓 log 后，**不要立刻折腾网络**，先保持通话约 30 秒以上。

---

### 9.3 观察窗口里你怎么用手机

目标：让会议**自然**跑一段时间；有恢复就记一下，**不要为了测而去人为制造故障**。

**可以做：**

1. 三台保持在会上，正常说话、静音/取消静音、切后台再回来（日常用法即可）。
2. 若某台界面上某位成员出现「同步中 / Sync」或短暂连不上：
   - 用手机记下大概时间（例如 19:40）
   - 记下是「谁看谁」异常（例如：M01 上看 M03 在同步）
   - **继续等**，不要急着退出会议
3. 若过一会儿又恢复正常通话：同样记一下时间，然后可以结束本轮，让电脑侧停抓 log。

**不要做：**

1. 不要进系统设置里开关 WiFi、切换到别的热点、开关飞行模式来「制造恢复」。
2. 不要故意把某台踢出会议再拉回来当刺激。
3. 不要强行杀 App、清数据、换 SSID。
4. 不要为了「Sync 还在」就反复进出会或重装。

（说明：Sync 出现多久**不是**本轮要证明的事；有自然恢复窗口即可。）

---

### 9.4 本轮结束时

1. 若已经出现过至少一次「短暂异常 → 又恢复通话」，或你觉得已经自然观察够久：  
   告诉电脑侧「可以停 log」。
2. 停 log **之前**：三台尽量还留在会上（或至少不要立刻卸包）。
3. 停完之后：可以正常退会；手机不用再看任何日志。

---

### 9.5 电脑侧配合（给你对照，不是手机操作）

开抓 / 停抓由电脑执行（现场只需口头同步即可）：

```powershell
.\scripts\adr0047-vfield-start-run.ps1
.\scripts\adr0047-vfield-stop-run.ps1 -LogDir "<开抓时打印的 LogDir>"
```

---

## 10. Log placement

```text
talkback/logs/adr0047-vfield-YYYYMMDD-HHMMSS/
```

Start / stop:

```powershell
.\scripts\adr0047-vfield-start-run.ps1
.\scripts\adr0047-vfield-stop-run.ps1 -LogDir <LogDir>
```

Append disposition one-liner (e.g. `SAMPLE_DISPOSITION.txt`):

```text
sample_kind: ORDINARY_INTENT_MANIFEST | ORDINARY_HOLLOW_ANOMALY | NOT_0047_ORDINARY_PATH
session / edge / obligationGen / attempt
markers found (INTENT_BOUND / MANIFESTED / EVALUABILITY_*)
0046 orthogonality: successor contract absent? (Y/N)
```

---

## 11. Post-merge sample log

| LogDir | Disposition |
|--------|-------------|
| `talkback/logs/adr0047-vfield-20260809-193925/` | **NO_ORDINARY_RECOVERY_EPISODE** — 现场一切正常；健康基线，**不是** ADR-0047 PASS/FAIL |
| `talkback/logs/adr0047-vfield-20260809-193727/` | pre-baseline restart artifact（建会前开抓后重启，不作样本） |

`SAMPLE_DISPOSITION.txt` 已写在 `193925` 目录下。

V-field' 仍 **OPEN（opportunistic）**：日常开会若出现自然恢复再抓；**不**为出样本人为 flap。

---

## 12. Prior evidence (pre-merge Case B)

| LogDir | Role |
|--------|------|
| `talkback/logs/adr0046-vfield-20260809-112330/` | Pre-fix Case B — **NOT** post-merge compliance sample (`NOT_ADR0046_VFIELD_SAMPLE`) |

Archive: [vfield-case-b-20260809-112330-media-online-obligation-pending.md](./vfield-case-b-20260809-112330-media-online-obligation-pending.md)

Post-merge builds **≥ PR #148**；attribution 覆盖仍待 ≥1 条 natural ordinary+defer 样本。

---

## 13. Governance reminder

```text
ADR-0047 ACCEPTED
        |
        +-- Design C1' / DP-ACCEPT ✅
        |
        +-- Runtime Auth F1 ✅
        |
        +-- PR #148 MERGED (434313c) ✅
        |
        +-- V-desk' ✅ (pre-merge)
        |
        +-- V-field' ⏳ opportunistic (healthy baseline 193925 logged)
        |
        +-- no new ADR / Design grill unless boundary violation
        |
        +-- other tracks may proceed; observation does not block
```
