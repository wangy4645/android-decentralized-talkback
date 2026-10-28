# RCA — PA-SR4 Runtime Execution (`RCA_PA_SR4_RUNTIME_EXECUTION`)

**Field run:** `talkback/logs/phase-a-sr4-field-20260909-161221`  
**Verdict:** `FAIL_PA_SR4` (valid product fail — SR3 prerequisite green)  
**Date:** 2026-09-09  
**Scope:** Shadow runtime **datagram-plane execution** only. **No code change in this document.**

**Explicitly out of scope:** SOURCE / B1-R2 · OBS sufficiency reopen · decoder / AudioTrack quality · ADR-0056 cutover.

---

## Failure locus (frozen)

```text
SR1–SR3 same-session established (peer M02)
  → MEMBER_MEDIA_READY(M01) APPLIED
  → WIRING_MEMBER_INSTALLED admittedCount=1 transportScopeActive=true
  → M01 PTT stimulus (captureON on conference session)
  → 0 × INGRESS_ACTIVITY
  → 0 × PLAYOUT_ACTIVITY
  → activeJitterSources=0 liveDecoders=0 jitterBufferCount=0
```

**Not** SR3 / SOURCE / B1-R2 (peer `WIRE_INGRESS factType=9 outcome=APPLIED` + `SHADOW_MEMBER_INSTALLED module=M01 outcome=APPLIED`).

**Not** OBS gap (`meeting-product-integration-phase-a-sr4-obs-sufficiency-audit.md` = SUFFICIENT; pipeline simply never reached emitters).

**Not** underlay multicast bind failure on peer (M02 `transportScopeActive=true` immediately after `WIRING_SESSION_STARTED`).

---

## Correlated identity

| Key | Value |
|-----|-------|
| sessionId | `c70b2e03-fdf5-4643-81e8-e5eaf6380f98` |
| conferenceId | `a6f1c24c2b831e6ce6d0b999c7e0731c` |
| hostModuleId | `M01` |
| peerModuleId | `M02` |
| mediaKeyEpoch | `1` |
| source post-bind digest | `e407181874dc09c3dbcfec2465236599fb46d36aa268b0ecf9e78af141592fb6` |

---

## Timeline (authoritative log order)

| Time (local) | Device | Event |
|--------------|--------|-------|
| 16:16:11.599 | M01 | `SHADOW hook=SESSION_STARTED outcome=DEFERRED_NO_FACT` |
| 16:16:11.656 | M01 | `PROFILE01_ORIGIN_EMIT factType=1 outcome=EMITTED` (CREATION) |
| 16:16:23.157 | M01 | `PROFILE01_SOURCE_POST_BIND_REPUBLISH … target=M02 outcome=EMITTED` |
| 16:16:23.467 | M01 | `MEMBER_MEDIA_READY module=M02 outcome=DEFERRED_NO_BINDING` |
| 16:16:23.481 | M01 | `PTT_TIMING captureON session=c70b2e03…` → `EDGE_TASK origin=startCapture edge=M02` (WebRTC) |
| 16:16:24.234 | M02 | `WIRE_INGRESS factType=1 outcome=APPLIED` |
| 16:16:24.307 | M02 | `WIRING_SESSION_STARTED transportScopeActive=true` |
| 16:16:24.320 | M02 | `WIRE_INGRESS factType=9 outcome=APPLIED` |
| 16:16:24.326 | M02 | `WIRING_MEMBER_INSTALLED module=M01 admittedCount=1 activeJitterSources=0` |
| 16:16:24.326 | M02 | `SHADOW_MEMBER_INSTALLED module=M01 outcome=APPLIED` |
| 16:16:24.344+ | M02 | `PTT_TIMING captureON` + `startCapture edge=M01` (ICE receive-path sync — not harness M01 PTT) |
| — | M02 | **No** `PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY` |
| — | M02 | **No** `PROFILE01_SHADOW_RUNTIME phase=PLAYOUT_ACTIVITY` |
| — | M01 | **No** `WIRING_SESSION_STARTED` / `SHADOW_SESSION_STARTED` ever |

Adjudication: `ADJUDICATION.txt` — `SR4-A_INGRESS_ACTIVITY=False lines=0`, `SR4-B_PLAYOUT_ACTIVITY=False lines=0`, `SR3_MEMBER_MEDIA_READY_HOST=True`, `SR2_WIRING_LIVE=True`.

---

## RCA layers (evidence order)

```text
Layer 1  Transport ingress   — socket scope vs datagram poll vs on-wire presence
Layer 2  Runtime admission   — admitted source / catalog / epoch gates
Layer 3  Playout execution   — jitter / decode / mix / RecordingPlayoutMetricsSeam
```

