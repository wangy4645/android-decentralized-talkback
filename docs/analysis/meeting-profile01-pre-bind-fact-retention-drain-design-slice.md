# Meeting Profile01 Pre-Bind Fact Retention & Drain — Implementation Design Slice

**Parent RCA:** `meeting-profile01-deferred-session-bridge-rca.md`  
**Status:** DESIGN **SIGNED OFF** — **v1 IMPLEMENTED** — **field retain+drain PROVEN** — **CREATION APPLIED NOT YET**

```text
Session-bridge RCA             COMPLETE
Design slice                   SIGNED OFF
Pre-bind retention v1          IMPLEMENTED (unit T1–T5 PASS)
Field retain+drain             PROVEN (…-132834)
Field CREATION APPLIED         NOT PASS — VERIFY_APPLY_GAP (REJECTED_INCOMPLETE)

ADR-0061                       FREEZE
CREATION origin/replay         FREEZE
MEMBERSHIP                     HOLD
Phase A                        BLOCKED
```

**Authoritative field run:** `talkback/logs/meeting-product-j0-creation-e2e-20260908-132834/`

```text
pre-Accept:  DEFERRED_NO_SESSION_RETAINED factType=1
Accept:      SESSION_BIND_DRAIN count=1 (same session)
post-drain:  REJECTED_PUBLISH detail=REJECTED_INCOMPLETE  (not APPLIED)
```

Retain/drain seam closed the silent-drop gap. Remaining blocker is **apply completeness** — see `meeting-profile01-creation-apply-completeness-rca.md` (primary: **C REQUIRED_PREDECESSOR_MISSING**).

### v1 fact-type boundary (frozen)

```text
routingIdentity.factType == CREATION + no session binding
    → retain bounded raw SignedFact
    → DEFERRED_NO_SESSION_RETAINED

other Profile01 factType + no session binding
    → NOT retained
    → PRE_BIND_NOT_RETAINED factType=<n> reason=UNAUTHORIZED_FACT_TYPE
```

MEMBERSHIP / MEDIA_KEY_PACKAGE / SOURCE_DECLARATION pre-bind retention requires explicit future authorization after MEMBERSHIP HOLD review.

**ADR escalation:** Not required for v1. Upgrade to full ADR only if design review finds retention lifecycle must span multiple session/generation with cross-session authority — not indicated today.

---

## 1. Problem & target flow

### 1.1 Today (field-proven `…-113711`)

```text
CONFERENCE_SIGNED_FACT arrives (peer, pre-Accept)
→ sessionIndex.bindingForSession(signal.sessionId) == null
→ observability DEFERRED_NO_SESSION
→ return (fact discarded)

INVITE_ACCEPT → registerMeetingProfile01Session
→ binding exists
→ no re-drive
→ CREATION never APPLIED
```

### 1.2 v1 target flow (frozen)

```text
CONFERENCE_SIGNED_FACT arrives
→ no session binding
→ minimal routing decode → conferenceId128
→ retain raw signed fact (+ envelope)
→ WIRE_INGRESS outcome=DEFERRED_NO_SESSION_RETAINED

INVITE_ACCEPT
→ registerMeetingProfile01Session
→ bind established (sessionId ↔ conferenceId128)
→ SESSION_BIND_DRAIN
→ detach matching retained batch (consume retention)
→ re-enter existing MeetingProfile01FactWireIngress apply path per fact
→ decode → verify → validate → registry → replayAfterPublish
→ WIRE_INGRESS … APPLIED
```

**Ownership:** Meeting Profile01 **ingress / session-bridge** layer. Not media engine (ADR-0061). Not host origin replay (`MeetingProfile01CreationOriginBridge` — **FREEZE**).

---

## 2. Non-scope (frozen)

