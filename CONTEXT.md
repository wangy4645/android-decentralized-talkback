# Talkback

安卓无中心对讲应用。射频 mesh 提供扁平 IP 子网；应用在已入网前提下做发现、信令与 WebRTC 音频传输。

## Language

### 身份与拓扑

**Module**:
运行 Talkback 的 Android 通信板。mesh 上 WebRTC 链路的端点；拥有本端 Runtime（Activity 栈、媒体、ICE、采集）。同一 Module 对外只有一条 module 级 RTP 上行。
_Avoid_: 设备, 节点, 板卡

**Endpoint**:
挂载在 Module 上的对讲终端（手咪、耳机等），用户按 PTT 的实体；拥有身份域（Floor Owner、信令发起者）。由 `moduleId` + `endpointId` 全局标识。
_Avoid_: 终端, 用户, 单兵

**EndpointKey**:
Endpoint 的全局键，格式 `{moduleId}-{endpointId}`（如 `M01-E03`）。Contact 域对外能力（Unicast、Endpoint Text、Monitor 等）的业务寻址唯一键；moduleId 仅用于 mesh 路由，由 EndpointKey 派生，不构成并列业务地址。
_Avoid_: 地址, peerId, remoteKey（实现名）

### 组网与信任边界

**RF Task Key**:
射频链路层 mesh 入网圈定；不同密钥无法进入同一 mesh。换钥等于换网。由射频层/运维带外管理；Talkback 应用不自动擦除。
_Avoid_: sharedSecret, 任务密钥（未区分层时）

**Shared Secret**:
同一 RF mesh 内的应用层密码学边界：信令 HMAC 验签 + gossip 发现过滤。持有同一 secret 的端才可发现、验签通过并通话。不是 RF 入网门禁，但是 mesh 内的「应用子网」成员资格。
_Avoid_: RF Key, channelId, 加密 Channel

**Channel Id**:
应用层逻辑编组标签（Group/Conference 归属）。无密码学意义；持有同一 Shared Secret 的端可加入任意 channelId。不是隔离边界。
_Avoid_: 子网, 密钥, 保密组

**Callable Roster**:
Module 级「可呼叫门禁」：`module ∈ CallableRoster` ⟺ 存在该 module 的验签通过来源 + 可用 transport（gossip 验签、或验签通过的 HELLO 等）。未验签的 NSD/static 仅为 transport 候选，不算 callable。目录数据不反向提门。
_Avoid_: 发现列表, PeerDirectory, 在线列表

**Endpoint Directory**:
Module 级 endpoint 目录（有哪些 endpoint、优先级等），仅由验签通过的 HELLO 填充；键为 `moduleId`，与 Callable Roster 正交。`StateSyncManager` 已存 `endpoints` 列表，须展全部 endpoint，非只取 primary。
_Avoid_: Callable Roster, HELLO（作动词时）, 通讯录

**Contacts Projection**:
UI 展示层：对 Callable Roster 做「先门后形」投影——仅对 callable module 渲染其 Endpoint Directory 中的**全部** endpoints（V2 不按 endpoint 可达性过滤）；callable 无目录则不展示；有目录非 callable 不展示。module 级在线态单独指示；endpoint 级灰显留 V2.1。
_Avoid_: peerDisplayRoster（实现名）, 合并列表

### 频道与会话

**Channel**:
任务内的逻辑对讲组，Group PTT 与 Conference 的编组容器；由 channelId 标识（无密码学边界）。不拥有单呼，也不是所有通话的父对象。持有 Channel Membership（静态意图），运行态演化不得回写。
_Avoid_: 会话, 通话, 保密组

**Channel Membership**:
Channel 的静态编组意图：「哪些人/模块应属于这个组」。配置型、长生命周期；可含离线方；不参与 Session 运行态演化，永不被 Session 回写。
_Avoid_: roster, 在线列表, Session 成员

**Session**:
一次具体的单呼、组呼或会议实例，拥有生命周期、Disposition、Membership 与媒体拓扑等业务状态；由 `sessionId` 标识。与 Channel 无所有权关系；可携带可选的来源上下文。
_Avoid_: 频道, 通道

**Session Origin**:
Session 的可选元数据，记录「从哪进入」——如 Channel、Contacts、History、Emergency。供 UI 导航与历史统计；不参与信令语义，不参与运行时准入；被叫侧默认不可见。
_Avoid_: channelId（单独使用时）, 归属, 所有权

### 通信形态

**Unicast**:
独立的端对端 Session：全双工媒体，无成员（Membership）与 Floor 语义。可携带 Session Origin，但不参与 ChannelMode。
_Avoid_: 单呼, 点对点通话, 直呼

**Endpoint Text**:
Endpoint 间经验签信令投递的短文本单元；不属于 Session，不参与 Disposition、Membership 或媒体拓扑，不占用 Foreground Activity 的 Unicast/Group/Conference 槽位。V1 寻址为单个 EndpointKey（Endpoint Target）。不绑定 TalkbackSession；以 Message Id 标识单条投递，与 sessionId 正交。
_Avoid_: SMS, 短信, Message（未指明类型时）

**Message Id**:
单条 Endpoint Text 的全局标识，用于去重与 replay 防护；不表示通话结构，不可替代 sessionId。
_Avoid_: sessionId（用于 Text 时）, messageId（实现字段名）

**Endpoint Text Delivery**:
Endpoint Text 的 V1 投递约束：目标须 online 且所属 module callable 方可发送；经 UDP 信令 best-effort 投递，无 ACK、无离线队列、无 store-and-forward。收到即展示、不持久化、无 inbox、无 replay；可选 `deliverHint` 观测，但不引入 retry 语义。
_Avoid_: 可靠投递, 已读回执（V1）, inbox, 历史记录（V1）

**Transient Control Event**:
Endpoint Text 的时间性语义：仅存在于发生时刻的控制事件；不形成系统记忆，不进入 notification center 或历史页。V1 刻意 amnesic，防止滑向 IM 存储模型。
_Avoid_: 会话线程, 消息存储, 未读计数（V1）

**Endpoint Text Payload**:
Endpoint Text 的 V1 内容约束：纯 UTF-8 文本，上限 256 字符；短指令风格，不含结构化附件或二进制 payload。图片不属于 Endpoint Text V1 范畴。
_Avoid_: 长文, 富文本, 文件/坐标 payload（V1）

**Endpoint Text Rate Limit**:
Endpoint Text 的 V1 发送防护：按 `(sender, endpointKey, signalType)` 维度限流（如约 1 条/秒），超限静默丢弃，不排队、不 retry；仅作用于 ENDPOINT_TEXT，不影响 Floor、PTT、Session 信令。
_Avoid_: 全局信令限流, 排队 backlog, priority 队列（V1）

