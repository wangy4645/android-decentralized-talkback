# Phase A Shadow Runtime — PR-PA-SR4-TX Design

**PR:** `PR-PA-SR4-TX` — Shadow Multicast Transmit (Host Session + Capture Tap)  
**RCA:** `meeting-product-integration-phase-a-sr4-rca-runtime-execution-20260909-161221.md`  
**Prerequisite:** PA-SR3 **CLOSED** · PA-SR4 RCA **CLOSED** · B1-R2 **CLOSED**  
**Date:** 2026-09-09  
**Status:** **DESIGN FROZEN** (TX scope only)

```text
PA-SR4 RCA        CLOSED (GAP-A/B/C identified)
PR-PA-SR4-TX      DESIGN FROZEN
PR-PA-SR4-RX      DESIGN NEXT — not in this PR
PR-PA-SR4-PLAY    HOLD — not in this PR
PA-SR4 full gate  still VALID FAIL until RX (+ maybe PLAY) proven
ADR-0056          KEEP — audible owner unchanged
cutover           NOT AUTHORIZED
```

---

## Goal

Close **GAP-A** and **GAP-B** from SR4 RCA — host shadow session assembly and product-origin multicast egress — **without** changing PTT product semantics or ADR-0056 ownership.

```text
production capture (existing PTT / floor path)
      ├── ADR-0056 WebRTC              ← audible owner (unchanged)
      └── ADR-0058 shadow TX tap       ← metrics/evidence only (this PR)
```

This PR proves **protected multicast datagrams leave the host** under a real Meeting session. It does **not** prove peer ingress or playout (PA-SR4 remains open until RX).

---

## Non-goals (frozen)

```text
- New PTT semantics, floor rules, or Meeting state machine changes
- Shadow control of microphone / AudioRecord ownership
- Reverse extract from WebRTC encoded RTP/SRTP
- Product receive executor (PR-PA-SR4-RX)
- Mix/playout slot driver (PR-PA-SR4-PLAY — HOLD)
- PA-SR4 PASS / cutover authorization
- SOURCE / B1-R2 reopen
- Host MEMBER_MEDIA_READY(M02) closure (out of SR4-TX scope)
```

---

## Architecture

```text
┌─────────────────────────────────────────────────────────────────┐
│ TalkbackCoordinator.startSessionCapture / stopSessionCapture    │
│   (existing CONFERENCE trigger — no new PTT API)                │
└────────────┬───────────────────────────────┬────────────────────┘
             │                               │
             ▼                               ▼
   dispatchConferenceCaptureControl    Profile01ShadowMulticastTransmitSeam
   → WebRtcAudioEngine.startCapture     .onProductionCaptureStart/Stop
   (ADR-0056 — unchanged)                      │
                                               ▼
                                    ProcessLocalMicFrameSource.acquire
                                    → PcmFrameAssembler (10 ms → 20 ms)
                                    → Opus encode (ConcentusOpusDecoderSeam)
                                    → Egress gate (TX-3)
                                    → SourceScopedSrtpEgress.protectNext
                                    → transport.sendFromSource
                                               │
                                               ▼
                                    MulticastTransportObservability.packetsSent

┌─────────────────────────────────────────────────────────────────┐
│ Host authoritative CREATION commit + supplement READY             │
│   → HostLocalSessionFactProjection (TX-A)                       │
│   → registry.publishSession (existing ingress seam)             │
│   → replaySessionStarted (idempotent)                           │
│   → ConferenceSessionMediaWiring.startSession                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## TX-A — Host shadow `SESSION_STARTED` replay (TX-1)

### Problem

Host invokes `onConferenceSessionStarted` at invite creation **before** `ConferenceSessionMediaControlFactRegistry` has a session fact → `DEFERRED_NO_FACT`. Peer later gets `WIRE_INGRESS factType=1 APPLIED` + `SHADOW_SESSION_STARTED`. Host never replays.

Field evidence: `M01 SHADOW hook=SESSION_STARTED outcome=DEFERRED_NO_FACT`; no `WIRING_SESSION_STARTED` on host (`c70b2e03…` run).

### Contract

After host **authoritative CREATION commit**, local shadow `SESSION_STARTED` must eventually reach **`outcome=APPLIED`** with `WIRING_SESSION_STARTED transportScopeActive=true`.

**Must:**

- Reuse proven Profile01 authoritative state (`creationStateBySession.signedFactBytes`, supplement registry, session index).
- Project into registry via **existing** `Profile01ConferenceMediaFactIngress.ingestCreationSignedFact` → `publishSessionDeclaration` (same decode/validate/publish path peer uses after verify).
- Replay `ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted` via shared helper (extract from `MeetingProfile01FactWireIngress.replaySessionStarted` or equivalent).
- Be **idempotent**: if `ConferenceSessionMediaBridge.hasSession(sessionId)`, skip `startSession` but log `SHADOW_SESSION_ALREADY_LIVE`.

**Must not:**

- Self-ingress CREATION through UDP / signaling loopback.
- Invent a parallel host-only session fact shape or registry writer.
- Re-emit CREATION on the wire for replay purposes.

### Trigger (two-legged AND)

```text
maybeProjectHostShadowSession(sessionId):
  creationStateBySession[sessionId] present     # authoritative commit done
  AND supplement READY for conferenceId/epoch   # same gate peer uses
  → ingestCreationSignedFact(cached bytes, supplement, iface)
  → on ACCEPTED: replaySessionStarted(...)
