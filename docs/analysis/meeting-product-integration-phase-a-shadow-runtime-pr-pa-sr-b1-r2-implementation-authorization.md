# Phase A Shadow Runtime — PR-PA-SR-B1-R2 Implementation Authorization

**PR:** `PR-PA-SR-B1-R2` — SOURCE Post-Bind Republication Fence  
**Design:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r2-source-post-bind-publication-design.md`  
**RCA:** `meeting-product-integration-phase-a-sr3-rca-source-chain-20260909-152252.md`  
**Prerequisite:** PR-PA-SR-B1 **CLOSED / UNIT-PROVEN** · B1-R1 **CLOSED / UNIT-PROVEN**  
**Signed:** 2026-09-09  
**Status:** **IMPLEMENTATION AUTHORIZED** (B1-R2 scope only)

```text
Prerequisite
  PR-PA-SR-B1     CLOSED / UNIT-PROVEN · FIELD FAIL (publication lifecycle)
  B1-R1           CLOSED / UNIT-PROVEN
  B1-R2 design    DESIGN FROZEN
  PA-SR3 RCA      CLOSED

Authorization status
  IMPLEMENTATION AUTHORIZED only for PR-PA-SR-B1-R2 scope

PR-PA-SR-B1     CLOSED / UNIT-PROVEN · FIELD FAIL (publication lifecycle)
B1-R1           CLOSED / UNIT-PROVEN
B1-R2           IMPLEMENTATION AUTHORIZED
PA-SR3          VALID FAIL / FIELD RE-RUN BLOCKED

B0 / A1 / J0    UNCHANGED_CLOSED
P1-C / P1-E     UNCHANGED_CLOSED
PA-SR4…SR6      HOLD
ADR-0056        KEEP
cutover         NOT AUTHORIZED
```

B0 signer/sender fence, B1-HC1…HC3, B1-R1 eligibility continuation, and B1-6 byte-identical
republication remain **closed** — not re-litigated in B1-R2.

**Do not re-run PA-SR3** until R2 unit closure is recorded.

---

## Goal (authorized)

```text
pre-bind SOURCE transport EMITTED
  → peer not yet consumption-eligible (PRE_BIND_NOT_RETAINED — frozen correct)
  → peer becomes post-bind consumption-eligible (admission_ready_after_answer_settled)
  → host replays cached signed SOURCE bytes byte-identically
  → successful send marks post-bind delivery satisfied
  → peer may WIRE_INGRESS factType=9 APPLIED
```

B1-R2 closes the PA-SR3 field-proven **post-bind publication lifecycle gap** only. It does **not**
change SOURCE build eligibility (B1-R1), signing, identity semantics, ingress apply, or
`registry.publishMember` contracts.

**Frozen core contract:**

```text
SOURCE transport publication success
≠
peer post-bind consumption delivery satisfied
```

---

## Authorized implementation scope

### AUTHORIZED

```text
PR-PA-SR-B1-R2
SOURCE Post-Bind Republication Fence

- SourcePostBindPublicationIdentity
- SourcePostBindPublicationLedger
- existing cached SOURCE artifact lookup (sourceStateBySession.signedFactBytes)
- onConferenceAcceptMediaSettled
    → maybeRepublishSourceDeclarationAfterPeerBind
- MeetingProfile01SourceOriginBridge.onPeerSourceConsumptionEligible
- byte-identical post-bind resend (cached bytes only)
- success-only markSatisfied on post-bind ledger
- PROFILE01_SOURCE_POST_BIND_REPUBLISH OBS
- unit tests R2-1 … R2-7
- B1 + B1-R1 regression (B1-1 … B1-7, R1-1 … R1-5, B0 fence)
```

### NOT AUTHORIZED

```text
- SOURCE rebuild / re-sign / canonical re-encode on post-bind path
- sourceGeneration mutation
- sourceInstanceId / SSRC / signature identity changes on post-bind path
- B1-R1 obligation store or continuation trigger changes
- Profile01 ingress validate/apply changes for factType=9
- pre-bind SOURCE retention on peer (B1-5 frozen)
- timer / polling / generic retry worker
- ICE CONNECTED as republication trigger
- onEligiblePeers transport-ledger bypass or semantic change
- MEDIA_KEY_PACKAGE lifecycle changes (P1-C-R1 closed)
- PA-SR4 … PA-SR6 fixes
- ADR-0056 / Meeting media cutover
- manual / harness replay to close PA-SR3
- PA-SR3 field re-run before R2 unit closure
```

---

## Hard constraints (non-negotiable)

### R2-HC1 — Byte-identical replay (inherited B1-6)

```text
Post-bind republication MUST send sourceStateBySession[sessionId].signedFactBytes.copyOf()
with no re-sign, no canonical re-encode, no sourceGeneration bump.
```

### R2-HC2 — Distinct ledger semantics

```text
SourcePublicationLedger (transport) and SourcePostBindPublicationLedger (consumption delivery)
are independent facts.

