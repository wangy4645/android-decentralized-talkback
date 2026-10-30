# Primary Native SRD Fault — 9b Phase-2 Source Trace

**ID:** primary-native-srd-fault-native-phase2-source-trace-20260825  
**Date:** 2026-08-25  
**Type:** Desk source trace · hang capture procedure  
**Status:** **ACTIVE** (source trace complete · hang stack pending)  
**Parent:** [primary-native-srd-fault-native-phase2-repro-authorization-20260825.md](./primary-native-srd-fault-native-phase2-repro-authorization-20260825.md) · [primary-native-srd-fault-webrtc-revision-pin-20260825.md](./primary-native-srd-fault-webrtc-revision-pin-20260825.md)

**Stack:** `io.getstream:stream-webrtc-android:1.3.10`

---

## 1. Pin WebRTC commit — **COMPLETE (desk)**

Full evidence: [primary-native-srd-fault-webrtc-revision-pin-20260825.md](./primary-native-srd-fault-webrtc-revision-pin-20260825.md)

```text
Maven:     io.getstream:stream-webrtc-android:1.3.10
Stream:    m125.4 (libs @ GetStream 37b8867, 2024-12-17)
Milestone: Chromium M125 · webrtc refs/branch-heads/6422
Commit:    b831eb816ef847d09d446ef4168e36b13af163f8  (main@{#42072})
Stamp:     WebRTC source stamp 2024-04-15T04:12:23  (embedded in arm64 .so)
Browse:    https://webrtc.googlesource.com/src/+/b831eb816ef847d09d446ef4168e36b13af163f8/
```

**Note:** 1.3.10 Maven release changed Java only (audio attributes). Native `.so` unchanged vs m125.4. Stream may carry **undocumented patches** on top of branch point — stamp does not prove branch-heads/6422 tip.

**Adjudication shorthand:**

```text
webrtc_commit=b831eb816ef847d09d446ef4168e36b13af163f8
webrtc_branch=refs/branch-heads/6422
webrtc_source_stamp=2024-04-15T04:12:23
```

---

## 2. Trace map — `SetRemoteDescription(answer)` → hang

**Pinned browse commit:** `b831eb816ef847d09d446ef4168e36b13af163f8`

### Entry (Android JNI → native PC)

| Step | File @ pin | Function / fact |
|------|------------|-----------------|
| Java | `sdk/android/api/.../PeerConnection.java` | `setRemoteDescription` → `nativeSetRemoteDescription` |
| JNI | `sdk/android/src/jni/pc/peer_connection.cc` | `JNI_PeerConnection_SetRemoteDescription` → `ExtractNativePC(...)->SetRemoteDescription(...)` **on caller thread** |
| Proxy | `pc/peer_connection.h` + `pc/proxy.h` | `PeerConnection::SetRemoteDescription` marshals to **`signaling_thread`** |
| SDP | `pc/sdp_offer_answer.h` / `pc/sdp_offer_answer.cc` | `SdpOfferAnswerHandler::DoSetRemoteDescription` → **`ApplyRemoteDescription`** (sync) → `SetRemoteDescriptionPostProcess` |

**JNI fact (verified on pin):** no explicit `signaling_thread()->Invoke` in JNI; blocking happens inside `PeerConnection` proxy / `MethodCall::Marshal` (`PostTask` + `event_.Wait(kForever)` on **tb-edge** thread).

### Sync subgraph inside `ApplyRemoteDescription` (hang window)

| Step | File @ pin | Thread / notes |
|------|------------|----------------|
| Transceiver / channel | `pc/sdp_offer_answer.cc` | `SdpOfferAnswerHandler::CreateChannels` · `UpdateTransceiversAndDataChannels` · `UpdateTransceiverChannel` |
| Transport | `pc/jsep_transport_controller.cc` | `JsepTransportController::SetRemoteDescription` · may `BlockingCall` **network_thread** |
| Voice / media | `media/engine/webrtc_voice_engine.cc` | channel create / `AddSendStream` — often `worker_thread()->Invoke` |
| ADM (Talkback) | `sdk/android/.../JavaAudioDeviceModule.java` | `nativeLock` on ADM pointer; shared factory ADM across all PCs |

**Field correlate:** ord-2 TIER_JNI = **ENTER without EXIT** → blocked in **sync** portion (before `SetRemoteSdpObserver.onSetSuccess`). Matches 9b: not Java latch / not `sdpApplyMutex` wait (field: `waitMs=0`).

### Factory threads (shared by PC-A and PC-B)

| File @ pin | Threads |
|------------|---------|
| `sdk/android/src/jni/pc/ownedfactoryandthreads.cc` | `network_thread` · `worker_thread` · `signaling_thread` |

All PCs from one `PeerConnectionFactory` share these three threads + one `MediaEngine` + one ADM.

### Product call site (Talkback — frozen, not fix target)

```text
TalkbackCoordinator → runWithLease(E3) → RealWebRtcAudioEngine.applyRemoteAnswer
  → WebRtcSharedFactory.sdpApplyMutex
  → invokeNativeSet → peerConnection.setRemoteDescription (JNI)
```

