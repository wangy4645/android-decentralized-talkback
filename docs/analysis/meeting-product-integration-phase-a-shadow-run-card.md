# Meeting Product Integration — Phase A Shadow Run Card

## Status

```text
Gate: Meeting Product Integration — Phase A Shadow (design)
Meeting J0 / Profile01 chain       CLOSED / FIELD-PROVEN

Meeting Profile01 wire ingress     IMPLEMENTED / UNIT PASS
Phase A Shadow Runtime Gate        AUTHORIZABLE / NEXT
  auth: meeting-product-integration-phase-a-shadow-runtime-gate-authorization.md
  run:  meeting-product-integration-phase-a-shadow-runtime-run-card.md
Phase A A1–A4 field                BLOCKED pending Shadow Runtime PASS
Origin Discovery (historical)      COMPLETE / ORIGIN_GAP (superseded by J0 for CREATION E2E)

Conference media foundation        FROZEN
Phase B Controlled Media Enable    HOLD
ADR-0056 cutover                   HOLD / KEEP
ADR-0056 deletion                  FORBIDDEN
Meeting media cutover              NOT AUTHORIZED
```

Phase A shadow **field-adjudication** proceeds via **Phase A Shadow Runtime Gate** after J0 PASS.
Origin Discovery ORIGIN_GAP is historical; J0 supersedes CREATION peer E2E for control-chain closure.

## Principle

> **让真实 Meeting 生命周期驱动新媒体路径，但用户实际会议行为仍由 ADR-0056 承担。**

Prove **wiring correctness** first — not cutover.

## Shadow boundaries (locked)

```text
1. Shadow may start real multicast runtime + receive facts + emit observability.
2. Shadow MUST NOT open AudioTrack or change ADR-0056 user-hearable Meeting audio.
3. Shadow failure → SHADOW_FAILED / DEFERRED_* only — never fail Meeting.
```

Implementation:

```text
ConferenceSessionMediaWiring.forShadow(context)
  → ConferenceMulticastRealMediaAssembly.createShadow
  → RecordingPlayoutMetricsSeam (no AudioTrack)
  → real AndroidRuntimeResources / multicast transport
```

## Lifecycle mapping (reuse existing hooks — no second Meeting lifecycle)

| Meeting event | Delegate hook | TalkbackCoordinator call site |
|---------------|---------------|-------------------------------|
| Meeting create | `onConferenceSessionStarted` | `meshCallInternal` — `SessionType.CONFERENCE` session created |
| Member join / media ready | `onConferenceMemberMediaReady` | ICE `CONNECTED` — `conferenceParticipantManager.onMediaConnected` |
| Member leave / authority prune | `onConferenceMemberRemoved` | `commitAuthorityPrune` · `applyConferenceMembershipLeaveLocal` |
| Rejoin / successor | `onConferenceMemberReplaced` | `handleConferenceRejoinRequest` — `JOIN_RESTORE` |
| Meeting end | `onConferenceSessionStopped` | `releaseSessionMedia` — conference hangup |

```text
Meeting create        → shadow startSession
Member join/ready     → shadow installMember
Member leave/prune    → shadow removeMember
Rejoin/successor      → shadow replaceMember
Meeting end           → shadow stopSession
```

## Phase A exit gates

```text
A1 lifecycle parity
   create/join/leave/rejoin/end hooks fire 1:1 with Meeting events
   observability: MeetingProductMediaShadow.observability

A2 generation correctness
   replaceMember consumes registry pair; stale source fenced
   (requires Profile01 facts in control registry — next wire slice)

A3 isolation
   shadow exception → SHADOW_FAILED; Meeting/ADR-0056 unaffected
   test: MeetingProductMediaShadowDelegateTest

A4 teardown
   stopSession → wiring empty (source/jitter/decoder/socket)
   (requires APPLIED session — after fact wire connected)
```

## Not in Phase A

```text
N=10 re-proof
Mesh multicast re-proof
User hears new-path audio
ADR-0056 deletion
Phase B controlled media enable
```

## Observability

```text
TAG: MEETING_MEDIA_SHADOW
evidenceScope: MEETING_PRODUCT_SHADOW_PHASE_A
snapshot: MeetingProductMediaShadow.observability.snapshot()
```

## Profile01 fact wire (Phase A slice — IMPLEMENTED)

```text
Meeting CONFERENCE_SIGNED_FACT (ADR-0058 outer envelope)
    ↓ base64 SignedFact bytes
MeetingProfile01FactWireIngress
    ↓
Profile01ConferenceMediaFactIngress (existing — no policy duplication)
    ↓
registry / MeetingProfile01AwareConferenceSessionMediaFactPort
    ↓
5 delegate hooks → shadow wiring (forShadow)
```

Wire outcomes: `MeetingProfile01FactWireIngress.observability` (tag `MEETING_MEDIA_SHADOW`).

Typical outcomes before facts arrive on wire:

```text
DEFERRED_NO_FACT      — session hook before control facts published
DEFERRED_NO_BINDING   — member ready before member fact
DEFERRED_NO_REPLACE_PAIR — rejoin before replace pair in registry
DEFERRED_NO_SESSION   — wire fact for unknown Meeting sessionId (peer pre-Accept)
```

Coordinator `DEFERRED_*` → `APPLIED` via hook replay once wire ingress applies the fact.
**Exception (pre-bind, v1 CREATION-only):** `DEFERRED_NO_SESSION_RETAINED` retains CREATION until
`SESSION_BIND_DRAIN`; other fact types log `PRE_BIND_NOT_RETAINED` — see
`meeting-profile01-pre-bind-fact-retention-drain-design-slice.md`. Decode/verify/publish failures →
`REJECTED_*` / `SHADOW_FAILED` only — never fail ADR-0056 Meeting.

sessionId (UUID) ↔ conferenceId (wire id128) bridged by `MeetingProfile01ConferenceSessionIndex`.

## Next: Phase A Shadow Runtime field gate

```text
meeting-product-integration-phase-a-shadow-runtime-gate-authorization.md   (AUTHORIZABLE / NEXT)
meeting-product-integration-phase-a-shadow-runtime-run-card.md
```

After J0: adjudicate **shadow runtime** (SESSION_STARTED → admitted sources → ingress/playout → teardown)
while ADR-0056 keeps user-hearable audio. Do **not** harness-inject Profile01 facts.

## References

- Gate: `meeting-product-integration-gate.md`
- Foundation freeze: `conference-media-foundation-freeze.md`
- Hooks: `ConferenceSessionMediaCoordinatorDelegate.kt`
- Shadow: `MeetingProductMediaShadow.kt`
- Factory wiring: `TalkbackRuntimeFactory` → `forShadow`