```

Call sites (both idempotent):

1. `onMeetingProfile01HostCreationAuthoritativeCommit(sessionId)` — supplement may already be READY.
2. `onMeetingProfile01MediaSupplementReady(conferenceId, epoch)` — creation may already be committed.

### OBS (TX-A)

Reuse existing shadow lifecycle lines:

```text
SHADOW hook=SESSION_STARTED outcome=APPLIED
PROFILE01_SHADOW_RUNTIME phase=WIRING_SESSION_STARTED transportScopeActive=true
PROFILE01_SHADOW_RUNTIME phase=SHADOW_SESSION_STARTED
```

New behavior-neutral marker (recommended):

```text
PROFILE01_SHADOW_RUNTIME phase=HOST_SESSION_PROJECTED sessionId=H outcome=APPLIED|DEFERRED_*
```

---

## TX-B — Capture tap seam (TX-2)

### Problem

Conference PTT today only drives `dispatchConferenceCaptureControl` → `WebRtcAudioEngine.startCapture()` (ADR-0056). No path feeds shadow `sendFromSource`.

### Contract

Fan out **process-local microphone PCM** to shadow encoder when production capture **starts** for a CONFERENCE session. Shadow does **not** initiate capture; it **observes** the same start/stop intent production already uses.

### Tap point (frozen)

**Primary:** `ProcessLocalMicFrameSource` → `WebRtcSharedFactory.addLocalOutboundSink` (`LocalMicFrameSource.kt`).

Rationale:

- Documented ADR-0056 Phase 1a-5 PCM tap; **pre-encode**, not WebRTC RTP.
- Does not acquire microphone independently; `acquire`/`release` paired with shadow tap lifecycle only.
- Topology-agnostic (MESH field runs show `localMicActive=false` on anchor feed — shadow must not depend on `ConferenceLocalMicFeed` ANCHOR-only gating).

**Forbidden tap points:**

- WebRTC encoded packet / RTP extractor
- Inbound remote PCM (`InboundPcmSink`)
- Shadow-initiated `AudioRecord` / `startCapture` on behalf of product

### Lifecycle coupling

```text
TalkbackCoordinator.startSessionCapture(CONFERENCE)
  → Profile01ShadowMulticastTransmitSeam.arm(sessionId)   # try; may no-op if gate closed

TalkbackCoordinator.stopSessionCapture(CONFERENCE)
  → Profile01ShadowMulticastTransmitSeam.disarm(sessionId)

ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStopped
  → disarm + clear egress state
```

`arm`/`disarm` are **synchronous try** calls from coordinator hooks wrapped in try/catch at seam boundary (TX-4). Coordinator must not await network I/O.

### PCM framing

- Assemble via `PcmFrameAssembler` / `ConferencePcmFormat` (48 kHz mono s16).
- Encode 20 ms frames via `ConcentusOpusDecoderSeam.encodePcm` (existing harness seam).
- One encoded frame → one `sendFromSource` attempt per media tick (no burst catch-up that blocks coordinator).

---

## TX-3 — Egress gate (frozen)

Shadow may `encode → protect → sendFromSource` only when **all** hold:

| Gate | Source |
|------|--------|
| G1 Shadow session live | `ConferenceSessionMediaBridge.hasSession(sessionId)` && `transportScopeActive` |
| G2 Local member binding | `registry.member(conferenceId, localModuleId)` matches current session epoch |
| G3 Local SOURCE committed | `Profile01LocalConferenceSourceIdentityAuthority.currentCommitment` + SOURCE fact applied for local module |
| G4 Media key alignment | `member.mediaKeyEpoch == session.mediaKeyEpoch == activeMediaKeyEpoch` |
| G5 Generation fence | `member.sourceGeneration` / `incarnationId` match registry; stale generation → drop frame |
| G6 Production capture armed | shadow tap `armed` for session (coupled to startSessionCapture) |

On any drift (session stop, epoch bump, replace, revoke): **disarm immediately**, drop in-flight frames, log `SHADOW_TX_FENCED reason=…`. No retry queue that spans session generations.

Egress material:

- `SourceScopedSrtpEgress` from session `masterKey`/`masterSalt` + local `ssrc` + `sourceIdentity` (from registry member binding).
- Endpoint from `ConferenceSessionMediaFact.endpoint`.
- Send via `ConferenceSessionMediaWiring.transport(sessionId).sendFromSource`.

---

## TX-4 — Shadow failure isolation (frozen)

```text
Shadow multicast encode/protect/send failure
  → log + observability counter only
  → MUST NOT propagate to TalkbackCoordinator capture/WebRTC path
  → MUST NOT change PTT floor, Meeting state, mute, or user-hearable audio
  → MUST NOT throw through coordinator hooks
