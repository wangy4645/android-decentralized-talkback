# Primary Native SRD Fault — Pinned Instrumentation Implementation IA

**ID:** primary-native-srd-fault-pinned-instrumentation-ia-20260825  
**Date:** 2026-08-25  
**Type:** Implementation authorization · **ONE SHOT**  
**Status:** **AUTHORIZED**  
**Parent:** [primary-native-srd-fault-pinned-instrumentation-design-20260825.md](./primary-native-srd-fault-pinned-instrumentation-design-20260825.md) (**ACCEPTED**)

---

## Frozen posture

```text
Instrumentation design         ACCEPTED (sign-off 2026-08-25)
Instrumentation IA             AUTHORIZED (this doc)
Minimal native fix design      BLOCKED (Outcome 1 only)
Implementation fix             BLOCKED
Field capture                  AUTHORIZED (one run, inst APK only)
Java Coordinator / lease / GCT NOT AUTHORIZED
```

**Boundary (unchanged):**

```text
S2-b1 CONFIRMED  ≠  async BlockingCall authorized
                 ≠  mutex force-release authorized
                 ≠  ADM reset authorized
```

Outcome 1 only proves **lock-order / hold relationship**. Fix design remains a separate gate.

---

## Authorized deliverables

| # | Deliverable | Location |
|---|-------------|----------|
| 1 | Instrumentation sources + patch seams | `talkback/webrtc-instrumentation/pnsrd-inst-1/` |
| 2 | Apply + build notes | `talkback/webrtc-instrumentation/pnsrd-inst-1/APPLY.txt` |
| 3 | Host deps: optional local inst AAR | `android-board-talkback/build.gradle.kts` |
| 4 | Capture: `PNSRD_INST` log pull | `scripts/pnsrd-hang-capture-on-hang.ps1` |
| 5 | ONE production hang + adjudication | `talkback/logs/pnsrd-inst-1-<timestamp>/` |

**Build output:** `pnsrd-inst-1` debug AAR (SHA256 **need not** match production). Embed string `pnsrd-inst-1` in arm64 `.so`.

---

## Implementation scope (strict)

### In

```text
rtc_base/pnsrd_instrumentation.{h,cc}
mutex.h central Lock/Unlock hook (§6 filtered — NOT global telemetry)
P0: PushdownMediaDescription · SetRemoteContent brackets
P1: labeled acquire sites (Call / VoiceEngine / VC)
P2: nativeGetEnabled · MethodCall::Marshal
PNSRD_INST logcat only
```

### Out

```text
Talkback Java/Kotlin telemetry changes
Coordinator · lease · GCT · extra SRD_* events
Second instrumentation round if Outcome 3
Fix patches
Maven publish of inst AAR
```

---

## Execution sequence

```text
1. Apply patches @ b831eb816ef847d09d446ef4168e36b13af163f8 (+ Stream m125.4 packaging)
2. Build arm64 inst AAR → libs/pnsrd-inst-1-stream-webrtc-android.aar
3. Host APK with local AAR override (record build id)
4. pnsrd-hang-capture-start-run.ps1
5. Real ord-2 TIER_JNI hang
6. on-hang (SIGQUIT + PNSRD_INST logcat)
7. Adjudicate design §8 → ADJUDICATION.txt (Outcome 1 | 2 | 3)
8. Revert AAR override unless Outcome 3 requires debugger track
```

---

## Adjudication routing

| Outcome | Action |
|---------|--------|
| **1** | Write **minimal native fix design** (lock-order seam only) · still no impl IA |
| **2** | Refute S2-b1 · re-route from new graph · no fix design |
| **3** | **STOP** telemetry · unstripped + native debugger · no further log layers |

---

## Sign-off chain

```text
Instrumentation Design  → ACCEPTED
Instrumentation IA      → AUTHORIZED (this doc)
Inst AAR + ONE capture  → IN PROGRESS
Outcome 1               → Minimal Fix Design gate opens
```
