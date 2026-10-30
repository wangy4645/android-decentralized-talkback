# Primary Native SRD Fault — Pinned Instrumentation Design (ONE SHOT)

**ID:** primary-native-srd-fault-pinned-instrumentation-design-20260825  
**Date:** 2026-08-25  
**Type:** Native instrumentation design · **NO impl · NO field · NO fix design IA**  
**Status:** **ACCEPTED** (sign-off 2026-08-25) · impl IA authorized separately  
**Parent:** [primary-native-srd-fault-production-hang-stack-capture-runbook-20260825.md](./primary-native-srd-fault-production-hang-stack-capture-runbook-20260825.md) §15 · [ADJUDICATION capture](../logs/pnsrd-hang-capture-20260825-151146/ADJUDICATION.txt)

**Evidence base:** stack capture `pnsrd-hang-capture-20260825-151146` · mutex holder thread-level hypothesis **15408 AudioTrackJavaThread**

---

## 1. Single closure question

```text
At TIER_JNI hang, which exact webrtc::Mutex instance X is held by tid 15408,
at which labeled acquire site, without a matching RELEASE —
while signaling 14825 is inside PushdownMediaDescription / SetRemoteContent BlockingCall
and worker 14824 is blocked on Lock(X)?
```

**PASS (outcome 1):** log sequence proves `ownerTid=15408` · `mutex=X` · `heldAt=<label>` · `release=NEVER` through hang window · worker `Lock(X)` blocked · signaling inside `SET_REMOTE_CONTENT_*` bracket.

**Anything else:** route per §8 (outcomes 2–3).

---

## 2. Scope fence (hard)

### In scope (only)

```text
AudioTrackJavaThread  (ADM playout / nativeGetEnabled path)
signaling_thread      (PushdownMediaDescription / BlockingCall)
worker_thread         (SetRemoteContent lambda)
shared media mutex    (webrtc::Mutex — object identity, not Java sdpApplyMutex)
```

### Explicitly out of scope

```text
Java Coordinator / lease / GCT / extra SRD_* tiers
Factory partition · PC reset · force-release
4PC+ lab · field contrast matrix · Java re-prime
Global WebRTC trace · all-mutex logging · network_thread telemetry
```

This is **not** a “system telemetry PR”. One native patch set · one debug AAR · one production hang capture · one adjudication.

---

## 3. Pin and build artifact

| Field | Value |
|-------|-------|
| Upstream commit | `b831eb816ef847d09d446ef4168e36b13af163f8` |
| Stream packaging ref | `io.getstream:stream-webrtc-android:1.3.10` / m125.4 |
| Instrumentation label | `pnsrd-inst-1` (embedded in `.so` string + log prefix) |
| Production SHA256 match | **NOT REQUIRED** for this ONE SHOT (debug/instrumented build) |
| Post-build check | arm64 `.so` embeds `pnsrd-inst-1` + `WebRTC source stamp 2024-04-15T04:12:23` base |

**Delivery:** replace `stream-webrtc-android` native dep in **host prod flavor only** for capture build; revert after adjudication. No Maven publish.

---

## 4. Event catalog

All events → **single log sink:** Android `LOG()` tag **`PNSRD_INST`** (not `Talkback`). One line per event · fixed key order · no JSON.

### 4a. Mutex events (required)

| Event | Emit point |
|-------|------------|
| `MUTEX_ACQUIRE_ENTER` | `webrtc::Mutex::Lock()` entry (after thread filter) |
| `MUTEX_ACQUIRE_EXIT` | `Lock()` returned (holder = current tid) |
| `MUTEX_RELEASE` | `Unlock()` |
| `LOCK_OWNER_SNAPSHOT` | On worker `Lock()` slow-path block **or** external trigger (§6) |

**Required fields (every mutex event):**

```text
ev=<EVENT>
ts=<monotonic_ns>
tid=<gettid()>
tname=<pthread_getname_np or "unknown">
mutex=<uintptr_t>&label=<SOURCE_LABEL>
ownerTid=<gettid of holder or 0>
depth=<hold depth on this mutex for current tid after event>
```

**`SOURCE_LABEL`:** static string at patch site (see §5). Central `Mutex::Lock` hook uses label `MUTEX/central` only when no outer labeled wrapper fired.

**`LOCK_OWNER_SNAPSHOT` additional fields:**

```text
blockedTid=<tid attempting Lock>
blockedLabel=<label on blocked acquire>
held=<comma-separated mutex:ownerTid:label:heldNs;...>   # cap 8 entries, LRU by mutex ptr
```

