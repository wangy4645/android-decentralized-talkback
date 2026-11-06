# EDGE-LINK-CONTROLLER-001 — 组频道 mesh 边建链的唯一 owner

状态：Phase 0 + Phase 1 已落地（本 PR）；Phase 2–4 待办。
范围：GROUP（pairwise mesh）会话。不触碰 GKE HELLO、Establishment store、factType=11、AQC 音频路径。

## 1. 为什么又要改一次

三轮场测（11-04 / 11-05 / 11-06）各自暴露一族独立的 ICE 卡住：

| 日期 | 形状 | 根因层 |
| --- | --- | --- |
| 11-04 | coordinator 单线程被 MEDIA_KEY RSA 占用，ICE 事实排队 | 线程/调度 |
| 11-05 | STABLE+remoteOffer 下 iceRestart 必 `ABORT_ROLLBACK_ILLEGAL`；glare；ACCEPT 丢失；ufrag 不一致 | 协商状态 |
| 11-06 | SIF/GIDR ACTIVE 10 s 门闩与 ICE 实际进度脱钩，answerer 永不能 reinvite | 控制面门闩 |

每轮都打了针对性补丁（本 PR 里 `OutboundGroupInviteAttemptSupport.shouldIceRestartForPairwiseOffer` /
`staleTimeoutMsForMediaProgress` 与 `tryRecoverStuckOffererPairwiseMeshPeer` 是 11-06 的战术件）。
结构性问题没变：**一条边没有 owner**。边的状态散落在 attempt 表、obligation、`meshCompletedModules`、
reconciler pacing、qosMonitor ICE 快照五处；各自有自己的时钟（10 s / 6 s / 5 s / 20 s）；
没有任何一处有 `FAILED` 出口，所以 UI 永远停在 "sync channel / ICE"。

## 2. 目标状态

每个 `(channelId, peerModuleId)` 一个权威状态机 `EdgeLinkController`
（`core/session/edge/`），纯 Kotlin、可注入时钟、无 I/O：

```
IDLE ─slot─▶ OFFER_SENT ─answer─▶ ICE_CHECKING ─ICE up─▶ CONNECTED ─DISCONNECTED─▶ DEGRADED
  │             │ T_answer×(1+retx)      │ T_ice                      ▲                 │ grace
  │             ▼                        ▼                            │                 ▼
  │          RESTARTING(iceRestart, 仅当 legal, ≤restartMax) ─T_ice──▶ REBUILDING(≤rebuildMax) ─▶ FAILED(backoff)
  └─ANSWERER─▶ AWAITING_OFFER ─T_offer─▶ RequestReoffer(≤reofferRequestMax) ─▶ REBUILDING ─▶ FAILED
```

- 每边**单一 deadline**（`EdgeDeadline`），任意事实到来都重置/取消它，不再有多套时钟竞争。
- **generation 过滤**：带 generation 的事实若小于边当前 generation 一律丢弃；REBUILDING 期间 generation
  前进即视为重建完成。杜绝旧 PC 的迟到 CONNECTED/FAILED 污染新边。
- **升级梯唯一**：重传 → ICE restart（仅 `RestartCapability.legal`，解决 11-05 的非法 rollback 自旋）
  → REBUILD_PC → FAILED+backoff（5 s 起倍增至 40 s；peer HELLO 回来立刻短路）。
- **answerer 有出口**：`RequestReoffer` 信令让 answerer 能让 offerer 重发（解决 `TOPOLOGY_MESH_NOT_OFFERER`
  永久等待）。
- 每次状态变化输出一行 `EDGE_LINK ch= peer= gen= role= FROM->TO cause= ice= deadline= retx= restarts= rebuilds= reoffers=`。
  这是以后 RCA 唯一需要 grep 的行。

## 3. 8–10 人 mesh 约束（已进入设计）

每节点 7–9 条边，全频道最多 45 条边。参数见 `EdgeLinkConfig`：

