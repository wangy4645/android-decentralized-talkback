# RCA-OBS-001 — SRD Post-Admission Native Execution (Read-Only)

**ID:** conference-srd-post-admission-native-execution-rca-obs-001-authorization  
**Date:** 2026-08-21  
**Type:** Implementation Authorization · Read-Only Observability  
**Status:** **COMPLETE** · field **PASS** (`logs/rca-obs001-srd-native-20260821-131854/`)

**Parent RCA:** [conference-srd-post-admission-native-execution-failure-rca.md](./conference-srd-post-admission-native-execution-failure-rca.md)

**Seed session:** `034563a9-3fa8-49c2-a677-b96393498627` · `logs/ia002-admission-20260821-120004/`

---

## Goal

Close observability gaps so each edge lifecycle can be adjudicated **without behavior change**.

**Not:** fix SRD · change timeout · retry · mutex policy change.

---

## Authorized scope (read-only)

### OBS-1 — Unified SRD lifecycle correlation

Extend existing events (`SRD_DISPATCH`, `SRD_EXECUTOR_ENTER`, `SRD_ENTER`, `SRD_*` in `RealWebRtcAudioEngine`) so **one line** can join:

```text
sessionId
edgeKey
remoteModuleId
pcGeneration
conferenceGeneration (when available)
```

Prefer extending `ConferenceSrdObservability` + `logSrdBoundary` — **no new event names required** unless gap cannot be closed.

---

### OBS-2 — Signaling state at boundaries

Log `signalingState` (and `localDesc`/`remoteDesc` summary) at:

```text
SRD_ENTER
SRD_MUTEX_ACQUIRED
SRD_JNI_RETURN
SRD_CALLBACK_ENTER / SRD_CALLBACK_FAILURE
```

Partially present in `WEBRTC_NEGOTIATION phase=BEFORE/AFTER` — **normalize into SRD boundary lines** for grep.

---

### OBS-3 — Callback / thread identity

On every `SRD_CALLBACK_*` and `SRD_EXIT`:

```text
callbackThread
callbackTid
observerHash
pcHash
```

(M02 seed already shows `signaling_threa` — M03 missing callback entirely; make absence obvious in analysis.)

---

### OBS-4 — Gap marker at watchdog

When `CONFERENCE_MEDIA_EDGE_FAILED reason=SRD_TIMEOUT`, emit **one** correlate line:

```text
SRD_WATCHDOG_GAP
lastEvent=<last SRD_* seen>
missing=<JNI_RETURN|CALLBACK|EXIT|MUTEX_RELEASED>
edgeKey=...
pcHash=...
```

Behavior-neutral: log only; **do not** change watchdog budget.

---

### OBS-5 — Answer SDP size (compare only)

At `SRD_ENTER`:

```text
answerSdpBytes=
answerSdpLines=
```

For M02/M03/M04 lateral compare only.

---

## Explicitly not authorized

```text
SRD timeout change
retry / rollback policy change
sdpApplyMutex scope change
ICE restart · Ready · Recovery · Topology
executor / observation lane changes
product telemetry expansion beyond RCA grep needs
```

---

## Desk validation (after impl)

**Setup:** force-stop · clean conference · M01 anchor · M01→M02/M03/M04 star

**Must produce per edge:**

```text
Full chain M02: through SRD_EXIT SUCCESS
M03/M04: explicit SRD_WATCHDOG_GAP missing=...
```

**Pass:** adjudicator can answer **which segment** failed without re-opening executor/Anchor domains.

---

## Posture

```text
IA-002                    COMPLETE (PASS)
P0.1g Acceptance 1        FAIL / HOLD
This OBS package          AUTHORIZED — read-only
RCA fix                   NOT AUTHORIZED
```

---

## State diagram

```text
IA-002 PASS (admission)
        |
        v
RCA v0 OPEN
        |
        v
RCA-OBS-001 AUTHORIZED  ← this document
        |
        v
Desk field capture
        |
        v
RCA root cause freeze → separate fix authorization (if any)
```
