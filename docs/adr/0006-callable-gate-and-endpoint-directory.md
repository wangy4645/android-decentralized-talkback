# Callable 门与 Endpoint 目录分离

Callable Roster（门：module 是否可呼叫）与 Endpoint Directory（形：module 有哪些 endpoint、优先级）以 `moduleId` 为键正交存储，永不合并为单一 PeerDirectory。UI 通过 Contacts Projection 做「先门后形」渲染，不在界面层做交集判断。

**决定原因**：`peerDisplayRoster()` 将 HELLO ∪ 发现 ∪ static 无差别合并，且每 module 只展示一个 primary endpoint，产生幽灵联系人、无 HELLO 也出行、1:N 展不开三问题。合并门与目录会导致半成品对象，各消费方再度 `if (endpoints == null)` 扩散。

## 核心不变量

- **R19（门）**：见 ADR-0005。Callable 判据是验签 + transport，非消息类型名。
- **R20（目录与投影）**：`EndpointDirectory[module]` 仅验签 HELLO 填充。Contacts = 对 callable module 投影 directory **全部** endpoints（V2，不按 endpoint 可达性过滤）；callable 无目录 / 有目录非 callable → 不展示。module 级在线单独指示；endpoint 灰显留 V2.1。

## 两层结构

| 层 | 问题 | 来源 | TTL 特征 |
|----|------|------|----------|
| **Callable Roster** | 能不能呼通这个 module | 验签通过发现 / 验签 HELLO + transport | 可达性，短 |
| **Endpoint Directory** | 有哪些 endpoint、优先级 | 验签 HELLO | 目录内容，可缓存更久 |

```
CallableRoster[moduleId]     EndpointDirectory[moduleId]
        │                              │
        └──────── Contacts Projection ─┘
                  （先门后形，按 endpoint 展开）
```

## 代码差距（V1 → V2）

| 现状 | 目标 |
|------|------|
| `mergeRemoteModuleViews` 无验签门 | Callable 仅验签来源 |
| `resolvePrimaryEndpointId` 单行 | 展 `StateSyncManager.endpoints` 全量 |
| `peerDisplayRoster` module 中心 | `buildEndpointList` endpoint 中心投影 |

## Considered Options

- **单一 PeerDirectory**：门与形字段残缺共存；拒绝。
- **UI 层做 Callable ∩ Directory 判断**：逻辑散落；拒绝，改为 view 层单一投影函数。
- **callable 无目录显示「同步中」**：V2 隐藏；拒绝占位（接受 R20）。
- **V2 仅展示可达 endpoint**：1:N 场景漏终端；拒绝，V2 全展示 directory（接受 A）。
- **门 + 目录分离 + 先门后形投影**：接受。

## Consequences

- HELLO 验签通过既可填目录，也可作 callable 证据；未验签目录永不提门。
- 与 ADR-0005 `verified` 标记及 V2 UI 方案 A 衔接。
- 1:N endpoint 展示依赖 directory 全量展开，不再 primary 兜底。