**Endpoint Attachment**:
Endpoint 间二进制 payload（图片、文件等）的 data-plane 能力；与 Endpoint Text（control-plane）正交。V2 范畴：分片传输、可选 ACK/retry、独立传输信道；不得塞入 UDP 信令 envelope 或 Endpoint Text payload。
_Avoid_: base64 图片信令, Text 通道传文件, IM 存储模型（V1）

**Control-Plane Text**:
Endpoint Text 在 Runtime 中的定位：control-plane 带外信令，不占 Floor、不占 Session slot、不参与 Foreground Activity / Media Admission；发送与接收均 bypass Session FSM 与 Floor FSM。本端 reachable 于 control-plane 即可发；不依赖 RTP/WebRTC 或既有 Session 连通性。接收与渲染独立于 Session、Floor、Busy 状态；delivery 不等于 interrupt。可选 Session Hint 仅用于日志关联，不参与逻辑。
_Avoid_: 会话内消息, 媒体信道短信, Floor 全局锁, TEXT_REJECT

**Endpoint Text Priority**:
Endpoint Text 的展示优先级分级，决定 UI 呈现强度但不打断媒体与交互流。INLINE 为轻提示；IMPORTANT 为置顶 banner；CRITICAL 为强提示（如 badge），均不抢 Floor、不中断 PTT、不打断 WebRTC Session。V1 协议可携带 priority 字段，但 UI 固定 INLINE，priority 不参与任何行为决策。
_Avoid_: 拒收（busy 时）, 排队延迟展示, 与 Floor 抢占, priority 驱动 V1 UI

**Message Target**:
消息类能力的寻址意图。V1 仅 Endpoint Target（单个 EndpointKey）；Module Target、Group Target 等为扩展类型，不替代默认一对一 Endpoint Text。
_Avoid_: moduleId 作为默认收件人, 广播（未指明目标类型时）

**Group PTT**:
半双工组呼 Session：Membership + Floor + Media。Floor 登记与裁决粒度为 Endpoint；同一 Module 上无论哪个 Endpoint 持麦，物理上行仍只有一条 module 级 RTP。
_Avoid_: 组呼, 组播, mesh 通话

**Conference**:
全双工会议 Session：Membership + Mesh + Media。发言粒度为 Module；无 Floor 抢权，各 Module 可同时收发。
_Avoid_: 会议模式, Meeting, 全双工组呼

**Conference Host Ownership**:
Conference Session 由发起方 Host 拥有其生命周期；仅 Host 显式结束会议或离开会议可终止 Session。参与者 join/leave 不改变 Session 是否存在。
_Avoid_: roster 决定会议结束, 最后一人离开即结束

**Conference Session Exists**:
Host 尚未结束或离开会议时，Conference Session 在运行态中持续存在；与当前是否有 connected remote 无关。Solo Host 等待他人加入时 Exists 仍为 true。
_Avoid_: isLiveConferenceSession, 会议还活着

**Conference Session Operational**:
Conference Session 是否已有至少一条可用的远端媒体链路（transmit-ready）。Solo Host 常见 Exists=true、Operational=false；这是等待态，不是 Session 终止。
_Avoid_: 会议已结束, channel ready

**Conference Lifecycle Established**:
Conference Session 已跨过建立边界后的持久生命周期事实；除明确的 Conference lifecycle authority 终止决策外不可降级。媒体断连、远端媒体数为 0、成员离开、恢复流程均不得撤销 Established。
_Avoid_: 用 ICE/remoteMediaCount 推导未建立, 把恢复中当重新建会

**Conference Connectivity Recovery**:
已存在 Conference Session 内的媒体连接恢复过程；可影响 UI 投影为 Reconnecting，但不属于 Conference Lifecycle。会议可在一个或多个媒体路径 recovering/failed 时保持 Established。
_Avoid_: Conference Lifecycle RECOVERING, 用恢复流程驱动会议重新开始

**Conference Connectivity Edge Recovery**:
Conference Connectivity Recovery 的最小恢复粒度：`(conferenceSessionId, remoteModuleId)` 标识的一条本端到远端 Module 的媒体连接边。某条 edge recovering/failed 不改变 Conference Lifecycle Established；UI 与指标可将多条 edge 聚合为参与者级 Reconnecting 或会议级 Degraded 投影。
_Avoid_: 一个 peer 断网导致整场 Conference recovering, 用 channelId 或 sessionId 作为唯一恢复状态键

**Recovery Edge**:
一条 Conference connectivity edge 在 recovery 域中的身份与义务单元，键为 `(sessionId, remoteModuleId)`。可经历多轮 **Recovery Attempt** 与多个 **Obligation Episode**（`obligationGeneration`）；其 completion 义务与单次 attempt 的 terminal 正交。见 ADR-0022 R28 / R28-J。
_Avoid_: 把 attempt_timeout 当作 edge 结束, RecoveryAttempt（未区分 edge 时）

**Edge Lifecycle**:
本端 `(sessionId, remoteModuleId)` edge recovery record 的连续存活周期（v1 隐式身份：record 存在即 lifecycle 活跃）。开始于 record 创建；仅因 membership 明确移除、conference 终止、或本端 session teardown 而结束（`cancelEdge` / remove）。**`OBLIGATION_CLOSED(*)` 不终止 Edge Lifecycle。** 见 ADR-0022 R28-J。
_Avoid_: 把 `RECOVERED` 或 `OBLIGATION_CLOSED` 当作删 record, 把 ICE 断连当作离会

**Obligation Episode**:
一个 Edge Lifecycle 内的一次 recovery responsibility 周期；由 `obligationGeneration` 标识。每 episode 独立走 R28-H OPEN → attempts → CLOSED。`CLOSED(RECOVERED)` 或 `CLOSED(OBLIGATION_DEADLINE)` 结束当前 episode，不结束 Edge Lifecycle。见 ADR-0022 R28-J。
_Avoid_: 把 episode 与 attempt 混用, 把 CLOSED 当作 edge 销毁

**obligationGeneration**:
Obligation Episode 在**当前 Edge Lifecycle 内**的单调递增序号；新 episode 为 `gen+1`；新 lifecycle（record 重建）从初始值重新开始。不是 edge identity、不是 membership epoch、不是 endpoint generation。见 ADR-0022 R28-J。
_Avoid_: 把 generation 当作 edge 代数, 在 `FAILED_MEDIA_RECOVERY` / SUPERSEDE 时 bump gen

**Recovery Attempt**:
Recovery Edge 上的一轮有界 recovery 执行，由 `attemptId` 标识并隶属于该 edge。Attempt terminal 含 `RECOVERED`、`CANCELLED`、`ATTEMPT_TIMEOUT`、`SUPERSEDED`；**`ATTEMPT_TIMEOUT` 终止 attempt，不终止 edge 义务**。
_Avoid_: 把 timeout 当作 membership prune, 无 attempt 代数的 retry

