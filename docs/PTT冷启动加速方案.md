# PTT 冷启动加速方案

> 版本：V1.0  
> 日期：2026-06-15  
> 关联：[`现场测试方案与执行手册.md`](现场测试方案与执行手册.md)

## 目标

同密钥 3 台设备，Start Service 后 **5s 内可发起组呼、8s 内可发麦**；UI 区分发现 / 同步 / 连接 / 就绪。

## 改动摘要

### 发现层（Bootstrap）

- [`MeshSweepGossipDiscovery.kt`](../android-board-talkback/src/main/java/com/talkback/core/discovery/MeshSweepGossipDiscovery.kt)：`bootstrapSweepIntervalMs`（3s）、`bootstrapAnnounceIntervalMs`（2s）、`bootstrapDurationMs`（60s）；roster 为空或收敛前快速重扫；启动即子网 ANNOUNCE。

### 目录层

- [`TalkbackRuntimeManager.collectChannelRemotes()`](../talkback-app/src/main/java/com/talkback/appprod/runtime/TalkbackRuntimeManager.kt)：gossip 发现的路径对 `primaryEndpointId` 使用 `E01` 回退，不阻塞于 HELLO。

### HELLO

- [`TalkbackCoordinator.start()`](../android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt)：立即 `broadcastHello()`；bootstrap 期 1s 周期，之后 3s。

### Channel 预建

- [`ChannelWarmupPolicy`](../talkback-app/src/main/java/com/talkback/appprod/runtime/ChannelWarmupPolicy.kt) + `warmupChannel()`：发现队友后后台 `ensureChannelSession`。
- 首次组呼 / 队友从无到有：跳过 `MESH_CALL_BACKOFF_MS`（3s）；无队友失败不计 backoff。

### UI

- [`ChannelReadiness`](../android-board-talkback/src/main/java/com/talkback/core/session/ChannelReadiness.kt)：`DISCOVERING` / `DIRECTORY_SYNC` / `CONNECTING` / `READY` / `BLOCKED`。
- Talk 页横幅：`channel_discovering` / `channel_syncing` / `channel_connecting`。

## 现场验收

见手册 **TC-PTT-01**：3 台零配置，Start Service 后 5s 内 Talk 页有队友且可 PTT（非 NoPeers）。
