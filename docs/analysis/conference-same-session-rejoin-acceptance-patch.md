# ConferenceSameSessionRejoinAcceptancePatch

**Status:** **UNIT VERIFIED** · **FIELD VERIFIED** (via RCA-002 unlock)

```text
ConferenceSameSessionRejoinAcceptancePatch

Status:
  UNIT VERIFIED
  FIELD VERIFIED

Evidence:
  logs/conf-same-session-rejoin-20260810-182832/
  Conference rejoin invite sent = 2
  Conference invite reconnect accepted = 2
  EDGE_RECOVERED = 4
  (unlocked by RCA-002 ELIGIBLE → second ARMED → OBTAINED)
```

Upstream:

```text
RRA-001~005                         CLOSED
Phase-2 Delivery                    VERIFIED
Media Action Ownership              VERIFIED
RCA-002 Opportunity Reacquisition   FIELD VERIFIED
Acceptance same-session rejoin      FIELD VERIFIED
```

Implementation scope (acceptance only):

```text
handleGroupInvite acceptance branch only
```

---

## Gap name (frozen)

```text
CONFERENCE_SAME_SESSION_REJOIN_ACCEPTANCE_MISSING
```

Alias: Conference reconnect invite misclassified as duplicate invite  
Not named: BUSY reject bug

---

## Field evidence (pre-RCA-002) — BLOCKED

| LogDir | OBTAINED | rejoin invite | note |
|--------|----------|---------------|------|
| conf-same-session-rejoin-20260810-180016 | 0 | 0 | EXPIRED |
| conf-same-session-rejoin-20260810-180916 | 0 | 0 | EXPIRED |
| conf-same-session-rejoin-20260810-181648 | 0 | 0 | INBOUND_RESUMED then EXPIRED, no reacquisition |

Root (delivery, not acceptance): oneshot `REATTACH_REQUESTED` / `transport_in_flight` after SENT.

---

## Patch review (design — still stands)

### 1. Predicate 严格性 ✅
CONFERENCE ∧ same sessionId ∧ rejoin=true ∧ SDP ∧ caller/initiator=host

### 2. 复用 reconnect path ✅
→ existing `acceptGroupInviteReconnect`

### 3. BUSY 保留 ✅
predicate false → existing BUSY

---

## Next field (after RCA-002 deploy)

Expect:

```text
SENT → ARMED → EXPIRED
  → REACQUISITION_ELIGIBLE
  → (gate ready) second SENT → ARMED → OBTAINED
  → Conference rejoin invite
  → reconnect accept
  → EDGE_RECOVERED
```
