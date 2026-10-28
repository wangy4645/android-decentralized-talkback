# ADR-0056 Phase 1b — Conference Anchor Admission Contract Mapping

## Status

**PHASE 1b COMPLETE / PASS** (2026-08-18) — contract + implementation + unit/integration
**Field NOT AUTHORIZED** · **Phase 2 NOT STARTED**
**Closeout:** [0056-phase-1b-complete.md](./0056-phase-1b-complete.md)

**Parent:** [ADR-0056](../adr/0056-conference-topology-first.md) (v2.1) · [Phase 0.5 mapping](./0056-phase-05-contract-mapping.md) · [CPP contract](./0056-cpp-conference-presence-projection-contract.md) (CLOSED) · [1b-1 scope](./0056-phase-1b-1-topology-authority-facade-scope.md)

**Prerequisite:** Phase 0.5 CONTRACT PASS; Phase 1a + CPP CLOSED

**Purpose:** 冻结 Conference `MESH → ANCHOR` admission、topology snapshot、ActualMediaEdgeSet 与 Presence 的工程边界，为 Phase 2 RecoveryEdgeProvider 留出 seam；本阶段不修改 RecoveryController。

**Field anchor (HOST ≠ ANCHOR):** Run A `267e47cc` (`producer=M02`, HOST=M01 / ANCHOR=M02); Run B `a25590bb` (`producer=M01`, HOST=ANCHOR=M01). See `talkback/logs/cpp-truth-gate-4p-20260818-102714/` + Run B merge.

---

## 1. Normative chain

```text
MembershipAuthority
        ↓
ConferenceTopologyAuthority
        ↓ atomic publish
ConferenceTopologySnapshot
        ↓
Anchor Admission
        ↓
ActualMediaEdgeSet
        ↓
ConferencePresenceSnapshot
        ↓
ParticipantPresenceProjection
        ↓
UI / ConferenceHealth
```

Recovery 接入点只定义，不实现：

```text
ActualMediaEdgeSet
        ↓
RecoveryEdgeProvider
        ↓
AdmittedRecoveryTarget
        ↓
ADR-0022 Recovery Edge / RecoveryAttempt
```

硬规则：

```text
Only authority creates topology truth.
Runtime produces observations.
Presence consumes topology/media facts.
Recovery consumes admitted media topology.
Recovery MUST NOT create topology truth.
```

---

## 2. Phase 1b 六项 Contract Matrix

| Contract | Existing model / code | Writer / Owner | Current state | Phase 1b decision |
| -------- | --------------------- | -------------- | ------------- | ----------------- |
| Anchor admission | `SFU_LITE_CONFERENCE_THRESHOLD_MODULES`, topology selection / relay wiring | 当前分散于 Coordinator / conference setup | 🟡 | 必须收敛为单一 admission decision |
| `ConferenceTopologyAuthority` | 当前无独立 class；Coordinator + participant/anchor paths 分散 | 多处 | 🟡 | facade / transaction boundary；Phase 1 最小落点允许 Coordinator facade |
| `ConferenceTopologySnapshot` | session / roster / anchor / digest 分散 | 多处 | 🟡 | atomic immutable snapshot |
| `anchorId` | existing anchor election / canonical merge | Anchor authority | 🟡 | 唯一 authoritative value |
| `anchorEpoch` | `TopologyDigest.anchorEpoch` | Anchor election / failover | ✅ | 保留，作为 anchor authority lineage |
| `meshGeneration` | `TopologyDigest.meshGeneration` | topology change path | 🟡 | Conference activation 已冻结；Phase 1b 必须明确 bump 与 admission coupling |
| `ActualMediaEdgeSet` | 当前无 canonical set | implicit peer/media state | 🔴 | 新 topology fact；只由 snapshot transaction admit |
| `MediaEdge` | topology-level object；`ConferenceEdgeKey` 是 execution key | TopologyAuthority | 🟡 | 不与 Recovery Edge 混淆 |
| `ConferencePresenceSnapshot` | CPP contract | topology/presence producer | ✅ | downstream only |
| CPP | `ConferencePresenceProjector` | projector | ✅ | Phase 1b consumer，不反向参与 admission |
| `RecoveryEdgeProvider` | not implemented | — | ⏸ | Phase 2 seam only |
| Recovery Controller | ADR-0022 existing | Controller | ✅ | 本阶段不修改 |

---

## 3. Contract 1 — Anchor admission 唯一决策点

### 3.1 Admission 必须是 topology decision

禁止：

```text
participant count
        ↓
某个 Coordinator 分支直接启动 relay
```

正确：

```text
Membership / conference facts
        ↓
ConferenceTopologyAuthority
        ↓
topologyMode = MESH | ANCHOR
anchorId
anchorEpoch
meshGeneration
ActualMediaEdgeSet
```

### 3.2 Threshold 不是最终 authority

现有：