```text
ADR-0061 code / media continuation          FREEZE
MeetingProfile01CreationOriginBridge        FREEZE (no host resend workaround)
ConferenceSessionMediaControlFactRegistry   no pre-bind raw writes
Profile01 validator / dedupe contract       no semantic changes
MEMBERSHIP origin / Phase A membership      HOLD
Coordinator timer / retry state machine     forbidden
Second apply path (drain → direct registry) forbidden
```

---

## 3. Component shape

### 3.1 New types (integration package)

```kotlin
/** Pre-bind retention mailbox — NOT authoritative registry. */
class MeetingProfile01PreBindFactRetention(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val observability: MeetingProfile01PreBindRetentionObservability,
) {
    fun retain(
        conferenceIdHex: String,
        factDigestHex: String,
        signal: SignalEnvelope,
    ): RetainOutcome

    /** Detach (consume) all eligible entries for conferenceId; does not apply. */
    fun detachForDrain(conferenceIdHex: String, nowMs: Long = clock()): List<DetachedRetainedFact>

    fun discardForSession(
        sessionId: String,
        conferenceIdHex: String?,
        reason: DiscardReason,
    )

    fun purgeExpired(nowMs: Long = clock())
}

data class DetachedRetainedFact(
    val conferenceIdHex: String,
    val factDigestHex: String,
    val signal: SignalEnvelope,
)

enum class RetainOutcome {
    RETAINED,
    DEDUPED,
    REJECTED_BOUNDS,
    REJECTED_NO_ROUTING_ID,
}

enum class DiscardReason {
    SESSION_UNREGISTERED,
    WRONG_CONFERENCE,
    WRONG_SESSION,
    RETENTION_EXPIRED,
    SUPERSEDED_BIND,
}
```

### 3.2 Wire ingress extension

`MeetingProfile01FactWireIngress` gains optional `preBindRetention` dependency.

```kotlin
fun onConferenceSignedFact(signal: SignalEnvelope): WireIngressOutcome
fun drainRetainedAfterSessionBind(sessionId: String): DrainOutcome
```

`WireIngressOutcome` adds:

```text
DEFERRED_NO_SESSION_RETAINED   — retained for bind drain
```

Does **not** remove `DEFERRED_NO_SESSION` enum value; v1 replaces the silent-drop path when retention succeeds. Decode/routing failures remain `REJECTED_*` without retention.

### 3.3 Session index hook

`MeetingProfile01ConferenceSessionIndex.registerSession` stays a pure insert. Drain is invoked by coordinator **immediately after** `registerMeetingProfile01Session` (same transaction as bind), not inside the index class — keeps index free of ingress dependency.

```kotlin
// TalkbackCoordinator.registerMeetingProfile01Session (extended)
private fun registerMeetingProfile01Session(session: TalkbackSession) {
    meetingProfile01SessionIndex?.registerSession(...)
    meetingProfile01FactWireIngress?.drainRetainedAfterSessionBind(session.id)
}
```

Host create path (`meshCallInternal`) also calls `registerMeetingProfile01Session` — drain is a no-op when no retained facts (T2).

### 3.4 Factory wiring

`TalkbackRuntimeFactory`: construct `MeetingProfile01PreBindFactRetention`, inject into `MeetingProfile01FactWireIngress`. Single instance shared; no coordinator-owned mailbox.

---

## 4. Retention ownership

| Store | Role | Pre-bind raw bytes? |
|-------|------|---------------------|
| `MeetingProfile01PreBindFactRetention` | **v1 owner** — bounded mailbox | Yes |
| `MeetingProfile01ConferenceSessionIndex` | sessionId ↔ channelId / conferenceId binding | No |
| `ConferenceSessionMediaControlFactRegistry` | authoritative post-verify facts | **Never** pre-bind |

**Rationale:** Registry contract is “verified, session-mapped, publish-ready fact.” Pre-bind payload has not crossed trust/apply boundary.

---

## 5. Routing identity — key by `conferenceId128`, not `sessionId`

### 5.1 Why not `pendingFacts[sessionId]`

