# Meeting Profile01 — Peer Existing-Session Endpoint Authority Fix Contract Amendment

**Parent ADR:** `talkback/docs/adr/0058-conference-multicast-media-group.md` §Q2 (Media Group Descriptor immutability) · §Q4 wire registry  
**Impl profile:** `talkback/docs/analysis/0058-implementation-profile-01-control-crypto.md`  
**Prior contract:** `talkback/docs/analysis/meeting-profile01-epoch3-staged-package-lifecycle-fix-contract.md` (E3-A)  
**RCA basis:** IG-2 / IG-2A desk — `MeetingProfile01E3AIG2FullChainDeskTest` (production-fidelity CREATION wire → epoch2 ACTIVE → E3-A epoch3 chain)

**Status:** **ACCEPTED — CONTRACT ONLY (AMENDMENT)**  
**Implementation:** **NOT AUTHORIZED**  
**Field verification:** **NOT AUTHORIZED**

```text
E3-A STAGED lifecycle                    IMPLEMENTED / PASS

IG2-1 session rotate@3                   CONFIRMED production-representative
Root                                     PEER ENDPOINT AUTHORITY GAP

S1–S4 RCA                                CLOSED

New ADR                                  NOT NEEDED
                                         (ADR Q2/Q4 + RCA4b sync primitive sufficient)

This amendment                           AUTHORIZED — CONTRACT ONLY

Preferred peer endpoint authority        authoritative materialized/published session
                                         endpoint within same (conferenceId, conferenceEpoch)

IncompleteApplyContinuation              remains ephemeral — KEEP
discardBelowMediaKeyEpoch                KEEP — unchanged

E3-B replace→ready                       BLOCKED-BY-IG2-1
                                         (not NOT NEEDED until IG2-1 cleared + IG2-3/4 adjudicated)

Production implementation                NOT AUTHORIZED
Field                                    NOT AUTHORIZED
```

**Authorizes:** contract amendment for peer **existing-session** registry sync at membership/media-key
head — endpoint authority ownership only (EC-1 … EC-6).

**Does NOT authorize:** implementation · unit/desk execution of the fix · field · new ADR ·
`discardBelowMediaKeyEpoch` behavior change · CREATION re-send for rotation · host
`CreationOriginBridge` clone on peer · generic peer rotation orchestrator · E3-B · TX fence
relaxation · IG2-2 commitment ordering workaround.

---

## 1. Frozen RCA chain (IG-2 / IG-2A)

```text
E3-A: future KEY@3 → STAGED → membership catch-up → supplement@3 ACTIVE     PASS

Existing completion chain after supplement@3:
  onSupplementReadyForConference
  → maybeMaterializeSessionAtMembershipHead
      hasPublishedSession == true  → syncSessionDeclarationToMembershipHead
      creationSignedBytesForEndpoint ← incompleteApplyContinuation.peekNewestPending()
      pending empty (discarded at epoch2 GENERATION_FENCE)
      → NO_CREATION_CONTEXT
  → sessionControl remains epoch2                                      FAIL (IG2-1)

Parallel (downstream inconsistent state — do not fix independently):
  onMeetingProfile01MediaSupplementReady
  → commitLocalSource@3 (membership head)                              PASS early (IG2-2)
  → registry/binding remain epoch2/gen1
  → commitment epoch3/gen2
  → SOURCE_GENERATION_MISMATCH                                         FAIL (IG2-5)
```

**Root-cause class:** `implementation authority gap` — peer existing-session rotation binds
**durable immutable endpoint authority** to **ephemeral** `IncompleteApplyContinuation`.

**Not root cause:**

- E3-A STAGED lifecycle (closed)
- `discardBelowMediaKeyEpoch` (legal GENERATION_FENCE)
- missing CREATION wire re-send (explicitly forbidden by ADR rotation semantics)
- IG2-2 early commitment (downstream of IG2-1 — no separate fix authorized)

