# ADR-0028: Participant Presence Ownership (Local Reachability Projection)

## Status

**Accepted** (2026-07-17) — freezes per-participant presentation ownership after soak `wifi-ui-norestore-20260716-175905` exposed illegal cross-projection state combinations. Complements ADR-0022 (edge recovery facts), ADR-0025 (conference-level presence aggregates). **Narrows and supersedes** ADR-0025 R30-I participant hint/avatar ownership. Does **not** introduce a new runtime, controller, or authority.

## Summary

> 当前系统的问题不是某个投影算错，而是同一个用户感知事实（「我现在是否还能感知到这个人」）被 `recoveringPeers`、`displayState`、`hint`、`avatar` 四套投影重复表达，因此系统能够构造出彼此矛盾但局部正确的状态组合。

ADR-0028 freezes:

1. **`LocalReachability(P)`** — the sole presentation owner for per-peer user-visible reachability on this device.
2. **Invariant V1** — if this node is receiving decodable audio from `P`, UI **MUST NOT** show `P` as reconnecting.
3. **Input / output boundary** — whitelist local facts; forbid aggregate counts and conference-level derivations.
4. **Time semantics** — debounce lives in media facts; SLA lives in tests only; projection stays stateless.
5. **P2 out-of-scope** — asymmetric half-open mesh edges are allowed; cross-device UI consistency is not a goal.
6. **R30-J** — end-to-end regression gate from `receivePathLive` to hint/avatar.

```text
Problem class:

  Illegal state combinations are expressible across presentation projections.
  Not: one projector computed wrong.
```

## Context

### Incident that motivated this ADR (2026-07-16)

Session `f71423b4`, mesh topology `M01↔M02`, `M01↔M03`, `M02↔M03`. M03 WiFi flap.

Two distinct phenomena were previously conflated:

#### P1 — Presentation consistency defect (in-scope; eliminable)

On M03, after edge recovery completed:

```text
recoveringPeers = []
hint            = "M01 reconnecting..."
```

Both held simultaneously for ~8 seconds (`17:57:42`–`17:57:49`).

Each projection was locally faithful to its own inputs. The combination was **user-illegal** but **code-legal**. This is the defect class ADR-0028 eliminates by construction.

#### P2 — Bilateral edge observation lag (out-of-scope; not eliminable)

On the same session:

```text
M01: ICE M03 CONNECTED  @ 17:57:36
M03: ICE M01 CONNECTED  @ 17:57:50
```

A 14-second gap on the same logical mesh edge. M03 correctly showed M01 as reconnecting during that window because **this device was not receiving M01 audio**.

Mesh has no global `EdgeAvailability = true`. Only:

```text
EdgeAvailability(M01 → M03)   // M01's local observation
EdgeAvailability(M03 → M01)   // M03's local observation
```

Half-open edges are a **design-allowed** state, not a regression.

### What prior ADRs already fixed (and what they did not)

| ADR / gate | Fixed | Did not fix |
|------------|-------|-------------|
| ADR-0022 R27′ | Pill reads `joinedCount` / `connectedCount` / `recoveringPeers`, not roster size | Per-peer hint/avatar single owner |
| ADR-0025 R30-I | Hint must not derive from `recovering` alone; `playbackReady` priority | `playbackReady` still bound to local `VISIBLE_CONNECTED`, not receive-path liveness; hint and recovering still separate owners |
| ADR-0022 Appendix C | Recovery obligation / ICE restart ownership | Presentation lag after recovery |
| ConferenceDisplayStateResolver (local) | Conference-level LIVE vs WAITING | Per-peer reconnecting attribution |

Each layer repaired internal consistency. **Cross-layer user consistency** had no owner.

### What this ADR is not

| Concern | Owner |
|---------|-------|
| ICE restart / recovery obligation | ADR-0022 |
| Roster / membership mutation | ADR-0023 |
| Conference-level aggregates (`joinedCount`, `connectedCount`, `recoveringPeers`) | ADR-0025 `ConferencePresenceProjector` |
| Conference LIVE / WAITING / pill phase | ADR-0020, ConferenceDisplayStateResolver |
| `receivePathLive` debounce implementation | Media / receive-path layer |
| Cross-device UI symmetry | **Not a goal** |

