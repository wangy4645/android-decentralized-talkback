# Phase A Shadow Runtime — OBS Sufficiency Audit

**Date:** 2026-09-09  
**Scope:** Read-only audit before `phase-a-shadow-runtime-first-run.ps1` + adjudicator  
**Baseline evidence:** `talkback/logs/j0-meeting-operational-20260909-103707/`

---

## Question

Can existing `MEETING_MEDIA_SHADOW` / conference runtime logs **directly adjudicate** PA-SR1…PA-SR6 without inferring PASS from absence of failure?

---

## Verdict

**INSUFFICIENT for SR4 and SR6** on J0-class evidence.  
**Thin OBS required** (`PROFILE01_SHADOW_RUNTIME`) before field driver/adjudicator.

---

## Field-by-field audit

| Adjudication need | Existing OBS | J0 PASS log (`103707`) | Sufficiency |
|-------------------|--------------|------------------------|-------------|
| PRE-G/I/J | `GROUP_GATE` / `INVITE_JOIN_GATE` / `JOIN_GATE` | PASS artifacts present | **Sufficient** (driver reuse) |
| CREATION APPLIED (SR1 prereq) | `WIRE_INGRESS factType=1 outcome=APPLIED` | Observed on M02 | **Sufficient** (J0 closed; not re-litigated) |
| `SESSION_STARTED` shadow hook | `SHADOW hook=SESSION_STARTED outcome=*` | `DEFERRED_NO_FACT` on both devices | **Insufficient alone** — hook outcome without runtime snapshot |
| Wiring live / identity (SR2) | None field-logged | `runtimeSnapshot()` exists but not emitted | **Insufficient** |
| `MEMBER_MEDIA_READY` (SR3) | `SHADOW hook=MEMBER_MEDIA_READY outcome=*` | `DEFERRED_NO_BINDING` | **Insufficient alone** — no catalog/admitted proof |
| Source admitted / catalog (SR3) | No stable tag | No `admittedCount` / `catalogEntries` in logcat | **Insufficient** |
| Ingress activity (SR4) | `MulticastTransportObservability` in-memory only | No `packetsReceived` / `ingressAccepted` in logcat | **Insufficient** |
| Jitter/decode/playout activity (SR4) | `RecordingPlayoutMetricsSeam` counters in-memory | No `successfulPlayoutWrites` / playout phase in logcat | **Insufficient** |
| Shadow playout clock advances (SR4) | None | None | **Insufficient** |
| Production AudioTrack fence | `AndroidAudioTrackPlayoutSeam.start()` silent | Cannot prove `NO_AUDIO_TRACK` vs ADR-0056 path | **Insufficient** |
| Member leave revoke/drain (SR5) | `SHADOW hook=MEMBER_REMOVED outcome=*` | J0 run did not exercise leave | **Untested** — hook exists; needs `WIRING_MEMBER_REVOKED` snapshot |
| Rejoin replacement (SR5) | `MEMBER_REPLACED` hook | Untested | **Insufficient** without generation/catalog OBS |
| Meeting end teardown (SR6) | `SHADOW hook=SESSION_STOPPED` | J0 run did not end meeting | **Untested** |
| A4 empty assert (SR6) | `assertRuntimeEmptyAfterStop` throws only | No `TEARDOWN_EMPTY` emission | **Insufficient** |
| Registry clear / endScope | None field-logged | None | **Insufficient** |

---

## Risk if adjudicator built on current OBS only

```text
False PASS via:
  SHADOW hook invoked (INVOKED / DEFERRED_*)
  catalog non-empty inference from MEMBER_MEDIA_READY attempt
  “no SHADOW_FAILED” silence

Violates frozen SR4 boundary:
  wiring/catalog ≠ shadow runtime running
```

---

## Thin OBS added (behavior-neutral)

Tag: `MEETING_MEDIA_SHADOW` · prefix: `PROFILE01_SHADOW_RUNTIME`

| Phase | When | Fields |
|-------|------|--------|
| `WIRING_SESSION_STARTED` | `ConferenceSessionMediaWiring.startSession` | transportScopeActive, catalogEntries, admittedCount, jitter/decoders |
| `SHADOW_SESSION_STARTED` | Coordinator delegate APPLIED | same snapshot + sessionId/conferenceId/epoch |
| `WIRING_MEMBER_INSTALLED` / `SHADOW_MEMBER_INSTALLED` | installMember APPLIED | moduleId, admittedCount, catalogEntries |
| `INGRESS_ACTIVITY` | pipeline ingress accepted (throttled) | packetsReceived, ingressAccepted, jitter/decoders, playout writes |
| `PLAYOUT_ACTIVITY` | shadow playout write / mix cycle | successfulPlayoutWrites, audioTrackOwner=SHADOW_METRICS_ONLY |
| `WIRING_MEMBER_REVOKED` | removeMember | post-revoke snapshot |
| `TEARDOWN_EMPTY` | stopSession after A4 assert | all counters zero, transportScopeActive=false |
| `AUDIO_TRACK_OWNERSHIP` | `AndroidAudioTrackPlayoutSeam.start()` | owner=PRODUCTION_ACTIVE (**EXPECTED** — ADR-0056 audible owner) |

Implementation: `Profile01ShadowRuntimeObservability.kt`

---

## Adjudicator dependency

PA-SR4 **requires** on-session `PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY` **and** `phase=PLAYOUT_ACTIVITY` with `audioTrackOwner=SHADOW_METRICS_ONLY`.

PA-SR6 **requires** `TEARDOWN_EMPTY` with `transportScopeActive=false catalogEntries=0 admittedCount=0 activeJitterSources=0 liveDecoders=0`.

`AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE` during shadow window → **EXPECTED** (ADR-0056).

**FAIL_SHADOW_RUNTIME** only when shadow `INGRESS_ACTIVITY` / `PLAYOUT_ACTIVITY` claims `audioTrackOwner=PRODUCTION*`.

---

## Next steps (authorized)

```text
1. OBS sufficiency audit          — this document
2. Thin OBS                       — implemented
3. field driver + adjudicator     — in progress
4. Phase A Shadow Runtime field   — pending new APK if OBS not in deployed build
```
