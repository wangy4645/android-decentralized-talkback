# D-α Lab Spike Authorization-001

**ID:** conference-srd-d-alpha-lab-spike-authorization-001  
**Date:** 2026-08-21  
**Version:** v0.1  
**Type:** Lab Spike Authorization · **LAB ONLY**  
**Status:** **COMPLETE — FAIL** · **Production NOT AUTHORIZED** · **P0.1g HOLD**

**Evidence:** [logs/dalpha-lab-spike-20260821-155612/ADJUDICATION.txt](../../logs/dalpha-lab-spike-20260821-155612/ADJUDICATION.txt)

---

## Purpose (single)

> **Prove or disprove:** two `PeerConnectionFactory` instances sharing one `JavaAudioDeviceModule` can execute **concurrent native SRD** independently — such that Factory A native stall does **not** block Factory B `setRemoteDescription` completion.

**Not:** full Conference · B2-1 integration · production path change · P0.1g field pass claim.

---

## Authorization boundary

| | |
|-|-|
| **AUTHORIZED** | Independent lab harness / instrumented test · read-only adjudication doc |
| **NOT AUTHORIZED** | Production Conference path · `WebRtcSharedFactory` change · `SharedLocalAudio` production change · Commit 4 · B2-2 implementation · P0.1g rerun |

```text
Architecture “possible” ≠ Android/WebRTC runtime supported
Must be demonstrated in lab before Implementation Authorization
```

---

## Experiment model

```text
                Shared ADM (one instance)
                        |
            +-----------+-----------+
            |                       |
        Factory A               Factory B
            |                       |
          PC-A                    PC-B
            |                       |
         Edge A                  Edge B   (logical · not production edges)
```

**Not** full 4p Conference. **Not** production `dispatchConferenceAcceptMedia`.

---

## Five mandatory verifications

### V1 — Factory independence

```text
factoryA != factoryB (object identity)
native signaling domain A != B (observed: concurrent SRD progress on B while A stuck)
```

Java wrapper difference alone is **insufficient**.

### V2 — ADM safe sharing

```text
ADM
 ├── Factory A
 └── Factory B
```

Must **not** observe:

```text
init conflict · callback cross-wire · release cascade · capture interrupt
```

### V3 — Local audio lifecycle (lab scope)

`SharedLocalAudio` **must not** be reused across Factory in production shape.

Lab must validate **pattern only:**

```text
Factory A → local track from Factory A APIs
Factory B → local track from Factory B APIs
(same logical mic — separate lab attach · no production SharedLocalAudio change)
```

### V4 — Core: A stuck, B completes (P0.1g shape)

```text
Factory A: SRD_NATIVE_CALL_ENTER → (no EXIT) — induced stall

Factory B: SRD_NATIVE_CALL_ENTER → EXIT → callback path
```

**FAIL immediately if B blocked at:**

```text
signalingState() / negotiationSnapshot() equivalent
```

### V5 — Lifecycle / release isolation

```text
Factory A dispose  →  Factory B still operational
ADM release        →  must NOT occur while Factory B active
```

---

## Harness requirements

```text
Package: com.talkback.lab.dalpha
Type:    androidTest instrumented (device / emulator with WebRTC native)
Must NOT import production Conference coordinator paths for SRD apply
May use org.webrtc APIs directly
```

### Prohibited in spike

```text
Modify production Conference SRD path
Wire B2-1 domain gate into lab as normative proof
Modify sdpApplyMutex
Watchdog / retry / ICE restart / Recovery
Change SharedLocalAudio production implementation
4p field conference test as substitute
```

---

## Pass / Fail / Blocked

### PASS (all required)

```text
Factory A native SRD stuck (induced)
        AND
Factory B native SRD completes (ENTER → EXIT → callback)
        AND
Shared ADM stable (capture not broken)
        AND
Factory A teardown does not destroy Factory B
```

→ Eligible for **B2-2 / D-α Implementation Authorization** review (separate gate).

### FAIL (any)

```text
B cannot enter native SRD while A stuck
ADM sharing conflict
Factory A release destroys B
Native signaling still globally serialized across factories
```

→ Escalate D-β risk audit or D-γ — **not** production D-α.

### BLOCKED

```text
Cannot construct 2 Factory + 1 ADM via public API without production hacks
```

→ Document API gap; do not force production workaround.

---

## Adjudication artifact

On completion, write:

```text
talkback/logs/dalpha-lab-spike-YYYYMMDD-HHMMSS/ADJUDICATION.txt
```

Minimum fields:

```text
verdict: PASS | FAIL | BLOCKED
v1_factory_independent: ...
v2_adm_shared: ...
v3_local_audio_pattern: ...
v4_a_stuck_b_completes: ...
v5_lifecycle: ...
device: ...
webrtc: stream-webrtc-android:1.3.10
notes:
```

---

## Architecture meaning if PASS

```text
D-α = per-edge Factory + shared ADM
B2-1 = ownership / safety gate (retained)
Stack:
  Edge → B2-1 lease → per-edge Factory → independent native SRD domain
```

Enables **design** for P0.1g — not P0.1g PASS until production implementation + field IA.

---

## Lab result (2026-08-21)

```text
verdict: FAIL
```

**Evidence:** [ADJUDICATION.txt](../../logs/dalpha-lab-spike-20260821-155612/ADJUDICATION.txt)

| Gate | Result |
|------|--------|
| Shared ADM + 2 Factory construct | **FAIL** — SIGSEGV/SIGABRT on worker_thread (M01+M03) |
| V4 native SRD independence | **Unreachable** |

**Implication:** D-α is **not** a production direction on `stream-webrtc-android:1.3.10` for these devices.

---

## Posture

```text
B2-2-A              COMPLETE — FAIL
Model C             REJECTED
Model D-α           FAIL (lab)
D-β                 ON TABLE (not authorized)
D-γ                 OUT OF SCOPE

D-α lab spike       COMPLETE — FAIL
Production impl     NOT AUTHORIZED
Commit 4            HOLD
P0.1g               HOLD
```

---

## Next

1. Architecture review after D-α FAIL — D-β lab control vs hold partition track
2. Commit 4 — **HOLD**
3. P0.1g — **HOLD**

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | D-α lab spike authorized; harness scope; pass/fail criteria |
| 2026-08-21 | v0.2 | Lab COMPLETE — FAIL; shared ADM 2-factory crash M01/M03 |
