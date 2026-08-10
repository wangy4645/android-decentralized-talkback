# Post-handoff invite BUSY — small audit 001

**Status:** ATTRIBUTION ONLY · no patch · no RCA-002  

```text
RRA / Phase-2          CLOSED
Media action owner     CLOSED
Current failure        POST-HANDOFF INVITE ACCEPTANCE
```

Corpus: `logs/rca001-ownership-handoff-20260810-173234/`

---

## Question A — BUSY predicate

### Unique conference invite → BUSY path (this failure)

```text
handleGroupInvite
  membershipSnapshot? → apply / return          // 741-byte path
  existing GROUP+GROUP? → acceptGroupInviteReconnect  // CONFERENCE never enters
  prepareForGroupInvite == false
    → sendGroupBusyReject  (payload "BUSY" or holding-session encode)
```

Triggering predicate for same-conference recovery invite:

```text
prepareForGroupInvite:
  onChannel.type == incomingType
  && onChannel.id == signal.sessionId
  → return false     // no SDP apply, no hangup, no reconnect
```

Observed field attributes (fail 17:34:06):

| Field | Value |
|-------|--------|
| existing session type | CONFERENCE (held) |
| incoming invite type | CONFERENCE (`Conference rejoin invite sent`) |
| same sessionId | yes |
| same conferenceId | yes |
| media owner after handoff | HOST_RESTART (supersede already done) |
| reconnect branch taken | **never** (`Group invite reconnect` count = **0** whole run) |

Other BUSY send sites exist (unicast / join / acceptGroupInvite false) — **not** the post-handoff Host→M02 loop here.

---

## Question B — Why 17:39 “bypassed” BUSY

Host intent is explicit:

```text
ICE_RESTART_REQUESTED (M02)
  → GROUP_INVITE ~2055
  → "Conference rejoin invite sent -> M02"
  → HAVE_LOCAL_OFFER / OFFER_AWAITING_ANSWER
  → CALL_REJECT BUSY from M02
  → Ignoring BUSY (INV-MEM-001 / duplicate invite)
```

So **2055 is the Host primary media rejoin vehicle**, not a stale leftover control datagram.

Success (17:39) did **not** go:

```text
GROUP_INVITE → GROUP_ACCEPT → ANSWER → ICE
```

It went:

```text
existing conference PC
  → ICE DISCONNECTED → ICE CONNECTED   (self-heal)
  → EDGE_RECOVERED
  → later 2055 still BUSY (post-recover; Host ignores as duplicate)
```

M02: `GROUP_ACCEPT_HANDOFF` for that recover edge: absent.  
Whole run: `Group invite reconnect` = 0.

**Implication:** success proves ICE can sometimes converge without invite acceptance; it does **not** prove BUSY is harmless. Fail loop = rejoin invite rejected while ICE stays FAILED → `OFFER_AWAITING_ANSWER` stuck → timeout.

---

## Question C — Fix boundary

```text
Ownership handoff (assignMediaActionOwner)     CLOSED — do not touch
Phase-2 / INV-T3 / retry / transport           CLOSED — do not touch
ICE-first redesign                             WAIT
Blind BUSY → ACCEPT (new session)              FORBIDDEN (dual-session risk)

Investigate / future patch scope (if authorized):
  conference same-session rejoin invite
  → reconnect SDP path
  (mirror GROUP acceptGroupInviteReconnect; keep dual-session guard)
```

### Verdict (BUSY: wrong gate vs duplicate)

| Situation | Classification |
|-----------|----------------|
| Same sessionId CONFERENCE invite, ICE already CONNECTED | **Correct duplicate protection** |
| Same sessionId CONFERENCE **rejoin** invite, Host in `OFFER_AWAITING_ANSWER`, ICE not connected | **Wrong / missing reconnect gate** — BUSY blocks Host’s intended answer path |

```text
High confidence:
  PARTICIPANT_REATTACH veto          CLOSED
  2055 = Host conference rejoin intent (primary), not noise

Medium confidence:
  CONFERENCE lacks same-session reconnect predicate
  (GROUP has it; CONFERENCE falls through to BUSY)

Open until patch:
  exact reconnect admission (rejoin flag / ice-down / owner) — design only when authorized
```

---

## One-line answer

> BUSY here is **not** ownership; it is **same-session conference reject without reconnect**. When ICE self-heals, BUSY looks like duplicate protection; when ICE stays down, the same predicate is an **erroneous recovery gate**.