**Verdict:** Failure is **fully explained at Layer 1** (no TX into shadow multicast + no RX executor on product path). Layers 2–3 **not reached**.

---

## Frozen questions (four)

### Q1 — M02 shadow multicast receiver: started and bound to current session?

**Partially yes — scope active; receive executor absent.**

Field (M02):

```text
PROFILE01_SHADOW_RUNTIME phase=WIRING_SESSION_STARTED
  sessionId=c70b2e03… transportScopeActive=true catalogEntries=0 admittedCount=0
```

Code: `ConferenceSessionMediaWiring.startSession` calls `transport.beginScope(handle, …)` then `configureNetworkInterface` — this is the real product multicast socket scope (`ConferenceMulticastRealMediaAssembly.createShadow` → `AndroidRuntimeResources.forAndroidProduct`).

**However:** In the product Meeting shadow path, **nothing polls** `ConferenceMulticastRtpSrtpTransport.receiveOnce` / `ConferenceMulticastMediaPipeline.receiveAndProcess` / `ConferenceSessionMediaWiring.admitDatagram`.

Evidence:

- `receiveOnce` / `receiveAndProcess` callers are **runners only** (`RealMeshE2ERunner`, `ReceiverScaling*Runner`, `Slice*Multicast*Runner`, etc.).
- No caller under `MeetingProductMediaShadow`, `ConferenceSessionMediaCoordinatorDelegate`, or `TalkbackCoordinator`.

`RealMeshE2ERunner.soakReceive` shows the **intended** product pattern (receive loop → `wiring.admitDatagram` → SSRC catalog resolve) — but it is **not wired** into Phase A shadow lifecycle.

**Classification:** Runtime assembly / lifecycle gap — **multicast scope without datagram-plane executor**, not WiFi/multicast bind failure.

---

### Q2 — M01 PTT: does production path feed ADR-0058 shadow multicast TX?

**No — PTT feeds ADR-0056 WebRTC capture only; host shadow session never started.**

Field (M01, first conference PTT):

```text
PTT_TIMING event=captureON session=c70b2e03…
EDGE_TASK … origin=startCapture edge=M02   # peer WebRTC engine, not shadow transport
CONFERENCE_AUDIO_PATH … localMicActive=false injectionPortState=CLOSED
```

Code: `TalkbackCoordinator.startSessionCapture` → `dispatchConferenceCaptureControl` → `engine.startCapture()` on conference media engines (WebRTC JNI path). **No reference** to `ConferenceSessionMediaWiring`, `sendFromSource`, or `sendProtectedArtifact` in coordinator / shadow integration.

Product codebase: `sendProtectedArtifact` / `sendFromSource` exist on `ConferenceMulticastRtpSrtpTransport` but are invoked from **directed runners / soak harnesses**, not Meeting PTT.

Host shadow TX prerequisite also missing:

```text
M01 SHADOW hook=SESSION_STARTED outcome=DEFERRED_NO_FACT   # invite-time, registry empty
M01 never logs WIRING_SESSION_STARTED / SHADOW_SESSION_STARTED
```

`onConferenceSessionStarted` is invoked at host invite (`TalkbackCoordinator` ~4082) **before** CREATION is in `ConferenceSessionMediaControlFactRegistry`; `MeetingProfile01AwareConferenceSessionMediaFactPort.sessionFact` returns null → `DEFERRED_NO_FACT`. **No later replay** of `SESSION_STARTED` on M01 after host-local CREATION publish (unlike M02 peer, which replays via `SESSION_INCOMPLETE_DRAIN` / `WIRE_INGRESS factType=1 APPLIED`).

**Classification:** **Product integration gap** — SR3 proves control-plane binding on peer; it does **not** prove product PTT injects RTP/SRTP into shadow multicast sender. Sender assembly is absent on host and disconnected from PTT on all devices.

---

### Q3 — If packets reached socket, why no `INGRESS_ACTIVITY`?

**Not adjudicable as admission rejection — packets never entered shadow pipeline.**

`INGRESS_ACTIVITY` is emitted only from `ConferenceMulticastMediaPipeline.admitProtectedDatagram` after successful `admitWire` (`Profile01ShadowRuntimeObservability.maybeLogIngressActivity`).

Preconditions on M02 **were satisfied** at binding time:

```text
WIRING_MEMBER_INSTALLED admittedCount=1 catalogEntries=1 transportScopeActive=true
```

If datagrams had been polled and SSRC-mapped, `SessionBindingIngressResolver` + `admitWire` would be the gate to observe. Field shows **zero** ingress OBS → failure is **before** `admitProtectedDatagram`, consistent with Q1/Q2 (no RX loop, no TX).

