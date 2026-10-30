# Phase A Shadow Runtime — Post PR-PA-SR-A1 Field Re-Run

**LogDir:** `talkback/logs/phase-a-shadow-runtime-20260909-111359/`  
**Stamp:** 2026-09-09-111359  
**Build:** `cac628d` + PR-PA-SR-A1 (local)  
**Devices:** M01 `HTUBB21B09220661` · M02 `DSJ-2407041`

---

## Verdict

```text
VERDICT=FAIL_SHADOW_RUNTIME
FIRST_RUN_EXIT=1
SHADOW_RUNTIME=TESTED (RCA-A partial fix · RCA-B open)
J0_status=UNCHANGED_CLOSED
Profile01_Meeting_chain=UNCHANGED_CLOSED
ADR-0056=KEEP
Meeting_media_cutover=NOT_AUTHORIZED
```

**Not** INVALID — PRE-G / PRE-I / PRE-J PASS · J0 prerequisite CREATION APPLIED observed.  
**Not** J0 regression.

**Adjudicator note:** Initial run reported PA-SR1 FAIL due to stale `SimpleMatch` pattern (missing `module=null` in log line). Pattern fixed in `phase-a-shadow-runtime-adjudicate.ps1`; re-adjudication below is authoritative.

---

## Class 1 — Prerequisites

| Gate | Result |
|------|--------|
| PRE-G | PASS |
| PRE-I | PASS |
| PRE-J | PASS (`hostSessionId=2a4fc2fd-e8dd-478d-9964-ecf28a15b263`) |
| J0 prerequisite | APPLIED (`conferenceId=9c8e12a2f5b87745fa3f687446e6d113`) |

---

## Class 2 — Runtime (PA-SR1…SR4)

| Step | Result | Evidence |
|------|--------|----------|
| PA-SR1 | **PASS** | M02: `WIRE_INGRESS factType=1 outcome=APPLIED` → `SHADOW hook=SESSION_STARTED … outcome=APPLIED` (+79ms) |
| PA-SR2 | **PASS** | `PROFILE01_SHADOW_RUNTIME phase=SHADOW_SESSION_STARTED transportScopeActive=true` (M02) |
| PA-SR3 | **FAIL** | `MEMBER_MEDIA_READY outcome=DEFERRED_NO_BINDING` (M01 module=M02, M02 module=M01) |
| PA-SR4 | **FAIL** | no `INGRESS_ACTIVITY` / no `PLAYOUT_ACTIVITY` |

Driver fingerprints after 45s observation: `SESSION_STARTED=False MEMBER_READY=False INGRESS=False PLAYOUT=False` (driver fingerprints still coarse; M02 log is authoritative for SR1/SR2).

### RCA-A field signal (peer wire path — M02)

```text
11:29:43.420  WIRE_INGRESS factType=1 outcome=APPLIED
11:29:43.498  PROFILE01_SHADOW_RUNTIME phase=WIRING_SESSION_STARTED transportScopeActive=true
11:29:43.499  SHADOW hook=SESSION_STARTED outcome=APPLIED
11:29:43.567  SHADOW hook=MEMBER_MEDIA_READY module=M01 outcome=DEFERRED_NO_BINDING
```

**RCA-A:** **FIELD-VERIFIED on peer (M02)** — CREATION replay uses published `membershipVersion`; `sessionFact` readable; `SESSION_STARTED` APPLIED.

### Host (M01) residual

```text
11:29:31.603  SHADOW hook=SESSION_STARTED outcome=DEFERRED_NO_FACT   (pre-CREATION emit; session bind)
```

M01 has **no** `WIRE_INGRESS factType=1` (host origin path). Host never receives a second post-CREATION `SESSION_STARTED` replay on this run. **Out of PR-PA-SR-A1 scope** (wire ingress only); peer evidence sufficient for PA-SR1 per adjudication criteria.

---

## Class 3 — Teardown (PA-SR5…SR6)

| Step | Result | Evidence |
|------|--------|----------|
| PA-SR5 | **FAIL** | leave/rejoin chain incomplete |
| PA-SR6 | **FAIL** | no `SESSION_STOPPED` / teardown empty |

---

## Status board update

```text
PR-PA-SR-A1             UNIT VERIFIED · FIELD VERIFIED (peer / PA-SR1+SR2)
RCA-A                   CLOSED (wire ingress CREATION replay)
RCA-B                   CLOSED (read-only) — TYPE A: binding not established (no SOURCE origin)
PR-PA-SR-B1             DESIGN FROZEN — see rca-b-fix-design.md
PA-SR3…SR6              NOT CLOSED
```

---

## Next (frozen routing)

1. **RCA-B read-only** — `MEMBER_MEDIA_READY → DEFERRED_NO_BINDING` after SESSION_STARTED APPLIED (binding / SOURCE / `mediaConnectedModules` gate).
2. **Do not bundle** host-origin CREATION replay unless RCA-B audit requires it.
3. **Do not** reopen J0 / P1-C / cutover / ADR-0056.
