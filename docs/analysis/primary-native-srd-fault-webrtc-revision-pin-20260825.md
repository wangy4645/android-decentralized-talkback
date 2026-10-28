# Primary Native SRD Fault — WebRTC Revision Pin

**ID:** primary-native-srd-fault-webrtc-revision-pin-20260825  
**Date:** 2026-08-25  
**Type:** Desk revision pin · read-only AAR inspection  
**Status:** **PINNED (desk)** · Stream post-branch patches **PROVISIONAL**  
**Parent:** [primary-native-srd-fault-native-phase2-source-trace-20260825.md](./primary-native-srd-fault-native-phase2-source-trace-20260825.md)

**Product dependency:** `io.getstream:stream-webrtc-android:1.3.10`

---

## 1. Pin chain (authoritative for source browse)

```text
talkback (build.gradle.kts)
  io.getstream:stream-webrtc-android:1.3.10          Maven AAR
        │
        ├─ Java delta vs 1.3.9: audio attributes only (GetStream PR #310)
        │
        └─ libjingle_peerconnection_so.so
              Stream packaging label: m125.4
              GetStream libs commit: 37b8867 (2024-12-17, "Update patches m125.4")
                    │
                    └─ Chromium WebRTC M125 · branch refs/branch-heads/6422
                          branch point: b831eb816ef847d09d446ef4168e36b13af163f8
                          (main@{#42072}, 2024-04-15)
```

| Field | Value | Confidence |
|-------|-------|------------|
| Maven coordinate | `io.getstream:stream-webrtc-android:1.3.10` | **PINNED** |
| GetStream tag | `1.3.10` (2025-09-11) | **PINNED** |
| Stream native label | **m125.4** (`37b8867763b8b64f2050b3a5e959e1eef1b687d9`) | **PINNED** |
| Chromium milestone | **M125** | **PINNED** ([Chromium Dash](https://chromiumdash.appspot.com/fetch_milestones?mstone=125)) |
| WebRTC branch | **`refs/branch-heads/6422`** | **PINNED** |
| Upstream commit (stamp match) | **`b831eb816ef847d09d446ef4168e36b13af163f8`** | **PINNED** |
| Embedded source stamp | `WebRTC source stamp 2024-04-15T04:12:23` | **PINNED** (from AAR `.so`) |
| arm64-v8a `.so` SHA256 | `4E9D2DF81F7DB6D184A9D0024E6F9825C6325C3EE54A8D61EF657F02EF8D85E7` | **PINNED** |
| Stream patches after branch point | unknown set / count | **PROVISIONAL** |

**Browse base (line-level RCA):**

```text
https://webrtc.googlesource.com/src/+/b831eb816ef847d09d446ef4168e36b13af163f8/
https://webrtc.googlesource.com/src/+/refs/branch-heads/6422/
```

---

## 2. Evidence procedure (desk, 2026-08-25)

### 2a. Maven AAR

```text
Source: Gradle cache stream-webrtc-android-1.3.10.aar
Desk copy: talkback/logs/pnsrd-desk-aar-20260825/
Extract: jni/*/libjingle_peerconnection_so.so
```

AAR `META-INF` carries no WebRTC commit hash (Gradle metadata only).

### 2b. Embedded build stamp

Strings in arm64 `libjingle_peerconnection_so.so`:

```text
WebRTC source stamp 2024-04-15T04:12:23
SdpOfferAnswerHandler::ApplyRemoteDescription
SdpOfferAnswerHandler::DoSetRemoteDescription
signaling_thread
worker_thread
pc_worker_thread
Java_org_webrtc_PeerConnection_nativeSetRemoteDescription
Java_org_webrtc_audio_JavaAudioDeviceModule_nativeCreateAudioDeviceModule
```

Stamp matches `call/version.cc` at upstream commit `b831eb81…` exactly.

### 2c. GetStream release delta

`1.3.9…1.3.10` compare: **no** `stream-webrtc-android/libs/**` change.  
Native shipped in 1.3.10 == native at m125.4 libs update (Dec 2024).

### 2d. Chromium / WebRTC branch map

Chromium Dash M125:

```text
chromium_branch: 6422
webrtc_branch:   6422
```

`refs/branch-heads/6422` log shows branch from `b831eb81…` with two post-branch cherry-picks (`89e26b7`, `8505a98`). Stream build stamp **does not** include those version bumps → compiled tree is **at or before branch point**, plus **unknown Stream patches** (m125.4).

---

## 3. Adjudication fields (use in capture / RCA artifacts)

```text
webrtc_maven: io.getstream:stream-webrtc-android:1.3.10
webrtc_stream_label: m125.4
webrtc_chromium_milestone: M125
webrtc_branch: refs/branch-heads/6422
webrtc_commit: b831eb816ef847d09d446ef4168e36b13af163f8
webrtc_source_stamp: 2024-04-15T04:12:23
webrtc_so_sha256_arm64: 4E9D2DF81F7DB6D184A9D0024E6F9825C6325C3EE54A8D61EF657F02EF8D85E7
webrtc_stream_libs_commit: 37b8867763b8b64f2050b3a5e959e1eef1b687d9
webrtc_pin_confidence: STAMP+BRANCH (Stream delta patches PROVISIONAL)
```

---

## 4. Guardrails

```text
Do NOT treat 2PC/3PC lab PASS as "different WebRTC revision"
Do NOT reopen Java Coordinator / Barrier / Lease for revision uncertainty
If stack inconclusive → pinned-source instrumentation (see runbook §15)
Do NOT claim exact Stream cherry-pick list without their DEPS / build log
```

---

## 5. Status

```text
Revision pin (desk)     COMPLETE
Source browse commit    b831eb816ef847d09d446ef4168e36b13af163f8
Production hang stack   PENDING (runbook)
Native RCA / fix IA     BLOCKED until stack names wait site
```