## Decision

### R30-J-0 — Core goal

ADR-0028 answers exactly one question on each rendering node:

> **On this device, should the user currently see participant P as reconnecting?**

It owns **only**:

```text
participant avatar state
participant hint
participant badge
```

It does **not** own conference lifecycle, channel readiness, meeting pill phase, or edge recovery control.

### R30-J-1 — Sole owner: `LocalReachability(P)`

Freeze:

```text
LocalReachability(P) = f(local facts)
```

Properties (mandatory):

| Property | Requirement |
|----------|-------------|
| Pure function | Same inputs → same output |
| Stateless | No timer, latch, hysteresis, or internal cache |
| Non-persistent | Not stored across ticks as authoritative state |
| Presentation-only | Not consumed by Recovery, Media, or Membership decisions |

`LocalReachability(P)` is the **only** place that may decide per-peer presentation:

```text
ONLINE
RECONNECTING
JOINING
OFFLINE
LEFT
```

**Forbidden after migration:**

```text
hint computed independently of avatar
avatar computed independently of hint
displayState computed independently of LocalReachability
recoveringPeers used directly as hint input
connectedCount != joinedCount used to derive per-peer reconnecting
```

All participant presentation **MUST** consume `LocalReachability(P)` (directly or via a single mapper that is a thin alias).

**Anti-pattern (explicitly forbidden):**

```kotlin
// ❌ Fourth runtime state machine
data class LocalReachabilityState(
    CONNECTED,
    RECOVERING,
    OFFLINE,
)
```

```text
EdgeRecovery → LocalReachabilityState → Presence   // ❌
```

`LocalReachability` is a **projection**, not a runtime plane.

### R30-J-2 — Input whitelist

`LocalReachability(P)` **MAY** consume only:

| Input | Source | Purpose |
|-------|--------|---------|
| `receivePathLive(P)` | Media / receive-path layer (debounced boolean fact) | User is currently receiving decodable audio from P |
| `membership(P)` | Roster / membership authority projection | Distinguish RECONNECTING vs LEFT |
| `edgeRecoveryFacts(P)` | ADR-0022 facts (`recovering`, `mediaUnavailable`, etc.) | Diagnostic inputs when receive path is not live |
| `everConnected(P)` | Local edge history | Distinguish JOINING vs RECONNECTING |
| `conferenceLifecycle` | Conference runtime projection | TERMINATED / ended context only |

`membership(P)` is **explicitly allowed** (per-peer, not aggregate):

```text
RECONNECTING  ≠  LEFT

peer still in conference, media not restored   →  RECONNECTING
peer no longer in conference                   →  LEFT / hidden
```

### R30-J-3 — Input blacklist

`LocalReachability(P)` **MUST NOT** consume:

```text
joinedParticipantCount
connectedParticipantCount
recoveringCount
recoveringPeers (as aggregate set without per-peer facts)
roster.size
meeting pill state
conference phase
channelReady
connected != joined   →  reconnecting   (forbidden derivation)
```

Aggregate counts belong to **conference summary** (ADR-0025 header diagnostics). They **MUST NOT** drive per-peer avatar, hint, or badge.

### R30-J-4 — Time semantics (debounce vs SLA)

#### Debounce belongs to the media layer

`receivePathLive(P)` **MUST** be a **stable boolean fact** produced upstream:

```text
true  iff  this node has received decodable audio from P
           continuously for ≥ Tplayback
```

Recommended engineering default (not frozen by this ADR):

```text
Tplayback = 500ms
```

Rationale: jitter, Opus PLC, keyframe gaps, mute/unmute transitions must not flap presentation.

`LocalReachability` **MUST NOT** implement `Tplayback`. It reads `receivePathLive` as a fact.

#### SLA belongs to tests and observability

```text
Tsla
```

(e.g. `1s`) is used only for:

- soak assertions
- log-based regression scripts
- observability dashboards

Example contract:

```text
receivePathLive(P) = true
    ⇒
within Tsla:
    hint(P) == null
    avatar(P) != RECONNECTING
```

**Forbidden:**

```kotlin
// ❌ SLA timer inside projection
class LocalReachabilityResolver {
    private val clearHintDeadline = ...
}
```

If projection grows timers, it has become a fourth runtime. Reject.