Observability: `SRD_NATIVE_CALL_ENTER` / `EXIT` on `Talkback` tag; TIER_JNI = `lastEvent=SRD_NATIVE_CALL_ENTER`, missing `NATIVE_CALL_EXIT`.

---

## 3. Hypothesis → trace focus (field + lab context)

**Lab result:** 2PC arms 0–3 PASS · 3PC-A/B PASS → does **not** eliminate primary defect; narrows repro, not mechanism.

| Class | If stack narrows | Stack / trace should show |
|-------|------------------|----------------------------|
| **S1** | signaling backlog | `signaling_thread` runnable stuck; PC-B `MethodCall::Marshal` waiter on **tb-edge** |
| **S2** | signaling ↔ worker inversion | `signaling_thread` in `Invoke` → `worker_thread`; worker blocked on signaling or lock |
| **S3/S7** | ADM / shared track | `worker_thread` in `WebRtcVoiceEngine` / ADM init; `JavaAudioDeviceModule` / AudioRecord thread |
| **S4** | shared track ID contention | voice channel setup with same track id on second PC |

**Production hang capture (authorized):** [primary-native-srd-fault-production-hang-stack-capture-runbook-20260825.md](./primary-native-srd-fault-production-hang-stack-capture-runbook-20260825.md) · scripts under `talkback/scripts/pnsrd-hang-capture-*.ps1`

---

## 4. Hang capture procedure (device lab)

When arm reports `verdict=HANG`:

### 4a. Logcat (always)

```text
adb logcat -s PNSRD_LAB:* org.webrtc:* libc:*
```

Collect `SRD1_*` / `SRD2_*` lines + ICE state transitions.

### 4b. Thread stacks (during hang)

```text
adb shell pidof <test_package_or_app_host>
adb shell debuggerd -b <pid>
# or
adb shell kill -3 <pid>   # ANR trace to logcat
```

**Inspect threads named:**

```text
signaling_thread
worker_thread
network_thread
AudioRecordThread / AudioTrackThread (Android ADM)
pnsrd-srd-b          ← blocked in JNI
```

### 4c. Expected discriminant stacks

**S1/S2 pattern:**

```text
pnsrd-srd-b:  waiting on PeerConnection proxy marshal / condition variable
signaling_thread:  in ApplyRemoteDescription OR queued behind PC-A task
worker_thread:  in voice engine / channel OR waiting on signaling
```

**S3/S4/S7 pattern:**

```text
pnsrd-srd-b:  marshal wait
worker_thread:  AudioDeviceModule / InitRecording / AddSendStream
signaling_thread:  Invoke waiting on worker (audio init)
```

Record stacks in `ADJUDICATION.txt` under `hang_stacks=...` (paste truncated).

### 4d. WebRTC internal trace (optional)

If rebuilding WebRTC is out of scope, skip. For deep dive only:

```text
PeerConnectionFactory.initialize(... setEnableInternalTracer(true))
# pull /sdcard/Download/webrtc-trace*
```

Not enabled in default lab harness (behavior-neutral to Stream AAR).

---

## 5. Adjudication fields (per arm run)

```text
arm: 0|1|2|3
verdict: PASS|HANG|FAIL_SETUP
srd1_elapsed_ms: ...
srd2_elapsed_ms: ... | TIMEOUT
pc_a_ice_at_srd2: NEW|CHECKING|CONNECTING|CONNECTED|...
track_mode: PER_PC|SHARED
pc_a_media: INACTIVE|ACTIVE
factory_hash: ...
webrtc: stream-webrtc-android:1.3.10
webrtc_commit: b831eb816ef847d09d446ef4168e36b13af163f8
webrtc_branch: refs/branch-heads/6422
webrtc_source_stamp: 2024-04-15T04:12:23
mechanism_hint: S1|S2|S3|S4|S7|NONE|INCONCLUSIVE
notes:
hang_stacks: (if HANG)
```

---

## 6. Interpretation guardrail

```text
2 PC all PASS  →  does NOT close primary RCA
2 PC arm2 HANG arm3 PASS  →  narrows S1/S2 (not product fix)
2 PC arm1 HANG arm0 PASS  →  narrows S4/S3/S7 → architecture track
3 PC field HANG + 2 PC PASS  →  scaling/interaction limitation label
```

---

## 7. Status

```text
Mainline                     NATIVE RCA (see runbook §15)
Revision pin (desk)          COMPLETE → webrtc-revision-pin doc
Source trace map             COMPLETE @ b831eb81…
2PC / 3PC lab                STOP (all PASS — no repro)
Production hang stack        AUTHORIZED · PENDING next TIER_JNI (ONE SHOT)
Exit if stack inconclusive   pinned-source instrumentation (NOT more field/lab)
Native RCA / fix IA          BLOCKED until stack names wait site
Java layer                   NOT IN SCOPE · re-prime NOT SELECTED
```
