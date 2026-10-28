# ADR-0056 Gate 3 / Gate 4 — Capacity Gate CLOSED

## Status

**CAPACITY GATE — CLOSED** (2026-08-18)

**Scope of CLOSED:** contract (K1–K8) + Authority publish-path implementation + unit evidence.

**Not claimed:** Gate 3 field (8p) · Gate 4 field (10p) · hardware · ADR-0056 Phase 3 UI.

**Parent:** [K1–K8 contract](./0056-capacity-gate-8-10-contract.md) · [Implementation scope](./0056-capacity-gate-implementation-scope.md) · [ADR-0056](../adr/0056-conference-topology-first.md) Decision 4

---

## What CLOSED means

Architecture constraint is installed on the only topology write path:

```text
AdmissionPolicy.decide
        ↓
candidate snapshot
        ↓
CapacityGate.evaluate
        ↓
Authority installAtomic
        OR
    Rejected (current / previous / transition unchanged)
```

Capacity is a **gate**, not a producer. Not a second admission authority.

```text
N≤3  MESH, ActualMediaEdgeSet = ∅
N≥4  ANCHOR star, |edges| = N−1
N=8  Gate 3 shape (7 edges)
N=10 Gate 4 shape (9 edges)
MESH at N≥4  Rejected — no temporary mesh / peer-pair fallback
```

---

## Acceptance

| Layer | Result |
| ----- | ------ |
| K1–K8 contract | PASS (`ConferenceCapacityGatePhaseKFixturesTest` 8/8) |
| K1 publish path (N=4 MESH not installed) | PASS |
| K6 publish path (N=8 MESH cannot become current) | PASS |
| N=8/10 star install | PASS |
| N=3 MESH still admitted | PASS |
| Rejected keeps previous/current/transition | PASS |
| RecoveryTarget ⊆ ActualMediaEdgeSet | unchanged (K4/K8; Phase 2) |
| Health aggregate only | unchanged (K7/K8; Phase 2-5) |

Tests: `ConferenceCapacityGateAuthorityPublishTest`

---

## Publish path

```text
ConferenceTopologyAuthority.installAtomic
    rejectIfCapacityInvalid(candidate)   // evaluate only here
    → compose AuthorizedTransition
    → install
```

`ConferenceCapacityGateContract.evaluate` in `src/main`: **Authority only**.

Coordinator / Health / Recovery **do not** call evaluate or mutate topology for capacity.

---

## Static inspection

```text
CapacityGate.evaluate     ConferenceTopologyAuthority only
Coordinator → capacity → topology mutation    ABSENT
Health → capacity adjustment                  ABSENT
Recovery → capacity adjustment                ABSENT
Rejected → MESH downgrade install             ABSENT
```

---

## Non-scope (still frozen)

```text
Gate 3 field (8p) / hardware curve
Gate 4 field (10p) / Tests A–F
ADR-0056 Phase 3 Health → UI
R28 / RNA / completion
WiFi recovery main chain
N>10 product policy
new K fixtures
```

---

## Formal door

```text
CAPACITY GATE — CLOSED

Contract        PASS
Implementation  PASS
Publish path    PASS
Static gate     PASS

Gate 3 / Gate 4 Field   NOT AUTHORIZED
ADR-0056 Phase 3 UI     FROZEN
R28 / RNA / Completion  FROZEN
```

Field 8p / 10p and Phase 3 UI are **separate authorizations**. Do not mix into this closeout.
