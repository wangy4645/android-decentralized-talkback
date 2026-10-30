# ADR-0056 Phase 3 — ConferenceHealth → UI Scope

## Status

**PHASE 3 UI — COMPLETE / PASS** · Closeout: [0056-phase-3-health-ui-closeout.md](./0056-phase-3-health-ui-closeout.md)
**Contract:** [U1–U8](./0056-phase-3-health-ui-contract.md) **PASS**
**Field NOT AUTHORIZED**

**Parent:** [ADR-0056](../adr/0056-conference-topology-first.md) Decision 5 · [Phase 2-5 Health](./0056-phase-2-5-health-projection-contract.md) · [Capacity Gate CLOSED](./0056-capacity-gate-closeout.md)

**Not this track:** Gate 3/4 field · topology / capacity / RecoveryEdgeProvider · R28 · ADR-0055 Track P/O

**Purpose:** 冻结 **用户可见房间态** 只读 `ConferenceHealth`，不再用 per-edge recovery 当房间 ONLINE/SYNCING 权威。

---

## 为何先做这一刀（不是 8p field）

```text
Health projection     已存在（2-5 PASS）
Room UI               仍读 ConferenceRuntimePhase / edgeRecovering
INV-056-14            尚未接到用户可见层
```

8p field 需要 8 机 + 硬件曲线，且 **不改架构**。当前缺口是：Health 已算出 `MEDIA_USABLE`，UI 还可能被 obligation 拉成 SYNCING。

Gate 3 field 保持独立授权。

---

## 目标（一句话）

```text
ConferenceHealth.mediaUsable == true
        ↓
user-facing ONLINE
```

```text
recovery in-flight / obligationOpen
        ↓
MUST NOT alone force room SYNCING
```

UI 是 **Health 的只读投影**，不是 topology / recovery / capacity 的 writer。

---

## 1. 谁读、谁写

| Artifact | Writer | Reader |
| -------- | ------ | ------ |
| `ConferenceTopologySnapshot` | Authority only | Health binder (fact) |
| `ConferenceHealth` | Binder `project()` only | UI / presentation |
| Room ONLINE / NOT_ONLINE | derived from `mediaUsable` | ViewModel / display resolver |
| Edge recovering | Recovery Controller | diagnostic / peer chrome only |

禁止：

```text
UI → topology
UI → ActualMediaEdgeSet
UI → AdmittedRecoveryTarget
UI → CapacityGate
ViewModel → 猜 MEDIA_USABLE
```

---

## 2. 投影链（唯一合法）

```text
ConferenceHealthBinder.project()
        ↓
ConferenceHealth { mediaUsable, userFacingOnline, recoveryInFlight, ... }
        ↓
ConferenceHealthUiProjection   (Phase 3 contract；未授权前不写实现)
        ↓
room-facing ONLINE | NOT_ONLINE
```

冻结映射（不新增中间房间态机）：

```text
mediaUsable == true   →  ONLINE
mediaUsable == false  →  NOT_ONLINE
```

`DEGRADED` 仅当 **MEDIA_USABLE 确实受损**（Decision 5）。Phase 3 **不得**用 `edgeRecovering` 单独制造房间 DEGRADED/SYNCING。

`recoveryInFlight`：可作诊断 / 边栏，**不得**覆盖 `userFacingOnline`。

---

## 3. 与现有 Runtime 的关系

`ConferenceRuntimeState` / `ConferenceRuntimeProjector`（ADR-0020/0021）继续可作 **lifecycle / control-plane**。

Phase 3 房间可用性权威改为：

```text
room usability  = ConferenceHealth
lifecycle phase = ConferenceRuntimeState   (不得单独否决 ONLINE when mediaUsable)
```

禁止把 `phase == RECOVERING` 或 `edgeRecovering` 再映射成房间 SYNCING，如果 `mediaUsable == true`。

ADR-0055 Track P/O **不在本 scope 打开**。

---

## 4. 建议 fixtures（签收 scope 后再写；现不扩测）

| ID | 证明 |
| -- | ---- |
| U1 | `mediaUsable` → UI ONLINE |
| U2 | 无可用 admitted media → 非 ONLINE |
| U3 | `recoveryInFlight` + 可用 media → 仍 ONLINE |
| U4 | MESH 不套 ANCHOR 房间 ONLINE |
| U5 | UI 投影不写 snapshot / 不产 recovery target |
| U6 | `ConferenceRuntimePhase.RECOVERING` 不得单独否决 ONLINE |
| U7 | peer recovering chrome ≠ 房间 SYNCING |
| U8 | Health null / 无 snapshot → 非 ONLINE，不 invent topology |

---

## 5. 明确不做

```text
Gate 3 / Gate 4 field
topology / capacity / Authority 改动
RecoveryEdgeProvider / R28 / RNA / completion
ADR-0055 Track P/O
UVCP rewrite
新的房间状态枚举（除 ONLINE / NOT_ONLINE）
```

---

## 6. Exit（授权实现后）

```text
U1–U8 PASS
UI 只读 ConferenceHealth
INV-056-14 在 UI 层成立
topology / recovery / capacity 文件 0 行为改动
        ↓
Phase 3 UI Impl PASS
        ↓
Gate 3 field still NOT AUTHORIZED
```

---

## 7. 当前门

```text
Capacity Gate              CLOSED
ADR-0056 Phase 3 UI Scope  FROZEN (this doc)
Phase 3 UI Impl            NOT AUTHORIZED
Gate 3 / 8p Field          NOT AUTHORIZED
Gate 4 / 10p Field         NOT AUTHORIZED
R28 / RNA / Completion     FROZEN
```