```text
SFU_LITE_CONFERENCE_THRESHOLD_MODULES = 4
```

只允许作为 **admission policy input**。

不能：

```text
if (count >= 4) {
    topology = ANCHOR
}
```

同时另一个路径：

```text
if (anchor != null) {
    relay = true
}
```

两边各自做一次 admission。

最终必须只有一个 canonical result：

```text
ConferenceTopologySnapshot.topologyMode
```

---

## 4. Contract 2 — HOST ≠ ANCHOR

**Phase 1b 第一优先级 fixture.**

现场已证明：

```text
HOST = M02
ANCHOR = M01
```

禁止任何隐含规则：

```text
host == anchor
```

或者：

```text
host determines media topology
```

### 4.1 Authority composition

**Membership**

```text
MembershipAuthority
    → canonical roster
```

**Anchor**

```text
ConferenceTopologyAuthority
    → anchorId
    → anchorEpoch
```

**Media topology**

```text
ConferenceTopologyAuthority
    → ActualMediaEdgeSet
```

**Presence**

```text
ConferencePresenceSnapshot
    → CPP
```

**UI**

```text
CPP only
```

因此：

```text
HOST ≠ ANCHOR
```

是合法的一等状态，而不是 exception。

---

## 5. Contract 3 — Snapshot 原子性

`ConferenceTopologySnapshot` 最低包含：

```text
conferenceId
rosterEpoch
anchorEpoch
anchorId
meshGeneration
topologyMode
members
actualMediaEdges
```

所有这些字段必须来自**同一次 publish transaction**。

禁止出现：

```text
anchorId = M01
anchorEpoch = 9
meshGeneration = old
actualMediaEdges = new
```

这种半拓扑。

---

## 6. Snapshot 发布规则

建议逻辑：

```kotlin
publishConferenceTopologySnapshot(
    membershipFacts,
    anchorFacts,
    mediaAdmissionFacts
)
```

内部一次计算：

```text
newSnapshot =
    topologyMode
    anchorId
    anchorEpoch
    meshGeneration
    members
    actualMediaEdges
```

然后：

```text
atomic publish(newSnapshot)
```

Consumer 只能读取 snapshot。

---

## 7. Contract 4 — `ICE_CONNECTED ≠ MediaEdge ADMITTED`

Phase 1b 第二重要的不变量之一。

允许：

```text
ICE_CONNECTED
    = runtime observation
```

但必须经过 topology authority：

```text
ICE_CONNECTED
      ↓
media admission evaluation
      ↓
MediaEdge ADMITTED
```

因此：

```text
ICE_CONNECTED
≠
ActualMediaEdgeSet membership
```

反过来也成立：

> 一个 admitted edge 可以短暂处于 ICE reconnecting，而它仍然属于 current topology。

否则 topology 会随着 transient ICE 状态剧烈抖动。

---

## 8. ActualMediaEdgeSet

Anchor topology 下：

```text
Anchor = M01

ActualMediaEdgeSet =
{
    M01-M02,
    M01-M03,
    M01-M04
}
```

不存在：

```text
M02-M03
M02-M04
M03-M04
```

仅仅因为 roster 中存在这些 participant。

因此：

```text
ActualMediaEdgeSet
    ≠ all member pairs
```

而是：

```text
topology admission projection
```

---

## 9. Contract 5 — `meshGeneration` / `anchorEpoch` 联动

Phase 0.5 已经冻结：

```text
rosterEpoch
anchorEpoch
meshGeneration
obligationGeneration
```

Phase 1b 必须明确：

**MESH → ANCHOR**

```text
topologyMode changes
    → meshGeneration +1
```

**Anchor failover**

```text
anchorEpoch +1
meshGeneration +1
```

**Same-anchor topology edge change**

```text
ActualMediaEdgeSet changes
    → meshGeneration +1
```

**ICE transient**

```text
ICE restart
candidate refresh
signal retry
    → no generation bump
```

---

## 10. Generation validity

当前 topology snapshot：

```text
G = (anchorEpoch, meshGeneration)
```

比较规则：

**Anchor epoch**

```text
snapshot.anchorEpoch < current
    → invalid

snapshot.anchorEpoch == current
    → same authority lineage

snapshot.anchorEpoch > current
    → newer authority; trigger reconciliation
```

**meshGeneration**

同一 `anchorEpoch` 下：

```text
higher meshGeneration
    → newer topology

lower meshGeneration
    → stale topology

equal
    → same topology generation
```

但：

```text
meshGeneration
```

不能独立决定 anchor authority。

---

## 11. Anchor failover cutover

必须保证：

```text
old:
anchorEpoch = 10
anchor = M01
meshGeneration = 31
```

切换：

```text
new:
anchorEpoch = 11
anchor = M02
meshGeneration = 32
```

之后：

```text
old generation
    → invalid for new topology admission
```

允许 transition 期间旧 edge 存活，但必须落在：

