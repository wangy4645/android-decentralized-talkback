# Meeting Profile01 — P1-C Field Invite Join Precondition Gate (PRE-I1…I6)

**Parent:** P1-C field re-run / P1-C-R1  
**Status:** **CLOSED / FIELD PASS** (2026-09-09) — harness frozen · **does not modify package / R1 product code**

```text
Group precondition      CLOSED / FIELD PASS
Invite join precondition CLOSED / FIELD PASS
  log: talkback/logs/p1c-package-field-20260909-101752/
P1-C-R1                 CLOSED / FIELD-PROVEN
R1 product hook         FIELD-PROVEN (product auto POST_BIND_EMITTED)
P1-C package field      CLOSED / FIELD-PROVEN
Meeting J0              AUTHORIZABLE / NEXT
```

## Pollution chain (resolved)

```text
residual GROUP → peer stuck in GROUP_PTT → PRE-G CLOSED (20260909-100420)
```

## Current blocker (invite join harness)

```text
invite arrived (PRE-I1)
→ overlay / btnJoinMeeting not clicked in expiry window
→ CALL_REJECT / invite expired
→ INVALID_INVITE_JOIN_PRECONDITION
→ R1_FIELD=NOT_TESTED
```

## Hard machine-checkable gate (peer only; after PRE-G + host CREATE)

| ID | Check |
|----|--------|
| PRE-I1 | peer observed Meeting invite for host session `H` |
| PRE-I2 | invite overlay visible / `btnJoinMeeting` clickable |
| PRE-I3 | `btnJoinMeeting` tap dispatched (`MEETING_INVITE_UI state=JOIN_CLICKED`) |
| PRE-I4 | `acceptIncomingMeeting(H)` dispatched (`MEETING_INVITE_UI state=ACCEPT_DISPATCHED`) |
| PRE-I5 | no `CALL_REJECT` / invite expiry before bind |
| PRE-I6 | peer `SESSION` bind = `H` |

Immediate abort (no R1 wait, no C-F7):

```text
invite for H not observed
overlay / btnJoinMeeting not visible in window
JOIN_CLICKED / ACCEPT_DISPATCHED OBS missing after tap
CALL_REJECT or invite expired before bind
peer did not bind H
→ VERDICT=INVALID_INVITE_JOIN_PRECONDITION
→ R1_FIELD=NOT_TESTED
```

## Field harness invite path (frozen)

```text
PRE-G PASS → peer foreground + Meeting tab (no CREATE)
Host CREATE → H
Poll 500ms (not blind sleep):
  wait PRE-I1 invite OBS
  wait PRE-I2 overlay / btnJoinMeeting
  tap btnJoinMeeting immediately when clickable
  verify PRE-I3 JOIN_CLICKED + PRE-I4 ACCEPT_DISPATCHED OBS
  wait PRE-I6 bind to H
Peer: NEVER channelOnlineArea / btnPtt CREATE
```

## Behavior-neutral OBS

```text
MEETING_INVITE_UI sessionId=<H> state=OVERLAY_VISIBLE|JOIN_CLICKED|ACCEPT_DISPATCHED
```

## Implementation

- `talkback/talkback-app/.../MeetingInviteUiObservability.kt`
- `MainActivity` / `MeetingInviteFragment` / `TalkViewModel.acceptIncomingMeeting`
- `talkback/scripts/p1c-package-field-first-run.ps1` — PRE-I gate + `INVITE_JOIN_GATE.txt`
- `talkback/scripts/p1c-package-field-adjudicate.ps1` — honors invite gate before package/R1

Only after **PRE-I1…I6 PASS** may Branch B (R1 product hook) adjudication run.