Transport PUBLISHED MUST NOT imply post-bind satisfied.
Post-bind satisfied MUST NOT clear or reinterpret transport ledger.
```

### R2-HC3 — Product bind/admission trigger only

```text
Post-bind republication opens ONLY from:
  onConferenceAcceptMediaSettled
    → maybeRepublishSourceDeclarationAfterPeerBind
    → reason = admission_ready_after_answer_settled

No timer, poll, ICE-only, or generic retry worker.
```

### R2-HC4 — No pre-bind retention

```text
Peer PRE_BIND_NOT_RETAINED factType=9 remains correct and frozen.
Recovery is host post-bind replay only — not peer retention.
```

### R2-HC5 — No build reopen (B1-R1 authoritative)

```text
Post-bind path requires existing SourcePublicationState (BUILT artifact).
MUST NOT call onLocalConferenceSourceCommitted to rebuild SOURCE.
MUST NOT mutate sourceGeneration, sourceInstanceId, or signature identity on post-bind send.
```

### R2-HC6 — Post-bind callback ≠ unconditional resend (authorization hard gate)

```text
onPeerSourceConsumptionEligible MUST NOT send on callback entry alone.

Before send, MUST:
  1. Load current cached SOURCE artifact from sourceStateBySession[sessionId]
  2. Construct full SourcePostBindPublicationIdentity from current session/source state
  3. Verify identity matches current authoritative source identity:
       conferenceId, sourceModuleId, sourceGeneration, sourceInstanceId,
       targetModuleId, factDigest, membershipVersion, mediaKeyEpoch
  4. Verify transport ledger shows pre-bind published for matching transport identity
  5. Verify post-bind ledger NOT already satisfied for this post-bind identity

If identity drift / stale generation / superseded source → SKIPPED_STALE_IDENTITY (discard).
MUST NOT send old-generation bytes after source replacement.
```

### R2-HC7 — FAILED keeps obligation open (authorization hard gate)

```text
SourcePostBindPublicationLedger.markSatisfied ONLY when publishToPeer returns success.

On SEND_FAILED:
  → post-bind ledger MUST remain unsatisfied
  → obligation stays open for a later legitimate product event
  → same cached bytes may be retried on next admission_ready_after_answer_settled
  → MUST NOT introduce timer / polling / generic retry worker to retry failures

FAILED OBS outcome=FAILED is diagnostic only — not satisfaction.
```

### R2-HC8 — Success consumes post-bind obligation only

```text
Successful post-bind send marks post-bind satisfied for the verified identity only.
Does not consume B1-R1 eligibility obligations, does not mutate transport ledger,
does not trigger rebuild.
```

---

## Post-bind identity (frozen)

```text
SourcePostBindPublicationIdentity
  conferenceId
  sourceModuleId
  sourceGeneration
  sourceInstanceId
  targetModuleId
  factDigest
  membershipVersion
  mediaKeyEpoch
```

Ledger bucket: `sessionId` → set of satisfied identities (same pattern as package post-bind ledger).

Transport identity for pre-bind gate (existing, unchanged):

```text
SourcePublicationIdentity
  conferenceIdHex, sourceGeneration, factDigestHex, peerModuleId
```

Post-bind identity is a **strict superset** — generation/instance/membership epoch mismatch → stale skip.

---

## Coordinator wiring (frozen)

Parallel to P1-C-R1 — do not merge into package bridge.

```text
onConferenceAcceptMediaSettled(sessionId, moduleId, …)
  → maybeRepublishMediaKeyPackageAfterPeerBind(...)   // existing — unchanged
  → maybeRepublishSourceDeclarationAfterPeerBind(      // new — B1-R2
       session, remoteModuleId,
       reason = "admission_ready_after_answer_settled"
     )
  → bridge.onPeerSourceConsumptionEligible(...)
  → sendConferenceSignedFactToPeer (existing transport path)
```

Frozen `reason` string: `admission_ready_after_answer_settled`.

---

## OBS (authorized — behavior-neutral)

```text
PROFILE01_SOURCE_POST_BIND_REPUBLISH
  session=… conferenceId=… sourceModuleId=… target=…
  sourceGeneration=… factDigest=… membershipVersion=… mediaKeyEpoch=…
  reason=admission_ready_after_answer_settled
  outcome=ATTEMPTED|EMITTED|FAILED|ALREADY_SATISFIED
