# Phase A Shadow Runtime — Adjudication Criteria (PA-SR1…PA-SR6)

**Parent:** `meeting-product-integration-phase-a-shadow-runtime-gate-authorization.md`  
**OBS audit:** `meeting-product-integration-phase-a-shadow-runtime-obs-sufficiency-audit.md`  
**Status:** **FROZEN** (2026-09-09)

---

## Verdict model (three-state)

| Verdict | Meaning |
|---------|---------|
| **PASS** | PRE-G/I/J PASS · J0 prerequisite APPLIED · PA-SR1…SR6 PASS · no AudioTrack fence violation |
| **INVALID_*** | PRE/capture/device/harness insufficiency → `SHADOW_RUNTIME=NOT_TESTED` |
| **FAIL_SHADOW_RUNTIME** | Preconditions green · shadow/runtime product contract violated |

```text
FAIL_SHADOW_RUNTIME ≠ J0 regression
```

Do not re-open Profile01 / J0 / P1-C / P1-E unless **new independent evidence** shows those contracts broken in the same run.

---

## PA-SR1 — Meeting lifecycle admitted

```text
PRE-G / PRE-I / PRE-J PASS (reuse J0 harness gates)
Profile01 CREATION APPLIED on peer (factType=1 outcome=APPLIED)
→ SHADOW hook=SESSION_STARTED session=H outcome=APPLIED
```

J0 continuation chain is **prerequisite only** — not re-adjudicated unless digest/identity fence breaks.

---

## PA-SR2 — Wiring becomes live

```text
PROFILE01_SHADOW_RUNTIME phase=SHADOW_SESSION_STARTED|WIRING_SESSION_STARTED
sessionId=H
transportScopeActive=true
```

Identity keys must match manifest: `hostSessionId`, `conferenceId`, `mediaKeyEpoch`.

---

## PA-SR3 — MEMBER_MEDIA_READY

```text
SHADOW hook=MEMBER_MEDIA_READY session=H module=peer outcome=APPLIED
PROFILE01_SHADOW_RUNTIME phase=SHADOW_MEMBER_INSTALLED|WIRING_MEMBER_INSTALLED
admittedCount>=1 catalogEntries>=1
```

Catalog non-empty **alone** is insufficient for SR4.

---

## PA-SR4 — Real shadow ingress + playout runtime (**gate-critical**)

**Required** (both):

```text
PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY
  packetsReceived>0 OR ingressAccepted>0
  activeJitterSources>=1 OR liveDecoders>=1

PROFILE01_SHADOW_RUNTIME phase=PLAYOUT_ACTIVITY
  successfulPlayoutWrites>0
  audioTrackOwner=SHADOW_METRICS_ONLY
```

**Not sufficient:**

```text
SESSION_STARTED alone
MEMBER_MEDIA_READY alone
catalog/admittedCount alone
absence of SHADOW_FAILED
```

---

## PA-SR5 — Leave → rejoin

```text
SHADOW hook=MEMBER_REMOVED outcome=APPLIED (peer leave)
PROFILE01_SHADOW_RUNTIME phase=WIRING_MEMBER_REVOKED admittedCount=0 (or drained)
Rejoin: SHADOW hook=MEMBER_MEDIA_READY outcome=APPLIED (replacement/current)
No stale-generation ingress after revoke (no INGRESS_ACTIVITY for revoked moduleId before re-admit)
```

---

## PA-SR6 — Meeting end teardown

```text
SHADOW hook=SESSION_STOPPED session=H outcome=APPLIED
PROFILE01_SHADOW_RUNTIME phase=TEARDOWN_EMPTY
  transportScopeActive=false
  catalogEntries=0 admittedCount=0
  activeJitterSources=0 liveDecoders=0 jitterBufferCount=0
```

Sequence expectation: block ingress → revoke/drain → stop playout → retire epoch → endScope → assert empty.

---

## Hard fence — shadow must NOT own production AudioTrack

```text
ADR-0056 audible path = ACTIVE / OWNER   (EXPECTED in Phase A)
Phase A shadow        = NO_AUDIO_TRACK / NON_AUDIBLE
                      = PLAYOUT_ACTIVITY audioTrackOwner=SHADOW_METRICS_ONLY
```

`AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE` from production `AndroidAudioTrackPlayoutSeam` is **EXPECTED** (ADR-0056 continues to own audible audio) — **not** a FAIL.

Hard **FAIL_SHADOW_RUNTIME** only when shadow runtime claims production ownership, e.g.:

```text
PROFILE01_SHADOW_RUNTIME phase=PLAYOUT_ACTIVITY|INGRESS_ACTIVITY
  audioTrackOwner=PRODUCTION*
```

or any evidence that the shadow path opened/owned production AudioTrack.

---

## INVALID routing

| Condition | Verdict |
|-----------|---------|
| GROUP / invite / join gate FAIL | `INVALID_*` |
| Missing M01/M02 logs | `INVALID_CAPTURE` |
| CREATION APPLIED not observed | `INVALID_PREREQUISITE` |
| Driver did not run leave/end phases | `INVALID_DRIVER` |

---

## Script

`talkback/scripts/phase-a-shadow-runtime-adjudicate.ps1`