**Recovery Completion Obligation**:
当前 **Obligation Episode** 在 OPEN 期间必须有人负责 **re-evaluate completion** 的契约义务。Episode terminal：`RECOVERED`、`OBLIGATION_DEADLINE` 等 R28-H close set。Edge Lifecycle terminal（record remove）：Membership `LEFT`、Conference `TERMINATED`、本端 teardown。与 R26 membership 窗口兼容；不等同于「再发一次 reattach」的实现动作。见 ADR-0022 R28-H / R28-J。
_Avoid_: timeout 即系统撒手, participant 发过一次即完成, 把 episode CLOSED 当作 edge 销毁

**Recovery Completion Owner**:
Recovery Edge 上 completion 义务的**逻辑唯一 owner**；由本端 **Conference Edge Recovery Controller** 维护，**不属于**某个 Module。全网 exactly-one 指 edge 域内单一义务，非「M01 或 M02 谁当 owner」。
_Avoid_: initiatesReattach 侧即 lifetime owner, participant/host 二元 owner

**Preferred Recovery Initiator**:
Recovery Edge 上 **优先** 由哪一侧发起 reattach 的 role 提示；由 `initiatesReattach` 表达（participant→host 边通常为 participant）。决定 preferred action，**不**决定 completion ownership，**不**在 primary 离线时终止义务。
_Avoid_: initiatesReattach == owner, fallback evaluator（module 级）

**Recovery Action Authority**:
Module 在 Recovery Edge 上**允许执行**的动作集合，由 role 与 reachability 约束。Controller re-evaluate 时 **MAY** 仅 invoke 当前可达侧具备 authority 的动作。v1 最小集：preferred initiator 可 dispatch reattach；authority 可 accept/reject 与 bounded media recovery；双方均不可 mutate membership。见 ADR-0022 R28-C。
_Avoid_: 把 capability 当作 ownership, host 发 participant reattach, 写死为 ICE restart 唯一媒体动作

**Recovery Completion Decision**:
Recovery Edge Controller 在 re-evaluate 时必须产出的**显式决策**，四选一：role-allowed completion action、`WAITING(reason)`、`SUPERSEDED(nextAttemptId)`、`CANCELLED(reason)`。允许等待，**禁止无决策等待**（soak vacuum：SENT 后对端无任何 decision）。
_Avoid_: passive wait, 每次 re-evaluate 必须发包

**Media Edge Restored**:
本端到远端 Module 的 transport / ICE 连接重新可用；属于 **Connectivity 事实**。不等于 obligation episode 完成。ICE CONNECTED 是 Media Edge Restored 的常见表现，但 recovery controller **不得**将其直接当作 episode terminal。见 ADR-0022 R28-E / R28-J。
_Avoid_: ICE CONNECTED == RECOVERED, 把 media 恢复当 control-plane 完成

**Recovery Edge Completed**:
Recovery Controller **显式宣布** 当前 **Obligation Episode** terminal（如 episode `CLOSED(RECOVERED)`）。Edge Lifecycle **可能继续**（record 仍在）。Membership / conference teardown 才结束 Edge Lifecycle。见 ADR-0022 R28-E / R28-J。
_Avoid_: 用 ICE 连通推断 recovery 完成, 把 episode RECOVERED 当作删 edge

**Attempt Terminal**:
当前 **Recovery Attempt** 的结束；含 `RECOVERED`、`FAILED_MEDIA_RECOVERY`、`CANCELLED`、`SUPERSEDED`（含 `ATTEMPT_TIMEOUT` 路径）。**不**终止 **Edge Obligation**。见 ADR-0022 R28-F。
_Avoid_: attempt_timeout == edge 结束

**Edge Obligation**:
一个 **Obligation Episode** 内 completion owner 持续 **re-evaluate** 的义务（R28-H 作用域 = 单 episode）。单次 Attempt Terminal **不**自动解除该 episode 的 obligation。Episode terminal：`RECOVERED`、`OBLIGATION_DEADLINE` 等 R28-H close set。Edge Lifecycle terminal（record remove）：Membership `LEFT(remoteModuleId)`、Conference `TERMINATED`、本端 teardown — 与 **Recovery Completion Obligation** 在 episode 级同族，见 ADR-0022 R28-H / R28-J。
_Avoid_: FAILED_MEDIA_RECOVERY 后撒手, 删 edge record 即无义务, 把 episode CLOSED 当作 edge 结束

**Superseded Attempt**:
Material capability 变化后，controller **显式废弃**当前 attempt 并 **MAY** 开启新 attempt（新 watchdog budget）。非每次 material transition 都必须 SUPERSEDE。见 ADR-0022 R28-F。
_Avoid_: reachability 变化 == 必须新 attempt, 隐式 retry

**Recovery Capability Signature**:
`EdgeReachabilitySnapshot` 在 recovery 域的投影：当前 **permitted recovery actions** 集合 + `waitingReason`。Material transition ⇔ signature 变化（非任意网络事件）。由 **Coordinator** 检测；Controller 消费。见 ADR-0022 R28-G。
_Avoid_: 裸 bool 向量 diff, HELLO 即 re-evaluate, authorityReachable 直接等于可 COMPLETE

**Recovery Control-plane Started**:
当前 attempt 已越过 control-plane 边界（如 reattach 已请求/已接受、ICE restart 进行中）。此状态下 ICE 恢复 **MAY** 在 completion evaluation 中直接 yield `RECOVERED`；与 `RECOVERY_PENDING` 且未发 reattach 的路径不同。实现字段 `controlPlaneStarted`；见 ADR-0022 R28-E。
_Avoid_: 用 phase 枚举代替 control-plane 边界

**Edge Reachability Snapshot**:
Recovery Edge 上、本端对远端 Module 的**只读聚合事实**，由 Conference Edge Recovery Controller 从各域 fact 组装，**不拥有、不回写**下层。四维正交：`linkReady`（Connectivity）、`peerDiscovered`（Discovery）、`routeConverged`（Signaling/Mesh）、`authorityReachable`（Conference Runtime）。Recovery 决策 **MUST NOT** 依赖 `peerReachable` / `transportReady` 等单 bool 塌缩。见 ADR-0022 R28-D。
_Avoid_: 线性 reachability 枚举当唯一 gate, transportReady 代替 routeConverged

**Recovery Signal Dispatch Gate**:
发起 recovery 信令（如 reattach）的前置条件：`linkReady ∧ peerDiscovered ∧ routeConverged`。Soak #73-B 卡在 `routeConverged=false` 而非 authority 层。
_Avoid_: channelReadiness==READY 当作可发 reattach

**Recovery Completion Gate**:
Recovery 协议完成向的前置条件：Recovery Signal Dispatch Gate **且** `authorityReachable`。
_Avoid_: authorityReachable 隐含 routeConverged

