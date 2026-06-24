# 架构评审纪要（实施对照）

## 架构红线（已写入实现）

| 原则 | 实现位置 |
|------|----------|
| module ↔ module 单 RTP | `ModuleMediaEngineFactory` |
| 禁止 endpoint 级 Mesh | 组呼对每个远端 module 一条 PC，非每个 endpoint |
| Floor Owner + CAS | `FloorState`, `FLOOR_DENY`, `TalkbackCoordinator` |
| Gossip 发现 + mDNS + 手动覆盖 | `MeshSweepGossipDiscovery`, `CompositeModuleDiscoveryService` |
| Channel 抽象 | `ChannelManager`, `groupCall(channelId)` |
| 组呼 Module Mesh | `GroupMeshPlanner`, `GROUP_JOIN`, `GroupSessionPayload` |
| 组呼 Floor 权威 | `GroupFloorController`, 发起方裁决 + 全员广播 |

## 评分对照

- 方案方向：无中心 **RF mesh 承载** + IP 层 WebRTC PTT — 保持不变；组网准入在射频任务密钥，应用层负责会话/媒体。
- 当前代码阶段：Phase B 组呼 mesh 已闭环（代码层），Phase C 部分落地，V3 见 `V3-roadmap.md`。

## 验收建议

- V1：单呼 + Floor CAS + 安全 + 30min — 可先签收。
- V2：组呼 3+ module mesh + HELLO + 静态 Peer — 需 3 机现场实测后签收。
