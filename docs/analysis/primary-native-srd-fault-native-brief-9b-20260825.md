# Primary Native SRD Fault — Native Source Brief (9b)

**ID:** primary-native-srd-fault-native-brief-9b-20260825  
**Date:** 2026-08-25  
**Type:** Desk / native source brief · **NO code · NO field · NO 9a**  
**Status:** **COMPLETE**  
**Parent:** [primary-native-srd-fault-native-seam-investigation-20260825.md](./primary-native-srd-fault-native-seam-investigation-20260825.md) · [primary-native-srd-fault-minimal-fix-design-review-20260825.md](./primary-native-srd-fault-minimal-fix-design-review-20260825.md)

**Stack:** `io.getstream:stream-webrtc-android:1.3.10` (Chromium WebRTC Android JNI; prebuilt AAR)

**Evidence:** `pnsrd-1a-20260825-120721/` · `pnsrd-1b-20260825-123029/`

---

## 1. Question (single)

```text
PeerConnectionFactory (shared ADM, N attached PCs)
    → PC-A: setRemoteDescription(answer) PASS + ICE active
    → PC-B: setRemoteDescription(answer)
    → why may the JNI call not return (TIER_JNI)?
```

**Not:** re-prove ordinal bug · not assume WebRTC defect · not design a fix.

---

## 2. Production runtime (what §11 discriminant means in native terms)

### Topology (host conference, clean 1a/1b)

```text
WebRtcSharedFactory
  ├── 1× PeerConnectionFactory
  │     ├── network_thread   (factory-owned)
  │     ├── worker_thread    (factory-owned)
  │     └── signaling_thread (factory-owned, shared by all PCs)
  ├── 1× JavaAudioDeviceModule
  └── SharedLocalAudio → 1× AudioTrack "tb_shared_audio" on every PC

RealWebRtcAudioEngine × 3  (one PC per remote)
  each: addTrack(shared local) + unified-plan offer/answer
```

### Ordinal correlate (factory-wide, not per-PC ENTER fields)

| Moment | Factory-wide context |
|--------|----------------------|
| SRD#1 ENTER | No PC yet `remoteDesc=ANSWER`; ICE on completing edge not yet active |
| SRD#1 EXIT | One PC `STABLE` + `remoteDesc=ANSWER`; ICE tasks on that PC's edge |
| SRD#2 ENTER | Entering PC still `HAVE_LOCAL_OFFER` / `remoteDesc=NONE` (symmetric to ord-1 ENTER) **but** ≥1 other PC already post-SRD + ICE active |

**Interpretation:** defect is not “wrong PC snapshot at ENTER”; it is **multi-PC factory runtime after first successful answer apply**, while the hanging call is on a **different** PC.

### Java containment already cleared (not the hang site)

```text
L1 lease/E3     cleared
L2 sdpApplyMutex  free (waitMs=0)
L3 per-edge executor  independent thread (tb-edge-…)
```

---

## 3. What TIER_JNI means in source terms

Observed sequence (ord-1 PASS):

```text
tb-edge thread: SRD_NATIVE_CALL_ENTER
              → peerConnection.setRemoteDescription (JNI)
              → SRD_NATIVE_CALL_EXIT (~70–116ms)
              → SRD_JNI_RETURN
signaling_thread: SRD_CALLBACK_ENTER → onSetSuccess
```

Ord-2 HANG:

```text
tb-edge thread: SRD_NATIVE_CALL_ENTER
              → (JNI never returns → no EXIT, no CALLBACK)
```

### JNI entry (Android)

`JNI_PeerConnection_SetRemoteDescription` calls `ExtractNativePC(...)->SetRemoteDescription(observer, sdp)` directly on the **calling thread** (edge executor), not via an explicit `signaling_thread()->Invoke` wrapper in JNI.

Source: `sdk/android/src/jni/pc/peer_connection.cc` (Chromium WebRTC).

### Blocking model

`PeerConnection::SetRemoteDescription` is exposed through proxy / internal routing that **eventually runs SDP work on the PC's signaling thread** (factory-shared). Cross-thread calls use `MethodCall::Marshal` → `PostTask` + **`event_.Wait(kForever)`** on the caller until the posted task finishes.

Source: `pc/proxy.h` · `api/proxy.h` · `pc/peer_connection.h` (`operations_chain_`).

