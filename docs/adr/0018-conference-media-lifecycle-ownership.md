# ADR-0018: Conference Media Lifecycle Ownership (ADR-CONF-001)

## Status

Accepted (2026-07-08) — implemented in RO-M2 PR-1; supersedes implicit reuse behavior in RO-M2a (#63)

## Summary

Conference **media session** lifecycle (PeerConnection per remote module) is owned by
`MediaSessionManager` under explicit recovery policy. **Membership and signaling changes MUST NOT
recreate an active conference media session.** ICE connectivity loss is a transport recovery event,
not a media session recreation event.

## Context

RO-M2a (#63) introduced `MediaSessionManager`, GROUP→MEETING barrier, and soak KPI
`MEDIA_SESSION_REUSE=0`. Soak `logs-ro-m2a-20260708-150500` showed:

| Observation | Implication |
|-------------|-------------|
| 16× `MEDIA_SESSION_REUSE=1` with `previousScope=CONFERENCE` | Live PC destroyed on in-meeting paths |
| `Re-sent conference invites` followed by `ICE=CLOSED` / `TRANSPORT_NOT_READY` | Signaling retry triggered media recreate |
| Control `MEETING_START READY` while UI still Connecting | Control / Media / UI not converged (see ADR-0019, future projection ADR) |

[ADR-0017](./0017-transition-declaration-and-media-lifecycle.md) separates **Transition** (when
establishment predicate is satisfied) from **Media Lifecycle** (connectivity observability). This ADR
defines **who may mutate media sessions** and **when recreation is allowed** — orthogonal to
Transition READY and to participant UI projection ([ADR-0010](./0010-conference-membership-vs-media-projection.md)).

### Architectural layers (frozen)

```text
Signaling / Membership     — invites, roster, accept/decline (control plane)
Media Lifecycle            — PeerConnection per module (data plane)
Transition                 — MEETING_START / MEETING_END predicates (governance)
Conference Runtime         — projected meeting availability (future PR-2)
UI                         — reads runtime projection only (future PR-3)
```

## Decision

### Invariants (MUST NOT violate)

| ID | Invariant |
|----|-----------|
| **M1** | An **active** conference media session **MUST NOT** be recreated due to participant membership changes (join, leave, pending invite). |
| **M2** | An **active** conference media session **MUST NOT** be recreated due to signaling retries (`resendConferenceInvites`, invite timeout, counter-invite). |
| **M3** | `ICE=DISCONNECTED` on an active conference PC **MUST** trigger **recovery** (ICE restart), not immediate `close()` + `create()`. |
| **M4** | `MEDIA_SESSION_REUSE=1` during soak **MUST** be 0 across conference establishment and in-meeting retry paths (hard gate S8). |

### Ownership

| Component | Role |
|-----------|------|
| `MediaSessionManager` | Sole executor of PC create / close / generation; emits `MEDIA_LIFECYCLE` and `MEDIA_SESSION_REUSE` |
| `ConferenceRecoveryController` (PR-1 stub → PR-3 full) | Decides recovery escalation; **only** authority that may request PC recreation after failed recovery |
| `TalkbackCoordinator` | Orchestration; may call `requestRecovery()`; **MUST NOT** call `create()` to “fix” invite or roster events |
| Invite / signaling paths | **MUST NOT** call `MediaSessionManager.create()` for conference scope except first attach on a module with no live PC |

### Media session reuse policy (CONFERENCE scope)

When `create(moduleId, CONFERENCE)` is invoked and an entry already exists:

| Existing ICE state | Action |
|--------------------|--------|
| CONNECTED | Reuse existing engine; `MEDIA_SESSION_REUSE` MUST NOT increment |
| CONNECTING / CHECKING (< timeout) | Reuse; wait or recovery handles stall |
| DISCONNECTED | Reuse; enter recovery; ICE restart first |
| CHECKING (duration > policy timeout) | Enter recovery escalation (not immediate recreate) |
| FAILED | Enter recovery escalation |
| CLOSED | Close path complete; allow new `create()` |

**Scope change** (e.g. GROUP → CONFERENCE): barrier / `resetAll` per RO-M2a remains valid; this ADR
does **not** permit CONFERENCE → CONFERENCE recreate on signaling alone.

### Recovery escalation (ConferenceRecoveryPolicy v1)

Trigger **recovery escalation** (not immediate recreate) when:

```text
iceState == FAILED
OR (iceState == CHECKING AND checkingDuration > checkingTimeoutMs)   // default 8s
```

Escalation sequence:

```text
1. ICE restart (up to maxIceRestartAttempts, default 2)
2. If still not CONNECTED → recreate PC (via MediaSessionManager)
```

Policy parameters live in `ConferenceRecoveryPolicy`, not hard-coded in `MediaSessionManager`.

> **Trigger ≠ action.** Failed/checking-timeout means “enter recovery flow”, not “destroy PC now”.

### Relationship to Transition READY

[ADR-0016](./0016-transition-completion-contract.md) / [ADR-0017](./0017-transition-declaration-and-media-lifecycle.md):
`MEETING_START READY` requires `connectedInviteeCount > 0` (at least one remote ICE connected) for
MULTI_PARTY — **not** full roster. Media recreation policy does not change this predicate.

[ADR-0014](./0014-conference-host-owned-lifetime.md): Solo host with zero remotes connected is
**Exists=true, Operational=false** — legal. Media policy must not auto-end host session.

## Considered Options

| Option | Rejected because |
|--------|------------------|
| Always recreate on any `create()` call | Causes soak `MEDIA_SESSION_REUSE=1`, ICE churn, false Connecting |
| Never recreate within CONFERENCE | Wedged FAILED/CLOSED PCs never recover |
| Coordinator decides recreate on invite retry | Blurs signaling and media planes; reintroduces soak failures |
| Recreate on `DISCONNECTED` immediately | Violates M3; loses transient disconnect recovery |

## Consequences

- PR-1 changes `MediaSessionManager.create()` for CONFERENCE + non-terminal ICE → reuse.
- `resendConferenceInvites` audit: must not implicitly acquire new mesh engines (see ADR-0019).
- Unit tests: reuse CONNECTED; recreate CLOSED; DISCONNECTED does not increment reuse violation.
- Soak: S8 remains **hard** fail on `MEDIA_SESSION_REUSE=1`.
- RO-M2b (#64) ICE path / SDP fixes build on stable in-meeting PC lifecycle, not replace this ADR.

## Implementation tracking

| Item | PR / location |
|------|----------------|
| `MediaSessionManager` CONFERENCE reuse rules | PR-1 |
| `ConferenceRecoveryPolicy` data class | PR-1 |
| `ConferenceRecoveryController` (minimal) | PR-1 stub, PR-3 full |
| ADR-0019 signaling separation | PR-1 doc + PR-3 enforcement |
| Soak S8 | `scripts/soak-tcc-hard-gates.ps1` |
| `ConferenceRuntimeProjector` | PR-2 (out of scope here) |

## References

- [ADR-0010](./0010-conference-membership-vs-media-projection.md) — UI projection vs media facts
- [ADR-0014](./0014-conference-host-owned-lifetime.md) — Host-owned session lifetime
- [ADR-0017](./0017-transition-declaration-and-media-lifecycle.md) — Transition vs Media Lifecycle
- Soak: `logs-ro-m2a-20260708-150500`
- Epic: #62 RO-M2; issues #63, #64
