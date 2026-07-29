# ADR-0026: Conference Media Transmit Barrier Scope (ADR-CONF-008)

## Status

**Accepted** (2026-07-14) — supersedes ADR-0022 open decision on conference-wide transmit barrier (2026-07-14). Complements ADR-0022 (recovery obligation), ADR-0025 R30-I (presentation), ADR-0021 (edge recovery lifecycle).

## Summary

Conference mesh is **edge-isolated**: each WebRTC peer connection is independent. A remote edge recovering MUST NOT block local capture toward healthy peers.

Soak `ae235af4` (2026-07-14) proved `CONFERENCE_WIDE` barrier:

```text
M02 WiFi loss
  M01-M03 ICE CONNECTED
  M01/M03 stop_capture (canPublish=false)
  => entire meeting silent
```

This ADR freezes:

1. **Transmit barrier is edge-scoped** — local publish gated by local conference state only.
2. **Remote recovery is diagnostic** — `recoveringPeers` / obligation facts MUST NOT enter `ConferenceMediaTransmitGate`.
3. **Observability retains peer health** — `CONFERENCE_BARRIER_SNAPSHOT` still logs per-peer recovery; `policy=EDGE_SCOPED`.

```text
Roster Plane     — who is in the meeting
Media Plane      — which edges carry audio
Recovery Plane   — which edges are recovering (diagnostic)
Presentation     — user-perceived availability (ADR-0025)
Transmit Gate    — local capture allowed? (THIS ADR)
```

## Context

### Superseded semantics (ADR-0022 v1)

`ConferenceMediaTransmitGate` v1 implemented **CONFERENCE_WIDE**:

```text
anyEdgeRecovering || any obligationOpen(remote)
        => stop ALL local conference capture
```

Rationale at the time: ICE CONNECTED != transmit-ready; conservative freeze during recovery.

### Why CONFERENCE_WIDE conflicts with architecture

| Model | Barrier philosophy |
|-------|-------------------|
| GROUP (floor / half-duplex) | Global transmit coupling is reasonable |
| CONFERENCE (mesh / full-duplex) | Each edge is independent; single-edge fault must not conference-mute healthy participants |

Conference has no center mixer, no single media path. Blocking all capture because one remote edge is recovering collapses mesh into a synchronized session — contradicting decentralized WebRTC edges + authority roster.

### Evidence

| Soak | Observation |
|------|-------------|
| `c416371c` | M02 loss: M01 `connected=M03` but `stop_capture` |
| `ae235af4` | M02 loss ~11s silent on M01/M03 while M01-M03 healthy |
| `df7a5404` | soak3: M02 loss; M01↔M03 audio continues; no `CONFERENCE_WIDE` on healthy peers |

Step 1 (ADR-0025 R30-I) fixed presentation false reconnecting. Remaining mute is **barrier scope**, not recovery completion or UI.

## Decision

### R36-A — Edge-scoped transmit barrier

`ConferenceMediaTransmitGate` MUST evaluate **local publish preconditions only**:

| Input | Blocks capture when |
|-------|---------------------|
| `localConferenceActive` | session not accepted / not in conference |
| `localMuted` | user muted |
| `localPublisherReady` | no healthy local publish transport (e.g. zero ICE-connected peers) |

`ConferenceMediaTransmitGate` MUST NOT consult:

- `recoveringPeers`
- `edgeObligationOpen`
- `anyEdgeRecovering`
- per-remote recovery phase

> **Remote edge recovery is not a user-visible transmit fault unless it removes all local publish paths.**

### R36-B — Local publisher readiness

`localPublisherReady` means: this device has **at least one** ICE-connected conference peer to publish toward.

| Scenario | `localPublisherReady` |
|----------|----------------------|
| M01: M03 connected, M02 recovering | `true` |
| M02: all edges down (WiFi off) | `false` |
| M01: explicit local publisher fault | `false` (caller sets) |

`localPublisherReady` is **not** `ICE_CONNECTED` on a specific remote recovery edge. It is **existence of any publish path**.

### R36-C — Observability policy rename

`ConferenceBarrierPolicy.CONFERENCE_WIDE` is **deprecated**. Snapshots MUST emit `EDGE_SCOPED`.

Peer recovery facts remain in snapshot fields (`recoveringPeers`, `obligationOpenPeers`, `blocked` peer diagnostics) for soak and watchdog. They MUST NOT drive `canPublish`.

### R36-D — Presentation boundary (unchanged)

ADR-0025 R30-I: UI hint/avatar uses media availability, not recovery obligation. ADR-0026: transmit gate uses local publish path, not recovery obligation. These are aligned but independent layers.

## Non-goals

| Item | Why |
|------|-----|
| Change recovery obligation lifetime (R28-H) | ADR-0022; barrier scope only |
| Per-peer selective capture routing | Future; gate is allow/deny local capture |
| Auto-prune on recovery failure | ADR-0023 / R29-E |
| GROUP transmit model | ADR-0008 unchanged |

## Consequences

- **Positive:** One participant WiFi loss no longer conference-mutes healthy mesh edges; matches product expectation for decentralized talkback.
- **Negative:** Brief audio may reach peers whose edge to the failed participant is still recovering (acceptable: they may not hear back until recovery completes).
- **Neutral:** Recovery facts unchanged; only gate scope moves. Existing `CONFERENCE_BARRIER_SNAPSHOT` format retained with `policy=EDGE_SCOPED`.

## Acceptance gates

| Gate | Criterion |
|------|-----------|
| G-R36-1 | M02 disconnect: M01 `canPublish=true` while M01-M03 ICE connected |
| G-R36-2 | M02 disconnect: M03 `canPublish=true` while M03-M01 ICE connected |
| G-R36-3 | M02 all edges down: M02 `canPublish=false` |
| G-R36-4 | M01 `localPublisherReady=false` with healthy peers: `canPublish=false` |
| G-R36-5 | Remote `obligationOpen` alone: `canPublish` unchanged |
| G-R36-6 | Soak: M02 WiFi loss — M01/M03 continue capture within 1s of peer path healthy |
| G-R36-7 | **Device soak** session `df7a5404` (`soak3`, 2026-07-14): M02 disconnect — M01↔M03 audio continues; no `CONFERENCE_WIDE` / `stop_capture` on M01/M03; `EDGE_SCOPED` semantics verified on M02. **PASS** |

**Snapshot logging note:** Under `EDGE_SCOPED`, `CONFERENCE_BARRIER_SNAPSHOT` is emitted on `canPublish` transitions and `stop_capture` only. While `canPublish` stays `true`, healthy peers may emit **no** barrier line. Absence of `CONFERENCE_WIDE` + `stop_capture` on M01/M03 during remote recovery is valid soak evidence.

## Implementation

- `ConferenceMediaTransmitGate.Input` — `localConferenceActive`, `localMuted`, `localPublisherReady`
- `TalkbackCoordinator.canPublishAudio` — derive `localPublisherReady` from `connectedConferencePeerIds`
- `ConferenceBarrierDiagnostics` — `policy=EDGE_SCOPED`; peer reasons telemetry-only

## References

- ADR-0022 — Recovery Completion Ownership (supersedes open barrier decision)
- ADR-0025 — Presence Plane / R30-I
- ADR-0021 — Conference Edge Recovery Lifecycle
- Soak `soak-M01-20260714-171542.log` session `ae235af4`
- Soak3 `soak3-M01-20260714-174553.log` session `df7a5404` (G-R36-7 **PASS**, 2026-07-14)
