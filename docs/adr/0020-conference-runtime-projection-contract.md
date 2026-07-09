# ADR-0020: Conference Runtime Projection Contract (ADR-CONF-003)

## Status

Accepted (2026-07-09) - #69 follow-up; complements ADR-0010, ADR-0014, ADR-0018, ADR-0019

## Summary

`ConferenceRuntimeState` is the canonical UI input for meeting availability. The projector MUST NOT conflate Lifecycle, Membership, Connectivity, or Conference Authority Reachability.

## Context

PR-3 soak (#69): Host solo after remotes leave showed Connecting because `connectedRemoteMediaCount == 0` implied not established. Participant count may conflate invite/roster with joined membership.

### Dependency direction

```text
Conference Authority
        |
Lifecycle + Membership
        |
Connectivity
        |
UI Projection
```

Connectivity MUST NOT drive Lifecycle. UI MUST NOT infer Membership from invite/roster alone.

## Decision

### Invariants

| ID | Invariant |
|----|-----------|
| L1 | **Established durability**: Only lifecycle authority termination revokes Established. `remoteMediaCount=0`, ICE loss, leave, recovery MUST NOT imply not established. |
| L2 | **Recovery is not lifecycle**: RECOVERING is Connectivity; UI may show RECOVERING while Lifecycle stays Established. |
| P1 | **Participant Active**: ACTIVE iff Established + JOINED + Authority Reachability REACHABLE. Arbitrary peer media insufficient. |
| P2 | **Membership defines count**: Primary participant count from JOINED; pending/media MUST NOT change count. |
| P3 | **Accepted != Joined**: Semantic freeze; full protocol split deferred. |
| H1 | **Host Active**: ACTIVE iff Established + Local Conference Ready; no `remoteMediaCount > 0` required. |
| U1 | **Degradation orthogonal**: Degraded is projection attribute; v1 may use flags/logs only. |

### Forbidden derivations

- `remoteMediaCount == 0` => CONNECTING
- `roster.size` => joined participant count
- ICE state => Conference lifecycle
- Any peer CONNECTED => Participant ACTIVE
- UI phase => mutating authority/membership

### ACTIVE formulas

**Host**: `ESTABLISHED && LocalConferenceReady` (v1: `sessionAccepted && transitionTerminalReady`).

**Participant**: `ESTABLISHED && JOINED && AuthorityReachability==REACHABLE` (v1 may map to host media connected).

`awaitingAdditionalParticipants=true` MAY coexist with ACTIVE; MUST NOT force CONNECTING.

### Projection scope boundary

`ConferenceRuntimeProjection` MUST consume authoritative runtime facts. MUST NOT infer JOINED from invitation, roster, or expected participants alone.

## Considered Options

| Option | Rejected |
|--------|----------|
| Global `remoteMedia==0 => CONNECTING` | Solo host regression |
| `transitionTerminalReady => ACTIVE` for all roles | Participant without host link |
| Revert to channelReady | Dual product definition |
| Membership protocol rewrite in #69 | Scope explosion |

## Consequences

- Fix `ConferenceRuntimeProjector`, tests, `CONFERENCE_PROJECTION` diagnostics.
- PRD: `docs/prd-conference-runtime-projection-contract.md`
- Soak gates G1-G5; hold #69 until follow-up green.

## References

- ADR-0010, ADR-0014, ADR-0018, ADR-0019
- PR #69, issue #70
