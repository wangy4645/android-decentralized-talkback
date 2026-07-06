# ADR-0015: Runtime Governance — Transition Coordination and Operation Gating

## Status

Proposed (2026-07-06)

## Context

Soak testing on three devices (CH-01) shows **non-deterministic failures after the same user flow**
(Meeting → PTT → Meeting → Single Call). Symptoms vary by run:

| Symptom | Subsystem |
|---------|-----------|
| Floor request not reaching authority | Floor / Routing |
| Second Meeting invite not delivered | Conference signaling |
| BUSY / stale channel mode | Channel lifecycle |
| PTT works after Meeting (recovery path) | Group runtime reconstruction |

These are not independent bugs. They share a structural cause:

> **After a runtime transition, subsystems converge to different end states because there is no
> provable “transition complete” contract and no unified admission control.**

Existing observability (`TOPOLOGY_SNAPSHOT`, `MEETING_RECOVERY`, `FLOOR_REQUEST_RECV`) proves
*what happened* but cannot prove *whether it is safe to start the next operation*.

This ADR introduces a **lightweight governance layer** without a heavyweight Transition Manager
that orchestrates runtime execution.

### North-star statement

> **Provable Runtime Transition Convergence** — transitions are per-channel, traceable, and
> gateable; the system can answer “is it safe to execute operation X on channel Y now?” with a
> machine-readable decision.

### Non-negotiable principles

1. **The coordinator does not own transition execution; it owns transition observability and
   admission control.**
2. **Runtime owns readiness; the coordinator only aggregates it (no authoritative cache).**
3. **All externally initiated operations must enter through a single OperationGate.**
4. **Only a Runtime Owner may declare `beginTransition`; Gate and UI must not lazily create
   transitions.**
5. **Any new external Operation must declare Capability dependencies in OperationPolicy before
   wiring into OperationGate.**

## Decision

### Architecture (four layers)

```
Runtime (owners)
    │  declare transition intent; own facts
    ▼
CapabilityProbe (v1: Adapter; read-only)
    │  readiness per Capability
    ▼
TransitionCoordinator (observer + gatekeeper)
    │  assign transitionId; aggregate snapshot; timer; metrics
    ▼
OperationGate + OperationPolicy + TransitionPolicy (governance domain)
    │  admission decision
    ▼
Operations (PTT / Meeting / Single Call / Bootstrap)
```

**TransitionCoordinator is NOT a Transition Manager.** It never calls Runtime mutators, never
decides business sequencing, and never caches readiness as source of truth.

### Capability-based admission (not transition-type-based)

Operations declare **required Capabilities**, not “control vs media transition”:

```kotlin
sealed interface Capability {
    data object Membership : Capability
    data object Routing : Capability
    data object Authority : Capability
    data object Conference : Capability
    data object Media : Capability
    // DirectoryCapability — add when Single Call enters Gate
}
```

Gate evaluates:

```
Operation → OperationPolicy.requires(capabilities)
         → CapabilitySnapshot (from Coordinator + Probes)
         → GateDecision
```

Transition types (`Control` vs `Media`) remain useful for **lifecycle description and metrics**,
but **Gate never branches on transition type**.

### OperationPolicy (domain policy, not registry)

`OperationPolicy` is the **single source of truth** for operation dependencies and admission
metadata (priority, timeout hints, degraded rules — v2).

Example (illustrative):

```kotlin
// Policy v1 — versioned with domain model (ADR-0015), not Runtime implementation
PTT.requires(Membership, Authority, Routing)
MEETING_INVITE.requires(Membership, Routing, Conference)
SINGLE_CALL.requires(Directory, Routing)  // when wired
```

Coordinator and Gate **do not know** what PTT or Meeting means — only Policy + Snapshot.

### GateDecision (v1: strict ALLOW / BLOCK)

```kotlin
sealed interface GateDecision {
    data class Allow(val transitionId: TransitionId) : GateDecision()

    data class Blocked(
        val primaryReason: BlockReason,
        val additionalReasons: List<BlockReason>,
        val category: BlockCategory,
        val transitionId: TransitionId,
        val blockingCapability: Capability?,
        val retryAfter: Duration?
    ) : GateDecision()
}

enum class BlockCategory { READINESS, POLICY }
```

**Block reason taxonomy** (responsibility boundary):