```

Implementation pattern: `MeetingProductMediaShadow.runShadowHook` boundary or dedicated try/catch inside `Profile01ShadowMulticastTransmitSeam` with `SHADOW_TX_FAILED` OBS.

---

## TX field gate (independent of PA-SR4 PASS)

**Gate name:** `PA-SR4-TX` (transmit-only)

```text
Host SHADOW hook=SESSION_STARTED outcome=APPLIED
Host WIRING_SESSION_STARTED transportScopeActive=true
→ M01 PTT stimulus (3× burst — same harness pattern as SR4)
→ PROFILE01_SHADOW_RUNTIME phase=SHADOW_TX_ACTIVITY packetsSent>0
   (or protectedDatagramsSent>0 — adjudicator frozen below)
```

**PASS means:**

```text
PR-PA-SR4-TX   FIELD-PROVEN
PA-SR4         VALID FAIL / RX NEXT
```

**Does not mean:** `INGRESS_ACTIVITY`, `PLAYOUT_ACTIVITY`, or PA-SR4 PASS.

### Recommended OBS (`Profile01ShadowRuntimeObservability`)

```text
PROFILE01_SHADOW_RUNTIME phase=SHADOW_TX_ACTIVITY sessionId=H
  packetsSent=N protectedSent=N sendErrors=N moduleId=M01 audioTrackOwner=SHADOW_METRICS_ONLY
```

Emit on first successful send + every 25th (mirror ingress throttle). Source: `MulticastTransportObservability` on session transport after `sendFromSource` success.

---

## Unit test matrix (minimum)

| ID | Case |
|----|------|
| TX-U1 | Host HLSP: creation committed + supplement READY → registry session + `startSession` called once |
| TX-U2 | Idempotent HLSP: second call does not double `beginScope` |
| TX-U3 | HLSP deferred: supplement missing → no registry session |
| TX-U4 | Egress gate: session missing → arm no-ops, 0 packets |
| TX-U5 | Egress gate: epoch mismatch → fenced, 0 packets |
| TX-U6 | Tap: production capture start → PCM frame produces send attempt (harness wiring) |
| TX-U7 | Tap: production capture stop → disarm, no further sends |
| TX-U8 | Isolation: send throws → coordinator capture path still completes (mock) |
| TX-U9 | Session stop → disarm + transport endScope unaffected on ADR-0056 mocks |

---

## File touch map (expected)

```text
NEW  Profile01HostLocalSessionFactProjection.kt (or equivalent)
NEW  Profile01ShadowMulticastTransmitSeam.kt
MOD  TalkbackCoordinator.kt          — capture start/stop hooks (thin)
MOD  TalkbackRuntimeFactory.kt       — wire seam
MOD  Profile01ShadowRuntimeObservability.kt — SHADOW_TX_ACTIVITY + HOST_SESSION_PROJECTED
MOD  MeetingProfile01FactWireIngress.kt — extract replaySessionStarted helper (optional)
TST  Profile01ShadowMulticastTransmitSeamTest.kt
TST  Profile01HostLocalSessionFactProjectionTest.kt
DOC  TX field run-card + adjudicator script (authorized separately)
```

---

## Sequencing after TX

```text
PR-PA-SR4-TX field PASS
  → authorize PR-PA-SR4-RX only
  → prove INGRESS_ACTIVITY
  → then decide if PR-PA-SR4-PLAY needed (do not preset)
```

---

## References

- RCA: `meeting-product-integration-phase-a-sr4-rca-runtime-execution-20260909-161221.md`
- Host deferral field: `talkback/logs/phase-a-sr4-field-20260909-161221/M01-logcat.txt`
- Peer replay pattern: `MeetingProfile01FactWireIngress.replaySessionStarted`
- Runner send reference: `RealMeshE2ERunner.soakSend`
- PCM tap: `ProcessLocalMicFrameSource`, `LocalMicFrameSource.kt`
- E2b deferral: `0058-e2b-slice3-product-seams.md`
