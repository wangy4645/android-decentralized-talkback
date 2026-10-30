# Phase 2 / Slice 1 — Session Wiring Gap Inventory

## Status

```text
COMPLETE (2026-09-07)
Scope: inventory only — no implementation, no TalkbackCoordinator refactor
Phase 1 receiver correctness: CLOSED @ N=10 — DO NOT REOPEN
Next: Slice 2 — minimal SessionMediaWiring seam
```

## Executive summary

Two **parallel media planes** exist today with **zero product intersection**:

| Plane | Owner | Lifecycle | Identity | Teardown |
|-------|--------|-----------|----------|----------|
| **Profile 03 multicast** (validated) | Phase 1 harness runners | Runner `run()` scope | `S1`–`S10` + SSRC fixture table | `endScope` + `stopPlayout`; assembly discarded |
| **Conference WebRTC** (product) | `TalkbackCoordinator` + `SessionMediaRegistry` | `TalkbackSession` | `moduleId` per peer | `releaseSessionMedia` → `admitConferenceRelease` |

`TalkbackCoordinator` has **no imports** of `AuthorityWiringRuntime`, `ConferenceMulticastRealMediaAssembly`, or `AdmittedMediaSource`. Session membership is tracked in `ConferenceParticipantManager`; media execution is entirely per-peer WebRTC engines.

Slice 2 must insert a **thin session-owned seam** between conference lifecycle events and the frozen multicast assembly — not extend harness runners, not replace WebRTC yet, not refactor coordinator internals.

---

## 1. Lifecycle owner

### Harness today

```text
ReceiverScaling*Runner / Slice*Runner
  owns: ConferenceMulticastRealMediaAssembly (ephemeral)
  start: installAuthority → syncAllAdmittedToRuntime → beginScope → startPlayout → playout clock
  stop:  endScope → stopPlayout → (assembly GC'd)
Sender runner
  owns: standalone ConferenceMulticastRtpSrtpTransport (no AuthorityWiring)
  start/stop: beginScope / endScope only
```

No `TalkbackSession`. No join/leave. Duration-bound soak.

### Product today

```text
TalkbackCoordinator
  session: TalkbackSession (CONFERENCE type)
  roster/invite/media UI state: ConferenceParticipantManager
  media engines: SessionMediaRegistry → MediaSessionManager (WebRTC per moduleId)
  teardown entry: releaseSessionMedia(session)
    → admitConferenceMediaRelease (per remote moduleId)
    → stopSessionCapture, clear audio buses, recovery clear
  hangup barrier: registerHangupMediaBarrier (WebRTC JNI release ordering)
```

`auditConferenceSessionLifecycle` logs session events; **does not touch P03 runtime**.

### Gap

| Item | State |
|------|--------|
| Session-scoped multicast assembly owner | **MISSING** |
| Shared receiver playout clock tied to session | **MISSING** (harness uses per-run `AbsoluteMediaPlayoutClock`) |
| Per-member sender tied to session + PTT | **MISSING** (harness sender is runner-scoped raw egress) |
| Coordinator hook for multicast lifecycle | **MISSING** |

### Slice 2 seam implication

Introduce **`ConferenceSessionMediaWiring`** (name TBD) as the **sole lifecycle owner** for one multicast assembly per conference session:

```text
onSessionMediaStart(sessionId, endpoint, …)   // once per session
onSessionMediaStop(sessionId)                 // symmetric; idempotent
```

Coordinator adds **thin calls** at existing session create/destroy (or first remote admitted / last released) — **delegate only**, no logic move into coordinator.

---

## 2. Identity mapping

### Harness today

```text
sourceIdentity:  literal "S1"…"S10" (Phase1MediaHarness.SourceFixture)
moduleId map:    deviceLabel → sourceIdentity (SENDER_SOURCE_BY_DEVICE)
                 M01→S1, M02→S2, M03→S3, M04=receiver only
SSRC resolve:    WireSourceIdentityResolver.resolveBySsrc(datagram, fixtures)
Authority:       Phase1MediaHarness.installAuthority(store, fixtures)
                 shared mediaKeyEpoch=1, incarnationId=1 per fixture
```

Ingress path: SSRC → fixture table → `sourceIdentity` string → `admitWire(sourceIdentity, …)`.

### Product today

```text
Primary key:     moduleId (EndpointAddress.moduleId.value)
Media state:     ConferenceParticipantManager.participantMedia (WebRTC ICE states)
Wire authority:  (not wired) — Profile 01 facts expected via VerifiedFactSeam
Profile 02/03:   sourceIdentity + incarnationId + ssrc + sourceAdmissionKey48 + mediaKeyEpoch
```