### R30-J-5 — Invariant V1 (frozen)

For rendering node N and peer P:

```text
V1

IF receivePathLive(P) == true
THEN LocalReachability(P) ∉ { RECONNECTING }
```

Equivalently for presentation outputs:

```text
IF receivePathLive(P) == true
THEN:
    hint(P) == null
    avatar(P) != RECONNECTING
```

**LHS is not:**

```text
ICE_CONNECTED
edge recovered
RECOVERY_EDGE_RECOVERED
conference ACTIVE
channelReady
playbackReady via VISIBLE_CONNECTED alone
```

**LHS is:**

```text
this node is receiving decodable audio from P
(debounced, via receivePathLive)
```

V1 is the core narrowing of ADR-0025 R30-I: user-perceived availability is anchored to **local receive-path liveness**, not local transmit-edge CONNECTED state alone.

### R30-J-6 — Presentation priority (replaces R30-I table)

When `receivePathLive(P) == false`, derive presentation from remaining facts only:

| Condition | LocalReachability / Avatar / Hint |
|-----------|-----------------------------------|
| `membership(P) != JOINED` (per visible-row policy) | LEFT / hidden |
| `receivePathLive(P) == true` | ONLINE — no hint (**V1**) |
| `mediaUnavailable(P) == true` | RECONNECTING |
| `everConnected(P) && !receivePathLive(P)` | RECONNECTING |
| `!everConnected(P) && !receivePathLive(P)` | JOINING |

`edgeRecoveryFacts(P).recovering` **MAY** inform presentation **only when** `!receivePathLive(P)` and together with `mediaUnavailable` / `everConnected`. It **MUST NOT** alone produce RECONNECTING when `receivePathLive(P) == true` (V1).

`recoveringPeers` / per-peer `recovering` **MUST** remain in `ConferencePresenceProjection` for soak, watchdog, and telemetry (ADR-0025 R30-I.1 unchanged). Presentation **MUST NOT** read them except via `edgeRecoveryFacts(P)` inside `LocalReachability`.

### R30-J-7 — P2 explicit out-of-scope

ADR-0028 **does not** guarantee that two devices show the same reachability for the same peer at the same time.

**Allowed:**

```text
M01 UI:  M03 ONLINE
M03 UI:  M01 RECONNECTING
```

 simultaneously.

**Reason:**

```text
mesh edge = two independent ICE agents' local observations
```

Half-open edge (A believes recovered, B does not) is **design-allowed**.

It is:

- **not** a regression
- **not** covered by R30-J
- **not** part of the UI contract

ADR-0028 constrains only:

```text
each device is honest about its own local perception
```

### R30-J-8 — Illegal state elimination (P1)

The following combinations **MUST** be unrepresentable after migration:

```text
recoveringPeers excludes P   AND   hint(P) contains "reconnecting"
recoveringPeers excludes P   AND   avatar(P) == RECONNECTING   (unless !receivePathLive per V1)
hint(P) reconnecting         AND   avatar(P) == ONLINE
```

Root cause of P1: `recoveringPeers`, `hint`, and `avatar` were computed by separate code paths. Fix: **single owner** `LocalReachability(P)` drives all three presentation surfaces.

### R30-J-9 — Regression gate R30-J

**Given:**

```text
receivePathLive(P) == true
conference not TERMINATED
membership(P) == JOINED
```

**Expect within Tsla (default 1s):**

```text
LocalReachability(P) == ONLINE
hint(P) == null
avatar(P) != RECONNECTING
recoveringPeers may still contain P   (telemetry only — must not affect presentation)
```

**Explicitly NOT tested:**

```text
ICE_CONNECTED
RECOVERY_EDGE_RECOVERED
connectedParticipantCount
joinedParticipantCount
cross-device UI symmetry
```

Test input is **audio restored** (via `receivePathLive`). Test output is **UI restored**. ICE, EdgeRecovery, PresenceProjector internals are hidden.

**P1 regression fixture (from 2026-07-16 soak):**

```text
recoveringPeers = []
receivePathLive(M01) = true   (once media layer exposes fact)
⇒ hint MUST NOT be "M01 reconnecting..."
```

## Relationship to existing ADRs

### ADR-0022 — Recovery Completion Ownership

