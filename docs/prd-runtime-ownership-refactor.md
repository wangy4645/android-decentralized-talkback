# PRD: Runtime Ownership Refactor Sprint

**状态**: Draft v2（架构评审修订）；RO-0 已完成 → `docs/runtime/runtime-public-api.md`  
**日期**: 2026-07-02  
**前置分析**: `docs/runtime/ownership-matrix.md`, `runtime-contract.md`, `mutation-map.md`, `floor-routing-data-flow.md`  
**相关 ADR**: ADR-0013（待修订）、ADR-0010/0011/0012（Conference Membership）

---

## Problem Statement

Talkback 已进入典型的**共享状态系统**失效模式：修 Rejoin 坏 Meeting，修 Meeting 坏 PTT，修 PTT 又坏 Meeting。根因不是某个函数写错，而是 **四个 Runtime 共享 `TalkbackSession` 与全局 Map，没有唯一 Owner 与 Public API**。

静态扫描已证明：

1. **Cache 被当 Fact 用**：`signalPeersByModule` 是 Floor Resolver 的决策输入；Conference Rejoin 等 10+ 协议通过 `rememberSignalPeer()` 成为 Floor 的隐式 Writer。
2. **Multi Writer**：`groupMembers`、`participants.media`、`remotePeersByModule`、`leftMemberEndpoints` 等多处双写/三写。
3. **无架构闸门**：`TalkbackCoordinator` 可直接改任意 Runtime 内部状态，PR review 无法系统性挡违规。

**长期目标**：不是修好 M03 PTT，而是让 Meeting / PTT / Mesh / Conference **独立演进、互不影响**。

---

## North Star: Coordinator as Runtime Facade

```
TalkbackCoordinator（编排 only）
        │
        ├── MembershipRuntime   ← applyRoster() / evict() / authority()
        ├── TransportRuntime    ← TransportRegistry.resolve()
        ├── MediaRuntime        ← onIceStateChanged() / mesh binding
        └── ConferenceRuntime   ← invite / roster / projection
```

Coordinator **只编排**，**不直接修改** Runtime 内部字段（`session.groupMembers =`、`participant.media =` 等最终消失）。

本 Sprint 不完成全量 Degodification，但每一步 Refactor 必须朝此 Facade 收敛。

---

## Solution Overview

```
Phase 0   Runtime Inventory + Public API 定义（文档，零行为变更）
    ↓
稳定基线   阻塞性 Regression 可修（含 M03 PTT 若仍阻塞验收）
    ↓
RO-1      OwnershipContractTest（v1 grep → v2 detekt）
    ↓  Merge
RO-2      Membership Single Writer（groupMembers）
    ↓  Merge
RO-3      Authority Single Writer
    ↓  Merge
RO-4      TransportRegistry 接口 + Stub 实现（resolve 仍委托 signalPeers）
    ↓  Merge
RO-5      TransportRegistry 真实迁移（HELLO/gossip 单 Writer）
    ↓  Merge
RO-6      Floor Resolver 纯函数 + ADR-0013 修订 + M03 红测/三台验收
    ↓  Merge
RO-7      participants.media 收归 MediaRuntime（放最后，影响面最大）
    ↓
RO-8+     leftMemberEndpoints 单存储、Coordinator Facade 深化…
```

**原则**：每 RO **独立 Merge**；Refactor 建立在**可验证的稳定基线**上。

---

## Freeze Policy（修订）

| | Architecture Freeze（废弃） | **Feature + Regression Freeze（采用）** |
|---|---------------------------|----------------------------------------|
| 禁止 | 一切 bug 修复 | 新 Feature、UI、协议扩展 |
| 允许 | — | Runtime Refactor、架构测试、ADR、**阻塞性 Regression 修复** |
| 基线 | 无稳定基线即开工 | **已知阻塞 Regression 先绿或先钉住红测**，再收 Ownership |

**阻塞性 Regression**：导致三台联调无法判断「改前坏 / 改后坏」的问题（如 M03 PTT 间歇失败）。允许在 RO 间隙用最小 patch + 红测钉住，但不得扩大为 Feature。

---

## Phase 0: Runtime Inventory + Public API

**不改代码**。在 `docs/runtime/ownership-matrix.md` 增补 **Public API** 列：