| Category | Meaning | Owner |
|----------|---------|-------|
| `READINESS` | System not ready yet; likely self-heals | Runtime convergence |
| `POLICY` | System ready but operation not allowed | Product / governance |

Examples:

- `READINESS`: `ROUTING_NOT_READY`, `AUTHORITY_NOT_READY`, `MEMBERSHIP_NOT_READY`
- `POLICY`: `COOLDOWN_ACTIVE`, `RATE_LIMITED`, `CHANNEL_BUSY`, `ROLE_RESTRICTED`,
  `TRANSITION_IN_PROGRESS`

**Primary reason selection** (v1):

1. Collect **all** blocking reasons (never `return` on first match in a loop).
2. `GatePriorityResolver` picks **one** primary using a **global, documented** ordering.
3. Remaining reasons → `additionalReasons`.

Consumption:

- **UI** → `primaryReason` only (mapped to user copy separately).
- **Metrics / SLI** → `primaryReason` only.
- **Logs / debug** → primary + additional.

**v1 priority default:** `READINESS` reasons rank above `POLICY` reasons (system truth before
product shield). Within each category, order is fixed in `GatePriorityResolver` / Policy —
not derived from `requires()` declaration order.

`transitionId` is **always present**; use explicit `TransitionId.NONE` when no active transition.

v1 does **not** introduce `ALLOW_WITH_DEGRADED`.

### Transition scope and lifecycle

**Scope:** per-channel only in v1.

```
transitionKey = (channelId, transitionId)
activeTransition[channelId] ≤ 1
```

Cross-channel and global transitions are **forbidden** in v1.

**Declaration:** only Runtime Owner calls `coordinator.beginTransition(trigger, channelId)`.
Gate and UI must not create transitions.

**Overlap policy:**

- New `beginTransition` while ACTIVE → **Reject** (log `TRANSITION_IN_PROGRESS`).
- Escape hatch → Runtime Owner calls `abortTransition(channelId, reason)` → old state `ABORTED`
  → then `beginTransition`.
- **No silent supersede** in v1.

**Triggers** (non-exhaustive; metrics dimension):

`MEETING_END`, `MEETING_START`, `GROUP_BOOTSTRAP`, `IDENTITY_REBOUND`, `HOST_FAILOVER`,
`REJOIN`, `NETWORK_CHANGE`, …

**Phases** (minimal state machine):

```
IDLE → PREPARING → RECONCILING → READY
                  ↘ TIMED_OUT / FAILED / ABORTED
```

Coordinator tracks phase; Runtimes do not report arbitrary phase graphs in v1.

### TransitionPolicy (timeouts)

Timeouts belong to **TransitionPolicy** (governance domain), not Coordinator logic.

Coordinator **executes timers only**.

| Terminal state | Semantics |
|----------------|-----------|
| `READY` | Required capabilities satisfied for transition completion |
| `TIMED_OUT` | Duration elapsed without convergence (time failure) |
| `FAILED` | Explicit capability/runtime failure (semantic failure) |
| `ABORTED` | Owner explicit abort |

**On `TIMED_OUT`:**

- Clear active transition slot (allow new `beginTransition`).
- **Does NOT** imply capabilities are ready.
- **Does NOT** auto-pass Gate.

**Dual-layer admission model:**

- Policy layer: transition in progress, cooldown, role, etc.
- Readiness layer: capability snapshot.

> Gate does not trust time; it trusts state.

Illustrative initial timeouts (tune from soak P95/P99):

| Trigger | Timeout (v1 starting point) |
|---------|---------------------------|
| `MEETING_END` | 12s |
| `MEETING_START` | 8s |
| `GROUP_BOOTSTRAP` | 10s |
| `IDENTITY_REBOUND` | 15s |

### CapabilityProbe (v1: Adapter)

```kotlin
interface CapabilityProbe {
    val capability: Capability
    fun readiness(channelId: String): CapabilityReadiness
}
```

v1 implements **Adapter Probes** under `governance/capability/` that **delegate** to existing
Runtime state. Rules:

- Probes are **read-only**, no side effects.
- Readiness definitions live **in the Probe file**, not in Coordinator or Gate.
- Coordinator **must not** read `TalkbackSession` fields directly — only Probes.
- Phase 2 may move Probe implementations into Runtime Public API **without changing the
  interface**.

