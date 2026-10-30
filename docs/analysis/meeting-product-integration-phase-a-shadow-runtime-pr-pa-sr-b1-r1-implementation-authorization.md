# Phase A Shadow Runtime — PR-PA-SR-B1-R1 Implementation Authorization

**PR:** `PR-PA-SR-B1-R1` — SOURCE Origin Eligibility Continuation  
**Design:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r1-origin-eligibility-continuation-design.md`  
**Prerequisite:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-implementation-authorization.md`  
**Field RCA:** `meeting-product-integration-phase-a-sr3-field-result-20260909-121252.md`  
**Signed:** 2026-09-09  
**Status:** **IMPLEMENTATION AUTHORIZED** (B1-R1 scope only)

```text
Prerequisite
  PR-PA-SR-B1     CLOSED / UNIT-PROVEN · FIELD FAIL (origin gate)
  B1-R1 design    DESIGN FROZEN

Authorization status
  IMPLEMENTATION AUTHORIZED only for PR-PA-SR-B1-R1 scope

PR-PA-SR-B1     CLOSED / UNIT-PROVEN · FIELD FAIL (origin gate)
B1-R1           CLOSED / UNIT-PROVEN
PA-SR3          AUTHORIZED FOR FIELD RE-RUN

B0 / A1 / J0    UNCHANGED_CLOSED
P1-C / P1-E     UNCHANGED_CLOSED
PA-SR4…SR6      HOLD
ADR-0056        KEEP
cutover         NOT AUTHORIZED
```

B0 signer/sender fence and B1-HC1…HC3 remain **closed** — not re-litigated in B1-R1.

---

## Goal (authorized)

```text
blocked SOURCE origin obligation
  → authoritative Profile01 eligibility later becomes true
  → event-driven reevaluation (R1-A or R1-B only)
  → first legal SOURCE build occurs exactly once
```

B1-R1 closes the PA-SR3 field-proven **eligibility / ordering gap** only. It does **not** change
SOURCE signing, identity semantics, ingress apply, registry binding, or publication/republication
contracts established by B1.

---

## Authorized continuations (exactly two — no third path)

### R1-A — Host self-origin convergence

```text
Host CREATION authoritative commit
  → seed local membership convergence from the same canonical CREATION view
  → reevaluate pending local SOURCE obligation
```

**Event source (frozen):** `MeetingProfile01CreationOriginBridge` after authoritative CREATION is
**committed/cached** as the local origin fact source — not merely after transient encode/sign.

**R1-A commit gate (authorization hard constraint):**

```text
createAndCacheCreationFact "success"
  MUST mean authoritative CREATION is committed/cached in creationStateBySession
  AND sessionIndex conference binding is established
  AND the signed bytes are the same artifact used for peer publication

MUST NOT treat transient CBOR/signature construction as sufficient.
MUST NOT conflate "bytes built" with "authoritative CREATION committed".
```

Projection (`membershipConvergence.seedFromCreation`) runs **only** at this commit point,
using the **identical** canonical membership view already encoded in the cached signed CREATION
artifact.

### R1-B — Peer ordering continuation

```text
Peer CREATION APPLIED / roster visibility established
  → reevaluate pending local SOURCE obligation
```

**Event source (frozen):** local peer ingress path after
`Profile01MembershipConvergenceRegistry.seedFromCreation` yields `Accepted` or `Idempotent`
(i.e. `WIRE_INGRESS factType=1 outcome=APPLIED` semantics on the peer). Coordinator invokes
continuation via bound `sessionId` — same callback pattern as `onMediaSupplementReady`.

**Not** a third continuation: ICE connected, supplement ready alone, topology fanout alone, or
timer expiry do **not** authorize re-evaluation unless they coincide with R1-A or R1-B above.

---

## Authorized implementation scope

### IN

```text
- membershipConvergence.seedFromCreation(...) at R1-A commit point only
- narrow SourceOriginEligibilityObligationStore (or equivalent)
- R1-A host CREATION-commit callback
- R1-B peer CREATION-APPLIED callback
- stale obligation discard on session/source generation identity drift
- minimal PROFILE01_SOURCE_ELIGIBILITY_CONTINUATION OBS (behavior-neutral)
- unit tests R1-1 … R1-5
- existing B1 regression (B1-1 … B1-7 + B0 fence)
```

### OUT

