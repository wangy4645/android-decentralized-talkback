# CONFERENCE same-session rejoin acceptance — asymmetry audit 001

**Status:** ATTRIBUTION ONLY · no patch · not a “BUSY bug”

```text
Phase-1 Progress                 CLOSED
Phase-2 Delivery                 CLOSED
Media Action Ownership           CLOSED
Post-handoff Invite Acceptance   ← current break
  root naming:
    CONFERENCE same-session rejoin
    missing acceptance transition
```

Corpus: `logs/rca001-ownership-handoff-20260810-173234/`  
Prior: `docs/analysis/post-handoff-invite-busy-audit-001.md`

---

## Naming

BUSY is the **symptom** of falling through the duplicate-session gate.

Real gap:

```text
CONFERENCE same-session rejoin acceptance path
```

Forbidden framing: “BUSY bug” · `BUSY → ACCEPT`.

---

## Q1 — `prepareForGroupInvite()` design assumption

It is an **admission gate for creating/accepting a new invite**, not a reconnect handler.

```text
purpose:
  clear conflicts / admit fresh GROUP_INVITE into acceptGroupInvite

same sessionId on channel:
  return false   // intentional duplicate / dual-session guard

then:
  handleGroupInvite → sendGroupBusyReject
```

Assumptions baked in:

| Assumption | Implication |
|------------|-------------|
| Caller wants a **new** accept path (`acceptGroupInvite`) | Existing same id must not pass |
| Dual-session / split mesh is worse than reject | Same id → false is correct for “new call” |
| Reconnect is **someone else’s job** | Must run **before** this gate |

GROUP already follows that structure:

```text
same session → acceptGroupInviteReconnect  // before prepare
else → prepareForGroupInvite → acceptGroupInvite
```

CONFERENCE never inserts a before-prepare reconnect, so recovery invites hit the new-session gate and look like duplicates.

**Answer:** yes — `prepareForGroupInvite` is for **new** mesh invite admission. Using it as the recovery decision is a category error.

---

## Q2 — Is `sessionId + recovery lineage` enough?

| Signal | Distinguishes duplicate vs recovery? |
|--------|--------------------------------------|
| `sessionId` alone | **No** — both cases share id |
| `payload.rejoin` | **Partial** — Host sets `rejoin=true` on conference rejoin invite; also used for soft-leave re-enter |
| ICE down / not CONNECTED | Strong for “need SDP reconnect” (GROUP reconnect already throttles on ice) |
| Media owner / HOST_RESTART | **Not read** in `handleGroupInvite` today |
| Recovery attempt / obligation gen | **Not read** at invite acceptance |

Today Host already emits enough **invite-local** intent:

```text
"Conference rejoin invite sent"
payload.rejoin = true
SDP present (iceRestart offer)
same sessionId
```

Participant never branches on that while the session is still held.

Sufficient discriminator for a future narrow path (design only):

```text
existing CONFERENCE
  && same sessionId
  && payload.sdp non-blank
  && (payload.rejoin || ice not CONNECTED)
→ reconnect on existing session
else same session → BUSY   // keep duplicate guard
```

Full recovery-lineage (owner / attempt id) is **not required** to prove the gap; it may refine admission later. Do not wait on lineage plumbing to name the missing transition.

**Answer:** `sessionId` alone is not enough; `sessionId + rejoin/SDP + media-down` is already available on the wire and unused for still-held conference.

---

## Q3 — Does conference already have similar capability?

**Yes on Host send / soft-leave re-enter. No on still-held inbound reconnect.**

### Exists (asymmetric)

| Capability | Where | Scenario |
|------------|-------|----------|
| Host rejoin invite + iceRestart offer | `trySendSingleConferenceInvite(rejoin=true)` | Outbound recovery |
| Soft-leave → remember host → `isIncomingConferenceRejoinInvite` | after leave, local session gone | **New** `acceptGroupInvite` |
| `payload.rejoin` → mesh peers | inside `acceptGroupInvite` after new accept | Post soft-leave mesh refill |
| `acquireMeshEngine(forReconnect=…)` | Host/media acquire | Not used by inbound conference accept (`forReconnect=false`) |

### Missing (the gap)

| Capability | GROUP | CONFERENCE still held |
|------------|-------|------------------------|
| Same-session inbound SDP reconnect | `acceptGroupInviteReconnect` | **absent** |
| Entry before `prepareForGroupInvite` | yes (`GROUP && GROUP`) | type guard excludes CONFERENCE |

Soft-leave path never runs in WiFi flap recovery: session stays in `sessions[sessionId]`, so `isIncomingConferenceRejoinInvite` is unreachable (blocked earlier by prepare).

```text
Host:     can SEND conference rejoin (full)
Participant soft-leave: can ACCEPT as new session
Participant still-in-call: cannot ACCEPT as reconnect  ← gap
```

---

## Why GROUP has it and CONFERENCE does not (historical shape)

Not a transport/ownership omission — an **incomplete type specialization**:

```text
handleGroupInvite
  sessions[id]?.let {
    if (GROUP && GROUP) reconnect   // mesh duplicate-as-ICE-restart
    // CONFERENCE: no branch
  }
  prepareForGroupInvite             // new-session gate
  isIncomingConferenceRejoinInvite  // only if prepare passed (session absent)
```

Conference “rejoin” was implemented for **leave → re-enter**, not for **stay → Host SDP rejoin while session alive**.

---

## Root candidate (refined)

```text
CLOSED:
  Phase-2 delivery
  Media action owner conflict

CURRENT:
  CONFERENCE same-session rejoin
  missing acceptance transition

SYMPTOM:
  valid Host rejoin GROUP_INVITE(SDP)
    → existing session gate
    → treated as duplicate
    → BUSY
```

---

## Fix boundary (unchanged)

Do not touch: Phase-2 · INV-T3 · DeliveryProgressFacade · Ownership supersede · Retry · ICE core · blind BUSY→ACCEPT.

Future patch surface (when authorized): only the **before-prepare** conference branch in `handleGroupInvite`, mirroring GROUP reconnect semantics on the existing session.
