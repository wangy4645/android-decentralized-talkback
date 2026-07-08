# ADR-0019: Conference Signaling and Media Lifecycle Separation (ADR-CONF-002)

## Status

Accepted (2026-07-08) — PR-1 invite audit + media reuse; full enforcement in PR-3

## Summary

**Signaling retries MUST NOT mutate media lifecycle.** Invite send, invite retry, roster updates,
and participant timeout handling are **control-plane events**. PeerConnection create, ICE restart,
and recreation are **media-plane events** owned by recovery authority per
[ADR-0018](./0018-conference-media-lifecycle-ownership.md).

## Context

Soak `logs-ro-m2a-20260708-150500` showed a recurring failure chain:

```text
Host: resendConferenceInvites / invite retry
  → MediaSessionManager.create(CONFERENCE)
  → MEDIA_SESSION_REUSE=1
  → ICE=CLOSED, TRANSPORT_NOT_READY
  → UI Connecting / Deferring while playback may already be active
```

The bug class is **control-plane events causing data-plane lifecycle mutations**:

```text
Invite timeout (small)  →  destroy/recreate PC (large)
```

This ADR freezes the boundary so future paths (roster update, anchor switch, participant evict)
cannot silently call `create()` “to make soak green”.

### Complements existing ADRs

| ADR | Relationship |
|-----|----------------|
| [ADR-0010](./0010-conference-membership-vs-media-projection.md) | UI must not interpret invite/media facts; projection layer |
| [ADR-0014](./0014-conference-host-owned-lifetime.md) | Participant leave ≠ session terminate; invite retry ≠ media reset |
| [ADR-0018](./0018-conference-media-lifecycle-ownership.md) | When PC may be recreated |

## Decision

### Invariants

| ID | Invariant |
|----|-----------|
| **S1** | `resendConferenceInvites`, invite timeout retry, and counter-invite **MUST** perform signaling only (send envelope, update `InviteState`, roster projection). |
| **S2** | Signaling retry paths **MUST NOT** call `MediaSessionManager.create()`, `close()`, or `resetAll()` for conference scope. |
| **S3** | Media recreation **MUST** be initiated only by `ConferenceRecoveryController` after recovery escalation per ADR-0018 (ICE restart exhausted or CLOSED). |
| **S4** | `TalkbackCoordinator` **MAY** call `requestRecovery(channelId, moduleId, reason)`; **MUST NOT** directly recreate PCs to recover from invite failures. |

### Allowed operations by layer

| Layer | Allowed on invite retry | Forbidden on invite retry |
|-------|-------------------------|---------------------------|
| Invite / signaling | `GROUP_INVITE` / conference invite send; update `InviteState`; `pendingConferenceInvites` | — |
| `ConferenceParticipantManager` | Roster / participant projection | Media state mutation |
| `ConferenceParticipantProjector` | Read-only projection | — |
| `MediaSessionManager` | — | `create`, `close`, `resetAll` |
| `ConferenceRecoveryController` | — (not invite path) | Triggered only by media recovery policy |

### Exception (only path to recreate)

PC recreation during an **active** conference is allowed **only** when:

```text
ConferenceRecoveryController
  has completed recovery escalation (ICE restart attempts exhausted)
  AND media state is FAILED / CLOSED / non-recoverable per ADR-0018
```

Invite retry **never** satisfies this condition by itself.

### Anti-pattern (explicitly forbidden)

```kotlin
// FORBIDDEN
fun resendConferenceInvites(...) {
    targets.forEach { remote ->
        mediaRegistry.conferenceEngine(remote.moduleId)  // implicit create → reuse violation
        sendInvite(...)
    }
}
```

```kotlin
// ALLOWED
fun resendConferenceInvites(...) {
    targets.forEach { remote ->
        sendInvite(...)  // signaling only
        updateInviteState(remote, INVITING)
    }
}
```

```kotlin
// ALLOWED (separate concern)
fun onMediaRecoveryEscalation(moduleId: String) {
    recoveryController.requestRecreateIfNeeded(moduleId)
}
```

## Considered Options

| Option | Rejected because |
|--------|------------------|
| **Soft rule** — some retries may recreate | Every control event becomes a footgun; soak regressions |
| **Dual track** — runtime + channelReadiness for meeting UI | Two product definitions; S10 cannot prove convergence |
| **MediaSessionManager auto-recreate on create()** without policy | Already caused `MEDIA_SESSION_REUSE=1`; fixed in ADR-0018 |

## Consequences

- PR-1: Document + audit `sendConferenceInvitesInternal` / `resendConferenceInvitesToUnconnected` /
  `trySendSingleConferenceInvite` for implicit `acquireMeshEngine` on retry-only paths.
- PR-3: Split `ConferenceRecoveryController`; invite retry tests assert zero `MEDIA_SESSION_REUSE`.
- Integration test: invite retry while peer ICE CONNECTED → `MEDIA_SESSION_REUSE=0`, same generation.
- Future: GROUP PTT / unicast keep separate policies; this ADR applies to **CONFERENCE** scope.

## Observability

Log prefixes (existing or extended):

| Marker | Meaning |
|--------|---------|
| `INVITE_DISPATCH_COMPLETED` | Establishment-window dispatch (ADR-0017 S9) |
| `Conference invite sent` | Signaling only |
| `Re-sent conference invites` | Must not correlate with `MEDIA_SESSION_REUSE=1` |
| `MEDIA_SESSION_REUSE=1` | Policy violation — S8 hard fail |
| `MEDIA_RECOVERY_*` (future) | Recovery controller actions |

## Implementation tracking

| Item | PR |
|------|-----|
| ADR-0018 media reuse rules | PR-1 |
| Invite path audit + tests | PR-1 / PR-3 |
| `ConferenceRecoveryController` | PR-3 |
| UI reads `ConferenceRuntimeState` only | PR-3 |
| S10 projection consistency (WARN → HARD) | PR-2 WARN, post-PR-3 HARD |

## References

- [ADR-0018](./0018-conference-media-lifecycle-ownership.md) — Media ownership and recovery escalation
- [ADR-0017](./0017-transition-declaration-and-media-lifecycle.md) — Declaration window, S9 establishment dispatch
- Soak: `logs-ro-m2a-20260708-150500`
