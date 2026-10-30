# Meeting Profile01 — P1-C Field Group Precondition Gate (PRE-G1…G4)

**Parent:** P1-C field re-run / P1-C-R1  
**Status:** **CLOSED / FIELD PASS** (2026-09-09) — harness frozen · **does not modify package / R1 product code**

```text
P1-C-R1              CLOSED / FIELD-PROVEN
Group precondition   CLOSED / FIELD PASS
Invite join precondition CLOSED / FIELD PASS
Authoritative log    talkback/logs/p1c-package-field-20260909-101752/
P1-C package field   CLOSED / FIELD-PROVEN
Meeting J0           AUTHORIZABLE / NEXT
```

## Pollution chain (field-proven)

```text
residual GROUP (grp:CH-01)
→ peer accept / stay GROUP_PTT
→ Meeting invite arrives but JOIN not executed
→ CALL_REJECT before bind
→ INVALID_JOIN_PRECONDITION (R1 not tested)
```

## Hard machine-checkable gate (before Meeting CREATE)

| ID | Check |
|----|--------|
| PRE-G1 | M01 no active GROUP (`P1C_GROUP_PRECONDITION_LEAVE outcome=CLEAR`) |
| PRE-G2 | M02 no active GROUP (same) |
| PRE-G3 | no `grp:CH-01` reformation / pending invite after latest CLEAR (quiet window) |
| PRE-G4 | only after G1…G3 PASS may host CREATE Meeting |

Immediate abort (no CREATE, no R1 wait, no C-F7):

```text
GROUP teardown timeout / residual grp:CH-01
→ VERDICT=INVALID_GROUP_PRECONDITION
→ R1_FIELD=NOT_TESTED
```

## Harness leave path (frozen)

```text
force leave via product leaveChannelSession (USER_LEAVE / hangup)
  — debug broadcast: com.talkback.appprod.debug.P1C_FORCE_LEAVE_CHANNEL_SESSION
  — live foreground service runtime (NOT am instrument — that spawns empty process)
re-assert Meeting tab preference after leave
observe CLEAR + quiet window (no blind long sleep)
bounded re-leave rounds on residual
NEVER clear-app-data / reinstall / wipe trust or session store
```

## Implementation

- `talkback/talkback-app/src/main/java/.../debug/P1cGroupPreconditionLeaveDebug.kt`
- `TalkbackForegroundService` DEBUG action `P1C_FORCE_LEAVE_CHANNEL_SESSION`
- `talkback/scripts/p1c-package-field-first-run.ps1` — PRE-G gate + early abort
- `talkback/scripts/p1c-package-field-adjudicate.ps1` — honors `GROUP_GATE.txt`