### 4b. Pushdown / SetRemoteContent (required)

| Event | Emit point @ pin |
|-------|------------------|
| `PUSHDOWN_MEDIA_DESCRIPTION_ENTER` | `SdpOfferAnswerHandler::PushdownMediaDescription` entry |
| `PUSHDOWN_MEDIA_DESCRIPTION_EXIT` | same, all return paths (RAII guard) |
| `SET_REMOTE_CONTENT_ENTER` | worker lambda body start inside Pushdown (`SetRemoteContent` / `VoiceChannel::SetRemoteContent_w` entry) |
| `SET_REMOTE_CONTENT_EXIT` | lambda exit |

**Required fields:**

```text
ev=<EVENT>
ts=<monotonic_ns>
tid=<gettid()>
tname=<...>
pc=<PeerConnection ptr if available else 0>
session=<short opaque id if available else 0>
```

### 4c. Optional (recommended for S2-b1 closure)

| Event | Emit point |
|-------|------------|
| `NATIVE_GET_ENABLED_ENTER` | `Java_org_webrtc_MediaStreamTrack_nativeGetEnabled` |
| `NATIVE_GET_ENABLED_EXIT` | same |
| `MARSHAL_ENTER` | `MethodCall::Marshal` after `PostTask`, before `event_.Wait` |
| `MARSHAL_RETURN` | `Marshal` after wait completes |

**Required fields:** `ev ts tid tname target=<signaling|worker|other>`

Optional events **must** use the same `PNSRD_INST` tag and **must not** add Java-side loggers.

---

## 5. Patch seams (@ `b831eb81…`)

Instrument **only** these sites (+ one central mutex hook):

| Priority | File @ pin | Seam | `SOURCE_LABEL` |
|----------|------------|------|----------------|
| P0 | `rtc_base/synchronization/mutex_pthread.h` (or Abseil backend) | `Lock()` / `Unlock()` | `MUTEX/central` + hold-table |

The central mutex hook **MUST** remain subject to §6 filtering and **MUST NOT** become an all-mutex production telemetry mechanism.
| P0 | `pc/sdp_offer_answer.cc` | `PushdownMediaDescription` | `SDP/PushdownMediaDescription` |
| P0 | `pc/sdp_offer_answer.cc` | worker `BlockingCall` lambda → `SetRemoteContent` | `SDP/SetRemoteContent` |
| P1 | `media/engine/webrtc_voice_engine.cc` | `WebRtcVoiceEngine` paths matching rodata cluster (`InitRecording`, stream create) | `VE/InitRecording`, `VE/CreateAudioSendStream`, … |
| P1 | `call/call.cc` | `Call::CreateAudioSendStream` and shared `call_mutex_` acquire sites | `Call/CreateAudioSendStream`, `Call/mutex` |
| P1 | `pc/channel.cc` or voice channel | `VoiceChannel::SetRemoteContent_w` | `VC/SetRemoteContent_w` |
| P2 | `sdk/android/src/jni/pc/media_stream_track.cc` | `nativeGetEnabled` | `JNI/nativeGetEnabled` |
| P2 | `pc/proxy.h` | `MethodCall::Marshal` | `PC/Marshal` |

**Do not** patch `Talkback` Java/Kotlin. **Do not** patch `JavaAudioDeviceModule.nativeLock` unless outcome 2 refutes AudioTrack holder (deferred).

### Hold table (in-process, bounded)

```text
Max 32 tracked mutex pointers
Per mutex: { owner_tid, acquire_ts, source_label, acquire_count }
Thread-local stack for nested acquires on same tid
```

On `MUTEX_RELEASE`: clear owner only when acquire_count → 0.

On `LOCK_OWNER_SNAPSHOT`: dump non-empty table entries.

---

## 6. Thread filter (noise control)

Emit mutex events **only if** any of:

```text
F1  tname contains "signaling" OR "worker_thread"
F2  tname contains "AudioTrack" OR "AudioTrackJava"
F3  SET_REMOTE_CONTENT_ENTER active on current tid (signaling/worker bracket — use thread-local flag)
F4  mutex ptr already in hold table with owner_tid matching F1/F2 thread
F5  blocked Lock() on worker tid (always log ENTER + SNAPSHOT)
```

**Never** log mutex events from `network_thread`, `AudioDeviceBuff`, `rtc-low-prio`, etc., unless F4 already tracked that mutex pointer.

---

## 7. Expected closure sequence (adjudication template)

At hang, logcat filtered `-s PNSRD_INST:*` should show a pattern equivalent to:

