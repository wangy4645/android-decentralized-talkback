# 三层密钥边界与 Callable Roster

Talkback 的信任与编组分三层，永不合并枚举：**RF Task Key**（在不在同一张网）→ **Shared Secret**（网内是否同一应用子网：可发现 + 可验签 + 可通话）→ **channelId**（子网内逻辑编组，无密码学意义）。换 RF Key = 换网；换 Shared Secret = 换应用子网；换 channelId 仅换编组。

**决定原因**：代码已用 Shared Secret 过滤 gossip 发现与全部信令（含 HELLO），但 NSD/static 发现不经验签，会产生「幽灵联系人」（可见不可呼）。领域文档曾误写 sharedSecret 只管信令与 Channel，需与实现对齐。

## 核心不变量

- **R18（三层正交）**：RF Task Key、Shared Secret、channelId 三层分离；channelId 不得当作加密或成员资格边界。
- **R19（Callable 门）**：`module ∈ CallableRoster` ⟺ 验签通过来源 + 可用 transport。NSD/static 未握手前不进 Callable；HELLO 目录不反向提门。验签通过的 HELLO 本身可作为 callable 证据。
- **R20（目录）**：`EndpointDirectory[module]` 仅由验签 HELLO 填充。V2 Contacts 对 callable module 投影 directory **全部** endpoints；callable 无目录 / 有目录非 callable 不展示（见 ADR-0006）。

## 发现源与验签（现状 → 目标）

| 发现源 | 现状 | 目标 |
|--------|------|------|
| gossip sweep | 验签过滤 | 保持 |
| NSD/mDNS | 不验签，可进 roster | `unverified` 直至首次验签信令 |
| static peers | 不验签，可进 roster | 同上 |

HELLO：不门禁 RF mesh 入网；但信令本身须 Shared Secret 验签，未通过的不采纳目录。

## Considered Options

- **sharedSecret 仅管信令**：与 gossip 实现不符；拒绝。
- **channelId 作子网隔离**：任何同 secret 端可进任意 channel；拒绝。
- **NSD/static 继续裸进可呼叫列表**：幽灵联系人；拒绝，采用 R19。
- **三层模型 + Callable 验签对齐**：接受。

## 落地阶段

1. **文档**：`CONTEXT.md` 三层词条（已完成）。
2. **UI（V2）**：`unverified` 条目**完全不展示**于 Contacts/可呼叫列表；仅日志与 metrics 记录，供密钥配错排障。灰显「未验证」（方案 B）留 V2.1。
3. **代码**：NSD/static roster 条目 `verified` 标记；未验签前不进入 UI 可呼叫数据源；首次 HELLO/probe 验签通过后晋升。

## Consequences

- Contacts 由 `buildEndpointList` 等 view 层做「先门后形」投影，替代 `peerDisplayRoster` 的 module 单行 + primary 兜底（见 ADR-0006）。
- CompositeModuleDiscovery 合并逻辑须尊重验签状态，不能仅凭 NSD/static 覆盖 gossip 结论。
- 保密组网手册中「HELLO 不做入网门禁」须改为「不门禁 RF，但受 sharedSecret 验签」。
