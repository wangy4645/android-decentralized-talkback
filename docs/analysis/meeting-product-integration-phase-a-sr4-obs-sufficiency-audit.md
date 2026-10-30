# PA-SR4 — OBS Sufficiency Audit (Real Shadow Media Activity Gate)

**Date:** 2026-09-09  
**Scope:** Read-only audit before PA-SR4 field authorization · **no product code changes**  
**Prerequisite:** PA-SR3 **CLOSED / FIELD-PROVEN** (`phase-a-sr3-field-20260909-154735`)  
**Parent OBS baseline:** `meeting-product-integration-phase-a-shadow-runtime-obs-sufficiency-audit.md`  
**Adjudication:** `meeting-product-integration-phase-a-shadow-runtime-adjudication-criteria.md` § PA-SR4

---

## Question

Can existing `MEETING_MEDIA_SHADOW` / `PROFILE01_SHADOW_RUNTIME` logs **directly adjudicate PA-SR4** — real shadow ingress + playout activity with ADR-0056 AudioTrack fence — without inferring PASS from SR1–SR3 hooks alone?

---

## Verdict

**SUFFICIENT for PA-SR4 field gate.**

Thin OBS (`Profile01ShadowRuntimeObservability.kt`) already implements gate-critical phases. **No new behavior-neutral OBS is authorized** before the first PA-SR4 field run.

If field FAILs with SR3 green but zero `INGRESS_ACTIVITY` / `PLAYOUT_ACTIVITY`, route to **runtime execution RCA** — not OBS sufficiency reopen.

---

## Gate-critical OBS inventory

| Need | Emitter | Log pattern | Adjudicator |
|------|---------|-------------|-------------|
| Ingress runtime | `ConferenceMulticastMediaPipeline` → `maybeLogIngressActivity` | `PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY sessionId=H packetsReceived=N ingressAccepted=N activeJitterSources=N liveDecoders=N … audioTrackOwner=SHADOW_METRICS_ONLY` | `phase-a-shadow-runtime-adjudicate.ps1` `Test-IngressActivityLine` |
| Playout runtime | `ConferenceMulticastMediaPipeline` / `ConferenceMulticastRealMediaAssembly` → `maybeLogPlayoutActivity` | `PROFILE01_SHADOW_RUNTIME phase=PLAYOUT_ACTIVITY sessionId=H successfulPlayoutWrites=N audioTrackOwner=SHADOW_METRICS_ONLY` | `Test-PlayoutActivityLine` |
| ADR-0056 fence (expected) | `AndroidAudioTrackPlayoutSeam` → `logProductionAudioTrackFence` | `PROFILE01_SHADOW_RUNTIME phase=AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE` | **EXPECTED** — not FAIL |
| Shadow ownership violation | same phases with wrong owner | `… audioTrackOwner=PRODUCTION*` on INGRESS/PLAYOUT | **FAIL** hard fence |

**Tag:** `MEETING_MEDIA_SHADOW` · **Implementation:** `Profile01ShadowRuntimeObservability.kt`

---

## Field-by-field sufficiency

| Adjudication need | OBS present? | Synthetic fixture | Post-SR3 field (`154735`) | Sufficiency |
|-------------------|--------------|-------------------|---------------------------|-------------|
| SR4 ingress packets + jitter/decoders | `INGRESS_ACTIVITY` | `pass-synthetic` ✓ | **0 lines** (observe-only window) | **Sufficient** when pipeline runs |
| SR4 playout writes | `PLAYOUT_ACTIVITY` | `pass-synthetic` ✓ | **0 lines** | **Sufficient** when pipeline runs |
| `audioTrackOwner=SHADOW_METRICS_ONLY` | on both phases | ✓ | n/a | **Sufficient** |
| Production AudioTrack not stolen by shadow | `AUDIO_TRACK_OWNERSHIP` + shadow owner check | documented | not exercised | **Sufficient** |
| Session correlation | `sessionId=` on all phases | ✓ | SR3 session bound | **Sufficient** |
| SR3 prerequisite in same run | `MEMBER_MEDIA_READY` + `SHADOW_MEMBER_INSTALLED` | separate gate | **PASS** on SR3 run | Prereq — not SR4 OBS |

---

## Residual risks (documented — do not block authorization)

| Risk | Mitigation in run card |
|------|------------------------|
| Ingress/playout log **throttling** (first + every 25th) | 45s observation window + 3× PTT burst stimulus on M01 |
| Short PTT may miss playout line | Poll until deadline; extend `ShadowObservationSec` only on INVALID_CAPTURE — not default |
| Host vs peer ingress asymmetry | Adjudicator accepts lines on **either** M01 or M02 log |
| No dedicated “playout clock tick” phase | `successfulPlayoutWrites>0` is authoritative per frozen SR4 contract |
| Driver fingerprint vs logcat mismatch | Adjudicator uses log lines only; driver fingerprints are diagnostic |

---

## What is NOT sufficient (frozen — adjudicator already enforces)

```text
SESSION_STARTED alone
MEMBER_MEDIA_READY hook alone
catalog/admittedCount alone
absence of SHADOW_FAILED
```

---

## OBS not authorized (this gate)

```text
New product phases for SR4
Behavior changes to increase log volume
AudioTrack / ADR-0056 path changes
SR5 leave/rejoin OBS additions
SR6 teardown OBS additions
```

Reopen OBS only if field shows **ambiguous adjudication** (e.g. activity present but parser cannot classify). That has **not** occurred — prior SR4 FAILs were **zero activity** with SR3 open.

---

## Sign-off

| Item | Value |
|------|-------|
| **Audit verdict** | **SUFFICIENT** |
| **New OBS before field** | **NOT REQUIRED** |
| **Next artifact** | `meeting-product-integration-phase-a-sr4-field-authorization.md` + run card |
