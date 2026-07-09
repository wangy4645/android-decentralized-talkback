# PRD: Conference Runtime Projection Contract (#69 Follow-Up)

**Status**: Ready for implementation  
**Date**: 2026-07-09  
**Parent**: [PR #69](https://github.com/wangy4645/android-decentralized-talkback/pull/69) RO-M2 PR-3  
**Prerequisite**: PR-3 soak (three-device); grill-with-docs semantics frozen  
**ADR**: ADR-0020  
**Suggested branch**: `ro-m2-pr3-projection-contract` (from `ro-m2-pr3-runtime-ui-recovery`)

---

## Problem Statement

RO-M2 PR-3 correctly moved Meeting UI from `channelReadiness` to `ConferenceRuntimeState`. Three-device soak exposed **projection semantic defects**, not transport/authority/media regression:

1. **P0 Solo Host**: After all remotes leave, Host UI returns to Connecting. `ConferenceRuntimeProjector` maps `transitionTerminalReady && connectedRemoteMediaCount == 0` to CONNECTING, conflating "no remote media" with "not established".
2. **Participant ACTIVE risk**: `connectedRemoteMediaCount >= 1` can drive ACTIVE when Conference Authority is unreachable.
3. **Count semantics (to confirm)**: M03 may show three in meeting while M02 only invited/pending.

Users need: solo host **ACTIVE + awaiting** (not Connecting); participant ACTIVE only with **Authority Reachability**; primary count must not include pending invites.

---

## Solution

Freeze **Conference Runtime Projection Contract** (ADR-0020). Fix how existing runtime facts are interpreted. **Do not** change membership protocol.

| ID | Rule |
|----|------|
| L1 | Established durable; connectivity cannot revoke |
| L2 | RECOVERING is Connectivity, not Lifecycle |
| P1 | Participant ACTIVE requires Authority Reachability |
| P2 | Primary count from JOINED membership |
| P3 | ACCEPTED != JOINED (ADR only; protocol split later) |
| H1 | Host ACTIVE = Established + Local Conference Ready |
| U1 | Degraded is projection attribute; no new UI phase in v1 |

---

## User Stories

1. As Conference Host, after all remotes leave, I want ACTIVE + awaiting so I know the meeting continues.
2. As Conference Host, with zero remote ICE, I want ACTIVE + awaiting, not Connecting.
3. As Participant, when Host is unreachable but another peer has media, I want not ACTIVE.
4. As Participant, when Host is reachable and I am joined, I want ACTIVE.
5. As user, during media recovery, I want Reconnecting, not Connecting.
6. As user, pending invitees must not count in primary meeting size.
7. As user, joined members recovering stay in count with recovering status.
8. As operator, I want CONFERENCE_PROJECTION logs with lifecycle/membership/connectivity/authority/phase.
9. As architect, I want forbidden derivations in ADR for PR review gates.
10. As PTT user, channelReadiness stays PTT-only.
11. As QA, I want G1-G5 unit tests on ConferenceRuntimeProjector.
12. As Host, awaiting with ACTIVE shows "waiting for more", not Connecting.
13. As reviewer, no WebRTC/invite retry/GROUP_JOIN changes (S8 intact).

---

## Implementation Decisions

### Test seam (highest)

**`ConferenceRuntimeProjector` pure function** — same seam as `ConferenceRuntimeProjectorTest`. No WebRTC mocks. Coordinator only assembles Input.

### ConferenceRuntimeProjector

Extend Input: `isConferenceHost` (or role), `authorityReachable` (participant v1: host media connected).

Phase rules:

- CONNECTING: not yet perceivably in meeting (`!transitionTerminalReady`, or participant fails P1).
- ACTIVE Host: `transitionTerminalReady && sessionAccepted` (v1 Local Conference Ready); **ignore** `remoteMediaCount`.
- ACTIVE Participant: P1 satisfied.
- RECOVERING: `mediaRecovering` only.
- **Forbidden**: `transitionTerminalReady && remoteMediaCount==0 => CONNECTING` for Host.

### TalkbackCoordinator

Wire new inputs in `projectConferenceRuntimeState`. Extend projection logger: `joined`, `pending`, `connected`, `authority`, `phase`, `awaiting`, `degraded`.

### UI

Keep reading `ConferenceRuntimeState.phase`. Primary meeting count: joined semantics; pending excluded from main label (minimum: stop roster/pending in "N participants").

### v1 mappings

- Local Conference Ready = `sessionAccepted && transitionTerminalReady`
- Authority Reachability = host media connected (implementation only)

---

## Testing Decisions

Assert external behavior (phase, awaiting, counts), not branch order.

| Gate | Scenario | Expected |
|------|----------|----------|
| G1 | Host solo, remote=0, terminal ready | ACTIVE, awaiting ok |
| G2 | Participant, peer media, authority false | not ACTIVE |
| G3 | Participant, authority true | ACTIVE |
| G4 | mediaRecovering | RECOVERING |
| G5 | Host established, remote=0 | not CONNECTING |

Prior art: `ConferenceRuntimeProjectorTest`. Full: `:android-board-talkback:testDebugUnitTest`.

---

## Out of Scope

- Invitation/Membership dual FSM, MEMBERSHIP_COMMITTED protocol
- WebRTC, ConferenceRecoveryController, invite retry (ADR-0018/0019)
- DEGRADED UI phase, RO-M2b, in-meeting PTT
- Soak S8 changes

---

## Further Notes

- Hold #69 until this follow-up is green or stacked.
- ADR-0010 visible count vs this PRD joined count: visible for avatars; joined for primary "N in meeting" when fixed.
- Implement order: ADR-0020 -> Projector + tests -> Coordinator + logs -> UI count -> soak.