**v1 minimum probe set:** Membership, Routing, Authority, Conference, Media (observe-only for
most operations).

## Consequences

### Positive

- Same user flow can be blocked with **stable, attributable** reasons (`transitionId` + primary
  reason).
- Metrics become actionable: completion rate, timeout rate, P95 duration **per trigger**.
- Phase 1 can ship with **minimal Runtime mutation** (Gate + Coordinator + Adapter Probes).
- Aligns with Runtime Ownership Refactor (Epic #50 / RO PRD): governance sits above Runtimes,
  not inside Coordinator god-path.

### Negative / trade-offs

- Additional indirection for every external operation entry.
- Adapter Probes may temporarily duplicate readiness logic already implied by
  `GroupRuntimeHealthProjector` — must converge definitions over time.
- Reject-on-overlap may surface as “try again” UX until recovery completes or times out.

### Out of scope (v1)

- `ALLOW_WITH_DEGRADED`
- Cross-channel / global transitions
- Silent supersede / merge
- RuntimeRevision / TransitionEpoch barriers (Phase 3)
- Replacing `ChannelModeFsm` — Gate complements it

## Implementation plan

### Phase 1 (≈2 weeks) — observe, block, measure

| Deliverable | Notes |
|-------------|-------|
| `OperationPolicy` v1 | PTT, MEETING_INVITE, SINGLE_CALL (stub), BOOTSTRAP |
| `TransitionPolicy` v1 | Triggers + timeouts |
| `TransitionCoordinator` | begin / abort / snapshot / timer |
| `OperationGate` | `canStart(op, channelId)` |
| Adapter Probes (5) | Membership, Routing, Authority, Conference, Media |
| `GatePriorityResolver` | Global primary-reason ordering |
| Wire entry points | `pressPtt`, `joinMeeting`, `ensureChannelSession`, unicast place |
| Structured logs | `GATE_DECISION`, `TRANSITION_BEGIN/COMPLETE/TIMEOUT` |
| Metrics hooks | completion rate, timeout rate, duration histograms |

**Functional acceptance**

- Gate coverage on all external operation entry points = 100%
- Every transition has `transitionId`
- Every `Blocked` has machine-readable `primaryReason` + `category`
- No silent transition overlap

**Soak acceptance (target)**

```
1000× (Meeting → PTT → Meeting → Single Call → Meeting)
```

- Transition timeout rate < 0.1%
- Gate reject rate < 1% (excluding intentional READINESS during recovery)
- Unknown / unclassified failure = 0

### Phase 2

- Runtime-owned CapabilityProbe implementations (move adapters inward)
- Runtimes report `FAILED` explicitly into Coordinator
- Transition completion = contract, not inference

### Phase 3

- `RuntimeRevision` / `TransitionEpoch` / barriers if Phase 1/2 metrics still show overlap bugs

## References

- `CONTEXT.md` — Runtime Governance glossary
- `docs/prd-runtime-ownership-refactor.md` — Epic #50 / RO backlog
- `docs/adr/0008-group-runtime-health-projection.md` — observability projection (not admission)
- `docs/adr/0013-floor-authority-route.md` — Routing capability substrate
- Issue #51 — floor routing after transition (symptom, not root epic)

## Related work

| Item | GitHub |
|------|--------|
| Epic | [#50](https://github.com/wangy4645/android-decentralized-talkback/issues/50) Runtime Transition Consistency |
| RO-G1 Phase 1 implementation | [#52](https://github.com/wangy4645/android-decentralized-talkback/issues/52) |
| RO-G2 Runtime-owned probes | [#53](https://github.com/wangy4645/android-decentralized-talkback/issues/53) |
| RO-G3 Soak KPIs | [#54](https://github.com/wangy4645/android-decentralized-talkback/issues/54) |
| Symptom: floor routing | [#51](https://github.com/wangy4645/android-decentralized-talkback/issues/51) |

## Code skeleton (Phase 1)

Package `com.talkback.governance` in `android-board-talkback`:

- `capability/` — Capability, CapabilityProbe, CapabilitySnapshot
- `gate/` — OperationGate, OperationPolicy, GatePriorityResolver, GateDecision
- `transition/` — TransitionCoordinator, TransitionPolicy
- `GovernanceObservabilityLog` — GATE_DECISION / TRANSITION_* log helpers

Unit tests: `src/test/java/com/talkback/governance/`