```text
AuthorizedTransition
```

而不是：

```text
wall-clock grace
```

---

## 12. Contract 6 — ActualMediaEdgeSet → Presence

CPP 已经关闭，因此 Phase 1b 不能重新设计 Presence。

唯一允许：

```text
TopologySnapshot
    ↓
ActualMediaEdgeSet
    ↓
ConferencePresenceSnapshot
    ↓
CPP
```

CPP 不得：

```text
create edge
admit topology
change anchor
```

---

## 13. HOST ≠ ANCHOR 的完整例子

```text
M02 = HOST
M01 = ANCHOR

Membership:
    M01 M02 M03 M04

Anchor:
    M01 / epoch=12

ActualMediaEdgeSet:
    M01-M02
    M01-M03
    M01-M04

Presence:
    produced from anchor-side observation

CPP:
    membership from topology
    mediaRelation from presence
```

UI 不得因为：

```text
HOST=M02
```

就把：

```text
M02
```

当成 media authority。

---

## 14. Phase 1b 禁止项

明确禁止：

```text
❌ RecoveryController modification
❌ RecoveryEdge lifecycle changes
❌ RecoveryAttempt changes
❌ GROUP topology changes
❌ ConferenceAudioBus changes
❌ CPP contract changes
❌ UI state changes
❌ receive-side presence uplink
❌ timeout-based topology authorization
❌ participant-pair fallback under Anchor
```

尤其：

> **不要为了让 4 人 Anchor 测试成功，在 recovery 层补一个 edge。**

如果某个 edge 不属于 `ActualMediaEdgeSet`，正确动作是修 topology admission，不是修 recovery。

---

## 15. Phase 2 seam

本阶段只冻结接口关系：

```text
ActualMediaEdgeSet
        ↓
RecoveryEdgeProvider
        ↓
AdmittedRecoveryTarget
        ↓
ConferenceEdgeKey
        ↓
existing ADR-0022 Recovery Edge
```

Phase 1b 不实现：

```text
RecoveryEdgeProvider
```

但 topology snapshot 必须已经包含足够信息，使 Phase 2 不需要重新推导：

```text
local
remote
anchorEpoch
meshGeneration
edge identity
transition authorization
```

---

## 16. Phase 1b Contract Fixtures

在任何实现授权之前，至少需要这些纯 contract tests：

**F1 — 4p cold-start Anchor**

```text
members=4
anchor=M01
topology=ANCHOR
edges=3
```

**F2 — HOST ≠ ANCHOR**

```text
host=M02
anchor=M01
```

必须得到同一 topology snapshot 语义。

**F3 — ICE connected but not admitted**

```text
ICE=M02-M03 CONNECTED
```

但 topology 不允许 M02-M03：

```text
ActualMediaEdgeSet
    不包含 M02-M03
```

**F4 — topology change**

```text
edge set changes
→ meshGeneration +1
```

**F5 — anchor failover**

```text
anchorEpoch +1
meshGeneration +1
old topology invalid
```

**F6 — transient ICE**

```text
ICE restart
→ no generation bump
→ edge remains admitted
```

**F7 — atomic snapshot**

不得出现：

```text
new anchor
+
old edges
```

**F8 — CPP consumption**

Topology snapshot 改变后：

```text
Presence
    follows snapshot
```

但：

```text
CPP does not mutate topology
```

---

## 17. Phase 1b Exit Gate

只有满足：

```text
F1–F8 PASS
```

才能进入：

```text
PHASE 1b IMPLEMENTATION AUTHORIZED
```

并且 Implementation 仍然分阶段：

```text
1b-1 TopologyAuthority facade
1b-2 atomic ConferenceTopologySnapshot
1b-3 Anchor admission
1b-4 ActualMediaEdgeSet
1b-5 4p admission integration
1b-6 4p field Gate
```

不要一次性把 1b 全部实现。

---

## 18. 核心结论

Phase 1b 必须第一次把 `Anchor admission → ActualMediaEdgeSet` 变成一个可验证的、原子发布的事实链。

一旦 F1–F8 全部通过，才真正有资格进入 `ConferenceTopologyAuthority` 的实现。

---

## 当前门状态

```text
Phase 0.5                    ✅ CLOSED
Phase 1a                     ✅ CLOSED
CPP                          ✅ CLOSED
4p Presence Truth            ✅ COMPLETE (split-capture)

Phase 1b                     ✅ COMPLETE / PASS
                             (contract + impl + unit/integration; NOT field)
                             closeout: 0056-phase-1b-complete.md
Phase 1b Field               🚫 NOT AUTHORIZED
Phase 2 Recovery             🚫 NOT STARTED
                             2-0 contract REVIEW — [0056-phase-2-0-recovery-binding-contract.md](./0056-phase-2-0-recovery-binding-contract.md)
6/8/10 Capacity              🚫 NOT AUTHORIZED
Bus / Health                 FROZEN
```