| State | Owner | Public API | Direct Write |
|-------|-------|------------|--------------|
| `groupMembers` | Membership | `membership.applyRoster()` | **Forbidden** |
| `floorAuthorityModuleId` | Membership | `membership.authority()` | **Forbidden** |
| signaling transport | Transport | `transportRegistry.resolve()` | **Forbidden** |
| `participants.media` | Media | `mediaRuntime.onIceStateChanged()` | **Forbidden** |

产出：

- `docs/runtime/runtime-public-api.md`（各 Runtime 对外方法清单 + Forbidden 列表）
- PR Review 用 **Runtime Boundary Review** 清单：新增代码是否绕过 Public API

**Issue**: RO-0

---

## Runtime Boundary Review（贯穿全程）

每个 Runtime 必须有 **Public API**；`TalkbackSession` 字段对 Coordinator 逐步 **private / internal + 门面**。

否则 OwnershipTest 永远追屁股跑。

| Runtime | 目标 Public API（草案） |
|---------|------------------------|
| Membership | `applyRoster()`, `evict()`, `promote()`, `authority()`, `bumpEpoch()` |
| Transport | `resolve()`, `onVerifiedHello()`, `onGossipPresence()`, `invalidate()` |
| Media | `onIceStateChanged()`, `bindMeshPeer()`, `markMeshCompleted()` |
| Conference | `onInviteSent()`, `onInviteAccepted()`, `applyPrune()`, `snapshot()` |

---

## RO-1: OwnershipContractTest

**v1（本 RO）**：源码文本扫描 + `@OwnershipExempt` 基线（允许误报，快速上线）

**v2（后续 RO）**：Detekt 自定义 rule 或 Kotlin AST（替代 grep，避免格式变化全炸）

首版规则（递减 exempt，禁止新增）：

| 规则 | 失败条件 |
|------|---------|
| Floor 读 signalPeers | `FloorAuthorityRoute` / `resolveFloorAuthorityRoute` 读 `signalPeersByModule` |
| Coordinator 写 groupMembers | `TalkbackCoordinator` 内 `groupMembers\s*=` |
| 非白名单写 participants.media | 不在 `MediaRuntime` / `ConferenceParticipantManager` |

**验收**：CI 绿；exempt 计数只减不增。

---

## RO-2: Membership Single Writer

**范围仅 `groupMembers`**（不含 authority、不含 media）。

- Coordinator L1830/L4060 → `GroupMembershipSupport` 专用 mutator
- 引入 `MembershipRuntime` 门面（可先 thin wrapper around Support）
- `TalkbackSession.groupMembers`  setter 改为 `internal` 或仅 Support 可访问

**验收**：RO-1 中 groupMembers 规则绿；现有 Membership 单测绿；**Merge 后再开下一 RO**。

---

## RO-3: Authority Single Writer

- `applyAuthorityBinding(session, id, reason)` 单入口
- `bumpFloorAuthorityEpoch` 保留，为 RO-5 预留 `transport.invalidate()` 钩子

**验收**：authority 赋值点收敛；Merge。

---

## RO-4: TransportRegistry 接口 + Stub

**先定接口，后换实现**（避免 Resolver 反复改签名）。

```kotlin
interface TransportRegistry {
    fun resolve(moduleId: ModuleId): TransportBinding?
    fun bindingEpoch(moduleId: ModuleId): Long
    fun invalidate(moduleId: ModuleId)
}
```

**Stub 实现**（本 RO）：

```kotlin
// DelegatingTransportRegistry — resolve() 仍读 signalPeers，行为不变
```

Floor / dial 路径改为依赖 `TransportRegistry` 接口，**行为零变更**。

**验收**：编译通过；现有 Floor 单测绿；Merge。

---

## RO-5: TransportRegistry 真实迁移

- `TransportManager`：仅 HELLO（验签）、gossip presence、static bootstrap 写入
- `rememberSignalPeer` 不再写 `discoveredByModule`；不再被决策路径读取
- `bumpFloorAuthorityEpoch` → `transportRegistry.invalidate(authority)`
- 红测：污染 `signalPeers[M01]=M02`，Floor 仍经 Registry 打到 M01

**验收**：Stub 替换为真实实现；污染红测绿；Merge。

---

## RO-6: Floor Resolver + 稳定基线验收

- `FloorAuthorityRoute.resolve(authority, endpoint, epoch, transport)` 纯函数
- 修订 ADR-0013
- **M03 PTT 三台验收 + `GroupFloorAuthorityRouteIntegrationTest` 扩展**

本 RO 是「架构收口」与「用户可见 Regression 结束」的汇合点。

