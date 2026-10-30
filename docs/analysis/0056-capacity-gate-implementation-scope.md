# ADR-0056 Gate 3 / Gate 4 — Capacity Gate Implementation Scope

## Status

**FROZEN SCOPE** (2026-08-18)
**Implementation PASS** · Closeout: [0056-capacity-gate-closeout.md](./0056-capacity-gate-closeout.md)
**Field NOT AUTHORIZED**

**Parent:** [Capacity Gate Contract K1–K8](./0056-capacity-gate-8-10-contract.md) **PASS**
**Not this track:** ADR-0056 Phase 3 (Health → UI) **FROZEN**

**Purpose:** 冻结 **谁判定、谁拒绝、谁仍是唯一 publish owner**。不是做 8/10 性能，也不是扩 K fixtures。

---

## 目标（一句话）

```text
existing admission + compose
        ↓
ConferenceCapacityGateContract.evaluate(candidate)
        ↓
Authority publish 唯一写入口
        ↓
Rejected → keep previous snapshot
        ↓
no MESH fallback / no peer-pair
```

---

## 1. 谁调用 capacity decision

| Role | Does | Does not |
| ---- | ---- | -------- |
| Coordinator | gather roster facts; call existing `ConferenceAnchorAdmissionPolicy.decide`; request Authority publish | compose capacity; invent edges; MESH fallback after Rejected |
| `ConferenceCapacityGateContract` | evaluate candidate snapshot shape (K1–K8) | publish; mutate; recover |
| `ConferenceTopologyAuthority` | **only** install after evaluate == admitted | second topology writer |
| Recovery / Health / CPP | read snapshot / facts | capacity decision |

```text
Coordinator
    → decide (existing policy)
    → compose candidate (existing 1b contracts)
    → Authority.publish*(candidate)
            ↓
        evaluate(candidate)     ← capacity gate lives here
            ↓
        installAtomic | Rejected
```

Coordinator **不得**在 Authority 之外“先改成 MESH 再 publish”作为容量退路。

---

## 2. Admission policy 是否扩展

**不扩展 API。复用** `ConferenceAnchorAdmissionPolicy`。

已冻结：

```text
threshold = 4   (policy input; Conference: MediaTopologyPolicy.SFU_LITE_CONFERENCE_THRESHOLD_MODULES)
N < 4  → Mesh
N ≥ 4  → Anchor
```

Capacity **不是**第二套 threshold / 第二套选举。

允许的唯一增量：Authority 在 install 前对 **candidate snapshot** 做 `evaluate`。  
禁止：新的 `CapacityAdmissionPolicy.decide` 平行于 anchor policy。

---

## 3. Authority 仍是唯一 publish owner

```text
ConferenceTopologyAuthority = snapshot 唯一写入口
```

`evaluate` 失败 → `PublishResult.Rejected`，**不 install**，previous / current / AuthorizedTransition 事务语义不变。

禁止：

```text
Capacity → 直接写 session.mediaTopology / actualMediaEdges
Health / Recovery / ICE → capacity publish
composeMeshSnapshot(N≥4) 绕过 evaluate 安装
```

K6 的实现含义：N=8 MESH candidate **不得**成为 current snapshot。

---

## 4. Gate failure 行为（冻结）

```text
Rejected
    → 保留 previous snapshot
    → 不 bump meshGeneration
    → 不 ANCHOR→MESH downgrade
    → 不 temporary mesh
    → 不 peer-pair edges
    → 不因 Rejected 改 RecoveryTarget / Health admission
```

```text
capacity pressure ≠ topology rewrite
```

Unchanged（candidate == current 且 shape 合法）仍是 Unchanged。

---

## 5. 明确不做（本 scope 实现时也禁止）

```text
hardware / CPU / temp / latency
field Gate 3 / Gate 4
ADR-0055 UI
R28 / RNA / completion
DEFAULT_MAX_CONFERENCE_MODULES 调参（独立议题）
N>10 产品策略（contract 已 Reject；本刀不扩产品上限）
新 K fixtures
```

---

## 6. Exit Gate — Implementation PASS（授权后）

```text
K1–K8 remain PASS
Authority rejects MESH at N≥4 (K1/K6 on publish path)
star N=8/10 still N−1 (K3/K5)
RecoveryTarget ⊆ star (K4/K8)
Health 不写 topology (K7/K8)
single publish owner inspection
no MESH fallback after Rejected
R28 unchanged
        ↓
Capacity Gate Impl PASS
        ↓
Gate 3 / Gate 4 field still NOT AUTHORIZED
```

---

## 7. 当前门

```text
Capacity Gate                 CLOSED
  closeout: 0056-capacity-gate-closeout.md
Gate 3 / Gate 4 field         NOT AUTHORIZED
ADR-0056 Phase 3 UI           FROZEN
R28 / RNA / Completion        FROZEN
```
