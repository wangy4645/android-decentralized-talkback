# PTT 媒体面架构 — 选举锚点方案

## 概述

组呼媒体面从 full mesh 演进为可插拔拓扑策略：

| 人数 | 拓扑 | 连接数 | 发话人上行 |
|------|------|--------|------------|
| ≤5 | `MeshTopology` | O(N²) | O(N) |
| ≥6 | `AnchorTopology` | O(N) | O(1) |

控制面保持去中心化（Gossip 发现、签名信令、Floor 控制）；媒体面由 `MediaTopology` 接口隔离。

## 锚点选举

- `AnchorElection.anchor(members) = min(moduleId)`（字典序）
- `AnchorElection.nextAnchor(members, current)` = 次小 moduleId（failover 热备）
- 各端基于本地 roster **独立计算**，无需额外协商
- 频道预热 host 复用同一规则：`ChannelMeshHostElection`

## 信令与连接

### Mesh（≤5）

- 发起方 `GROUP_INVITE` 全部成员
- `completeGroupMesh` 按 `GroupMeshPlanner` 补全 pairwise `GROUP_JOIN`

### Anchor（≥6）

- `maxGroupModules` 提升至 **8**
- 成员侧仅维持 **1 条 PC** 到锚点（非锚点发起方只 invite 锚点）
- 锚点侧维持到所有成员的 PC，并通过 `ProgramAudioBus` 转发 floor 音频
- `GroupSessionPayload` 携带 `mediaTopology` / `anchorModuleId`

## 锚点转发（单发话人）

```
floor holder ──1路上行──► 锚点 min(moduleId) ──扇出──► 听众×(N-1)
```

- `ProgramAudioBus`：锚点订阅 floor holder 入站 PCM（`InboundPcmSink`），扇出至各听众 PC 的 program track
- `WebRtcAudioEngine.setProgramRelayMode(MICROPHONE|PROGRAM)` 切换发话源
- 锚点自己讲话时仍走麦克风，不走转发

## Failover

锚点 ICE `FAILED` / `DISCONNECTED` 时：

1. 全员本地重算 `nextAnchor = next-min`
2. 释放旧锚点 PC，向新锚点发起 `GROUP_JOIN`
3. 目标收敛 <2s；锚点持麦时掉线则 floor 释放

## Phase 0 预热修复（已合入）

| 问题 | 修复 |
|------|------|
| 多机同时 warmup 互拆 session | 仅 `min(moduleId)` host 预热 |
| warmup 拆掉协商中 GROUP session | `allowReplaceIdle=false` on warmup path |
| host 连续 BUSY | 退避 3s → 8s → 15s |
| 非 host UI 长期 Syncing | 显示「等待频道同步」 |

## 测试用例

### 单元测试

- `AnchorElectionTest` — min / next-min
- `MediaTopologyTest` — 5↔6 切换、transmit peers
- `ChannelMeshHostElectionTest` — 预热 host 选举

### 集成测试

- `AnchorTopologyIntegrationTest` — 6 人 anchor / 5 人 mesh
- `ChannelWarmupHostIntegrationTest` — 5 节点单 host、无 idle replace
- 回归：`TalkbackCoordinatorIntegrationTest`、`GroupMeshPlannerTest`

### 现场 TC-PTT-ANCHOR

**前置**：6~8 台手机同频道，确认 roster 齐全。

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | 6 台同时进频道 | 日志 `topology=ANCHOR`；非锚点各 1 条 PC |
| 2 | 非锚点抢麦讲话 | 全员可听；锚点 CPU 有编码负载 |
| 3 | 锚点抢麦 | 直连麦克风，无转发绕路 |
| 4 | 拔掉/断网锚点 | <2s 切到次锚点；可继续 PTT |
| 5 | 8 台满员 | 无 `exceeds maxModules`；通话稳定 |

**回归**：TC-PTT-01（3 台冷启动）仍绿。

## 远期（未实现）

- 局域网 UDP 组播 fast-path（>12 人独立 SFU）