**Desk conclusion:** TIER_JNI = **edge thread blocked inside native**, most plausibly waiting on **factory `signaling_thread`** (or a nested `worker_thread` / `network_thread` `Invoke`) to complete the **synchronous portion** of PC-B's `SetRemoteDescription` — **before** `SdpObserver.onSetSuccess`.

This matches “no CALLBACK” without requiring Java observer involvement.

---

## 4. WebRTC factory architecture (relevant slices)

### Shared across all PCs from one factory

```text
signaling_thread
worker_thread
network_thread
cricket::WebRtcVoiceEngine / MediaEngine (via factory)
AudioDeviceModule (JavaAudioDeviceModule → native ADM)
```

Each `PeerConnection` has:

```text
operations_chain_     (per-PC SDP op serialization; W3C ops chain)
Call + transceivers
signaling_thread()    → returns factory->signaling_thread()  (same thread)
```

Source: `pc/peer_connection.h` · `pc/peer_connection_factory.cc`.

### Design intent vs talkback stress

WebRTC documents **one factory, many PCs** as normal. Talkback adds stressors:

```text
same ADM + same physical AudioTrack on all PCs
sequential host answer-apply (not parallel lab minimal SDP)
real ICE + continual gathering + conference edge executors
post-SRD#1 ICE/media activation on PC-A while PC-B enters SRD
```

---

## 5. `SetRemoteDescription(answer)` native path (code-level sketch)

Normative internal flow (Chromium `pc/`, stable across recent branches):

```text
PeerConnection::SetRemoteDescription
  → enqueue on PC operations_chain_ (signaling_thread)
  → SdpOfferAnswerHandler::SetRemoteDescription
       → parse / ValidateSessionDescription
       → ApplyRemoteDescription (sync phase on signaling thread)
            → JsepTransportController updates
            → worker_thread()->Invoke (media section / channel setup)
            → network_thread tasks (transport)
       → post observer onSetSuccess (async callback; after sync phase returns)
```

**Per-PC** ops chain prevents PC-B `CreateOffer` racing PC-B `SetRemoteDescription`; it does **not** isolate PC-B's chain from **other tasks on the same signaling_thread** posted by PC-A (ICE signaling, transceiver updates, Java observer callbacks).

**Key desk point:** ord-2 hang is **before** observer → failure is in **sync ApplyRemoteDescription subgraph** or **thread marshal wait**, not in Java latch/callback handling.

---

## 6. Candidate mechanisms (code-level, ranked)

Each is a **hypothesis class** — not a confirmed root cause. Falsification = what would disprove it in a **native repro or targeted trace** (not production field without IA).

### S1 — Shared `signaling_thread` backlog / non-returning task (HIGH)

**Mechanism:** PC-A post-SRD work (signaling state transitions, transport updates, queued Java JNI callbacks) keeps `signaling_thread` busy or stuck. PC-B `SetRemoteDescription` is posted behind that work; edge thread waits forever at `Marshal`.

**Why fits discriminant:** ord-1 ENTER has no competing post-answer PC; ord-2 ENTER always has PC-A in ANSWER + ICE active.

**Falsify:** Native trace shows `signaling_thread` idle while PC-B marshal waits; or hang with zero queued signaling tasks after PC-A quiesced.

---

### S2 — Signaling ↔ worker `Invoke` lock/order inversion (HIGH)

**Mechanism:** `ApplyRemoteDescription` on signaling thread calls `worker_thread()->Invoke(...)`. PC-A ICE/media path may hold worker resources or invoke back to signaling. PC-B SRD nested `Invoke` waits on worker while worker waits on signaling — **classic WebRTC cross-thread deadlock**.

**Why fits:** ord-2 correlates with PC-A **media/ICE activation** on worker/network, not merely SDP state enum change.

**Falsify:** Hang with PC-A ICE frozen/disabled (no worker activity) but same ordinal failure; or stack trace shows no worker involvement at hang.

