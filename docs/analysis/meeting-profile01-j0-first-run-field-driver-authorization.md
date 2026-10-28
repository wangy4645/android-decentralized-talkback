# Meeting Profile01 — J0 First-Run Field Driver Authorization

**Parent run card:** `meeting-profile01-j0-meeting-operational-run-card.md`  
**Harness driver:** `talkback/scripts/j0-meeting-operational-first-run.ps1`  
**Status:** **CLOSED / FIELD-PROVEN** (2026-09-09) — first run PASS `talkback/logs/j0-meeting-operational-20260909-103707/`

```text
J0 run card/adjudicator   CLOSED / DRY-RUN PASS
J0 field driver           CLOSED / READY
J0 first run              CLOSED / FIELD-PROVEN
Meeting J0                CLOSED / FIELD-PROVEN
Phase A                   ELIGIBLE FOR NEXT GATE
Meeting media cutover     NOT AUTHORIZED
ADR-0056                  KEEP
```

---

## What the driver does

Thin entry over frozen PRE-G / PRE-I / PRE-J orchestration (`p1c-package-field-first-run.ps1 -DriverMode J0`):

```text
1.  Pre-run forbidden checks (C-F7 / harness republish / manual drain = OFF)
2.  Dual-end GROUP cleanup
3.  PRE-G1…G4
4.  Host CREATE Meeting
5.  Peer invite overlay → JOIN
6.  PRE-I1…I6
7.  PRE-J1…J6 same-session bind
8.  Wait product continuation chain (no debug triggers)
9.  Stop capture
10. j0-meeting-operational-adjudicate.ps1
```

**Does NOT:** copy P1-C adjudication · inject C-F7 · call `P1C_REPUBLISH_AFTER_BIND` · manual P1-E drain.

---

## Pre-run hard OFF

| Item | J0 |
|------|-----|
| C-F7 injection | OFF |
| `P1C_REPUBLISH_AFTER_BIND` | OFF |
| harness-only post-bind | OFF |
| manual continuation drain | OFF |

PRE-G/I/J failure → immediate abort → `VERDICT=INVALID_<PRECONDITION>` · `J0_FIELD=NOT_TESTED` (no wait for package/continuation).

---

## First-run manifest (`J0_FIRST_RUN_MANIFEST.txt`)

```text
driverMode=J0_NO_NEGATIVE_FIXTURE
hostSessionId
conferenceId
creationFactDigest
mediaKeyEpoch
peerModuleId
cf7Injection=OFF
```

---

## Verdict taxonomy (J0-only)

| Outcome | Meaning |
|---------|---------|
| `PASS` | J0-1…J0-7 + identity fence |
| `INVALID_*` | PRE / capture / forbidden fixture |
| `FAIL_J0_CHAIN` | PRE green · product chain violation |

---

## Run

```powershell
.\talkback\scripts\j0-meeting-operational-first-run.ps1
# or reuse installed APK:
.\talkback\scripts\j0-meeting-operational-first-run.ps1 -SkipBuildInstall
```

Log dir: `talkback/logs/j0-meeting-operational-<stamp>/`