ADR-0058 Profile 01/02 defines `mediaKeyEpoch` / `sourceAdmissionKey48` from **control plane** at membership time — not from packets.

### Gap

| Item | State |
|------|--------|
| `moduleId` → `sourceIdentity` convention | **UNDEFINED** in product (harness uses `Sx`, not moduleId) |
| `moduleId` → SSRC + admission key derivation | **MISSING** (fixtures are static test vectors) |
| SSRC resolver backed by session roster | **MISSING** (`WireSourceIdentityResolver` is fixture-list harness) |
| VerifiedFactSeam fed from session join facts | **MISSING** |

**Recommended Slice 2 convention (minimal):**

```text
sourceIdentity := moduleId.value     // lexical P03 identity
ssrc + sourceAdmissionKey48          // from session/control fact at admit time (not harness hex)
mediaKeyEpoch                        // from conference media key epoch at session scope
incarnationId                        // monotonic per (sessionId, moduleId) generation
```

### Slice 2 seam implication

```text
onRemoteMemberAdmitted(sessionId, moduleId, SourceAuthorizationFact, MediaKeyContextFact)
  → wiring.installNewEpochSource + syncAdmittedToRuntime
onRemoteMemberRevoked(sessionId, moduleId, incarnationId)
  → wiring.revokeSource
```

Plus session-scoped **`SourceBindingCatalog`** (moduleId ↔ ssrc) for `resolveBySsrc` — replaces static fixture list.

---

## 3. Generation isolation

### Harness today

```text
All fixtures: incarnationId=1, mediaKeyEpoch=1 for entire soak
No rejoin, no key rotation, no retireMediaKeyEpoch in field runs
AuthorityWiringRuntime paths exist (unit-tested):
  installNewEpochSource / revokeSource / retireMediaKeyEpoch
```

Promotion live-edge fence runs in `MediaExecutionPipeline.selectTopK()` — **will apply automatically** once session wiring installs sources.

### Product today

```text
WebRTC: per-peer PC renegotiation / ICE restart (separate generation model)
Conference: membershipVersion / mediaKeyEpoch in control docs — not bridged to AuthorityFactStore
Rejoin: conferenceParticipantManager.onLateJoin, leftMemberEndpoints, reinvite flows
  → triggers requestConferenceEngine / mesh engine reuse
  → does NOT fence P03 incarnation
```

### Gap

| Event | WebRTC path | P03 multicast path |
|-------|-------------|-------------------|
| Member leave / prune | `admitConferenceRelease` | **no revokeSource** |
| Rejoin same moduleId | engine reuse or new PC | **no new incarnationId** |
| mediaKeyEpoch rotation | N/A (not P03) | **no retireMediaKeyEpoch** |
| Session end | `releaseSessionMedia` | **no assembly teardown** |

### Slice 2 seam implication

Mirror authority contract already in `AuthorityWiringRuntime`:

```text
Leave/revoke:  revokeSource(identity, incarnationId)     // hardFence pipeline + selection
Session end:   retireMediaKeyEpoch(epoch)               // fence all sources on epoch
Re-admit:      installNewEpochSource with NEW incarnationId  // never reuse old jitter state
```

Harness Slice 2 tests should include **leave → rejoin same moduleId** with incarnation bump (no field devices required).

---

## 4. Symmetric teardown

### Harness today (receiver)

```text
Symmetric partial:
  transport.endScope()        ✓  (multicast lock + socket)
  playoutClock.stop()         ✓
  assembly.stopPlayout()      ✓
Asymmetric / missing:
  revokeSource per fixture    ✗  (rely on process exit)
  hardFence all sources       ✗
  decoder pool explicit drain ✗  (acceptable for ephemeral assembly)
  retireMediaKeyEpoch         ✗
```

### Harness today (sender)

```text
transport.endScope() only — no authority install/revoke (egress-only)
```

### Product today (conference)

```text
releaseSessionMedia:
  admitConferenceRelease per moduleId (WebRTC)
  stopSessionCapture, clear buses, qos reset
  ConferenceParticipantManager.removeSession (on session remove path)
HangupMediaBarrier: coordinates async WebRTC release before channel teardown
```

### Gap

| Check | Harness | Product multicast |
|-------|---------|-------------------|
| Transport scope end | ✓ | N/A (not wired) |
| Playout stop | ✓ | N/A |
| Per-source hardFence on leave | ✗ | **required for session wiring** |
| Epoch retire on session end | ✗ | **required** |
| Leak harness (N join/leave cycles) | not tested | **Slice 2 G5** |
| Barrier ordering with coordinator hangup | N/A | **must align** with `releaseSessionMedia` call order |

