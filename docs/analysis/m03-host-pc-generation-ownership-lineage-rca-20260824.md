# M03 Host PC Generation / Ownership / Offer-Answer Lineage RCA

**ID:** m03-host-pc-generation-ownership-lineage-rca-20260824  
**Date:** 2026-08-25  
**Type:** RCA / Evidence  
**Implementation:** **NOT AUTHORIZED**  
**Out of scope:** force-release · PC reset · Factory rebuild · D-β/γ · LEASE_BUSY 新机制 · WiFi recovery 四域

**Session:** `e55028df-a68f-4bec-81a9-42a3b73be8e1` · CH-01  
**Host:** M01 `HTUBB21B09220661`  
**DUT:** M03 `MDX0220416001963`  
**Contrast:** M04 `DSJ-2407011` (SRD SUCCESS) · M02 `2d73067a` (LEASE_BUSY / DOMAIN_BLOCKED, 派生)  
**Logs:** `talkback/logs/group-conference-transition-field-20260824-174918/`

**Prior closed track:** PR #199 LEASE_BUSY 分类线。本 RCA 不重开分类。

---

## Status

```text
CLOSED (this RCA):
  Q1 Java generation ownership for M03 on host
  Q2 offer vs SRD generation (not pcHash)
  Q3 M03 vs M04 / M02 churn contrast (what is shared vs unique)

INCONCLUSIVE:
  Hypothesis A (stale/corrupt native PC) vs B (lifecycle race on a healthy PC)

NOT AUTHORIZED:
  behavior change
  PC reset / factory reset / force-release
```

---

## Verdict

Host 上 M03 的 gen=71/72/73 是 **同一次 `scheduleDeferredProvision` 把 pending 队列逐条 `provisionNew`** 产生的 Java `Entry` 代数，不是三次独立的 factory 生命周期。

`createOffer` 与 `setRemoteDescription(answer)` 的 **`pcGeneration` 同为 73**。`createOffer` **没有打 `pcHash`**，不能 hash 证明同一 native 对象。

**双 createOffer / 双 GROUP_INVITE 不是 M03 hang 的充分条件**：M02、M04 同样双 invite；M04 SRD 49ms SUCCESS。

M03 独有的现场失败仍是：host `SRD_NATIVE_CALL_ENTER` 无 `EXIT`（Case A JNI 不返回）。本 RCA **不能**据此授权 PC recovery。

---

## Q1 — 谁创建 / 谁拥有 / 何时释放 / 为何三代

### Timeline (M01, M03)

```text
17:49:38.389  mediaSessionClose  RELEASE_ENTER / RELEASE_COMPLETE
              EDGE occupancyMs=0
              本 capture 无 PC_CLOSE_ENTER module=M03
17:49:38.510  MEDIA_RELEASE_DEFERRED_CREATE CONFERENCE  (1)
17:49:38.516  MEDIA_RELEASE_DEFERRED_CREATE CONFERENCE  (2)
17:49:38.521  MEDIA_RELEASED origin=mediaSessionProvision
              → scheduleDeferredProvision 一次 drain
17:49:38.571  MEDIA_PROVISIONED GROUP      origin=releaseInFlight  gen=71
17:49:38.620  MEDIA_PROVISIONED CONFERENCE origin=requestEngine    gen=72
17:49:38.621  MEDIA_PROVISIONED CONFERENCE origin=releaseInFlight  gen=73
              此后至 SRD 无 MEDIA_PROVISIONED module=M03
```

### 机制（现有代码）

`MediaSessionManager.scheduleDeferredProvision`：`pendingEngineRequests.remove` 后 **`forEach` 每条都 `deliverProvisionedEngine` → `provisionNew`**。

`provisionNew`：`generationByModule++`，`factory.getOrCreate(moduleId)`，覆盖 `entries[moduleId]`。

`ModuleMediaEngineFactory.getOrCreate`：`engines.getOrPut`。drain 循环内 **没有** `factory.release`。因此 gen=71/72/73 是 **三个 Java Entry / 同一 factory slot**。当前 PC = 最后一次 provision 的 CONFERENCE gen=73。

gen=71 GROUP 的 `onReady` 仍会被调用，然后立即被 72/73 覆盖。GROUP 引擎所有权在 Java 层存活约 50ms。

### GROUP 为何进 pending

drain 顺序 CONFIRMED：`GROUP(releaseInFlight)` → `CONFERENCE(requestEngine)` → `CONFERENCE(releaseInFlight)`。

代码：`releaseInFlight` 时 `requestEngine` 会 `queuePendingEngineRequest(..., "releaseInFlight")`。

本 capture 在 GCT 窗口 **没有** `MEDIA_RELEASE_DEFERRED_CREATE module=M03 requestedScope=GROUP`（仅两条 CONFERENCE）。enqueue 现场行缺失，记为 **观测缺口**；drain 结果仍成立。

`mediaSessionClose` **不** `scheduleDeferredProvision`。hangup 未排空的 pending 会叠到后续 `mediaSessionProvision` drain。

### M04 对照

M04 同样：GROUP gen=7 → CONFERENCE 8 → CONFERENCE 9，然后双 createOffer。**三代 + GROUP 插入不是 hang 判别器。**

### M02 对照