At ingress, `sessionIndex` has no binding — using `sessionId` as retention key would conflate “Meeting session object not yet created” with “routing identity for Profile01 facts.” Drain at bind is defined as:

```text
register(sessionId)
→ expectedConferenceId = ensureConferenceIdHex(sessionId)
→ detach retained[expectedConferenceId]
```

Host CREATION facts embed `conferenceId128 = deriveId128Hex(sessionId)` (`MeetingProfile01CreationOriginBridge` + `Profile01ConferenceIdAuthority`). Peer bind uses the same derivation at drain fence time.

### 5.2 Minimal routing decode (allowed pre-trust)

Today `onConferenceSignedFact` returns before `decodeSignedFactPayload`. v1 **must** decode base64 + structural CBOR **only** for routing:

Add to `Profile01WireCborDecoder` (wire layer — no validator, no registry):

```kotlin
data class RoutingIdentity(
    val conferenceIdHex: String,  // 32-char lower hex
    val factType: Int,
    val factDigestHex: String,      // for dedupe; structural hash of full fact
)

/** Structural only — no signature verify, no lifecycle policy. */
fun readRoutingIdentity(signedFactBytes: ByteArray): RoutingIdentity?
```

Implementation reuses existing `parseEnvelope` + `decodeFullFact` + `authority.requireId128(0)` — same structural path as `readFactType`, plus conferenceId + `Profile01FactDigest.computeFactDigest(fullCanonicalBytes)`.

**Trust boundary unchanged:** verify / validate / publish happen only inside existing ingress after bind.

### 5.3 Retain path pseudocode

```text
onConferenceSignedFact(signal):
  if shadow disabled → SHADOW_DISABLED
  if binding exists → existing path (unchanged)

  signedBytes = decodeSignedFactPayload(signal.payload)
  if signedBytes == null → REJECTED_DECODE

  routing = readRoutingIdentity(signedBytes)
  if routing == null → REJECTED_DECODE (no retain)

  outcome = retention.retain(routing.conferenceIdHex, routing.factDigestHex, signal)
  if outcome == RETAINED → DEFERRED_NO_SESSION_RETAINED
  if outcome == DEDUPED → DEFERRED_NO_SESSION_RETAINED (idempotent observability)
  if outcome == REJECTED_BOUNDS → REJECTED_PUBLISH or new RETENTION_REJECTED_BOUNDS (observable)
```

Store full `SignalEnvelope` so re-ingress preserves `sessionId`, `from`, timestamps for existing apply + audit.

### 5.4 Drain fence (bridge responsibility)

Before re-ingress, for each detached entry:

```text
MUST PASS:
  detached.signal.sessionId == bindSessionId
  detached.conferenceIdHex == sessionIndex.ensureConferenceIdHex(bindSessionId)

ON FAIL:
  discard with observable reason (WRONG_SESSION / WRONG_CONFERENCE)
  do NOT call ingress apply
```

Stale / generation / signature failures: **existing ingress validator** → `REJECTED_*` terminal outcomes.

---

## 6. Bounded retention

| Limit | v1 constant | Behavior |
|-------|-------------|----------|
| Per `conferenceId128` count | `MAX_RETAINED_PER_CONFERENCE = 8` | reject new retain → `REJECTED_BOUNDS` + log |
| Lifetime | `MAX_RETENTION_MS = 120_000` | evict on `purgeExpired` / at detach |
| Dedupe identity | `(conferenceIdHex, factDigestHex)` | duplicate retain → `DEDUPED`, no stack growth |
| Global cap (optional) | `MAX_RETAINED_GLOBAL = 32` | reject → `REJECTED_BOUNDS` |

**Eviction observability (required):**

```text
RETENTION_EXPIRED_DISCARD     conferenceId=… digest=… ageMs=…
RETENTION_BOUNDS_REJECT       conferenceId=… reason=PER_CONFERENCE_CAP|GLOBAL_CAP
RETENTION_DEDUPED             conferenceId=… digest=…
RETENTION_SESSION_DISCARD     sessionId=… reason=SESSION_UNREGISTERED|…
```