```

| Bridge result | OBS `outcome` | Post-bind ledger |
|---------------|---------------|------------------|
| Gates pass, before send | `ATTEMPTED` (optional) | unchanged |
| Send success | `EMITTED` | `markSatisfied` |
| Send failure | `FAILED` | **open** (R2-HC7) |
| Already satisfied | `ALREADY_SATISFIED` | unchanged |

Existing `PROFILE01_ORIGIN_EMIT factType=9 outcome=EMITTED` (transport) unchanged.

---

## Unit test matrix (merge gate — R2-1 … R2-7)

| Id | Property | Required outcome |
|----|----------|----------------|
| **R2-1** | Pre-bind EMITTED → transport published → peer post-bind eligible | Same cached bytes emitted again; post-bind ledger satisfied |
| **R2-2** | Post-bind callback repeated | At most one successful post-bind publication |
| **R2-3** | Post-bind send failure | Post-bind ledger **not** satisfied; later same product event may retry same cached bytes |
| **R2-4** | Source generation / identity tuple changes | Old post-bind state does not satisfy new source; stale identity skipped — **no send** |
| **R2-5** | Already post-bind satisfied | No duplicate replay (`ALREADY_SATISFIED`) |
| **R2-6** | Pre-bind SOURCE on peer | `PRE_BIND_NOT_RETAINED` remains — no retention added |
| **R2-7** | B1-6 preserved | All republication (pre-bind + post-bind) byte-identical to cached artifact |

**Regression:** B1-1 … B1-7, R1-1 … R1-5, B0 fence remain PASS.

**R2 unit PASS does not close PA-SR3.** Field re-run remains blocked until R2 unit closure recorded.

---

## Field gate (separate — post R2 unit closure only)

Re-run `phase-a-sr3-field-first-run.ps1` **only after** R2-1…R2-7 + B1/R1 regression PASS.

**Do not run PA-SR3 during B1-R2 implementation.**

Target observable sequence:

```text
pre-bind SOURCE EMITTED
  → peer PRE_BIND_NOT_RETAINED factType=9
  → PROFILE01_SOURCE_POST_BIND_REPUBLISH outcome=EMITTED target=M02
  → peer WIRE_INGRESS factType=9 outcome=APPLIED
  → SHADOW MEMBER_MEDIA_READY module=M01 outcome=APPLIED
```

Frozen adjudication chain unchanged (`meeting-product-integration-phase-a-sr3-adjudication-criteria.md`).

| Step | B1-R2 field |
|------|-------------|
| PRE-G / PRE-I / PRE-J | Must remain green — else `INVALID_*` |
| **PA-SR3** | **Primary field gate** (re-run after unit closure) |
| PA-SR4 … PA-SR6 | Observe only — **do not close** on R2 run |

---

## State transitions

### On B1-R2 implementation authorization (this document)

```text
B1-R2           IMPLEMENTATION AUTHORIZED
PA-SR3          VALID FAIL / FIELD RE-RUN BLOCKED
```

### On R2 unit closure (target)

```text
B1-R2           CLOSED / UNIT-PROVEN
PA-SR3          AUTHORIZED FOR FIELD RE-RUN
```

PA-SR3 remains **VALID FAIL** until the frozen field chain actually PASSes — unit closure does
**not** advance PA-SR3 verdict.

### On PA-SR3 field PASS (target)

```text
PR-PA-SR-B1     CLOSED / UNIT+FIELD-PROVEN
B1-R2           CLOSED / UNIT+FIELD-PROVEN
PA-SR3          CLOSED / FIELD-PROVEN
PA-SR4…SR6      NEXT / still not closed
```

### On PA-SR3 field FAIL after R2 (target)

```text
B1-R2           CLOSED / UNIT-PROVEN · FIELD FAIL (new RCA)
PA-SR3          VALID FAIL / RCA OPEN
```

Do **not** roll back B0, A1, J0, B1-R1, P1-C, P1-E on field FAIL.

---

## Sign-off record

| Item | Value |
|------|-------|
| **Authorized PR** | PR-PA-SR-B1-R2 |
| **Authorization date** | 2026-09-09 |
| **Prerequisite** | PR-PA-SR-B1 CLOSED / UNIT-PROVEN · B1-R1 CLOSED / UNIT-PROVEN · B1-R2 design frozen · PA-SR3 RCA closed |
| **Code status at sign-off** | Authorization document only — R2 impl not started |
| **Field** | **Blocked** until R2 unit closure recorded |

---

## References

- B1-R2 design: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r2-source-post-bind-publication-design.md`
- RCA: `meeting-product-integration-phase-a-sr3-rca-source-chain-20260909-152252.md`
- B1-R1 authorization: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r1-implementation-authorization.md`
- B1 authorization / unit: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-implementation-authorization.md` · `…-unit-closure.md`
- PACKAGE analog: `meeting-profile01-p1-c-r1-post-bind-republish-obligation.md`
- PA-SR3 adjudication: `meeting-product-integration-phase-a-sr3-adjudication-criteria.md`
- PA-SR3 run card: `meeting-product-integration-phase-a-sr3-field-run-card.md`
- Field log: `talkback/logs/phase-a-sr3-field-20260909-152252/`
