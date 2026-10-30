# Phase A Shadow Runtime — First Field Run Result

**LogDir:** `talkback/logs/phase-a-shadow-runtime-20260909-105513/`  
**Stamp:** 2026-09-09-105513  
**Build:** `cac628d` (thin OBS included)  
**Devices:** M01 `HTUBB21B09220661` · M02 `DSJ-2407041`

---

## Verdict

```text
VERDICT=FAIL_SHADOW_RUNTIME
FIRST_RUN_EXIT=1
SHADOW_RUNTIME=TESTED (product contract miss)
J0_status=UNCHANGED_CLOSED
Profile01_Meeting_chain=UNCHANGED_CLOSED
ADR-0056=KEEP
Meeting_media_cutover=NOT_AUTHORIZED
```

**Not** INVALID — PRE-G / PRE-I / PRE-J PASS · J0 prerequisite CREATION APPLIED observed.  
**Not** J0 regression — Profile01 continuation chain closed as expected before SR window.

---

## Class 1 — Prerequisites

| Gate | Result |
|------|--------|
| PRE-G | PASS |
| PRE-I | PASS |
| PRE-J | PASS (`hostSessionId=8c0307dc-af41-44eb-bc99-47a6a287d1a4`) |
| J0 prerequisite | APPLIED (`conferenceId=9c69f86c69a037a8d531b1a9f19ff591`) |

---

## Class 2 — Runtime (PA-SR1…SR4)

| Step | Result | Evidence |
|------|--------|----------|
| PA-SR1 | **FAIL** | CREATION APPLIED present; `SHADOW hook=SESSION_STARTED outcome=DEFERRED_NO_FACT` (never APPLIED) |
| PA-SR2 | **FAIL** | no `PROFILE01_SHADOW_RUNTIME … WIRING/SHADOW_SESSION_STARTED` |
| PA-SR3 | **FAIL** | `MEMBER_MEDIA_READY outcome=DEFERRED_NO_BINDING` (M01↔M02); no catalog OBS |
| PA-SR4 | **FAIL** | no `INGRESS_ACTIVITY` / no `PLAYOUT_ACTIVITY` |

Driver fingerprints after 45s observation: `SESSION_STARTED=False MEMBER_READY=False INGRESS=False PLAYOUT=False`.

Authoritative M02 lines after CREATION APPLIED:

```text
WIRE_INGRESS … factType=1 outcome=APPLIED
SHADOW hook=SESSION_STARTED … outcome=DEFERRED_NO_FACT
SHADOW hook=MEMBER_MEDIA_READY module=M01 outcome=DEFERRED_NO_BINDING
```

---

## Class 3 — Teardown (PA-SR5…SR6)

| Step | Result | Evidence |
|------|--------|----------|
| PA-SR5 | **FAIL** | MEMBER_REMOVED not observed; rejoin not completed (wiring never live) |
| PA-SR6 | **FAIL** | SESSION_STOPPED / TEARDOWN_EMPTY absent |

Leave/rejoin/end driver phases ran but product shadow lifecycle never entered APPLIED wiring — teardown OBS correctly absent (not silence-as-PASS).

---

## AudioTrack fence

```text
AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE count=0
```

Fence semantics (pre-run corrected): PRODUCTION_ACTIVE = expected ADR-0056 owner — **not** FAIL.  
No shadow `audioTrackOwner=PRODUCTION*` claim observed. Fence not the fail cause.

---

## RCA scope (frozen)

```text
RCA_SHADOW_RUNTIME_ONLY
```

**RCA document (read-only):** `meeting-product-integration-phase-a-shadow-runtime-rca-handoff.md`

**Primary (RCA-A):** `registry.membershipVersion=0` (CREATION origin) vs `sessionIndex.rosterEpoch=1` (`INITIAL_ROSTER_EPOCH`) at synchronous post-apply replay → `factPort.sessionFact` null → `DEFERRED_NO_FACT`. Same store; not projection lag (+2ms field gap).

**Secondary (RCA-B):** `DEFERRED_NO_BINDING` cascade + wire replay gated on `mediaConnectedModules` / product hook ordering.

Do **not** reopen J0 / P1-C / P1-E / completion admission.

---

## Next

Narrow shadow handoff fix design (RCA-A replay epoch alignment) → re-run PA-SR only. No retry/sleep/generic fallback without ADR.
