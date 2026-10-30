# CPP Reconnecting Semantics Contract

**ID:** cpp-reconnecting-semantics-contract  
**Date:** 2026-08-22  
**Version:** v0.1  
**Type:** Architecture Contract · CPP / UI fuse  
**Status:** **FROZEN (Q1–Q3)** · no implementation · no lab authorization

**Prerequisite:** [CFC-1 Phase A DONE](./conference-native-failure-containment-phase-a-authorization.md) · [Failure Domain Contract](./conference-failure-domain-contract.md) · [CPP Per-Edge Media Fact Contract](./cpp-per-edge-media-fact-contract.md) · [ADR-0022 R27′-B](../adr/0022-recovery-completion-ownership.md)

**Field RCA:** `talkback/logs/cfc1-partial-field-20260822-101900/` — CFC-1 E∧D telemetry PASS; UI false-RECONNECTING from CPP `recoveringPeers` / mapper, not recovery controller.

**Out of scope:** CFC-1 classifier · Phase A authorization · B2-1 lease · topology · L3/L4 · native partition.

---

## Problem (closed RCA)

```text
CFC-1 failure projection        PASS
CPP recovering projection       legacy mixed semantics
two planes                      not fused at UI
```

Observed chain (bug):

```text
CppStarEdgeView.usable=false
    → reconnecting=true
    → reconnectingModuleIds
    → recoveringPeers
    → RECONNECTING
```

Parallel correct chain (ignored by main UI path):

```text
DOMAIN_BLOCKED
    → VISIBLE_DOMAIN_BLOCKED
    (not consumed by CPP presence / avatar bind)
```

`ConferenceEdgeRecoveryController` was **not** root cause (`controllerEdgeRecovering=false` while `inRecoveringPeers=true`).

---

## Frozen semantic axes

Three orthogonal axes — **must not collapse**:

| Axis | Meaning | Authority |
|------|---------|-----------|
| **Edge usability** | Star edge / media not yet usable | `PerEdgeMediaUsabilityFact` / `mediaRelation` |
| **Recovery obligation** | Active recovery window | `ConferenceEdgeRecoveryController` → `EdgeRecoveryFacts` |
| **Failure terminal** | L1/L2 classified terminal | `failureTerminalsByModuleId` → L-participant `displayState` |

```text
edge unusable          ≠ recovering
failure terminal       ≠ recovering
failure terminal       ≠ joining
```

---

## Q1 — `recoveringPeers` supply (FROZEN)

```text
recoveringPeers
= EdgeRecoveryFacts.recoveringRemoteModuleIds
∩ canonicalRoster
```

**Forbidden:**

```text
PerEdgeMediaFactContract.reconnecting
    → reconnectingModuleIds
    → recoveringPeers
```

`!usable` affects **only** `mediaRelation` / joining semantics — **not** `recoveringPeers`.

Aligns with ADR-0022 R27′-B: `recoveringPeers` MUST derive from `EdgeRecoveryFacts`, not ICE / HELLO alone.

**Implementation note (known drift):** `TalkbackCoordinator.projectConferencePresenceFromTopologySnapshot` currently unions `observed.reconnectingModuleIds` — **contract violation**; fix is in scope of impl authorization, not CFC-1.

---

## Q2 — Failure terminal → avatar/UI (FROZEN)

**Fusion point:** UI fuse layer (ViewModel bind). **Not** inside `ConferencePresenceProjection`.

```text
CPP (membership / media / recoveringPeers)
        +
visibleParticipants.failure displayState
        ↓
UI fuse layer
        ↓
avatar / endpoint / network presentation
```

**Precedence:**

```text
classified failure terminal present
    → failure projection wins
    → skip JOINING / RECONNECTING / recoveringPeers derivation for that module

no failure terminal
    → CPP + EdgeRecoveryFacts (Q1)
```

**Endpoint mapping (no new `EndpointStatus`):**

```text
VISIBLE_EDGE_FAILED      → DEGRADED
VISIBLE_DOMAIN_BLOCKED   → DEGRADED
```

**Forbidden for failure terminal:**

```text
✗ recoveringPeers
✗ RECONNECTING
✗ CONNECTING
✗ invisible
```

`ConferenceEndpointStatusMapper` mapping `VISIBLE_*` → `RECONNECTING` is **contract violation**.

**Implementation note:** `TalkViewModel.buildEndpointList` currently uses `renderFromProjection(CPP-only)` — must fuse `visibleParticipants` at bind time.

---

## Q3 — `joiningHint` / Meeting pill (FROZEN)

Aggregate hints **must** obey the same failure priority at UI fuse.

**`joiningHint` candidate set:**

```text
joining candidate
= JOINED
∧ ¬mediaConnected
∧ ¬recoveringPeers          (Q1: obligation only)
∧ ¬classifiedFailureTerminal
```

**Meeting pill:**

```text
recovering=[…]       = recoveringPeers (diagnostic / obligation only)
connectingHint       = fused joiningHint (same exclusion rules)
```

**Forbidden for failure terminal:**

```text
✗ joiningHint ("M04 joining...")
✗ recoveringPeers membership (without obligation)
✗ failure-specific aggregate pill copy (this phase)
```

Per-avatar `DEGRADED` carries failure UX; room-level failure aggregate hint is **deferred**.

---

## User-visible semantics (frozen)

```text
failure terminal
    → per-avatar DEGRADED

recovery obligation
    → recoveringPeers / RECONNECTING

not connected, no failure, no recovery
    → JOINING
```

---

## Field replay (expected after impl)

Session `b05588db-1d7e-4e68-92a7-866bf39bd029` (M01 anchor), post CFC-1 E∧D:

```text
M03 EDGE_FAILED
    → avatar DEGRADED
    → no joining hint
    → no recoveringPeers

M04 DOMAIN_BLOCKED
    → avatar DEGRADED
    → no joining hint
    → no recoveringPeers

Meeting pill
    recovering=[]
    connectingHint=null
```

CFC-1 telemetry (`CONFERENCE_FAILURE_*`, `CHAIN_VALIDATED scenario=E|D`) **unchanged** — this contract fixes presentation fuse only.

---

## Relationship to adjacent contracts

```text
CFC-1 Phase A              DONE — do not reopen
CPP Per-Edge Media Fact      edge usability facts remain; reconnecting flag decoupled from recoveringPeers
ADR-0022 R27′-B            recoveringPeers = EdgeRecoveryFacts (reasserted)
ADR-0034                   RECONNECTING = active repair only; DEGRADED = terminal / no active repair
Failure Domain Contract    L-participant mandatory; failure terminal ≠ Conference DEGRADED
```

---

## Implementation authorization

**Gate:** [CPP-RECONNECTING-SEMANTICS-IMPL](./cpp-reconnecting-semantics-impl-authorization.md) **ACCEPTED (desk)** · field replay **NOT adjudicated**

Five in-scope items only (AUTH-CPP-1 … AUTH-CPP-5). Hard gate: `!usable` MUST NOT reconstruct `reconnecting` / `recovering`.

---

## Adjudication tags

```text
CPP-RECONNECTING-SEMANTICS · Q1–Q3 FROZEN
CFC-1 Phase A DONE
CPP-RECONNECTING-SEMANTICS-IMPL · AUTHORIZED (desk)
```