**Conference Presence Projection**:
会议在场读模型：本端可见的 **joined / connected / recovering** 人数与 peer 集合，由 `ConferencePresenceProjector` 从 Membership roster、`EdgeRecoveryFacts`、`ConnectedPeers` 等只读事实产出。UI **MUST** 只消费此投影（及 Runtime 投影的 phase），**MUST NOT** 读 `ReachabilitySnapshot` 或在 ViewModel 内重建 presence。见 ADR-0022 R27′。
_Avoid_: memberKeys.size 当 joinedCount, ICE/transport 推断 recovering, 扩 ConferenceRuntimeProjector 塞 presence 字段

**Conference Presence Projector**:
与 `ConferenceRuntimeProjector` 并列的专用投影器：消费同一批只读 facts，输出 `ConferencePresenceProjection(joinedCount, connectedCount, recoveringPeers: Set<ModuleId>)`。职责为 **presence**（谁在场、谁连通、谁 recovering），非 lifecycle phase 或 authority。见 ADR-0022 R27′-B。
_Avoid_: 把 joinedCount 挂进 RuntimeProjection DTO, ViewModel filter roster

**Conference Edge Recovery Controller**:
每条 Conference connectivity edge 的 recovery 决策与状态机唯一 owner。负责 eligibility、RECOVERY_REATTACH 编排、bounded ICE restart 策略、termination 取消与 EdgeRecoveryFacts 产出；不直接操作 PeerConnection，不修改 Membership。见 ADR-0021。
_Avoid_: InviteHandler 顺手恢复, ICE 回调直接 recreate PC, Conference 级 recovery 状态机

**Edge Recovery Fact**:
`ConferenceEdgeRecoveryController` 产出的只读 recovery 事实（edge 状态、attempt、reject reason 等）。`ConferenceRuntimeProjector` 与 `ConferencePresenceProjector` 的 recovery 输入；不得从 ICE/connectedCount 反推。
_Avoid_: UI 根据 transport 猜 Reconnecting, Projection 读 ICE 回调

**Observation Fact**:
本机 edge observer 对 subject peer 的可证明运行时事实，键为 `(sessionId, observer=self, subject=peer, factType)`。v1 runtime **仅**允许 `observer=self` 的 Normative facts 进入本机决策链（recovery / presence）。禁止将远端节点的观察提升为本机 observation。见 ADR-0031 R31-O。
_Avoid_: 信令转述当 observation, host 汇总当全局 truth, 跨 observer promotion

**Normative Observation Fact**:
六种可进入本机决策链的 observation：`membershipLocal(P)`、`iceState(P)`、`recovering(P)`、`mediaUnavailable(P)`、`receivePathLive(P)`、`mediaEverLive(P)`。分别由 membership projection、ICE/PC、recovery controller、media receive path 产出。见 ADR-0031 R31-F、ADR-0030 R30-P-1。
_Avoid_: 把 obligationGeneration / phase 当 presence 输入

**Diagnostic Fact**:
可记入 log 与离线 Observation Matrix、**禁止**驱动 recovery FSM 或 Presence 的运行时字段。例：`obligationGeneration`、`obligationOpen`、`edgeRecoveryPhase`、`REACHABILITY_PROBE`、`transportEverConnected`、`rosterEpoch`、未来 `RemoteHint`。见 ADR-0031 R31-F。
_Avoid_: Diagnostic 进 LocalReachability, probe 字段当 authority bit

**Cross-observer Presence convergence (v1 non-goal)**:
Peer presence is observer-local. Devices are not required to display identical peer states. Validation uses per-observer consistency, not cross-device equality.见 ADR-0031 Non-goals。
_Avoid_: 三屏头像必须一致, 用远端 observation 修正本机 presence

**Recovery Admission**:
`RECOVERY_REATTACH` 控制面准入协议：校验 lineage 后允许已有 JOINED 成员恢复 edge binding；不改变 Membership。由 #72 实现；#73 补自动触发与 FSM。
_Avoid_: 把 RECOVERY_REATTACH 当作 NORMAL_JOIN, admission 完成即 recovery 完成

**Conference Authority Reachability**:
本端与 Conference lifecycle authority 之间是否存在可维持会议语义的有效关系；不是任意 peer media 连通。Participant ACTIVE 依赖 authority reachability，v1 可由 Host media connected 近似实现。
_Avoid_: connectedRemoteMediaCount, 任意 peer ICE CONNECTED

**Conference Participant Count**:
会议主人数由已提交的 Conference Membership（JOINED）决定；邀请中、预期 roster、媒体 connected/recovering/failed 不改变人数。媒体变化只能改变成员状态展示。
_Avoid_: visibleParticipantCount, roster.size, connectedRemoteMediaCount

**Conference Membership Joined**:
Conference Membership Authority 已确认并提交到运行时成员视图的入会事实；不是用户点击接受邀请，也不是媒体连通。`InvitationState.ACCEPTED` 与 `MembershipState.JOINED` 可在过渡窗口内不同步。
_Avoid_: ACCEPTED == JOINED, ICE connected == joined, invitee 本地自判 joined

**Local Conference Ready**:
本端已跨过 Conference admission 边界、可进入会议体验的语义事实。Host ACTIVE 依赖 Established + Local Conference Ready；v1 可由 session accepted + transition terminal ready 映射，但术语不绑定 PeerConnection、ICE 或具体音频引擎。
_Avoid_: session object exists, local PC created, remote media connected

**Conference Degradation**:
已存在会议中的能力下降或部分失败，是 UI Projection 的附加属性而非主 phase。Degraded 不替代 Lifecycle、Membership 或 Connectivity 状态；v1 可通过日志/status flag 表达。
_Avoid_: DEGRADED 作为大杂烩 phase, 用 degraded 反推会议未建立

**Membership**:
组呼或会议 Session 内的参与方集合（Session Membership）：运行态事实，含世代（epoch）、可达性、evict/rejoin。单呼不存在 Membership。创建时可从 Channel Membership 做一次性 snapshot 作为初始邀请集，之后冻结、不再同步 Channel。
_Avoid_: Channel 成员, 通讯录

**Runtime Presence**:
Session 与 Module 两层运行系统的只读投影（Observability Layer），供 UI、Admission 与 Monitor 统一观察。不参与决策、无副作用；不是 Domain 对象，也不是第四套状态机。
_Avoid_: 在线状态（未指明层级时）, presence（泛称）

**Module Presence Snapshot**:
Per-Module 高频读模型：`localUplinkGrant`（本端是否已实际开启上行）、`activeCaptureEndpoint`、speaking 指示、ICE 连通、Activity 栈顶等。权威源为 Mixer、采集闸门、ICE callback、Activity 栈；R5 的唯一校验面。
_Avoid_: Module 状态机, localFloorGrant