**Host contrast (S3 — frozen):**

```text
Host registry sync:
  creationSignedBytesForEndpoint ← CreationOriginBridge.readSignedCreationFact (durable)

Host wiring replay:
  replayHostMembershipWiring — no CREATION bytes; registry already at head

Peer wire path today:
  creationSignedBytesForEndpoint ← incompleteApplyContinuation.peekNewestPending (ephemeral)
```

Whether peer wiring requires host-style `replace→ready` after IG2-1 clears is **not decided**
by this amendment — remains IG-2 / E3-B adjudication.

---

## 2. Contract clauses (EC-1 … EC-6)

### EC-1 — ENDPOINT AUTHORITY

For an **already materialized peer session** within the same `(conferenceId, conferenceEpoch)`,
the **durable endpoint authority** for membership / media-key rotation is the **endpoint of the
authoritative materialized / published session declaration**.

`IncompleteApplyContinuation` is **not** durable endpoint authority.

`MeetingProfile01CreationOriginBridge.readSignedCreationFact` (host origin cache) is **not**
the required peer authority model for existing-session rotation under this amendment.

Peer rotation MUST obtain endpoint **fact/value** from the authoritative session row already
accepted for that conference epoch — not by elevating a creation-time transport envelope into
ongoing rotation authority.

### EC-2 — CREATION LIFECYCLE

CREATION wire bytes MAY be retained in `IncompleteApplyContinuation` **only while creation
application is incomplete** (missing supplement at CREATION `mediaKeyEpoch`).

After successful materialization or generation fencing:

- `discardBelowMediaKeyEpoch` **remains legal and unchanged**
- fenced pending MUST NOT be treated as rotation authority

Existing-session rotation **MUST NOT** require:

- CREATION to be re-sent on the wire
- CREATION to remain pending across a successful head materialization fence

Initial creation materialization semantics (`NO_CREATION_CONTEXT` when no session and no pending)
**MUST be preserved** — see fixture G.

### EC-3 — EXISTING-SESSION SYNC

**Given:**

```text
authoritative published / materialized session @ mediaKeyEpoch N
membership convergence head @ mediaKeyEpoch N+1 (same conferenceEpoch)
supplement ACTIVE in registry @ N+1
conferenceId + conferenceEpoch unchanged
```

**`syncSessionDeclarationToMembershipHead` SHALL derive the republished session declaration as:**

| Field | Authority source |
|-------|------------------|
| `endpoint` | existing authoritative session (EC-1) |
| `membershipVersion`, `mediaKeyEpoch`, `conferenceEpoch` | membership convergence head |
| `masterKey`, `masterSalt`, `keyContextHint64` | supplement registry @ head |
| `networkInterfaceName`, `channelId` | session binding / ingress context (unchanged) |

**It SHALL NOT require pending CREATION bytes.**

#### Input semantics (contract change — not implementation fallback)

The current parameter `creationSignedBytesForEndpoint` **misstates rotation authority**. This
amendment requires **contract-level input semantics**, not a silent fallback that leaves the
API implying CREATION is rotation authority.

**Required contract shape (one of — implementation chooses minimal diff):**

```text
Option A — explicit endpoint authority on sync:
  syncSessionDeclarationToMembershipHead(
    ...,
    endpointAuthority: SessionEndpointAuthority,
  )

  SessionEndpointAuthority =
    | InitialCreationContext(signedCreationBytes)   // first materialization only
    | ExistingPublishedSession(conferenceId)         // existing-session rotation (EC-1)

Option B — split entry points:
  materializeSessionAtMembershipHead(...)           // initial — CREATION context required
  syncExistingSessionDeclarationToMembershipHead(...) // rotation — EC-1 endpoint only
```

**Forbidden:** passing `peekNewestPending()` into sync for an **already materialized** session
while treating absence as `NO_CREATION_CONTEXT` when EC-1 preconditions are satisfied.

