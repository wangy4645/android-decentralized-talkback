# Conference Control / Media Execution Isolation

## Status

**FROZEN ROADMAP** (2026-08-20)

**Contract name:** Conference Control/Media Execution Isolation

**Track:** Conference realization execution boundary. **Not** a new ADR. **Does not** reopen ADR-0056 topology / Ready / Health / Recovery / membership / RNA / completion predicate.

**Relation:**

- ADR-0056 topology-first — **retained**; this document only isolates the last realization execution layer
- P0.1b `ConferenceMediaJniAffinity` (SRD / `addIceCandidate` off coordinator) — **kept**; insufficient alone
- M03 `setRemoteDescription` hang — **still a bug**; **out of scope until P0.1d**

```text
WebRTC / SDP / ICE / Anchor SFU-lite / ActualMediaEdgeSet / ConferenceAudioBus
  = RETAINED

Coordinator
  = RETAINED (single-thread control facts + dispatch)

Per-edge executor
  = RETAINED (PeerConnection JNI)

“Move all WebRTC APIs off coordinator in one PR”
  = CANCELLED (too large; not evidenced)

Invariant
  = Conference control facts MUST NOT wait on media JNI completion
```

This document is the only authorized sequence. Do not reopen Ready / Audio policy / Topology / ICE restart **semantics** / EdgeController / coordinator multi-thread split.

---

## Field facts (do not relitigate)

```text
M04  GROUP_ACCEPT → MEMBER_ACCEPTED → SRD AFTER
M03  GROUP_ACCEPT → MEMBER_ACCEPTED → SRD BEFORE → hang
M02  GROUP_ACCEPT ENQUEUE → no HANDLE_SIGNAL_ENTER
```

Interpretation:

```text
Problem A: M03 SRD JNI does not return          (P0.1d)
Problem B: after A, other peers cannot enter coordinator
           (P0.1c — remaining coordinator JNI on callback path)
```

P0.1b already moved SRD / settled `addIceCandidate` off coordinator. M04 ACCEPT can complete `MEMBER_ACCEPTED`. M02 still fails to dequeue. Therefore the remaining barrier is **another coordinator JNI call**, not `handleGroupAccept` itself.

Located callback chain (still on coordinator after P0.1b):

```text
ICE / track callback
  → runOnCoordinator
     → onMeshIceStateChanged
        ├─ updateSessionReceivePlayback → setRemotePlaybackEnabled   [JNI]
        ├─ syncConferenceRelay → setProgramRelayMode / PCM tap       [JNI]
        └─ attemptConferencePeerIceRestart → createOffer / snapshot  [JNI]
```

If M03 SRD holds WebRTC’s internal lock, any of the three on the coordinator thread stalls the whole control plane.

---

## Target architecture (unchanged)

```text
                  Conference Coordinator
                         │
                  control facts / dispatch
                         │
              ┌──────────┼──────────┐
              ↓          ↓          ↓
           Edge M02   Edge M03   Edge M04
              │          │          │
           WebRTC      WebRTC      WebRTC
```

One edge’s WebRTC failure must not stall other edges’ `MEMBER_ACCEPTED`, Leave, or further ACCEPT dequeue.

---

## Phases (authorized order)

### P0.1c — Execution isolation (NOW)

Two work items only.

**a) Runtime guard (behavior-neutral)**

In `RealWebRtcAudioEngine`, every JNI entry logs when the current thread is `talkback-coordinator`:

```text
MEDIA_JNI_ON_COORDINATOR op=<api> thread=talkback-coordinator tid=...
```

Do not throw. Do not change API semantics. One field run then lists remaining leaks instead of guessing.

**b) Move evidenced coordinator JNI off the control thread**

| Path | Coordinator keeps | Per-edge / media executor does |
|------|-------------------|--------------------------------|
| Playback | `lastPlaybackEnabledBySession` + decision | `setRemotePlaybackEnabled` |
| Relay | log + observability publish after hop | `ConferenceAudioBus.updateParticipants` + mic feed + receive-path taps |
| ICE restart | “dispatch accepted” fact; signal send after JNI | `negotiationSnapshot` / `createOffer` / `drainPendingIce` |
| Audio level poll | 100ms schedule / which sessions to poll | `refreshAudioLevel` → `getStats` |

