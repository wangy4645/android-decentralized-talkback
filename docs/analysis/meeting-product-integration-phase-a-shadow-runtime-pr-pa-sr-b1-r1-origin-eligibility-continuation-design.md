# Phase A Shadow Runtime — PR-PA-SR-B1-R1 Origin Eligibility Continuation Design

**Parent:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-implementation-authorization.md`  
**Triggered by:** PA-SR3 field FAIL — SOURCE origin eligibility / ordering gap (RCA valid)  
**Field evidence:** `talkback/logs/phase-a-sr3-field-20260909-121252/` · `meeting-product-integration-phase-a-sr3-field-result-20260909-121252.md`  
**Prerequisite:** PR-PA-SR-B1 **CLOSED / UNIT-PROVEN** · PA-SR3 **VALID FAIL / RCA OPEN**  
**Status:** **DESIGN FROZEN** (2026-09-09) · **IMPLEMENTATION AUTHORIZED** — see implementation authorization doc

```text
PR-PA-SR-B1     CLOSED / UNIT-PROVEN · FIELD FAIL (origin gate)
B1-R1           CLOSED / UNIT-PROVEN
PA-SR3          AUTHORIZED FOR FIELD RE-RUN

B0 / A1 / J0    UNCHANGED_CLOSED
P1-C / P1-E     UNCHANGED_CLOSED
PA-SR4…SR6      HOLD
ADR-0056        KEEP
cutover         NOT AUTHORIZED
```

**Authorizes (when implementation authorization follows):** narrow product fix — SOURCE declaration
eligibility must remain open when a prerequisite is not yet met, and re-evaluate **only** on two
frozen event-driven continuations. Does **not** authorize generic retry workers, membership gate
bypass, ICE-driven origin, or pre-bind SOURCE retention.

---

## 1. Problem (field-proven)

PA-SR3 preconditions green; SOURCE origin / apply / binding chain broken at **eligibility gate only**.
Trust (B0), transport, ingress apply, and registry binding semantics are **not** in scope.

### 1.1 Host gap (R1-A)

```text
Host CREATION origin emits signed factType=1
→ host does not ingress-apply its own CREATION
→ Profile01MembershipConvergenceRegistry.current() == null
→ SOURCE build reads membershipConvergence → MEMBERSHIP_NOT_CONVERGED
→ no BUILT / EMITTED for factType=9
```

Field evidence (M01):

```text
PROFILE01_ORIGIN_EMIT factType=9 moduleId=M01 ORIGIN_SOURCE_GAP reason=MEMBERSHIP_NOT_CONVERGED
PROFILE01_ORIGIN_EMIT factType=9 moduleId=M01 ORIGIN_SOURCE_GAP reason=NO_BUILT_SOURCE
```

### 1.2 Peer gap (R1-B)

```text
Peer MEDIA_KEY_PACKAGE decrypt → SUPPLEMENT READY
→ onMediaSupplementReady → SOURCE build attempt
→ CREATION not yet APPLIED / roster not visible
→ NOT_IN_ROSTER
→ CREATION subsequently APPLIED (WIRE_INGRESS factType=1)
→ no new SOURCE build obligation → permanent gap
```

Field evidence (M02):

```text
12:16:56.690  PROFILE01_MEDIA_SUPPLEMENT state=READY
12:16:56.695  PROFILE01_ORIGIN_EMIT factType=9 … ORIGIN_SOURCE_GAP reason=NOT_IN_ROSTER
12:16:56.732  WIRE_INGRESS factType=1 outcome=APPLIED   ← after SOURCE attempt
```

### 1.3 Unified diagnosis

Both gaps share one contract violation:

> **SOURCE declaration eligibility must be driven by authoritative local Profile01 session state.
> If a necessary prerequisite is not yet established, the SOURCE origin obligation must remain
> open and be re-evaluated when that prerequisite becomes established.**

B1 implementation treated a failed eligibility check as a terminal one-shot attempt. B1-R1 closes
that gap without changing B1-HC1…HC3, B0 fence, byte-identical republication (B1-6), or
pre-bind SOURCE non-retention (B1-5).

---

## 2. Frozen contract (B1-R1)

### 2.1 Eligibility inputs (unchanged from B1)

`MeetingProfile01SourceOriginBridge.onLocalConferenceSourceCommitted` may **first build** only when
all of the following hold against **authoritative local Profile01 session state**:

| Prerequisite | Read surface |
|--------------|--------------|
| Self-declaration | `localModuleId == publisher.signerModuleId` |
| Conference binding | `sessionIndex.conferenceIdForSession(sessionId)` |
| Membership eligibility | `membershipConvergence.current(conferenceIdHex)` with `hasMember(localModuleId)` |
| Media material | `mediaKeyAuthority.materialForSession(sessionId)` **or** `supplementRegistry.lookup(conferenceId, mediaKeyEpoch)` |

**No authority from ICE connectivity.** Media supplement READY is a valid **attempt trigger** but
not sufficient alone when membership eligibility is absent.

### 2.2 Obligation semantics (new)

```text
eligibility attempt
  → if all prerequisites met → build once (existing B1 path)
  → if blocked on MEMBERSHIP_NOT_CONVERGED or NOT_IN_ROSTER
      → retain pending SOURCE build obligation (identity-keyed)
      → log ORIGIN_SOURCE_GAP (existing OBS)
  → if blocked on other gaps (NO_SIGNER, NO_MEDIA_MATERIAL, …)
      → do NOT retain obligation in B1-R1 (out of scope / different RCA)
