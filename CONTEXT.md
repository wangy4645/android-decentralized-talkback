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
Endpoint 的全局键，格式 `{moduleId}-{endpointId}`（如 `M01-E03`）。
_Avoid_: 地址, peerId

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