Field 2026-08-20 (`p01c-accept1-20260820-154305`): after playback/relay/ICE-restart hop, M04 still `ENQUEUE` without `ENTER`. Last coordinator line was `MEDIA_JNI_ON_COORDINATOR op=refreshAudioLevel`. That poll is in P0.1c.

Do **not** migrate `createOffer` / `negotiationSnapshot` for normal join (P0.1e).

Relay hop uses session key `{sessionId}|__relay__` so coordinator is free even if one engine’s JNI blocks the relay thread. Per-target relay split is **not** this phase.

ICE restart `createOffer` is fire-and-forget onto the peer’s edge executor. Return `true` means **accepted for media execution**, not “offer UDP already sent”. Signaling send hops back with `runOnCoordinator`. Lock wrapping send stays; JNI must not run under coordinator + blocking signaling lock.

**P0.1c MUST NOT:**

- change `setRemoteDescription` / `PendingSdpWait`
- change ICE restart **policy** (when to restart) — only the thread of JNI
- change Ready / Health / Topology / Generation
- introduce `EdgeController`
- split coordinator into multiple control threads
- change Audio mix / MCU-lite **policy**
- move remaining `createOffer` / `SLD` for normal join (that is P0.1e)
- “fix M03 SRD hang”

### P0.1c — CLOSED / field PASS (2026-08-20)

Evidence: `talkback/logs/p01c-accept1-20260820-155316/` session `ab7b1400`.

```text
M03 SRD BEFORE (~7s blocked)
M02 MEMBER_ACCEPTED
M04 ENQUEUE → ENTER → MEMBER_ACCEPTED
refreshAudioLevel on coordinator = 0
```

### P0.1d — CLOSED / field PASS (2026-08-20)

Evidence: `talkback/logs/p01d-srd-timeout-20260820-160242/` session `a9928db1`.

```text
M03 SRD BEFORE → 3s SRD_TIMEOUT → EDGE_FAILED
M04 MEMBER_ACCEPTED after M03 FAILED (isolation holds)
M04 SRD also SRD_TIMEOUT → EDGE_FAILED  (own edge, not coordinator barrier)
```

**`EDGE_FAILED` is a terminal fact for the current generation.** Not “still connecting”.

```text
ADMITTED → APPLYING → CONNECTING
ADMITTED → APPLYING → EDGE_FAILED
```

Current generation after `EDGE_FAILED`:

```text
Ready      not realized
Health     not usable
Recovery   no auto re-realize  (NOT STARTED)
Audio      frozen
Topology   frozen
```

Do **not** attach Recovery / ICE restart / re-offer to this timeout.

Next: **Acceptance 2** (clean 4p, no fault injection). If 1–2 edges still `SRD_TIMEOUT`, the remaining problem is per-edge WebRTC realization / PC lifecycle — not coordinator.

### P0.1e — Remaining build-edge JNI (scope from guard logs)

`createOffer` / `SLD` for **normal** join stay on coordinator until `MEDIA_JNI_ON_COORDINATOR` proves they stall control facts. Then migrate that exact op only.

---

## Acceptance

**Acceptance 1 — fault isolation (required first)**

Deliberate M03 SRD block:

```text
M03: SRD BEFORE [blocked]
M02: GROUP_ACCEPT → MEMBER_ACCEPTED
M04: GROUP_ACCEPT → MEMBER_ACCEPTED
```

Coordinator must continue to dequeue `handleSignal` while one edge JNI is blocked.

**Acceptance 2 — normal 4p (only if Acceptance 1 PASS)**

No fault injection:

```text
3/3 admitted edges
3/3 ICE CONNECTED
3/3 Ready
```

If Acceptance 1 fails, do not run Acceptance 2, do not touch Audio, do not scale to 8–10.

---

## Gap naming

```text
CONFIRMED conference control thread still executes WebRTC JNI on ICE/track callback
restore coordinator / per-edge JNI affinity for playback, relay, and ICE-restart createOffer
```

Do not write: Recovery broken · Watchdog bug confirmed · fix recovery timeout · MediaState sticky · stop using WebRTC.

---

## Stop conditions

| Observation | Route |
|-------------|--------|
| Isolation PASS, M03 SRD still hangs | P0.1d only |
| Guard still logs JNI on coordinator after P0.1c | P0.1e, that `op=` only |
| membership not converged but completion succeeds | RCA-0036 / ADR-0038 — **not this track** |
| owner conflict | ADR-0039 — **not this track** |
