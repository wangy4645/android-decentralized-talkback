# Meeting Profile01 — P1-C Field Join Precondition Gate (PRE-J1…J6)

**Parent:** P1-C field re-run / P1-C-R1  
**Status:** **CLOSED / FIELD PASS** (2026-09-09) — harness frozen · **does not modify package / R1 product code**

```text
P1-C-R1              CLOSED / FIELD-PROVEN
P1-C field           CLOSED / FIELD-PROVEN
Group precondition   CLOSED / FIELD PASS
Invite join precondition CLOSED / FIELD PASS
Authoritative PASS   talkback/logs/p1c-package-field-20260909-101752/
Meeting J0           AUTHORIZABLE / NEXT
```

## Hard machine-checkable gate

| ID | Check |
|----|--------|
| PRE-J1 | host `sessionId = H` (`SESSION_CREATED` HOST / `USER_CREATE`) |
| PRE-J2 | peer does **not** `USER_CREATE` a different conference |
| PRE-J3 | peer observes invite for `H` |
| PRE-J4 | peer binds to `H` (`INVITE_ACCEPT` / `SESSION_BIND_DRAIN`) |
| PRE-J5 | host and peer report same `sessionId=H` |
| PRE-J6 | only after PRE-J5 may package / R1 / C-F7 be adjudicated |

Immediate abort (no R1 wait, no C-F7):

```text
peer SESSION_CREATED sessionId != H (USER_CREATE)
peer channelOnlineArea / CREATE path
peer CALL_REJECT for H before bind
→ VERDICT=INVALID_JOIN_PRECONDITION
→ R1_FIELD=NOT_TESTED
```

## Field harness join path (frozen)

```text
Host: Meeting mode → btnPtt CREATE only
Peer: wait invite overlay → btnJoinMeeting (acceptIncomingMeeting)
Peer: NEVER channelOnlineArea / btnPtt CREATE
```

## Implementation

- `talkback/scripts/p1c-package-field-first-run.ps1` — PRE-J gate + early abort
- `talkback/scripts/p1c-package-field-adjudicate.ps1` — honors `JOIN_GATE.txt`