```

**Build-once preserved:** once `SourcePublicationState` exists for the session, B1-R1 does not
re-build. Continuation only unlocks the **first** build. Post-bind republication remains
byte-identical replay (B1-6).

### 2.3 Allowed continuations only

| Id | Trigger | Effect |
|----|---------|--------|
| **R1-A** | Host CREATION authoritative publish/commit → local membership state readable for self-origin | Re-evaluate pending local SOURCE obligation for matching session |
| **R1-B** | Peer CREATION APPLIED / roster visibility established (`membershipConvergence.seedFromCreation` Accepted or Idempotent) | Re-evaluate pending local SOURCE obligation for matching conference + session |

**Explicitly forbidden:**

```text
- host membership gate bypass (fake converged=true)
- peer timer / sleep / polling retry
- SOURCE origin bound to ICE CONNECTED
- generic deferred-fact framework reuse (e.g. P1-E IncompleteApplyContinuation)
- pre-bind SOURCE retention
- manual / harness replay to close PA-SR3
```

---

## 3. Design decisions

### 3.1 R1-A — Host authoritative local membership projection

**Goal:** Host must read the **same** membership generation SOURCE eligibility already expects,
without ingress-applying its own CREATION datagram.

**Chosen approach:** Project CREATION origin authority into
`Profile01MembershipConvergenceRegistry` at the moment the host **authoritatively creates and
caches** its signed CREATION fact — using the **same canonical membership view** already encoded
into that fact.

```text
MeetingProfile01CreationOriginBridge.createAndCacheCreationFact(...)
  → after successful encode/sign/cache
  → derive Profile01WireSessionFact-equivalent membership head
  → membershipConvergence.seedFromCreation(wireEquivalent)
  → emit R1-A continuation signal (sessionId, conferenceIdHex)