```text
- membership gate bypass / synthetic converged=true
- timer / poll / sleep retry
- ICE-triggered SOURCE truth or eligibility
- generic deferred-fact framework (e.g. P1-E IncompleteApplyContinuation reuse)
- SOURCE pre-bind retention (B1-5 frozen)
- changes to SOURCE signing / identity (SSRC, sourceInstanceId, sourceGeneration rules)
- changes to B1 publication ledger or byte-identical republication (B1-6)
- changes to B0 signer/sender fence
- changes to ingress validate/apply for factType=9
- registry.publishMember semantic changes
- PA-SR4 … PA-SR6 fixes
- J0 / P1-C / P1-E
- ADR-0056 / Meeting media cutover
- manual / harness replay to close PA-SR3
```

---

## Hard constraints (non-negotiable)

### R1-HC1 — Same canonical CREATION view (R1-A)

```text
seedFromCreation MUST consume the identical canonical membership view
already encoded in the authoritative committed/cached CREATION artifact.

MUST NOT construct a second approximate roster, alternate membershipVersion,
or synthetic convergence head for host self-origin.
```

The wire-equivalent passed to `seedFromCreation` must derive from the same
`Profile01CreationAuthoritySnapshot` (or decoded canonical bytes of the cached signed fact) that
was committed to `creationStateBySession` — field-for-field:
`conferenceId`, `conferenceEpoch`, `membershipVersion`, `mediaKeyEpoch`, `membershipView`,
`mediaKeyCommitment`, `factDigest`.

### R1-HC2 — Obligation creation gate

```text
Pending SOURCE eligibility obligation MAY be created ONLY when
onLocalConferenceSourceCommitted returns ORIGIN_SOURCE_GAP with reason exactly:
  MEMBERSHIP_NOT_CONVERGED
  NOT_IN_ROSTER

Other gap reasons (NO_SIGNER, NO_MEDIA_MATERIAL, SIGNER_MODULE_MISMATCH, …)
MUST NOT create or retain a B1-R1 obligation.
```

### R1-HC3 — Identity verification before re-evaluation

```text
Before re-evaluating a pending obligation, MUST verify the frozen identity tuple:
  sessionId
  conferenceIdHex
  localModuleId
  sourceInstanceId
  sourceGeneration (pre-build committed identity)
  membershipVersion (or UNKNOWN sentinel if convergence was null at retain time)
  mediaKeyEpoch (or UNKNOWN sentinel if convergence was null at retain time)

Any drift in session, source generation, or convergence head → DISCARD (no blind retry).
```

### R1-HC4 — Build-once consumption

```text
Once SOURCE build succeeds (SourcePublicationState established):
  → pending obligation MUST be consumed / cleared
  → subsequent R1-A / R1-B callbacks MUST NOT trigger a new initial build
  → publication and republication continue on existing B1 path
     (SourcePublicationLedger + byte-identical replay per B1-HC3)
```

R1-R1 solves **when the first build becomes legal** — not **how** publication works afterward.

### R1-HC5 — No ICE authority

```text
SOURCE declaration eligibility MUST NOT be driven by ICE CONNECTED
or mediaConnectedModules semantics.
```

Inherited from B1-HC2; B1-R1 continuations do not introduce ICE as a trigger.

### R1-HC6 — Authoritative CREATION commit semantics (R1-A hard gate)

```text
R1-A projection is authorized ONLY after authoritative CREATION commit:
  creationStateBySession[sessionId] populated with signedFactBytes
  AND sessionIndex.bindConferenceId completed for that session

Encoding/signing bytes in a local variable without cache commit
does NOT authorize seedFromCreation or R1-A continuation.
```

This prevents repeating the `built != published/applied` semantic confusion on the host path.

---

## Pending obligation identity (frozen)

```text
sessionId
conferenceIdHex
localModuleId
sourceInstanceId
sourceGeneration
membershipVersion   (UNKNOWN sentinel allowed at retain time if convergence null)
mediaKeyEpoch       (UNKNOWN sentinel allowed at retain time if convergence null)
```

At most one pending obligation per `(sessionId, localModuleId, sourceGeneration)` at retain time.
Duplicate retain → idempotent (no duplicate obligations).

---

## Coordinator wiring (frozen)

Re-evaluation reuses existing coordinator entry:

```text
maybeCommitAndPublishLocalConferenceSource(session, reason)
  → profile01LocalConferenceSourceIdentityAuthority.commitLocalSource (idempotent)
  → meetingProfile01SourceOriginBridge.onLocalConferenceSourceCommitted
  → publishSourceDeclarationToEligiblePeers (if BUILT)
```

Frozen `reason` strings:

```text
r1a_host_creation_projected
r1b_peer_creation_applied
```

Topology fanout order on host (unchanged intent):

```text
CREATION create/cache + R1-A seed
  → MEDIA_KEY_PACKAGE fanout
  → SOURCE commit attempt
  → SOURCE publication fanout
```