### Slice 2 seam implication

`onSessionMediaStop` must be **idempotent** and ordered:

```text
1. stop playout clock + AudioTrack
2. for each admitted source: revokeSource (or retireMediaKeyEpoch once)
3. transport.endScope()
4. assert: activeJitterSourceCount==0, liveDecoders==0, no admitted in store
```

Coordinator: call wiring stop from existing `releaseSessionMedia` **before or after** WebRTC release (parallel planes) — **do not merge release paths**.

---

## Architecture sketch (target Slice 2)

```text
TalkbackCoordinator (unchanged logic; +thin delegates)
        │
        ▼
ConferenceSessionMediaWiring          ← NEW (minimal seam)
  sessionId → ConferenceMulticastRealMediaAssembly (1:1)
  AuthorityWiringRuntime (embedded in orchestrator)
  SourceBindingCatalog (moduleId/ssrc)
        │
        ▼
Frozen Phase 1 pipeline (NO changes)
  ConferenceMulticastMediaPipeline
  MediaExecutionPipeline + promotion fence
  Profile 03 caps @ N=10
```

---

## Proposed Slice 2 API (minimal)

```kotlin
interface ConferenceSessionMediaWiring {
    fun startSession(sessionId: String, endpoint: MediaGroupEndpointBinding, …): Boolean
    fun admitRemoteSource(sessionId: String, source: SourceAuthorizationFact, key: MediaKeyContextFact)
    fun revokeRemoteSource(sessionId: String, sourceIdentity: String, incarnationId: Long)
    fun startLocalSender(sessionId: String, …)   // PTT-bound; Slice 2b if needed
    fun stopLocalSender(sessionId: String)
    fun stopSession(sessionId: String)             // symmetric teardown
    fun assembly(sessionId: String): ConferenceMulticastRealMediaAssembly?
}
```

Implementation lives **outside** `TalkbackCoordinator`; coordinator holds optional reference and calls at 3–5 existing lifecycle points.

---

## Coordinator hook candidates (inventory only)

| Event | Existing location | Slice 2 call |
|-------|-------------------|--------------|
| Conference session created / accepted | session create / accept paths | `startSession` |
| Remote media connected | `onMediaConnected` / ICE connected handlers | `admitRemoteSource` (after control facts available) |
| Member pruned / left | `applyPrune`, leave handlers | `revokeRemoteSource` |
| Session hangup / leave | `releaseSessionMedia` | `stopSession` |
| Local PTT press/release | `startSessionCapture` / stop capture | `startLocalSender` / `stopLocalSender` (Slice 2b) |

**Open question for Slice 2 design:** where do `SourceAuthorizationFact` / `MediaKeyContextFact` originate in product? Likely conference control / GBC path — **inventory does not block seam**; Slice 2 harness can inject facts like Phase 1 until control bridge exists.

---

## Explicit non-goals (unchanged)

```text
Phase 1 receiver / caps / promotion fence     frozen
Real Mesh E2E field                           deferred
Meeting UI cutover                            deferred
ADR-0056 cutover                              KEEP
WebRTC removal or automatic unicast fallback  deferred
TalkbackCoordinator refactor                  forbidden in Slice 2
10-device field requirement                   not required
```

---

## Slice 2 validation plan (preview)

```text
G1  Harness: admit → play → revoke → assert fenced (no field)
G2  Harness: leave → rejoin same moduleId, new incarnationId, no stale jitter
G3  Harness: session stop → zero admitted / zero jitter / endScope
G4  Field (optional): 2–3 member session on M01–M04, lifecycle only
G5  No receiver cap regression (reuse frozen Phase 1 metrics if soak needed)
```

---

## References

- Phase 2 card: `phase2-conference-session-media-wiring-run-card.md`
- Phase 1 closure: `phase1-receiver-scaling-10source-run-card.md`
- Authority wiring: `AuthorityWiringRuntime.kt`, `AuthorityFactStore.kt`
- Harness pattern: `ReceiverScaling10SourceRunner.kt` (lines 175–177, 434–466, 518–522)
- Product media release: `TalkbackCoordinator.releaseSessionMedia` (~12595)
- E2b deferral: `0058-e2b-slice3-product-seams.md` (“Product session wiring — not authorized by this slice”)
- Identity resolver (harness): `WireSourceIdentityResolver.kt`