```text
... NATIVE_GET_ENABLED_ENTER tid=15408
... MUTEX_ACQUIRE_ENTER  tid=15408 mutex=0x???? label=VE/... or Call/...
... MUTEX_ACQUIRE_EXIT   tid=15408 mutex=0x???? ownerTid=15408
... MARSHAL_ENTER        tid=15408 target=signaling
    (no MARSHAL_RETURN)

... PUSHDOWN_MEDIA_DESCRIPTION_ENTER tid=14825
... SET_REMOTE_CONTENT_ENTER         tid=14824
... MUTEX_ACQUIRE_ENTER              tid=14824 mutex=0x???? label=VC/SetRemoteContent_w
... LOCK_OWNER_SNAPSHOT blockedTid=14824 mutex=0x???? ownerTid=15408 heldAt=VE/... release=NEVER
    (no MUTEX_ACQUIRE_EXIT for 14824 on 0x????)
    (no PUSHDOWN_MEDIA_DESCRIPTION_EXIT)
```

**Critical equality:** worker blocked `mutex` pointer **==** AudioTrack `MUTEX_ACQUIRE_EXIT` pointer **==** `LOCK_OWNER_SNAPSHOT` `ownerTid=15408` entry.

---

## 8. Adjudication outcomes (exactly three)

| # | Condition | Verdict | Next |
|---|-----------|---------|------|
| **1** | Same mutex ptr · `ownerTid=15408` · labeled acquire · no `MUTEX_RELEASE` before hang · worker blocked on same ptr inside `SET_REMOTE_CONTENT_*` · signaling in `PUSHDOWN_*` without EXIT | **S2-b1 CONFIRMED** | Authorize **minimal native fix design** (lock-order seam only) |
| **2** | Holder tid ≠ 15408 **or** mutex graph does not close **or** signaling not in pushdown bracket | **S2-b1 REFUTED** | Re-route S2 / S3 / S7 from new graph; **no** fix design until new closure |
| **3** | Insufficient / ambiguous logs (missing ptr equality, filter dropped events, no hang repro) | **INCONCLUSIVE** | **STOP** adding log layers · require unstripped rebuild + native debugger **or** accept no-fix-design |

Write result to: `talkback/logs/pnsrd-inst-1-<timestamp>/ADJUDICATION.txt` (same schema style as stack capture).

---

## 9. Capture procedure (field — authorized only after impl IA)

Reuse runbook scripts; **do not** change capture protocol beyond app binary swap:

```text
1. Deploy prod host build with pnsrd-inst-1 AAR (one build id recorded)
2. pnsrd-hang-capture-start-run.ps1
3. Real conference accept → wait TIER_JNI on ord-2 SRD
4. run-as SIGQUIT + logcat -d -s PNSRD_INST:* Talkback:* > inst-capture.log
5. pnsrd-hang-capture-stop-run.ps1
6. Adjudicate §8 before any fix doc
```

**Pre-hang baseline:** first `PUSHDOWN_*` / mutex events from ord-1 SRD (M02) may appear — adjudication uses **ord-2 hang window** only (timestamp ≥ ord-2 `SRD_NATIVE_CALL_ENTER` from existing `Talkback` watchdog).

---

## 10. Implementation authorization gate (next step)

Impl IA **permitted only when** this design is signed off. Impl scope:

```text
+ rtc_base/pnsrd_instrumentation.{h,cc}   (hold table + LOG macro)
+ patches listed §5 (P0 mandatory, P1/P2 as single PR)
+ host flavor deps swap for one capture APK
- no Talkback Java changes except gradle coord override for inst AAR
- no new Talkback log events
```

**Impl IA explicitly excludes:** fix design · Coordinator · lease · reset · factory partition.

---

## 11. Candidate fix direction (NOT authorized)

If outcome **1**:

> Prevent playout / ADM callback threads from synchronously `MethodCall::Marshal` to `signaling_thread` while holding shared media `webrtc::Mutex`.

This is a **design hypothesis placeholder only**. No file/function patch selection until §8 outcome **1** adjudication is written.

---

## 12. Status summary

```text
Stack capture              DONE (S2-b thread-level)
Unstripped exact match     UNAVAILABLE
Mutex object + acquire site  OPEN → this ONE SHOT closes it
Instrumentation design     ACCEPTED
Instrumentation IA         AUTHORIZED (see pinned-instrumentation-ia doc)
Inst sources + apply pack  LANDED (webrtc-instrumentation/pnsrd-inst-1)
Inst AAR build             PENDING (WebRTC tree + APPLY.txt)
ONE production capture     PENDING
```