Provides `edgeRecoveryFacts(P)` consumed as **input** to `LocalReachability`. Does **not** own presentation. Recovery obligation closing does not automatically imply hint clearing unless `receivePathLive` or other presentation inputs say so (V1 overrides when audio is live).

### ADR-0025 — Conference Presence Plane

**Retains:**

- `ConferencePresenceProjection` aggregates (`joinedCount`, `connectedCount`, `recoveringPeers`)
- Three Planes worldview (Roster / Self / Edge)
- Aggregate ↔ list consistency for telemetry
- R30-F header membership semantics
- R30-G join latency observability

**Superseded for participant presentation ownership:**

- R30-I presentation priority table → R30-J-5 / R30-J-6
- R30-I `playbackReady` via `VISIBLE_CONNECTED` as sole media-availability signal → `receivePathLive(P)` as primary V1 input
- R30-I single-source rule (`ParticipantDisplayStateMapper` drives hint + avatar) → `LocalReachability(P)` is the single source; mapper becomes thin projection glue

**Unchanged:**

- `recoveringPeers` remains for soak / watchdog
- No new sibling Presence DTOs (R30-B still applies)

### ADR-0020 — Conference Runtime Projection

Conference-level LIVE / ACTIVE / `channelReady` remain owned by runtime projection. `LocalReachability` **MUST NOT** read `channelReady` or conference phase to decide per-peer reconnecting (blacklist).

## Implementation guidance (non-normative)

Suggested shape:

```text
receivePathLive(P)          ← MediaReceivePathObserver (owns Tplayback)
membership(P)               ← existing roster projection
edgeRecoveryFacts(P)        ← ADR-0022 / ConferencePresenceProjector participant entry

        ↓  pure function, no state

LocalReachability(P)        ← new sole owner (replaces split hint/avatar logic)

        ↓

ParticipantPresentation     → avatar, hint, badge
aggregateAvailabilityHint() → fold over LocalReachability(*), not recoveringPeers
```

Migrate `ParticipantDisplayStateMapper` / `MeetingPresenceDisplay.resolveParticipantPresentation` to call `LocalReachability` rather than parallel derivations.

## Consequences

- **Positive:** Illegal cross-projection combinations become unexpressible; V1 anchors UI to what the user actually hears; P2 correctly excluded from presentation contract; debounce/SLA split prevents projection state machine creep.
- **Negative:** Requires `receivePathLive(P)` fact from media layer (may not exist yet — implementation prerequisite); ADR-0025 R30-I tests must be updated to R30-J semantics; existing `playbackReady` / `VISIBLE_CONNECTED` mapping needs explicit bridge or replacement for V1.
- **Neutral:** `recoveringPeers` retained for diagnostics; conference header counts unchanged; no Recovery or Membership authority changes.

## Soak / acceptance gates

| Gate | Pass criterion |
|------|----------------|
| **G-R30-J** | `receivePathLive(P)=true` ⇒ within `Tsla`, `hint(P)==null` and `avatar(P)!=RECONNECTING` |
| **G-R30-J1** | `recoveringPeers` excludes P and `receivePathLive(P)=true` ⇒ no reconnect hint for P (P1 fixture) |
| **G-R30-J2** | `receivePathLive(P)=false`, `everConnected`, `mediaUnavailable` ⇒ RECONNECTING |
| **G-R30-J3** | Unit test: `LocalReachability` is pure — no timer fields, no mutable state |
| **G-R30-J4** | Unit test: blacklist inputs (`connectedCount`, `joinedCount`) do not change output |
| **G-R30-J-OUT** | Cross-device asymmetric edge: **no gate** — documented out-of-scope only |

## References

- ADR-0022 — Recovery Completion Ownership (`edgeRecoveryFacts`, R27′ presence boundary)
- ADR-0025 — Conference Presence Plane Projection Contract (R30 aggregates; R30-I superseded for per-peer presentation)
- ADR-0020 — Conference Runtime Projection Contract
- ADR-0023 — Conference Membership Mutation Authority Boundary
- Soak logs: `logs/wifi-ui-norestore-20260716-175905/` (session `f71423b4`)
- Prior soak: `logs/wifi-recovery-norepro-20260716-173408/` (normal recovery ~12s — not P1)