```

**Rules:**

- Projection uses the **identical** `conferenceId`, `conferenceEpoch`, `membershipVersion`,
  `mediaKeyEpoch`, `membershipView`, `mediaKeyCommitment`, and `factDigest` as the signed CREATION
  artifact. No synthetic roster; no `converged=true` flag.
- `seedFromCreation` remains the single writer semantics for CREATION head — same as ingress path.
  Idempotent re-projection on CREATION replay is acceptable (`Idempotent` result).
- Projection happens **before** SOURCE eligibility re-evaluation on the same coordinator turn
  (topology publish fanout order preserved: CREATION create → R1-A seed → SOURCE attempt).
- Does **not** call `Profile01ConferenceMediaFactIngress.ingestSession` for host self-CREATION.

**Read surface for SOURCE:** unchanged — `membershipConvergence.current(conferenceIdHex)`.

### 3.2 R1-B — Peer ordering continuation

**Goal:** Early `onMediaSupplementReady` (or topology fanout) may race ahead of CREATION APPLIED.
Retain obligation; resume on membership eligibility establishment.

**Pending obligation store:** new narrow type inside B1-R1 scope, e.g.
`SourceOriginEligibilityContinuation` (name frozen at impl time). **Not** a reuse of
`MeetingProfile01IncompleteApplyContinuation` (P1-E ingress mailbox).

**Retain when:** `onLocalConferenceSourceCommitted` returns `ORIGIN_SOURCE_GAP` **and** gap reason
is exactly `MEMBERSHIP_NOT_CONVERGED` or `NOT_IN_ROSTER`.

**Identity key (frozen):**

```text
sessionId
conferenceIdHex
localModuleId
sourceInstanceId   (bytes)
sourceGeneration   (committed local identity generation from authority — pre-build)
membershipVersion  (expected at attempt time, or UNKNOWN sentinel if convergence null)
mediaKeyEpoch      (expected at attempt time, or UNKNOWN sentinel if convergence null)
```

At re-evaluation, obligation matches only if **all** key fields still equal current committed
source identity and current convergence head (when present). Any session teardown, new
`sourceGeneration` commit, or `membershipVersion` / `mediaKeyEpoch` drift → **discard** (no blind
retry).

**Trigger hook:** after `Profile01MembershipConvergenceRegistry.seedFromCreation` yields
`Accepted` or `Idempotent` on the **local peer** ingress path, invoke R1-B continuation for the
bound `sessionId` (via coordinator callback — same pattern as `onMediaSupplementReady`).

**Coordinator re-evaluation entry:** reuse `maybeCommitAndPublishLocalConferenceSource(session,
reason)` with frozen reasons:

```text
r1a_host_creation_projected
r1b_peer_creation_applied
```

No new public API surface beyond these internal continuation hooks.

### 3.3 Build-once and B1-6 preservation

```text
Continuation → maybeCommitAndPublishLocalConferenceSource
  → authority.commitLocalSource (idempotent for same identity)
  → bridge.onLocalConferenceSourceCommitted
      → if sourceStateBySession already holds matching identity → ALREADY_BUILT (no-op)
      → else if eligible → BUILT once
```

Publication / republication path unchanged: `onEligiblePeers` + `SourcePublicationLedger`.

### 3.4 OBS (minimal, behavior-neutral)

Existing `PROFILE01_ORIGIN_EMIT … ORIGIN_SOURCE_GAP reason=…` retained.

Add (implementation):

```text
PROFILE01_SOURCE_ELIGIBILITY_CONTINUATION
  session=… conferenceId=… moduleId=…
  action=RETAINED|REEVALUATED|DISCARDED_STALE|DISCARDED_BUILT
  trigger=r1a_host_creation_projected|r1b_peer_creation_applied|…
  obligationKey=…   (opaque hash of frozen identity tuple — no secrets)