| 参数 | 值 | 原因 |
| --- | --- | --- |
| `maxConcurrentNegotiations` | 3 | 单节点同时处于 OFFER_SENT/RESTARTING/REBUILDING 的边上限；9 条边冷启动分 3 批，避免 SDP/ICE 风暴和 coordinator 队列堆积（11-04 形状）。ICE_CHECKING 不占槽（本地计算，不产生信令）。 |
| `answerTimeoutMs`/`answerRetransmitMax` | 3 s × 2 | 同 lineage 重传而非新 offer，避免 glare。 |
| `iceCheckingTimeoutMs` | 8 s | LAN 内 ICE 正常 <2 s；8 s 仍 CHECKING 即视为卡死。 |
| `restartMax` | 1/代 | 第二次直接重建 PC，不再重复非法 restart。 |
| `rebuildMax` | 3 | 之后 FAILED，UI 显示"该成员不可达"，**不**拖垮频道其它 8 条边。 |
| `backoffInitialMs`/`backoffMaxMs` | 5 s / 40 s | FAILED 边按指数退避重试，退避中不占槽。 |
| `degradedGraceMs` | 5 s | DISCONNECTED 常自愈，先观察再动。 |
| `offerTimeoutMs`/`reofferRequestMax` | 5 s × 3 | answerer 侧等 offer 的上限与请求次数。 |

调度公平性：空槽按 `peerModuleId` 字典序分配；重建完成的边直接接管自己原先的槽（优先于冷启动）。
单元测试 `nineOffererEdges_respectConcurrencyCap_andDrainInOrder`、`oneFailedEdge_doesNotBlockOtherEdgesOrSlots`
覆盖这两条。

## 4. 分阶段

| Phase | 内容 | 状态 |
| --- | --- | --- |
| 0 | `EdgeLinkController` + 14 个确定性单测（三族场测形状、generation 过滤、并发上限、backoff）；gate `-PedgeLinkDeskOnly` | 完成 |
| 1 | coordinator **shadow** 接线：`OfferSent`（`recordInvitingAfterSuccessfulGroupInviteHandoff`）、`RemoteOfferApplied+AnswerSent`（`applyRemoteOfferWithLineage`）、`AnswerApplied`（`handleGroupAccept`）、`IceState`（`onMeshIceStateChanged` GROUP scope）、`Desired/Undesired` + 1 s `tick`（`edgeLinkShadowTick`）。只打 `EDGE_LINK` 与 `EDGE_LINK_SHADOW_DECISION ... mode=SHADOW`，`runCatching` 包裹。开关 `TalkbackCoordinatorConfig.edgeLinkShadowEnabled` | 完成 |
| 2a | `EdgeLinkMode.ACTIVE_OFFERER`（`TalkbackCoordinatorConfig.edgeLinkMode`，默认开）：offerer 侧三条动作由 `executeEdgeLinkAction` 执行——(c) `SendOffer(retransmitOfLineage)` 重发缓存的同 lineage GROUP_INVITE 信封（`edgeLinkLastInviteByPeer`），answerer 收到同 lineage 重复 offer 且 ICE 未连时重发缓存的 GROUP_ACCEPT（`EDGE_LINK_EXEC action=RESEND_ACCEPT`）；`SendOffer(iceRestart)` → `markTerminal` + `dispatchGroupInviteAfterEngineProvision(rejoin=true, iceRestartForOffer=true)`；(a) `REBUILD_PC` → `markTerminal` + `releaseGroup` + 重新 dispatch（新 generation 由 `withGeneration` 吸收）；(d) `RestartCapability` 每 tick 由 `computeIceRestartGateProbe` 喂入。所有分支都走既有 dispatch 门（WIRE_IN_FLIGHT / provision / RECENT_JOIN_OFFER / ATTEMPT_YOUNG），controller 自身不可能制造 offer 风暴。日志 `EDGE_LINK_EXEC ... action= result=`、`EDGE_LINK_EXEC_ERROR` | 完成（冷启动 `edge-link-coldstart-20261106-123538` 3 轮 20/20 CONNECTED，同 lineage `RETRANSMIT_OFFER:SENT` 已出现） |
| 2b | (b) `SignalType.GROUP_REOFFER_REQUEST`（answerer→offerer，JSON `channelId`+`generation`）：answerer 超时执行 `REQUEST_REOFFER`；offerer 入站喂 `ReofferRequested`（CONNECTED/RESTARTING/REBUILDING/FAILED 忽略，2 s debounce 防 UDP 重复烧预算）；answerer `REBUILD_PC` 只 `releaseGroup` 后等 offer（`RELEASED_WAITING_OFFER`）；`MARK_FAILED` 先打日志，UI 投影留 Phase 3 | 完成，待场测 |
| 3 | 旧门闩改为 `EdgeLinkState` 投影（`EdgeLinkLegacyGateProjection`）：ACTIVE 模式下 OFFER_SENT/ICE_CHECKING/RESTARTING/REBUILDING/FAILED 时 GIDR 不 expire attempt、L0 `tryRecoverStuckCheckingPeer`/`tryRecoverStuckOffererPairwiseMeshPeer`/`offerGroupMeshJoin` 打 `EDGE_LINK_LEGACY_SUPPRESS` 并返回。`GroupRuntimeHealth` 增加 `meshUnreachablePeers`：FAILED 边从 `transmitRequiredPeers` 剔除，频道可 OPERATIONAL，日志 `meshUnreachablePeers=` | 完成，待场测 |
| 4 | CONFERENCE 复用同一 controller（host 为 offerer） | 待办 |

