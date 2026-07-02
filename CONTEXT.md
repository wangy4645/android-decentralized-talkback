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