**Not** `UNKNOWN_SSRC` / epoch mismatch / key-context rejection (no wire-level reject OBS; no runner-style reject counters in product path).

---

### Q4 — Ingress present but playout 0?

**Not applicable — ingress never executed.**

Snapshot frozen at member install:

```text
activeJitterSources=0 liveDecoders=0 jitterBufferCount=0
```

`ensurePlayoutStarted` → `RecordingPlayoutMetricsSeam` does not self-drive mix cycles; playout OBS requires `runMixPlayoutCycle` after admitted frames. **Do not** route to decoder / AudioTrack RCA for this run.

---

## Root cause (closed)

```text
CONFIRMED: Phase A shadow wiring stops at control-plane lifecycle (SR1–SR3).
           Datagram-plane product integration is NOT present:

  GAP-A  Host shadow session never replays after CREATION (M01 DEFERRED_NO_FACT → no WIRING_SESSION).
  GAP-B  Conference PTT → WebRTC (ADR-0056) only; no shadow multicast TX seam.
  GAP-C  Peer multicast socket scope begins (transportScopeActive=true) but no product receive executor
         polls socket → pipeline → INGRESS_ACTIVITY.
```

This is a **real product integration gap**, not media quality, not OBS insufficiency, not SR3 SOURCE regression.

Aligns with historical deferral: `0058-e2b-slice3-product-seams.md` — “Product session wiring — not authorized by this slice” (underlay seams only; no Meeting PTT ↔ multicast execution).

---

## What SR3 proved vs what SR4 expected

| SR3 (closed) | SR4 (failed) |
|--------------|--------------|
| Facts ingress + registry + `MEMBER_MEDIA_READY` | Real RTP/SRTP through shadow runtime |
| `installMember` → admitted source in authority store | Multicast datagram TX from product PTT |
| `transportScopeActive` after `startSession` | Receive loop + `admitDatagram` / `admitProtectedDatagram` |
| Post-bind SOURCE republication (B1-R2) | `INGRESS_ACTIVITY` + `PLAYOUT_ACTIVITY` OBS |

SR3 **≠** “product path already sends/receives shadow multicast media.”

---

## Recommended fix tracks (authorization required — not started)

Minimal sequencing for a future implementation authorization (names illustrative):

```text
PR-PA-SR4-TX   Host SESSION_STARTED replay + conference capture → shadow encode/egress (sendFromSource)
PR-PA-SR4-RX   Product shadow receive executor (poll → admitDatagram by SSRC → pipeline)
PR-PA-SR4-PLAY Mix slot driver for shadow metrics playout (after ingress proven)
```

Each PR should keep ADR-0056 AudioTrack fence (`RecordingPlayoutMetricsSeam` only). Re-field PA-SR4 after TX+RX; playout may follow in same or next PR depending on slot timing.

**Do not** reopen SOURCE / B1-R2. **Do not** authorize cutover.

---

## Status board (frozen)

```text
PA-SR1–SR3      CLOSED / FIELD-PROVEN
PA-SR4          VALID FAIL / RCA CLOSED (this doc)
Failure locus   shadow runtime media execution — datagram-plane product integration gap
PR-PA-SR4-TX    IMPLEMENTATION AUTHORIZED (TX only — see pr-pa-sr4-tx-design.md)
PR-PA-SR4-RX    DESIGN NEXT — NOT AUTHORIZED
PR-PA-SR4-PLAY  HOLD
PA-SR5/SR6      HOLD
ADR-0056        KEEP
cutover         NOT AUTHORIZED
B1-R2           UNCHANGED / CLOSED
OBS (SR4)       SUFFICIENT — pipeline never ran
Next            PR-PA-SR4-TX implement + PA-SR4-TX field gate; RX after TX FIELD-PROVEN
```

---

## Evidence pointers

| Item | Location |
|------|----------|
| Adjudication | `talkback/logs/phase-a-sr4-field-20260909-161221/ADJUDICATION.txt` |
| M02 wiring + SR3 green | `M02-logcat.txt` ~2577–2595 |
| M01 PTT + no shadow session | `M01-logcat.txt` ~2575, ~3425–3434, ~3391 |
| Shadow wiring lifecycle | `ConferenceSessionMediaWiring.kt` |
| Ingress OBS emitter | `ConferenceMulticastMediaPipeline.kt` → `admitProtectedDatagram` |
| PTT → WebRTC only | `TalkbackCoordinator.kt` `startSessionCapture` / `dispatchConferenceCaptureControl` |
| Receive loop reference (not product) | `RealMeshE2ERunner.kt` `soakReceive` |
| SR4 OBS audit | `meeting-product-integration-phase-a-sr4-obs-sufficiency-audit.md` |
| E2b product seam deferral | `0058-e2b-slice3-product-seams.md` |