## 5. Phase 1 的场测验收（M01 ↔ M04，再扩到 8 台）

1. 正常建链：每条边应出现 `IDLE->OFFER_SENT->ICE_CHECKING->CONNECTED`，期间无 `EDGE_LINK_SHADOW_DECISION`。
2. 复现 11-06 死锁：shadow 应在 ~3 s 打出 `SEND_OFFER retransmitOf=<lineage>`，~9 s `iceRestart=true`，
   ~17 s `REBUILD_PC`——与 legacy 实际恢复时刻对比，差值就是 Phase 2 能挣回的时间。
3. 8 台冷启动：任一节点同一秒内 `OFFER_SENT` 的边 ≤3；全部 CONNECTED 的时刻 vs legacy。
4. 任何 `EDGE_LINK_SHADOW_ERROR` 行都是 bug，需在 Phase 2 前清零。

## 5a. 11-06 五机场测结果（run1–run5，`field-logs/edge-link-shadow-20261106-*`）

shadow 时间线把 legacy 的四族缺陷逐一暴露出来；每一轮只改一族、重装、复测。

| run | 现象 | RCA | 修复 |
| --- | --- | --- | --- |
| 1 | 每 tick 打 `CONNECTED->CONNECTED` | controller `onIce` 对已 CONNECTED 边重复转移 | controller 仅在非 CONNECTED 时转移；`repeatedConnectedIceFacts_doNotEmitTransitions` |
| 2 | M02↔M03/M04 永久 CHECKING | **A** bootstrap offer 未登记 attempt → GIDR 450 ms 后以 `iceRestart=true` 重发，新 lineage；**B** answerer 的 `ACCEPT_COMPLETE` 门永久丢弃新 lineage offer | A：`issueGroupBootstrapInvite` 成功后 `recordInvitingAfterSuccessfulGroupInviteHandoff`；首 offer 无 ICE 快照不 restart。B：`evaluateAcceptAttempt(acceptPath, iceConnected)`，Reconnect + ICE 未连放行 |
| 3 | M04↔M05 glare，双方都 polite 回滚 | **C** answerer（字典序大）经 `dispatchGroupInviteAfterEngineProvision` 越权发 offer | `NOT_OFFERER` 守卫；`acceptGroupInviteReconnect` 对 offerer 收到的 reconnect offer 打 `GROUP_RECONNECT_OFFER_IGNORED_LOCAL_IS_OFFERER` 并改为本端重邀 |
| 4 | M04→M05 26 s 才连；`GPLB_ACCEPT_FENCED=STALE` 共 16 次 | **D** offer 风暴：同一 PC 上 4 个被 release 合并的 provision 续体在 100 ms 内各铸一个 lineage（GM1..GM4），answerer 对 GM2 的 ACCEPT 被 GM4 判 STALE，直到 rebuild 才恢复 | `offerGroupMeshJoinSignaling` 在 engine 就绪后重新评估（`INVITE_PENDING` / `RECENT_JOIN_OFFER` / `LOCAL_OFFER_OUTSTANDING`）；GIE 续体若 3 s 内已有 join offer 则 `RECENT_JOIN_OFFER` 跳过；`GroupMeshReconciler.hasRecentJoinOffer` |
| 5 | 20 条有向边全部 CONNECTED，5 机 `OPERATIONAL`，`GPLB_ACCEPT_FENCED=0`，`EDGE_LINK_SHADOW_ERROR=0`；冷启动后 ~15 s 全部收敛 | — | — |