**Session Presence Snapshot**:
Per-Session 中频读模型：Membership roster、`protocolFloorOwner`（信令收敛后的 Floor 归属）、roster epoch、Session Disposition 等。权威源为 Session Manager / 信令收敛；用于 roster UI、准入与 reconvergence。
_Avoid_: Session 状态机（Disposition 仍为 Session 权威，Snapshot 仅投影）, floorOwnerKey（未区分协议/执行时）

### 发言权与媒体

**Floor**:
半双工场景下 Endpoint 级的发言权归属（协议权威），标识哪个 Endpoint 有权发言。全网以 `protocolFloorOwner` 为准；本端实际采集须另经 `localUplinkGrant` 收敛（Floor → Uplink 因果，两层解耦）。仅 Group PTT 使用。
_Avoid_: 话权, 麦权, 持麦方（口语可接受，术语用 Floor）

**Protocol Floor Owner**:
Floor ownership 的协议层 Representation，投影为 Session Presence 的 `protocolFloorOwner`。权威 Fact 为 Floor ownership（Owner：Floor FSM）；跨端一致性以信令收敛后的该字段为准。
_Avoid_: floorOwner, floorOwnerKey（未区分 Fact/Representation 时）

**Local Uplink Grant**:
本 Module 是否已实际开启上行采集，投影为 Module Presence 的 `localUplinkGrant`。由采集闸门与 Mixer 驱动；须单调跟随 Protocol Floor Owner，但允许有界滞后（夺权中）；执行态不得超前协议态。
_Avoid_: localFloorGrant, 持麦（口语）

**Floor Convergence**:
协议态与执行态不一致时的收敛规则。获权：`protocolFloorOwner == local` 且 uplink 未就绪 → UI「夺权中…」；超时从 `GRANT_APPLIED` 起算，有界等待后须主动让权（沉默霸麦）。失权：权已归他人 → 零窗口停采集。计时语义、标定、interim 500ms 与三阶段落地见 `docs/adr/0004-floor-acquire-timeout.md`。仅适用于已通过 ADR-0007 R31 校验的 grant；Late Grant 须 discard，不得靠 timeout release 补救。
_Avoid_: 同步延迟（未指明方向时）

**Endpoint Priority**:
Group PTT 内 Floor 冲突裁决用的优先级（Normal、Dispatch、Emergency），分布式、会话域；登记以 HELLO 目录为准。与 Runtime 的 Preempt Reason 正交：不共享枚举与状态机，不反向驱动 Activity 栈；仅通过 `actingEndpointId` 关联。Runtime 侧 Preempt Reason 可单向触发本端 Floor 副作用（如 Emergency 压栈同时申请高优先级 Floor）。
_Avoid_: Preempt Reason, 优先级（未指明层级时）

**Uplink**:
Module 向 mesh 对端发送的音频 RTP 流。每个 Module 在任意时刻最多一条上行，与 Endpoint 级 Floor 身份解耦。
_Avoid_: 上行链路, 发包

### 准入与模式

**Foreground Activity**:
某个 Module 的 Activity 栈顶当前执行的活动类型（Idle、Unicast、Group、Conference 等）。每个 Module 同一时刻至多一个 Active 前台活动；具体恢复哪个 Session 由 Activity Frame 决定。
_Avoid_: Endpoint Activity（易误解为 per-endpoint 栈）, 忙线

**Activity Frame**:
Module 级 Activity 栈中的一帧：活动类型、关联 `sessionId`、`actingEndpointId`（实际操作者）、`requestedBy`（请求发起者，V1 可与 acting 相同）、抢占原因、是否自动恢复。仅引用 `sessionId`，不拥有 Session 对象。
_Avoid_: Activity 对象, 通话句柄

**ChannelMode**:
某个 Channel 当前被哪种编组模式占用（Idle、Group PTT、Conference）。仅描述 Channel 域内 Group 与 Conference 的互斥，与单呼及 Foreground Activity 无关。
_Avoid_: 会话模式, 通话状态

### Runtime Model

**Runtime Model**:
Talkback 本端运行时模型。资源属 Module（Activity 栈、媒体、ICE、采集）；身份属 Endpoint（Floor、PTT、信令主体）；业务对象属 Session（生命周期、Disposition、Membership）。对象定义见 `docs/adr/0001-talkback-runtime-model.md`；Facts 如何安全演化见 `docs/adr/0007-intent-reality-consistency.md`；Group PTT 收敛可观测投影见 `docs/adr/0008-group-runtime-health-projection.md`。
_Avoid_: Endpoint Runtime, Module Runtime, Coordinator

**Runtime Governance**:
以可证明收敛为目标的运行时治理层。核心职责是统一准入、统一可观测与统一诊断，而非执行业务迁移本身。治理层不拥有 Runtime 事实，不替代 Runtime Owner。
_Avoid_: 统一状态机（把业务执行搬进治理层）, 超级协调器

**Transition Trigger**:
触发一次运行时收敛过程的外部或内部原因标签（如 Meeting 结束、Rejoin、网络变化）。用于度量与归因，不等同于业务命令本身。
_Avoid_: 隐式 transition, 无来源的收敛事件

**Transition Coordinator**:
Transition 的观察与聚合组件：汇总 Capability 就绪快照、提供超时与指标、输出可读快照；不直接驱动 Runtime 执行路径。其定位是 observer + gatekeeper，而非 owner。`transitionId` 仅由 Runtime Owner 通过 `beginTransition` 声明创建，Gate 与 UI 不得惰性创建或隐式触发。
_Avoid_: transition manager（执行型）, 业务编排中心, Gate 懒创建 transition

**Transition Declaration**:
Runtime Owner 对“状态变化意图已开始”的显式声明（如 Meeting 结束、Group 重建）。Coordinator 据此分配 `transitionId` 并跟踪 phase；声明不等于请求执行业务，而是可观测事实的起点。v1 作用域为 per-channel：`transitionKey = (channelId, transitionId)`；同一 channel 同时最多一个 ACTIVE transition；禁止 v1 使用 cross-channel 或 global scope。
_Avoid_: 由 Gate 阻塞时顺带创建 transition, 多处入口各自 begin, silent overlap

**Channel Consistency Domain**:
以 channelId 为边界的一致性域：Conference、Group Mesh、Floor、Routing 视图与 Transition 均归属同一 channel。治理与 Gate 默认以 channel 为隔离单元，避免单 channel 故障扩散为全机阻塞。
_Avoid_: 全机单例 transition, 跨 channel 混用 readiness snapshot

**Transition Overlap Policy**:
同一 channel 已有 ACTIVE transition 时，新 `beginTransition` 默认 Reject（记录 `TRANSITION_IN_PROGRESS`），禁止 silent supersede。仅 Runtime Owner 经显式 `abortTransition(channelId, reason)` 将旧 transition 标为 `ABORTED` 后，方可再 begin。
_Avoid_: 自动覆盖旧 transition, Gate 侧隐式 supersede