---

## RO-7: participants.media（最后做）

**放最后**：影响 Conference / Group / ICE / Mesh，预计几十处，Regression 面最大。

- `MediaRuntime.onIceStateChanged()` 唯一写 `participants.media`
- Conference Reducer 不再直写 media（`PeerReactivated` 发事件）
- Coordinator ICE 回调迁移

**验收**：RO-1 media 规则绿；Meeting + PTT + Conference 回归套件绿。

---

## RO-8+（后续，本 PRD 不阻塞）

| ID | 内容 |
|----|------|
| RO-8 | `leftMemberEndpoints` 单存储 |
| RO-9 | OwnershipContractTest v2（Detekt/AST） |
| RO-10 | Coordinator Facade 第二阶段：禁止 Coordinator 直接碰 session 可变字段 |

---

## Non-Goals

- 本 Sprint 内 Coordinator 全量拆文件（North Star 分步逼近）
- UI / Contacts / 新协议
- 一次性消灭所有 Multi Writer（分 RO Merge）

---

## Success Criteria

| 里程碑 | 标准 |
|--------|------|
| Phase 0 | `runtime-public-api.md` + ownership-matrix Public API 列 |
| 稳定基线 | M03 PTT 有红测或三台可复现 pass/fail 信号 |
| RO-1–3 | Membership/Authority 单 Writer；CI OwnershipTest 绿 |
| RO-4–5 | Floor 经 Registry；污染红测绿 |
| RO-6 | ADR-0013 修订；M03 三台验收 |
| RO-7 | media 单 Writer；全回归绿 |
| 长期 | 四 Runtime 可独立演进；Coordinator 仅编排 |

---

## Risk & Mitigation

| 风险 | 缓解 |
|------|------|
| Refactor 中无法区分新旧 Regression | **允许修阻塞 Regression**；每 RO 独立 Merge |
| Phase 1 过大一次炸 | 拆 RO-1…7，每步 Merge |
| grep OwnershipTest 脆 | v1 grep + exempt；v2 Detekt |
| Coordinator 绕过门面 | Public API + internal session 字段 |
| Registry 晚定义导致 Resolver 反复改 | RO-4 先接口+Stub |

---

## Issue Backlog（修订顺序）

| ID | GitHub | 标题 | Merge 后下一项 |
|----|--------|------|----------------|
| **RO-0** | [#27](https://github.com/wangy4645/android-decentralized-talkback/issues/27) (closed) | Runtime Inventory + Public API 文档 | RO-1 |
| **RO-1** | [#28](https://github.com/wangy4645/android-decentralized-talkback/issues/28) | OwnershipContractTest v1 + CI | RO-2 |
| **RO-2** | [#29](https://github.com/wangy4645/android-decentralized-talkback/issues/29) | Membership Single Writer（groupMembers） | RO-3 |
| **RO-3** | [#30](https://github.com/wangy4645/android-decentralized-talkback/issues/30) | Authority Single Writer | RO-4 |
| **RO-4** | [#31](https://github.com/wangy4645/android-decentralized-talkback/issues/31) | TransportRegistry 接口 + Stub（行为不变） | RO-5 |
| **RO-5** | [#32](https://github.com/wangy4645/android-decentralized-talkback/issues/32) | TransportRegistry 真实迁移 | RO-6 |
| **RO-6** | [#33](https://github.com/wangy4645/android-decentralized-talkback/issues/33) | Floor Resolver 纯函数 + ADR-0013 + M03 验收 | RO-7 |
| **RO-7** | [#34](https://github.com/wangy4645/android-decentralized-talkback/issues/34) | participants.media → MediaRuntime | RO-8+ |

**并行例外**：阻塞性 M03 Regression 可在 RO-0～RO-4 间隙用**最小 patch + 红测**处理，不扩大 scope。

---

## References

- `docs/runtime/ownership-matrix.md`（Phase 0 增补 Public API 列）
- `docs/runtime/runtime-contract.md`
- `docs/runtime/mutation-map.md`
- `docs/runtime/floor-routing-data-flow.md`
- `docs/adr/0013-floor-authority-route.md`

---

## Next Step

1. **RO-0**：补 `runtime-public-api.md` + ownership-matrix Public API 列  
2. **`/to-issues`**：发布 RO-0～RO-7 为 GitHub issues（`ready-for-agent` + `architecture`）  
3. **RO-1** 起：每 issue 新会话 `/implement`，**一步一 Merge**