**Precedent (pattern, not same stack):** blocking `setRemoteDescription` from a thread that must drain the pool that completes its internal work (libdatachannel #1611 — illustrates **sync SRD + pool/thread starvation**, not WebRTC identity).

---

### S3 — Shared `MediaEngine` / voice channel setup serialization (MEDIUM–HIGH)

**Mechanism:** One voice engine behind factory. PC-A answer-apply creates/starts audio channel tied to shared ADM. PC-B answer-apply contends on engine-global locks or ADM init (`InitRecording` / `InitPlayout` / stream count) during `ApplyRemoteDescription`.

**Why fits:** shared ADM is mandatory in talkback; D-α showed ADM sharing is fragile (different topology — see §8).

**Falsify:** Hang with dummy/silent ADM or receive-only transceivers on PC-A (no playout/capture init) but ordinal-2 still hangs.

---

### S4 — Talkback `SharedLocalAudio` single track on N PCs (MEDIUM, product-specific)

**Mechanism:** All PCs `addTrack(same AudioTrack)`. PC-A post-SRD may enable capture/playout paths (`startCapture`, remote track attach). PC-B SRD triggers transceiver negotiation referencing the **same** underlying `AudioSource` / native track ID; MediaEngine may serialize or mis-handle multi-PC attachment to one source.

**Code:** `SharedLocalAudio.kt` · `RealWebRtcAudioEngine` init `addTrack`.

**Falsify:** Lab repro with **per-PC local tracks** (same ADM, same factory) → ordinal-2 passes consistently.

---

### S5 — Java re-entrancy from signaling-thread callbacks (MEDIUM)

**Mechanism:** `PeerConnection.Observer` / `SdpObserver` JNI callbacks run on `signaling_thread`. If PC-A callback path synchronously dispatches back to edge executor or acquires `sdpApplyMutex` while PC-B edge holds mutex inside `setRemoteDescription`, deadlock.

**Desk note:** ord-2 never reaches `SRD_CALLBACK` on PC-B; primary block is **earlier**. S5 remains relevant if PC-A **ongoing** callbacks during PC-B JNI (ICE/state observers) acquire locks or block on PC-B edge.

**Falsify:** Hang with no-op empty `PeerConnection.Observer` on all PCs in repro.

---

### S6 — Cross-PC `operations_chain` coupling (LOW)

**Mechanism:** Spec says independent chains; if implementation bug tied chains at factory level, PC-A op would block PC-B op.

**Falsify:** Source review shows no shared chain; independent PC-B chain head blocked while PC-B chain empty.

---

### S7 — `JavaAudioDeviceModule` / Android audio thread `nativeLock` (MEDIUM)

**Mechanism:** ADM uses synchronized native bootstrap (`getNative` / `WebRtcAudioRecord` / `WebRtcAudioTrack`). Post-SRD ICE may start audio threads on PC-A; PC-B SRD path touches ADM on worker/signaling and blocks on `nativeLock` held by audio thread (or reverse).

**Falsify:** Hang before any ADM record/play thread start; or repro on non-Android desktop WebRTC with same graph.

---

### Mechanism summary table

| ID | Layer | Shared object | Ord-2 correlate | Java re-prime seam? |
|----|-------|---------------|-----------------|---------------------|
| S1 | Threading | `signaling_thread` | PC-A posts | **No** |
| S2 | Threading | signaling ↔ worker | PC-A ICE/media | **No** |
| S3 | Media | VoiceEngine + ADM | ADM active | **No** |
| S4 | Media | shared AudioTrack | 3× attach | **No** |
| S5 | JNI/Java | callbacks + mutex | cross-edge | **No** |
| S6 | PC | operations_chain | unlikely | **No** |
| S7 | Android | JavaAudioDeviceModule | audio threads | **No** |

**§11 alignment:** none of S1–S7 expose a **stable, Java-loggable factory counter** that E3 or `sdpApplyMutex` could reset. A fix, if any, targets **native thread/media graph** or **architecture** — not `sharedFactorySrdReprime()`.

---

## 7. D-α lab boundary (do not over-read)

| | D-α lab | Production fault |
|--|---------|------------------|
| Factories | **2** + 1 ADM | **1** + 1 ADM |
| PCs | 2 minimal | 3 + shared track |
| Result | construct crash / V4 unreachable (M01/M03) | ordinal-2 TIER_JNI hang |
| Implication | ADM+multi-factory **unsafe** on devices | **Different** failure mode; D-α **neither confirms nor refutes** S1–S7 |

V4 intent (A signaling held, B SRD completes) tests **two factories**, not **one factory, two PCs, sequential SRD** — so it does not gate this brief's primary question.

---

## 8. Why 9a deferred (explicit)

```text
SRD_BOUNDARY_SNAPSHOT (all attached PCs + session ordinal)
```

would re-document the **already known** correlate (post-SRD peer + ordinal) without explaining **which native mechanism** blocks. §11 + this brief: **next evidence must come from native source / repro**, not another Java-only snapshot tier.

**Re-authorize 9a only if:** 9b phase-2 cannot narrow S1–S7 and a **specific native variable** is named that Java snapshot must capture.

---

## 9. Decision tree (post-9b)

```text
native source brief (this doc)
        │
        ├─► S1/S2 narrowed by source + repro
        │       → design minimal fix at thread/task boundary
        │       → NOT Java re-prime wrapper
        │
        ├─► S3/S4/S7 → ADM / shared-track limitation documented
        │       → stop Java-layer patches
        │       → architecture / capability boundary review
        │         (per-PC track, factory split, relay topology, etc.)
        │
        └─► no mechanism narrowed
                → authorize 9a **targeted** instrumentation OR
                  native hang stack capture (signaling/worker)
                → Implementation IA remains BLOCKED
```

### Still frozen

```text
force-release · PC reset · Factory partition · B2
sharedFactorySrdReprime placeholder · Implementation IA · field 1a/1b
```

---

## 10. Recommended next work (9b phase-2 — desk/native, not product IA)

**Status:** Phase-2 **AUTHORIZED** — see [primary-native-srd-fault-native-phase2-repro-authorization-20260825.md](./primary-native-srd-fault-native-phase2-repro-authorization-20260825.md) · harness `com.talkback.lab.pnsrd` · [source trace](./primary-native-srd-fault-native-phase2-source-trace-20260825.md).

Priority order:

1. **Pin exact WebRTC commit** bundled in `stream-webrtc-android:1.3.10` (Stream release notes / AAR build metadata) and trace `SdpOfferAnswerHandler::SetRemoteDescription` + `ApplyRemoteDescription` on that commit.

2. **Minimal native repro** (instrumented test, **not** production path):
   ```text
   1 factory · 1 ADM · 2 PCs · shared AudioTrack optional arm
   PC-A: offer → SLD → SRD(answer) → ICE lite or loopback
   PC-B: offer → SLD → SRD(answer)   ← expect hang if matches field
   ```
   Arms: S4 off (per-PC tracks) · S3 off (recv-only) · PC-A idle at B-SRD.

3. **Hang capture:** `signaling_thread` / `worker_thread` stall stacks (Android debugger or WebRTC internal trace) at TIER_JNI — distinguish S1 vs S2 vs S3.

4. **Only then** decide 9a vs architecture track.

---

## 11. Verdict / gate impact

```text
9b brief                         COMPLETE
Primary defect class             shared factory multi-PC sequential SRD (native sync path)
Best native hypothesis classes   S1 + S2 (shared signaling/worker interaction)
                                  S3 + S4 + S7 (ADM / shared track — talkback-specific)
Java re-prime seam               STILL NOT NAMED — B NOT SELECTED
sharedFactorySrdReprime          MUST NOT implement
9a                               DEFERRED (conditional on 9b phase-2)
Implementation IA                BLOCKED
Field                            NOT AUTHORIZED
```

**One-sentence outcome:**

> Field + §11 show **where** it hangs (JNI sync wait); 9b names **plausible native mechanisms** in shared factory threading/media — not a Java factory counter — so the next step is **native repro + source trace**, not broader Java snapshot.

---

## Appendix — Source references

| Topic | Location |
|-------|----------|
| Android JNI SRD | `sdk/android/src/jni/pc/peer_connection.cc` |
| Factory threads | `sdk/android/src/jni/pc/ownedfactoryandthreads.cc` |
| Proxy marshal block | `pc/proxy.h` · `api/proxy.h` |
| PC ops chain | `pc/peer_connection.h` |
| SDP apply | `pc/sdp_offer_answer_handler.cc` |
| Product factory | `WebRtcSharedFactory.kt` |
| Product SRD path | `RealWebRtcAudioEngine.kt` (`invokeNativeSet`) |
| Shared track | `SharedLocalAudio.kt` |
| D-α scope | `conference-srd-d-alpha-lab-spike-authorization-001.md` |

Chromium paths are normative for `org.webrtc` API behavior in the prebuilt AAR; line numbers require pinned commit from step 10.1.