**Transition Policy**:
治理域中按 `TransitionTrigger` 定义 transition 生命周期策略（如超时、可选重试约束）。Coordinator 只执行 timer，不自行决定业务超时；超时属于领域策略而非实现细节。
_Avoid_: Coordinator 内硬编码 magic number, 用 Operation 超时替代 transition 超时

**Transition Terminal State**:
Transition 的终态语义：`READY`（收敛成功）、`TIMED_OUT`（时间内未收敛）、`FAILED`（能力层显式失败）、`ABORTED`（Owner 显式中止）。`TIMED_OUT` 清除 active slot，但不隐含 Capability 已就绪。
_Avoid_: 超时即放行, 将 TIMED_OUT 与 FAILED 混为同一指标桶

**Transition Completion Contract (TCC)**:
对 `completeTransition` 的显式契约：`TERMINAL=READY` 仅当对应 `TransitionTrigger` 的 Completion Predicate 为真。Runtime Owner 不得在业务函数返回时无条件标 READY；未满足时须记录 `TRANSITION_PREDICATE_EVAL`。见 `docs/adr/0016-transition-completion-contract.md`（ADR-0015 amendment）。
_Avoid_: 建会函数返回即 READY, 语义完成冒充真实完成

**Completion Predicate**:
按 `TransitionTrigger` 定义的收敛判定（如 `MEETING_START`：Host solo 有 accepted CONFERENCE；Host 有 invitee 时须 ≥1 peer ICE CONNECTED；`MEETING_END`：GROUP OPERATIONAL + 无 active CONFERENCE）。Predicate 由 Runtime Owner 评估，Coordinator 只接收结果并记日志。
_Avoid_: 用 transition 超时替代 predicate, 将 Capability 快照当作 completion 真值

**Establishment vs Recovery Transition**:
Establishment（如 `MEETING_START`）要求媒体/拓扑收敛后才可 READY；Recovery（如 `MEETING_END`）要求 GROUP 运行态恢复后才可 READY。二者 predicate 不同，不得共用「函数返回即完成」捷径。
_Avoid_: 所有 transition 统一用调用返回作完成信号

**Transition Declaration**:
一次 Transition 的显式 Intent 快照（如 `MeetingMode`、`expectedInviteTargets`），由 Declaration Owner 在 Declaration Window 内写入，dispatch 完成后冻结。Predicate 只读冻结后的 Declaration，不得用运行时瞬时空集反推业务意图。见 `docs/adr/0017-transition-declaration-and-media-lifecycle.md`。
_Avoid_: 用 `conferenceMemberRemoteIds.isEmpty()` 判 solo, 运行时 patch declaration

**Declaration Owner**:
每种 `TransitionTrigger` 的唯一声明写入者（如 `MEETING_START` → Runtime core / Coordinator）。仅 Owner 可 OPEN、更新（窗口内）、FROZEN；Coordinator 治理层与 Gate 只消费，不写入。
_Avoid_: App 层、UI、Gate 各自 declare

**Declaration Window**:
Declaration 从 `beginTransition` 到 `inviteDispatchFinished` 的受限初始化阶段（OPEN）；完成后立即 FROZEN，字段不可变。`CompletionPredicate` 在 FROZEN 前可评估但不得满足。
_Avoid_: begin 即冻结（尚不知 invite targets）, 冻结后改 targets

**Meeting Mode**:
`MEETING_START` Declaration 的显式业务意图：`SOLO_HOST` 或 `MULTI_PARTY`。与 `expectedInviteTargets` 强一致：`SOLO_HOST ⇔ targets 为空`；违反即 `INVALID_DECLARATION`。
_Avoid_: soloHost 布尔从 targets 推导, 双真源 mode vs targets

**Expected Invite Targets**:
本次 `MEETING_START` 意图邀请的对端集合，属于 Declaration 而非 Runtime roster 快照。MULTI_PARTY 下 dispatch 须全部成功，不允许失败后降级改写 targets。
_Avoid_: 发送失败后删目标, 用 participant manager 空集当 solo

**Media Lifecycle State**:
与 Transition 正交的媒体连通阶段观测（如 BOOTSTRAPPING、NEGOTIATING、PARTIAL_CONNECTED、CONNECTED）。仅供 UI、指标、soak；**不得**驱动 `completeTransition` / `abortTransition` / `beginTransition`。
_Avoid_: ICE CONNECTED 触发 transition 完成, 把全员 CONNECTED 塞进 READY predicate

**Dispatch Completed**:
Invite 发送阶段的终态事件（success / failed），由 InviteDispatcher 在 Policy 约束下重试后产出；Coordinator 只消费此事件，不参与重试逻辑。
_Avoid_: Coordinator 内 retry 循环, dispatch 失败自动改 declaration

**Dual-Layer Admission Model**:
准入的双层模型：Policy 层（transition 进行中等时间维约束）与 Readiness 层（Capability 快照）。Gate 不信时间，只信状态；`TIMED_OUT` 仅解除 transition 占用，不自动通过 Readiness 检查。
_Avoid_: 时间到了即认为系统可用, 单层布尔 ready

**Capability Probe**:
Capability 就绪状态的只读查询契约。v1 以 Adapter Probe 实现：治理层委托现有 Runtime 状态，Coordinator 仅聚合 Probe 输出；Probe 必须无副作用。Phase 2 可将实现迁入各 Runtime Public API，接口保持不变。
_Avoid_: Coordinator 内联读取 session 字段, Probe 内触发业务副作用

**Capability**:
对 Runtime 能力的稳定语义抽象（如 Membership Ready、Routing Ready、Authority Ready）。Operation 依赖 Capability，而不是依赖某个具体 Runtime 字段或 transition 类型。
_Avoid_: 直接读 Runtime 内部状态做跨域准入

**Capability Readiness**:
某项 Capability 在当前时刻是否可用于准入决策的判定。就绪判定由对应 Runtime Owner 定义；Coordinator 只聚合，不持有权威副本。
_Avoid_: Coordinator 缓存并拥有 readiness 真值

**Capability Snapshot**:
Transition Coordinator 在某一时刻对各 Capability readiness 的聚合视图。是读模型，不是事实所有权；用于 Gate 判定、日志与指标归因。
_Avoid_: Coordinator 内部真值存储, Runtime readiness 副本

**Operation Policy**:
治理域中的策略对象，定义每个外部 Operation 的 Capability 依赖与准入相关规则（如优先级、超时、降级、重试约束）。是依赖关系唯一真源，独立于 Runtime 实现版本演进。
_Avoid_: 依赖散落在入口 if, 仅作键值查找的 registry