**Allowed unchanged:** first-time materialization path when `hasPublishedSession == false` still
requires verified CREATION context (pending bytes or equivalent initial path).

### EC-4 — FENCES

Endpoint reuse from existing authoritative session is legal **only when all hold:**

```text
F1  conferenceId matches
F2  conferenceEpoch matches membership head conferenceEpoch
F3  existing session declaration is authoritative / materialized (published ACCEPTED|IDEMPOTENT)
F4  endpoint is internally valid for that session (complete declaration validation)
```

**Forbidden:**

```text
Never reuse endpoint across conferenceEpoch boundaries.

Never reuse endpoint from a superseded / stale session row.

No endpoint source may override a conflicting authoritative CREATION during initial creation
(first materialization still wins CREATION-verified descriptor).
```

When F1–F4 fail, sync MUST NOT silently fabricate endpoint — defer / reject per existing
`SessionHeadSyncOutcome` family (including `NO_CREATION_CONTEXT` for true initial gaps).

### EC-5 — COMPLETION (registry sync only)

Successful registry sync @ head **MUST** continue through the **existing** completion machinery
already wired in production (`TalkbackRuntimeFactory` supplement-ready chain):

```text
supplement@head ACTIVE
→ onSupplementReadyForConference
    (drain satisfiable CREATION incomplete where applicable
     + existing-session sync @ head per EC-3
     + replaySessionStarted
     + onCreationWireApplied / materializer hooks as today)
→ onMeetingProfile01MediaSupplementReady
    (commitLocalSource @ head + publish + host projection if applicable)
→ existing member binding / TX machinery
```

**Forbidden in this amendment slice:**

```text
commit a second source successor to bypass registry sync
bypass supplement readiness
relax TX fences (SOURCE_GENERATION_MISMATCH / epoch mismatch)
introduce generic peer rotation orchestrator cloning host replayHostMembershipWiring
bundle E3-B replace→ready into EC implementation
```

Whether peer wiring requires explicit `replace→ready` after IG2-1 clears is governed by **IG-2
adjudication after IG2-1** — not assumed or fixed here.

### EC-6 — IDEMPOTENCE

Repeated `supplementReady` / sync at the same authoritative head:

```text
session declaration mediaKeyEpoch unchanged
sourceGeneration not incremented a second time
no second wiring rotate obligation from registry sync alone
TX remains resolvable when full chain otherwise converged
```

Extends E3-A idempotence from staging ledger through **full completion chain** once IG2-1 is
cleared — duplicate supplementReady desk fixture becomes mandatory after convergence.

---

## 3. Relationship to E3-A and E3-B

| Slice | Status after this amendment |
|-------|----------------------------|
| **E3-A** | **IMPLEMENTED / PASS** — unchanged |
| **E3-B** | **BLOCKED-BY-IG2-1** — was CONDITIONAL; cannot adjudicate until session@head rotates and IG2-3/4 observed |

This amendment closes the **second** production-representative breakpoint (IG2-1). It does **not**
supersede E3-A and does **not** authorize E3-B.

---

## 4. Implementation gates (EG-1 … EG-2)

### EG-1 — Endpoint authority / existing-session sync only

First production change set **MUST implement only**:

```text
EC-1 peer durable endpoint authority (published session endpoint within conferenceEpoch)
EC-2 CREATION lifecycle fences (no discard semantic change)
EC-3 existing-session sync input semantics
EC-4 endpoint reuse fences
```

No E3-B. No host bridge clone unless desk proves EC-1 insufficient (not expected).

### EG-2 — Re-run IG-2 full chain

After EG-1 implementation, re-run IG-2 desk fixture (production fidelity CREATION wire → epoch2
ACTIVE → E3-A epoch3 chain + existing callbacks only).