M02：CONFERENCE 577 → 578，无 GROUP 插入。仍双 invite。失败是 M03 占 lease 后的 DOMAIN_BLOCKED，不是本问。

### PC_CLOSE

本 capture `PC_CLOSE_ENTER` **仅 M02**（31 次 mesh flap）。M03/M04 **零次**，含 GCT hangup。不能证明 native `peerConnection.close()` 已执行；也不能用“缺 close”解释 hang（M04 同样缺 close 且 SRD 成功）。

本 log 内 `MEDIA_PROVISIONED module=M03` 仅这 3 次；gen 从 70 起跳，代数来自进程更早的 GROUP 生命，不在本文件。

---

## Q2 — createOffer 与 SRD 是否同一 PC

### Host M03

```text
38.621  provision gen=73  CONFERENCE  当前 Entry
38.769  createOffer #1  STABLE / localDesc=NONE → SLD HAVE_LOCAL_OFFER
38.779  GROUP_INVITE 2128B  nonce=9b8cd303…  → M03
38.790  createOffer #2  已 HAVE_LOCAL_OFFER / localDesc=OFFER → 再 SLD
38.799  GROUP_INVITE 2439B  nonce=ce2a3727…  → M03
ICE candidates  pcGeneration=73
45.909  CONFERENCE_PC_IDENTITY  pcGeneration=73
45.919  NATIVE_DOMAIN_EXECUTION_ENTER  pcHash=84622929  pcGeneration=73
45.931  SRD_NATIVE_CALL_ENTER ANSWER  HAVE_LOCAL_OFFER localDesc=OFFER
        pcHash=84622929 gen=73
        无 EXIT → SRD_TIMEOUT
```

**generation：同一 73。** offer 时刻无 pcHash。38.621–45.931 无再 provision → Java 当前引擎未换代。

### Peer M03

```text
38.740  收到 invite 2128B
38.752  收到 invite 2439B
38.776  handleGroupInvite → CHANNEL_MODE CONFERENCE（第一封已进 invite 路径）
45.292  SESSION_CREATED / 用户 ACCEPT
45.499  applyRemoteOffer 一次  STABLE → 约 150ms settled
        peer pcGeneration=1
ICE CONNECTED @45.883（早于 host SRD）
```

`offerLineageId=NONE`。不能证明 answer 对应 2128 还是 2439。host 本地 description 是 **第二次 SLD**。若 peer 用第一份 offer 答，存在 SDP mismatch 可能；WebRTC 通常快速失败而非 JNI hang。**未证实。**

双 invite 来源：拓扑 realization 打了两次 `CONFERENCE_MEDIA_REALIZATION_OFFER`（M02/M03/M04 均有），两次 `onReady` 各 `createOffer`。

---

## Q3 — 为何 M03 churn、M04 没有（纠正）

| 项 | M03 | M04 | M02 |
|----|-----|-----|-----|
| GCT Java 三代 | 71 GROUP + 72/73 CONF | 7 GROUP + 8/9 CONF | 仅 577/578 CONF |
| 双 invite | 是 | 是 | 是 |
| createOffer 时 gen 已是末代 | 73 | 9 | 578 |
| SRD | ENTER 无 EXIT | EXIT 49ms SUCCESS `pcHash=134741790` gen=9 | 未进 native（LEASE_BUSY holder=M03） |
| 本 capture PC_CLOSE | 无 | 无 | 高频 |

**M04 不是“没有 churn”。** M03 与 M04 的 GCT provision 形状同类。差异是 **native SRD 是否返回**，以及 M03 host 槽位历史代数更高（进程级 GROUP 计数），后者 **未**被证明为 hang 因果。

---

## A vs B

```text
A  stale/corrupt native PC
   支持：历史 gen 高；本 capture 无 M03 PC_CLOSE；JNI 不返回
   反对：M04 同样无 PC_CLOSE + 同样三代 + 同样双 SLD，SRD 成功
   结论：未证实

B  lifecycle race（pending 全量 provision + 双 offer）
   支持：drain 全量 forEach 已证实；双 realization 已证实
   反对：同样 race 下 M04 成功；peer ICE 已 CONNECTED
   结论：race 存在，但是否导致 hang 未证实
```

**裁决：INCONCLUSIVE。** 不得在本证据上选 PC recovery。若做下一刀，只谈 **lifecycle 观测/去重授权**（pending 只兑现最后 CONFERENCE；realization 单次 offer），仍须单独 IA。禁止顺手授权 force-release / PC reset / Factory。

---

## Frozen / 禁止

```text
LEASE_NONE 不因控制面 terminal 断言
force-release UNAUTHORIZED
不改 completion predicate / RNA-5/6 / membership / timeout budget
不写 Recovery broken / Watchdog bug confirmed / fix recovery timeout / MediaState sticky
```

---

## 下一刀

RCA **CLOSED**。观测见 [gct-m03-lifecycle-observation-ia-001.md](./gct-m03-lifecycle-observation-ia-001.md)。Clean-start 裁决已冻结 primary defect 重定义，见 [gct-ia001-clean-start-adjudication-20260825.md](./gct-ia001-clean-start-adjudication-20260825.md)（**Shared Native SRD Fault**；recovery IA **未**授权）。