**Operation Gate**:
所有外部发起操作的统一准入入口。每个操作先声明依赖 Capability 集，再做 ALLOW/BLOCK 判定，保证 PTT/Meeting/Single Call/Bootstrap 使用同一准入语义。
_Avoid_: 各入口各自 if 判断, 旁路准入

**Gate Decision**:
Operation Gate 的结构化输出：是否放行，以及阻塞时的机器可读上下文（分类、原因码、关联 transition、重试建议、阻塞 capability）。供 UI、日志、指标与测试复用。
_Avoid_: 仅返回布尔值, 仅返回文案字符串

**Block Category**:
Gate 阻塞的一层分类：`READINESS`（系统未就绪）与 `POLICY`（系统就绪但策略不允许）。该分类是治理责任边界：READINESS 指向 Runtime 收敛问题，POLICY 指向产品/治理策略约束。
_Avoid_: 将全部阻塞混为 busy, 用来源模块名替代分类

**Readiness Reason**:
`READINESS` 下的阻塞原因码，表达某个必需 Capability 尚未达到可执行条件（如 `ROUTING_NOT_READY`、`AUTHORITY_NOT_READY`）。通常可随收敛自行恢复。
_Avoid_: 把冷却/限流等策略性拒绝写成 readiness

**Policy Reason**:
`POLICY` 下的阻塞原因码，表达系统主动的准入约束（如 `COOLDOWN_ACTIVE`、`RATE_LIMITED`、`ROLE_RESTRICTED`）。表示“可用但不允许”，不等于 Runtime 未收敛。
_Avoid_: 用 runtime not ready 解释策略拒绝

**Blocking Capability**:
在 `READINESS` 阻塞时被判定为未就绪的 Capability 标识，用于指标聚合与根因归属；应为强类型字段而非字符串解析。
_Avoid_: 仅输出自由文本 reason, 依赖日志正则反推 capability

**Transition Id**:
一次治理判定关联的全局 transition 关联键。用于串联 Gate、Coordinator、Runtime 日志与指标；当无活动 transition 时使用显式 NONE，而非缺省缺失。
_Avoid_: 可有可无的关联 id, 按日志时间猜测同一 transition

**Primary Block Reason**:
Operation Gate 在多重阻塞时选定的单一主因，供 UI 展示与 Metrics/SLI 聚合。必须由全局固定优先级算法选出，不得依赖代码遍历顺序或 Operation 类型特例。
_Avoid_: 返回原因列表让调用方自选, 按 if 链先命中即返回

**Gate Priority Resolver**:
治理层中决定多重阻塞时 Primary Reason 的唯一排序器。先收集全部阻塞原因，再按文档化优先级（如 Readiness 细项序、Policy 细项序）选出主因，其余进入附加原因列表。
_Avoid_: 各 Operation 各自定义优先级, 隐式依赖 requires 声明顺序

**Late Completion**:
过去发起的异步操作（信令、ICE、定时器等）在延迟后完成时，其所依赖的 Intent 可能已失效。未经验证即修改 Facts 是 Floor/Mesh/Conference 等竞态的共同根因。
_Avoid_: 孤儿 Floor, 迟到 Grant（仅作口语）

**Asynchronous Completion**:
在 Intent 之后、经 Physical Execution 或信令路径抵达的「完成」事件。须在各 Fact Owner 的 commit 边界经 R31 校验；Physical completion 不等于 Fact completion（R33）。
_Avoid_: 回调, 事件（未指明 completion 语义时）

**OperationToken**:
Runtime 对一次异步操作的语义封装：`(domain, identity, version, validity)`。不引入新协议编号；`version` 映射各域已有权威版本（如 `floorVersion`、`rosterEpoch`）；`validity` 为本地生命周期（VALID / INVALIDATED / COMPLETED），供 R31 校验。
_Avoid_: operationId, requestId（与协议字段混用时）

**Runtime Fact**:
Runtime 内可被多方依赖的权威事实（如 Floor ownership、Session membership、mesh topology、Session disposition）。仅由对应 Owner 修改；字段与 digest 条目为其 Representation，不是独立 Fact。
_Avoid_: protocolFloorOwner（单独作为 Fact 名）, digest 字段

**Projection Emitter**:
只读 Runtime Facts、生成 Digest、Presence、UI Snapshot 等投影的组件。不得 mutate Facts（R32）。Digest 是投影，不是权威状态。
_Avoid_: digest owner, 同步层（未指明单向时）

**Group Runtime Health**:
Group PTT Session 上 Membership → Planner → Mesh → Transmit 收敛链的只读投影（Observability Layer）。解释本机为何处于 Syncing、为何 Meeting 后恢复等；**不是**权威 Fact，**不是**状态机。v1 仅覆盖 Group PTT；详见 `docs/adr/0008-group-runtime-health-projection.md`。
_Avoid_: Group 状态机, mesh 状态机, readiness owner

**Topology Snapshot**:
某一时刻 `Group Runtime Health` 的序列化观测产物（`schemaVersion` 版本化）。用于 log diff、现场诊断与回放；**不得**回灌控制决策。日志形态建议 `TOPOLOGY_SNAPSHOT`。
_Avoid_: GROUP-PLAN 日志, debug 状态

**Group Topology Readiness**:
Group Runtime Health 内部的四态收敛标签（`DISCOVERING`、`MEMBERSHIP_PENDING`、`BUILDING`、`OPERATIONAL`）。**Transmit Policy 为唯一 readiness 裁决**；Mesh 层仅解释原因。v1 映射到现有 `ChannelReadiness` 供对照，UI 暂不直接依赖。
_Avoid_: Planner phase, TRANSMIT_PENDING（作为独立 UI 态）

**Membership Reconciled**:
本机 Membership 是否已收敛到**最近已知 authority view**（非全局共识）：`sessionAccepted`、与 authority digest 对齐、无 `suspectPeers`。未 reconciled 时为 `MEMBERSHIP_PENDING`。公理见 ADR-0008 R34。
_Avoid_: 全员 roster 一致, 全局共识

**Canonical Endpoint Binding**:
Active Group session 内，每个 `moduleId` 在 `session.groupMembers` 与 floor payload 中仅对应一个 canonical `endpointId`（R35）。验签 HELLO 发现 endpoint 变化时 **replace** 旧 binding 并 bump `rosterEpoch`，禁止 `M03-E01` 与 `M03-E03` 并存。详见 `docs/adr/0009-group-session-identity-consistency.md`。
_Avoid_: endpoint alias 合并, 双 key 共存

**Identity Drift**:
同一 module 在 Group session 不同层（roster / floor / mesh）使用不同 `EndpointKey` 的状态。典型指纹：`GRANT_DROPPED ROSTER_MISS`（远端 drift，R35）；`ownerIsLocal=false` + canonical grant + 无 `captureON`（本地 runtime drift，R38）。ICE 连通不能证明 identity 一致。
_Avoid_: mesh 未连接, floor bug（未区分层时）

