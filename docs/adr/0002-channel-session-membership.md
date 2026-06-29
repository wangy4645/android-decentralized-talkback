# Channel 与 Session Membership 单向关系

Channel Membership 表达静态编组意图（「应有哪些成员」）；Session Membership 表达单次通话的运行事实（「谁此刻在参与」）。二者正交：Session 创建时可从 Channel 做一次性 snapshot 生成初始邀请集，之后冻结；Session 的任何演化（evict、rejoin、离网）永远不回写 Channel。

**决定原因**：若 Session eviction 回写 Channel，Channel 会沦为隐式状态机，同一 Channel 的多次 Session 无法有不同参与者，且无法区分「临时踢出」与「永久移除」。Mesh epoch/roster 体系会被配置层污染。

## 核心不变量

- **R7（禁止回写）**：Session Membership → Channel Membership 路径禁止。Channel 只做配置源，不是运行态投影。
- **R8（冻结 snapshot）**：Session 启动时若引用 Channel，须显式 `snapshot(Channel.members)` 作为 initial invite set；之后不再监听或同步 Channel 变更。

## 三层关系

```
Channel Membership（静态意图）
        ↓ init snapshot only
Session Membership（运行事实，epoch-driven）
        ↓ drives
Floor / Media / ICE / Activity Stack

Runtime Presence（瞬时事实，散落但可读）— 与上两层均正交
```

## Considered Options

- **Session 结束同步 roster 回 Channel**：使 Channel 承载活跃关系；拒绝（R7）。
- **Channel 变更实时推送活跃 Session**：破坏 epoch 模型；拒绝（R8）。
- **单呼写入 Channel 成员**：单呼为 ad-hoc Session，与 Channel topology 无关；拒绝。

## Consequences

- `channelManager.join` 在单呼路径应迁为 Session Origin 副作用，而非 Membership 权威写入。
- UI「频道成员」与「当前组呼在线」须分开展示，数据源不同。
- 管理员「永久移除」是 Channel 配置操作，与 Session evict 分离。