---

## Unit test matrix (merge gate — R1-1 … R1-5)

| Id | Property | Required outcome |
|----|----------|----------------|
| **R1-1** | Host self-origin | CREATION authoritative commit → `seedFromCreation` → membership eligibility visible → SOURCE built **once** |
| **R1-2** | Peer early supplement | SOURCE attempt → `NOT_IN_ROSTER` → obligation retained → CREATION APPLIED (R1-B) → same obligation re-evaluated → SOURCE built **once** |
| **R1-3** | No polling / no duplicate | Multiple R1-A or R1-B callbacks → at most one initial build; no spurious `sourceGeneration` bump |
| **R1-4** | Stale session/source | Obligation from old session / source generation / membershipVersion → discarded; no build |
| **R1-5** | B1-6 preserved | Once built, post-bind republication = **byte-identical** `signedFactBytes` |

**Regression:** B1-1 … B1-7 remain PASS. B0 fence tests unchanged.

**R1 unit PASS does not close PA-SR3.** Field re-run remains blocked until R1 unit closure recorded.

---

## Field gate (separate — post R1 unit closure)

Re-run `phase-a-sr3-field-first-run.ps1` **only after** R1-1…R1-5 + B1 regression PASS.

Frozen adjudication chain (unchanged from PA-SR3 run card):

```text
M01 SOURCE self-origin
  → PROFILE01_ORIGIN_EMIT factType=9 outcome=EMITTED
  → M02 WIRE_INGRESS factType=9 outcome=APPLIED
  → registry.member established
  → SHADOW MEMBER_MEDIA_READY(M01) outcome=APPLIED
```

Pre-bind `PRE_BIND_NOT_RETAINED factType=9` → PASS only on **product auto** post-bind
republication with same `sourceGeneration + factDigest` (B1-6). No manual replay.

Verdict model unchanged: `PASS` · `INVALID_*` · `FAIL_PA_SR3`.

| Step | B1-R1 field |
|------|-------------|
| PRE-G / PRE-I / PRE-J | Must remain green — else `INVALID_*` |
| **PA-SR3** | **Primary field gate** (re-run) |
| PA-SR4 … PA-SR6 | Observe only — **do not close** on R1 run |

---

## State transitions

### On B1-R1 implementation authorization (this document)

```text
PR-PA-SR-B1     CLOSED / UNIT-PROVEN · FIELD FAIL
B1-R1           IMPLEMENTATION AUTHORIZED
PA-SR3          VALID FAIL / RCA OPEN (field re-run blocked)
```

### On R1 unit closure (target)

```text
B1-R1           CLOSED / UNIT-PROVEN
PA-SR3          AUTHORIZED FOR FIELD RE-RUN
```

PA-SR3 remains **VALID FAIL** until the frozen field chain actually PASSes — unit closure does
**not** advance PA-SR3 verdict.

### On PA-SR3 field PASS (target)

```text
PR-PA-SR-B1     CLOSED / UNIT+FIELD-PROVEN
B1-R1           CLOSED / UNIT+FIELD-PROVEN
PA-SR3          CLOSED / FIELD-PROVEN
PA-SR4…SR6      NEXT / still not closed
```

### On PA-SR3 field FAIL after R1 (target)

```text
B1-R1           CLOSED / UNIT-PROVEN · FIELD FAIL (new RCA)
PA-SR3          VALID FAIL / RCA OPEN
```

Do **not** roll back B0, A1, J0, P1-C, P1-E on field FAIL.

---

## Sign-off record

| Item | Value |
|------|-------|
| **Authorized PR** | PR-PA-SR-B1-R1 |
| **Authorization date** | 2026-09-09 |
| **Prerequisite** | PR-PA-SR-B1 CLOSED / UNIT-PROVEN · B1-R1 design frozen |
| **Code status at sign-off** | Authorization document only — R1 impl not started |
| **Field** | Not authorized until R1 unit closure recorded |

---

## References

- B1-R1 design: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r1-origin-eligibility-continuation-design.md`
- B1 authorization: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-implementation-authorization.md`
- B1 unit closure: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-unit-closure.md`
- PA-SR3 field result: `meeting-product-integration-phase-a-sr3-field-result-20260909-121252.md`
- PA-SR3 run card: `meeting-product-integration-phase-a-sr3-field-run-card.md`
- PA-SR3 adjudication: `meeting-product-integration-phase-a-sr3-adjudication-criteria.md`
- Analog (obligation continuation): `meeting-profile01-p1-c-r1-post-bind-republish-obligation.md`
- Field log: `talkback/logs/phase-a-sr3-field-20260909-121252/`