`purgeExpired` called from: `detachForDrain`, `retain`, `unregisterMeetingProfile01Session`.

Repeated host `PROFILE01_ORIGIN_REPLAY` must not grow an unbounded mailbox — dedupe by `factDigestHex` collapses replays.

---

## 7. Drain — no second apply path

### 7.1 Frozen drain sequence

```text
drainRetainedAfterSessionBind(sessionId):
  conferenceIdHex = sessionIndex.ensureConferenceIdHex(sessionId)
  batch = retention.detachForDrain(conferenceIdHex)   // consume first
  log SESSION_BIND_DRAIN session=… conferenceId=… count=N
  for fact in batch:
    fence sessionId + conferenceIdHex (§5.4)
    onConferenceSignedFact(fact.signal)               // existing path
  return DrainOutcome(applied, rejected, fenced)
```

**Critical:** `detach` before `onConferenceSignedFact` — retention consumption is exactly-once at bridge layer. Re-entrancy into drain from within apply is impossible (batch already empty).

### 7.2 Exactly-once semantics (scoped)

| Layer | Guarantee |
|-------|-----------|
| **Retention bridge** | Each retained entry consumed **at most once** on successful detach |
| **Profile01 apply** | Unchanged — validator may `IDEMPOTENT` / `REJECTED_STALE` on duplicate wire facts |

Do **not** invent global wire exactly-once. Duplicate wire delivery after APPLIED is still Profile01 contract.

### 7.3 Ordering

Drain feeds retained facts in **FIFO per conferenceId** (insertion order). v1 does not sort by factType; CREATION-before-MEMBERSHIP ordering for peer E2E matches host send order in field runs. If a future run shows ordering sensitivity, narrow follow-up — not v1 scope.

---

## 8. Teardown fences

| Scenario | Action |
|----------|--------|
| `unregisterMeetingProfile01Session` | `discardForSession` + `purgeExpired`; log `RETENTION_SESSION_DISCARD` |
| Session terminated before bind | same — unregister clears mailbox for derived `conferenceIdHex` |
| Wrong `conferenceId` at drain | fence discard — no ingress |
| Wrong `signal.sessionId` at drain | fence discard — no ingress |
| Retention expiry | drop before drain; `RETENTION_EXPIRED_DISCARD` |
| Superseded bind | v1: unregister of old session clears retention; no cross-session drain |

Generation / stale fact at apply: ingress `REJECTED_STALE` / `REJECTED_VERIFY` — bridge does not special-case.

---

## 9. Observability (field adjudication)

Tag: `MEETING_MEDIA_SHADOW` (wire ingress) + retention-specific lines same tag.

| Event | When |
|-------|------|
| `WIRE_INGRESS … outcome=DEFERRED_NO_SESSION_RETAINED factType=…` | pre-bind retain success |
| `SESSION_BIND_DRAIN session=… conferenceId=… count=N` | post-register drain start |
| `WIRE_INGRESS … outcome=APPLIED factType=1` | post-drain CREATION apply |
| `RETENTION_*` | bounds / expiry / fence discard |

### Field exit fingerprint (re-play `…-113711`)

```text
pre-Accept (M02):
  WIRE_INGRESS outcome=DEFERRED_NO_SESSION_RETAINED factType=1

Accept:
  SESSION_CREATED
  SESSION_BIND_DRAIN session=0897503f… count≥1

post-Accept (M02):
  WIRE_INGRESS factType=1 outcome=APPLIED
```

Only after this fingerprint may status advance:

```text
CREATION peer E2E     PASS
J0 end-to-end         PASS
→ then MEMBERSHIP HOLD review
```

---

## 10. Exit tests (unit — authorize with implementation)