**Runtime Local Identity**:
Group PTT 运行时本机身份的**只读派生视图**，由 canonical `groupMembers[localModuleId]` 经 `IdentityResolver` 导出；**不是** `TalkbackSession.local` 的存储副本。原则：*derived view, not stored state*（R38）。
_Avoid_: session.local 作 SSOT, 在 canonical 变更时 mutate session.local

**IdentityResolver**:
Group session 内读取 local runtime identity 的唯一入口（`local` / `isLocal` / `localKey`）。Canonical 由 Membership Authority 写入；Resolver 只读。Compat：`moduleId` fallback + `LOCAL_IDENTITY_DRIFT_COMPAT`（过渡，须可删除）。
_Avoid_: 散落 moduleId 比较, 多处 ownerIsLocal 逻辑

**Canonical Membership Completeness**:
Accepted Group session 的 canonical roster 必须：每个参与 module 恰好一个 endpoint；**local module 必须在 roster 内**（R39）。违反时 Floor/Resolver 异常是症状，不是独立根因。详见 ADR-0009。
_Avoid_: 把 snapshot 规则当作 R39, 未查 Producer 就 patch Consumer

**Committed Projection Publication**:
Membership snapshot 只能发布已 commit 的 canonical facts（R40）。Commit 边界内多次 mutation 合并为一次 coalesced publish；R39 未满足时 defer。详见 ADR-0009。
_Avoid_: 事件驱动逐条 broadcast, 静默 skip snapshot

**Conference Membership Projection**:
Conference roster 权威来源：`conferenceParticipantManager` / `memberViews`（R41）。表达谁属于会议；用于 Host 管理、调试、日志。**非** Conference 主 UI 数据源。
_Avoid_: 把 roster 直接画进主界面头像条

**Conference Membership Transition**:
Conference membership 的唯一写 seam：`ConferenceMembershipReducer`（ADR-0012 R-M1）。信令 handler 只 dispatch event（`PeerLeft`、`PeerReactivated`、`SnapshotCorrected` 等）；roster、left tombstone、participant 基线与 mesh 失效在同一 transition 内完成。Reconcile 是 Host roster 快照校准，不是 invite。
_Avoid_: Coordinator 各 handler 直接 applyPrune/onLateJoin, 把 mesh repair 当 membership 恢复

**Conference Visible Participants**:
Conference 用户可见在会者的 canonical UI 投影（R44）：`ConferenceParticipantProjector` → `visibleParticipants` / `visibleParticipantCount`。驱动头像、人数、Speaking、等待提示。规则由 Projector 解释 invite/media/LEFT，UI 只读 `ConferenceParticipantDisplayState`。
_Avoid_: ViewModel 内 `memberViews.filter`, 直接读 `invite`/`media`

**Conference Media Projection**:
本机 ICE 媒体链路可达性（`meshConnectedPeerCount` / `connectedMeshPeerIds`）。用于 transmit readiness、信号质量与诊断；**不得**驱动 Conference UI（R42）。
_Avoid_: 把 data-plane 当 control-plane

**Conference Media Session**:
Conference 范围内、按远端 `moduleId` 绑定的 PeerConnection 实例及其 generation。由 `MediaSessionManager` 创建与关闭；活跃会议中不得因 membership 或 invite retry 而隐式 recreate。见 `docs/adr/0018-conference-media-lifecycle-ownership.md`（ADR-CONF-001）。
_Avoid_: mesh engine（未指明 scope 时）, 每次 invite 新建 PC

**Media Session Reuse**:
Soak 硬门禁 S8 指标：`MEDIA_SESSION_REUSE=1` 表示在 CONFERENCE scope 内对已有 live PC 执行了 destroy+recreate。活跃会议路径上必须为 0；仅 scope 切换 barrier 或 recovery escalation 后的合法 recreate 除外。
_Avoid_: 把 reuse 当作性能优化开关

**Conference Recovery Escalation**:
Conference 媒体断连后的分级恢复：先 ICE restart（有界重试），仅在 FAILED / CHECKING 超时等条件满足后由 `ConferenceRecoveryController` 请求 PC recreate。`DISCONNECTED` 走 recovery，不立即 recreate。见 ADR-0018。
_Avoid_: invite 超时即重建 PC, Coordinator 直接 close+create

**Signaling–Media Separation**:
Conference 控制面（invite 发送、retry、roster 更新）与数据面（PeerConnection 生命周期）正交。信令 retry **不得**调用 `MediaSessionManager.create/close/resetAll`；media recreate 仅 recovery authority 在 escalation 后发起。见 `docs/adr/0019-conference-signaling-media-separation.md`（ADR-CONF-002）。
_Avoid_: resendConferenceInvites 顺带 acquireMeshEngine

**Conference Runtime State**:
会议可用性的只读投影（phase、recovering、awaiting 等），由 `ConferenceRuntimeProjector` 从 control + media facts 推导。会议 UI **必须**只读此投影；`channelReadiness` 仅用于 PTT/频道，不得驱动 Meeting pill。`ACTIVE` 可与 `awaitingAdditionalParticipants=true` 共存；`recovering` 仅 Projector 判定。PR-2/PR-3 落地。
_Avoid_: channelReadiness 驱动会议 Connecting, UI 直接读 ICE 回调

**UI Projection Principle**:
UI 不得直接解释 Runtime Facts；一切用户可见语义必须有专用 Projection（R43）。Conference、Contacts、Presence、GroupRuntimeHealth 均遵守。
_Avoid_: 在 ViewModel 散落业务 filter

**Session Disposition**:
某个 Session 当前的处置态，描述其生命周期阶段（如 Active、Suspended、Resuming、Terminating、Terminated）。媒体、UI 与同步快照均读取此态；从 Suspended 恢复须经 Resuming，因媒体与 Floor 重连是异步的。
_Avoid_: session 状态（未区分 lifecycle 时）, 布尔挂起标志

**Preemption**:
高优先级活动打断低优先级活动的过程。被抢占的 Mesh Session 进入 Suspended，并记录 Preemption Token；仅 Token 持有者可发起恢复，避免栈顶活动未结束时错误 resume 下层 Session。
_Avoid_: 抢占麦, 打断（未指明层级时）

**Preemption Token**:
记录「谁挂起了这个 Session、为何挂起」。恢复时须验证 Token 仍有效且发起者为 Token 持有者（通常为当前栈顶活动）。
_Avoid_: suspend 标志, 挂起位

**Preempt Reason**:
本 Module Runtime 内 Activity 栈抢占的原因分类（如用户主动、Emergency、系统自动等），写入 Activity Frame 与 Preemption Token。与 Endpoint Priority 正交：本地、Module 域；可单向映射为本端 Floor 操作，但 Floor 裁决结果不反向驱动栈。
_Avoid_: Endpoint Priority, 打断原因（口语）