```

Field adjudication for PA-SR3 **does not** require new OBS to PASS; chain remains frozen.

---

## 4. Implementation scope

### IN

```text
- R1-A: host CREATION origin → membershipConvergence seedFromCreation projection
- R1-B: pending SOURCE eligibility obligation + CREATION-applied re-evaluation
- Coordinator wiring for two continuation triggers only
- Unit tests R1-1 … R1-5
- Minimal continuation OBS (above)
```

### OUT

```text
- Membership gate bypass / synthetic convergence
- Timer, polling, generic retry worker
- ICE CONNECTED as SOURCE trigger
- Generic deferred-fact framework
- SOURCE pre-bind retention (B1-5 frozen)
- Changes to B0 signer/sender fence
- Changes to ingress validate/apply for factType=9
- registry.publishMember semantic changes
- PA-SR4 … PA-SR6 fixes
- J0 / P1-C / P1-E changes
- ADR-0056 / Meeting media cutover
- Manual replay / harness-only closure of PA-SR3
```

---

## 5. Unit test matrix (merge gate — R1-1 … R1-5)

| Id | Property | Required outcome |
|----|----------|----------------|
| **R1-1** | Host self-origin | CREATION authoritative commit → membership eligibility visible locally → SOURCE built **once** → EMITTED path reachable |
| **R1-2** | Peer early supplement | SOURCE attempt → `NOT_IN_ROSTER` → CREATION APPLIED → same pending obligation re-evaluated → SOURCE built **once** |
| **R1-3** | No polling / no duplicate | Multiple R1-A or R1-B callbacks for same obligation → at most one initial build; no `sourceGeneration` bump |
| **R1-4** | Stale session/source | Pending obligation from old `sessionId` / `sourceGeneration` / `membershipVersion` → discarded; no build |
| **R1-5** | B1-6 preserved | Once built, post-bind republication remains **byte-identical** `signedFactBytes` (regression of B1-6) |

**Regression:** B1-1 … B1-7 remain PASS. B0 fence tests unchanged.

**R1 unit PASS does not close PA-SR3.** Field re-run required.

---

## 6. Field gate (unchanged chain)

Re-run `phase-a-sr3-field-first-run.ps1` only after R1 unit closure.

Frozen adjudication chain:

```text
M01 SOURCE self-origin
  → PROFILE01_ORIGIN_EMIT factType=9 outcome=EMITTED
  → M02 WIRE_INGRESS factType=9 outcome=APPLIED
  → registry.member established
  → SHADOW MEMBER_MEDIA_READY(M01) outcome=APPLIED
```

Pre-bind `PRE_BIND_NOT_RETAINED factType=9` → PASS only if **product auto** post-bind
republication with same `sourceGeneration + factDigest` (B1-6). No manual replay.

Verdict model unchanged: `PASS` · `INVALID_*` · `FAIL_PA_SR3`.

---

## 7. State transitions

### On B1-R1 design freeze (this document)

```text
PR-PA-SR-B1     CLOSED / UNIT-PROVEN · FIELD FAIL
B1-R1           DESIGN FROZEN · IMPL NOT AUTHORIZED
PA-SR3          VALID FAIL / RCA OPEN
```

### On B1-R1 implementation authorization (signed 2026-09-09)

```text
B1-R1           IMPLEMENTATION AUTHORIZED
PA-SR3          VALID FAIL / RCA OPEN (field re-run blocked until R1 unit closure)
```

See: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r1-implementation-authorization.md`

### On R1 unit closure (target)

```text
B1-R1           CLOSED / UNIT-PROVEN
PA-SR3          AUTHORIZED FOR FIELD RE-RUN
```

### On PA-SR3 field PASS (target)

```text
PR-PA-SR-B1     CLOSED / UNIT+FIELD-PROVEN
PA-SR3          CLOSED / FIELD-PROVEN
PA-SR4…SR6      NEXT / still not closed
```

---

## 8. References

- B1-R1 implementation authorization: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r1-implementation-authorization.md`
- B1 authorization: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-implementation-authorization.md`
- B1 unit closure: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-unit-closure.md`
- PA-SR3 field result: `meeting-product-integration-phase-a-sr3-field-result-20260909-121252.md`
- PA-SR3 run card: `meeting-product-integration-phase-a-sr3-field-run-card.md`
- PA-SR3 adjudication: `meeting-product-integration-phase-a-sr3-adjudication-criteria.md`
- Analog (obligation continuation pattern): `meeting-profile01-p1-c-r1-post-bind-republish-obligation.md`
- Field log: `talkback/logs/phase-a-sr3-field-20260909-121252/`