| Test | Name | Assertion |
|------|------|-----------|
| **T1** | `factBeforeSession_retainBindDrainApplied` | No binding → retain → `registerSession` + drain → `APPLIED` + registry session |
| **T2** | `sessionBeforeFact_directPathUnchanged` | `registerSession` first → wire fact → immediate `APPLIED`; drain no-op |
| **T3** | `wrongConference_bindDoesNotDrainOther` | Retain for conference B; bind session A (derive A) → B not applied |
| **T4** | `duplicateBind_retentionConsumedOnce` | Drain twice / re-register → no duplicate apply from same retained entry |
| **T5** | `expiredOrTerminated_noAppliedObservableDiscard` | Expire or unregister before bind → `RETENTION_EXPIRED_DISCARD` / `RETENTION_SESSION_DISCARD`; no `APPLIED` |

Extend `MeetingProfile01FactWireIngressTest`; add `MeetingProfile01PreBindFactRetentionTest` for mailbox bounds/dedupe in isolation.

Existing tests asserting `DEFERRED_NO_SESSION` on unknown session → update to `DEFERRED_NO_SESSION_RETAINED` when routing decode succeeds.

---

## 11. Files touched (implementation checklist — not authorized yet)

| File | Change |
|------|--------|
| `Profile01WireCborDecoder.kt` | `readRoutingIdentity` |
| `MeetingProfile01PreBindFactRetention.kt` | **new** |
| `MeetingProfile01PreBindRetentionObservability.kt` | **new** (or nested) |
| `MeetingProfile01FactWireIngress.kt` | retain path + `drainRetainedAfterSessionBind` |
| `WireIngressOutcome.kt` / observability | new outcomes + logs |
| `TalkbackCoordinator.kt` | drain after `registerMeetingProfile01Session` |
| `TalkbackRuntimeFactory.kt` | wire retention instance |
| `MeetingProfile01FactWireIngressTest.kt` | T1–T5 |
| `meeting-product-integration-phase-a-shadow-run-card.md` | retain/drain semantics |

---

## 12. Invariants

**B1 — No registry pollution:** pre-bind bytes never written to `ConferenceSessionMediaControlFactRegistry` except via existing ingress apply.

**B2 — Single apply path:** drain → `onConferenceSignedFact` only.

**B3 — Bounded mailbox:** per-conference cap + TTL + dedupe; observable rejects.

**B4 — Retention consumption exactly-once:** detach before apply; no duplicate drain of same entry.

**B5 — Fence before apply:** `sessionId` + `conferenceId128` match bind context.

**B6 — No coordinator retry loop:** one live drain source at `registerMeetingProfile01Session`.

**B7 — FREEZE respect:** no changes to ADR-0061, `CreationOriginBridge`, or MEMBERSHIP paths.

---

## 13. ADR escalation criteria (deferred)

Open full ADR if implementation review finds:

```text
retention must survive sessionId reassignment across generations
cross-session fan-in of retained facts with competing authority
retention lifetime tied to membership epoch (not bind/unbind)
global mailbox shared across unrelated conferences without conferenceId keying
```

None of these are required for v1 peer INVITE_ACCEPT path.

---

## 14. Status board (post slice authorization)

```text
ADR-0061                    FIELD PROVEN / CLOSED FOR THIS GAP
Meeting release/quiescence  CLOSED
Session-bridge RCA          COMPLETE
Pre-bind retention slice    AUTHORIZED (design only)
Implementation code         NOT AUTHORIZED

CREATION peer E2E           BLOCKED BY SESSION-BRIDGE GAP
J0 operational chain        PASS
J0 end-to-end               NOT PASS
ADR-0061 code               FREEZE
CREATION origin/replay      FREEZE
MEMBERSHIP                  HOLD
Phase A                     BLOCKED
```

**Next authorized step:** implementation PR scoped to this slice + unit T1–T5, then field re-run `…-113711` fingerprint before any MEMBERSHIP / Phase A unhold.