冷启动基线（Fix A–D，shadow；`field-logs/edge-link-coldstart-20261106-122723`，4 轮 × 5 机）：
每轮 20/20 边 CONNECTED、全部 `OPERATIONAL`、`GPLB_ACCEPT_FENCED=0`、`SHADOW_ERROR=0`；
单机从首条日志到 4 条边全连 7.4–17.5 s（中位 ~11 s）。D 族守卫 4 轮均未触发。

已知残留：
- M05（RMX3946）的 logcat 流稳定丢 ~7% 行（`COORD_TASK seq` 有洞，`-G 16M` 无效），其 shadow 终态不可信；真实状态以 `TOPOLOGY_SNAPSHOT meshIceConnectedPeers` 为准。
- run4 中 M04→M05 首次 ICE 在 23 s 后 FAILED 才触发 rebuild；Phase 2 controller 接管后应在 8 s（`iceCheckingTimeoutMs`）内处理。
- D 族守卫在 run5 未触发（风暴依赖 release 时序），需在更多冷启动轮次里观察 `GROUP_JOIN_OFFER_SKIPPED_AFTER_PROVISION` 的出现频率。

## 5b. Phase 2b 场测（`field-logs/edge-link-phase2b-20261106-130024`，2 轮 × 5 机）

两轮全部 `OPERATIONAL`、20/20 ICE 连上、`GPLB_ACCEPT_FENCED=0`、`EDGE_LINK_SHADOW_ERROR=0`、`EDGE_LINK_EXEC_ERROR=0`。

| 轮 | 收敛（有 ICE 回调时间戳的节点） | 2b 是否开火 |
| --- | --- | --- |
| 1 | 5.9–9.4 s | 否（健康快路径，answerer 5 s 内已拿到 offer） |
| 2 | 10.1–12.4 s | 是：M04（answerer）`REQUEST_REOFFER SENT` → M02 `INBOUND_REOFFER ACCEPTED` → 同 lineage `RETRANSMIT_OFFER SENT lineage=GM5` |

D 族守卫本轮有触发（`GROUP_JOIN_OFFER_SKIPPED_AFTER_PROVISION`，主要为 `RECENT_JOIN_OFFER` / `LOCAL_OFFER_OUTSTANDING`），与「有风暴时跳过、无风暴时安静」一致。

## 5c. Phase 3 场测（`field-logs/edge-link-phase3-20261106-140014`）

5 机全部 `OPERATIONAL`、20/20 ICE 连上、`meshUnreachablePeers` 为空、`GPLB_ACCEPT_FENCED=0`、
`EDGE_LINK_SHADOW_ERROR=0`、`EDGE_LINK_EXEC_ERROR=0`。`EDGE_LINK_LEGACY_SUPPRESS` 已开火
（M02/M03 较多）：controller 占有协商时 L0/GIDR/MESH_JOIN 不再另铸 lineage。

下一步 Phase 4：CONFERENCE 复用同一 controller。

## 6. 线程模型 ADR

controller 只在 coordinator 线程被调用（所有钩子已在 `runOnCoordinator` 内）；tick 由 scheduler 投递到
coordinator。controller 不持锁、不阻塞。11-04 类"coordinator 被 RSA 占用"不是本 controller 能解的，
需 MEDIA_KEY 计算下沉到 worker 线程（另开 ADR）。