```text
If IG2-1 PASS:
  continue adjudicating IG2-2 → IG2-5 in order

If IG2-1 still FAIL:
  stop — amendment implementation insufficient; RCA before further contract delta

If IG2-1 PASS and IG2-3/4 FAIL with replace→ready as first causal break:
  E3-B becomes eligible for separate authorization (not bundled)

If IG2-1..IG2-5 PASS:
  E3-B = NOT NEEDED
  duplicate idempotence fixture (EC-6 / fixture H) MUST PASS without skip
```

---

## 5. Contract fixtures (required before implementation authorization)

Listed as contract obligations — **not authorized to implement now**.

### E — Existing session @ epoch2, no pending CREATION (primary IG2-1 class)

```text
PRECONDITION (production-equivalent):
  CREATION wire → incomplete pending → membership/package → materialize epoch2 ACTIVE
  incompleteApplyContinuation empty after epoch2 GENERATION_FENCE
  supplement@2 ACTIVE; sessionControl.mediaKeyEpoch == 2

E3-A epoch3 chain:
  KEY@3 STAGED → membership catch-up → supplement@3 ACTIVE

EXPECT:
  syncSessionDeclarationToMembershipHead uses EC-1 endpoint (not pending CREATION)
  sessionControl.mediaKeyEpoch == 3                                    (IG2-1)
  commitment.sourceGeneration == G+1 exactly once                      (IG2-2)
  continue IG2-3/4/5 adjudication
```

### F — conferenceEpoch change — endpoint MUST NOT carry forward

```text
Same as E except membership head advances with conferenceEpoch change
(or simulated epoch boundary)

EXPECT:
  EC-4 fence — endpoint reuse rejected
  MUST NOT republish epoch-N+1 session row using epoch-N endpoint
  MUST NOT silently succeed with stale endpoint
```

### G — Initial gap preserved — NO_CREATION_CONTEXT

```text
No authoritative materialized session
No pending CREATION
membership head + supplement@head otherwise ready

EXPECT:
  NO_CREATION_CONTEXT (or equivalent defer)
  MUST NOT fabricate endpoint from supplement or membership alone
```

**Critical:** EC fix MUST NOT regress initial creation materialization semantics.

### H — Duplicate completion @ epoch3 (EC-6)

```text
After fixture E converges to epoch3 head:

Replay supplementReady / production callback duplicate once

EXPECT:
  session remains epoch3
  sourceGeneration remains G+1
  no second rotate
  no duplicate incarnation
  TX remains resolvable (IG2-5)
```

---

## 6. Explicit exclusions

```text
New ADR                                           NOT NEEDED
CREATION re-send for rotation                     FORBIDDEN
discardBelowMediaKeyEpoch semantic change         FORBIDDEN
Peer CreationOriginBridge clone as default        NOT REQUIRED (EC-1 preferred)
creationSignedBytesForEndpoint silent fallback    FORBIDDEN (EC-3)
Delay commitLocalSource until TX unfence          FORBIDDEN (IG2-2 workaround)
E3-B in same change set as EC                     FORBIDDEN
Field rerun                                       NOT AUTHORIZED
WiFi recovery / RNA-5 / ADR-0038 domains          OUT OF SCOPE
```

---

## 7. Acceptance (contract document)

This amendment is **ACCEPTED — CONTRACT ONLY**.

**Next authorized step (separate gate):** EG-1 implementation authorization after explicit user
sign-off on this document — **not** bundled with E3-B or field.

**Evidence pointers:**

- IG-2 desk: `MeetingProfile01E3AIG2FullChainDeskTest`
- Host reference: `Profile01HostLocalSessionFactProjection.syncHostMembershipRegistry` (durable CREATION)
- Peer gap: `MeetingProfile01FactWireIngress.maybeMaterializeSessionAtMembershipHead` (`peekNewestPending`)
- Sync primitive: `Profile01ConferenceMediaFactIngress.syncSessionDeclarationToMembershipHead`
- Descriptor immutability: ADR-0058 §Q2
