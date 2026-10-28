# Phase A Shadow Runtime — PR-PA-SR-B1-R2 SOURCE Post-Bind Publication Design

**Parent:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-implementation-authorization.md`  
**Triggered by:** PA-SR3 field FAIL — SOURCE post-bind publication lifecycle gap (RCA closed)  
**Field evidence:** `talkback/logs/phase-a-sr3-field-20260909-152252/` · `meeting-product-integration-phase-a-sr3-rca-source-chain-20260909-152252.md`  
**Prerequisite:** PR-PA-SR-B1 **CLOSED / UNIT-PROVEN** · B1-R1 **CLOSED / UNIT-PROVEN** · PA-SR3 RCA **CLOSED**  
**Status:** **DESIGN FROZEN** (2026-09-09) · **IMPLEMENTATION AUTHORIZED** — see implementation authorization doc

```text
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

**Authorizes (when implementation authorization follows):** narrow product fix — after host
observes authoritative peer post-bind consumption eligibility, replay **cached signed SOURCE bytes**
when pre-bind transport publication succeeded but post-bind delivery is incomplete.

**Does NOT authorize:** pre-bind SOURCE retention · ingress semantic change · generic retry worker ·
timer/polling · ICE-driven republication · reopening B1-R1 build eligibility · registry.publishMember
changes · PA-SR4…SR6 · ADR-0056 cutover.

---

## 1. Problem (field-proven — RCA closed)

PA-SR3 preconditions green; host SOURCE **BUILT + EMITTED**; peer never **APPLIED** factType=9.

```text
SOURCE built once (host)
  → pre-bind transport EMITTED (conference_invite_sent)
  → SourcePublicationLedger = published(M02)
  → peer PRE_BIND_NOT_RETAINED factType=9 (frozen — correct)
  → peer later post-bind consumption-eligible
  → MEDIA_KEY_PACKAGE: POST_BIND_EMITTED ✓
  → SOURCE: no post-bind hook; transport ledger blocks onEligiblePeers resend
  → no WIRE_INGRESS factType=9 APPLIED
  → MEMBER_MEDIA_READY only DEFERRED_NO_BINDING
```

**Not** B1-R1 (build eligibility — host build succeeded on this run).  
**Not** harness / runtime readiness (PRE-G/I/J PASS on `20260909-152252`).

### 1.1 Core contract (frozen — one sentence)

```text
SOURCE transport publication success
≠
peer post-bind consumption delivery satisfied
```

---

## 2. Frozen lifecycle (B1-R2 — single obligation path)

```text
SOURCE built once
  → pre-bind transport EMITTED
  → SourcePublicationLedger = published (transport send accepted)
  → peer later becomes post-bind consumption-eligible
  → consult separate SourcePostBindPublicationLedger
  → replay same cached signed SOURCE bytes
  → successful send marks post-bind satisfied
```

### 2.1 Ledger split (frozen — do not reuse `SourcePublicationLedger`)

| Ledger | Class | Meaning |
|--------|-------|---------|
| `SourcePublicationLedger` (existing) | Transport | Pre-bind / fanout transport send accepted for `(conferenceId, sourceGeneration, factDigest, peerModuleId)` |
| `SourcePostBindPublicationLedger` (new) | Consumption delivery | Post-bind consumption delivery obligation satisfied for full `SourcePostBindPublicationIdentity` |

**Do not** reinterpret existing transport `PUBLISHED` as peer consumption-ready.  
**Do not** extend `SourcePublicationLedger` with post-bind flags — semantic facts stay distinct (R2-HC2).

Analog: `MediaKeyPackagePublicationLedger` + `MediaKeyPackagePostBindPublicationLedger` (P1-C-R1), but SOURCE replays **byte-identical** cached bytes (R2-HC1 / B1-6), not fresh ciphertext.

### 2.2 Post-bind identity key (frozen)

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

**Purpose:** stale source replacement (generation / instance / membership epoch change) does not
incorrectly satisfy post-bind delivery for a superseded SOURCE artifact.

Equality / hash must include all fields. Session-scoped ledger buckets keyed by `sessionId` (same
pattern as package post-bind ledger).

### 2.3 Post-bind republication inputs (frozen)

Opens only when **all** hold on host:

| Gate | Source |
|------|--------|
| Built SOURCE exists | `sourceStateBySession[sessionId]` with cached `signedFactBytes` |
| Transport published | `SourcePublicationLedger.isPublished(sessionId, transportIdentity)` for matching `(conferenceId, sourceGeneration, factDigest, targetModuleId)` |
| Post-bind not yet satisfied | `!SourcePostBindPublicationLedger.isSatisfied(sessionId, postBindIdentity)` |
| Peer consumption-eligible | Authoritative product bind/admission fact (§3) |
| Self-origin only | `localModuleId == declaringModuleId` from cached SOURCE state |

**No rebuild.** Read `signedFactBytes` from `SourcePublicationState` only (R2-HC6 / R2-HC7).

---

## 3. Peer eligibility trigger (frozen — R2-HC3)

Post-bind SOURCE republication opens only from **existing product authoritative facts** on host.

**Primary trigger (field-proven seam):**

```text
onConferenceAcceptMediaSettled(sessionId, moduleId, …)
  → maybeRepublishSourceDeclarationAfterPeerBind(
       session, remoteModuleId,
       reason = "admission_ready_after_answer_settled"
     )
```

Same coordinator entry as P1-C-R1 `maybeRepublishMediaKeyPackageAfterPeerBind` — parallel hook,
not a merge into package bridge.

**Not authorized in B1-R2:**

- Timer / polling / generic retry worker (R2-HC5)
- ICE CONNECTED alone as trigger
- Raw signaling visibility without admission settle
- `onEligiblePeers` reinterpretation to bypass transport ledger
- Peer-side retention of pre-bind SOURCE (R2-HC4)
- Manual harness replay as normal path

Optional future triggers (e.g. `markMeetingProfile01MediaConnected`) are **out of scope** unless
added by separate ADR; B1-R2 ships with `admission_ready_after_answer_settled` only.

### 3.1 Bridge API (design)

```text
MeetingProfile01SourceOriginBridge.onPeerSourceConsumptionEligible(
  sessionId,
  snapshot,
  localModuleId,
  targetModuleId,
  publishToPeer: (moduleId, signedFactBytes) -> Boolean,
): SourcePostBindRepublishOutcome
```

Coordinator delegates send via existing `sendConferenceSignedFactToPeer` (same as `onEligiblePeers`).

---

## 4. Hard constraints (R2-HC1 … R2-HC7)

| Id | Constraint |
|----|------------|
| **R2-HC1** | Post-bind republication **MUST** reuse exact cached signed SOURCE bytes (`sourceStateBySession.signedFactBytes.copyOf()`). No re-sign, no canonical re-encode. |
| **R2-HC2** | Transport ledger and post-bind ledger are **distinct semantic facts**. Transport `PUBLISHED` does not imply post-bind satisfied. |
| **R2-HC3** | Post-bind trigger only from established product bind/admission event — **`admission_ready_after_answer_settled`** coordinator seam (§3). |
| **R2-HC4** | Pre-bind drop does **not** authorize retention on peer. `PRE_BIND_NOT_RETAINED factType=9` remains correct; recovery is host post-bind replay only. |
| **R2-HC5** | No timer / polling / generic retry worker. |
| **R2-HC6** | R2 does **not** reopen SOURCE build eligibility. B1-R1 remains authoritative for first build timing. Post-bind path requires existing `BUILT` state only. |
| **R2-HC7** | Successful post-bind send consumes **post-bind publication obligation only**; does not mutate `sourceGeneration`, `sourceInstanceId`, signature identity, or transport ledger semantics. |

---

## 5. Outcomes (design)

```text
enum SourcePostBindRepublishOutcome {
  EMITTED
  ALREADY_SATISFIED
  SKIPPED_NO_BUILT_SOURCE
  SKIPPED_NO_PRE_BIND_PUBLICATION
  SKIPPED_NOT_SELF_ORIGIN
  SKIPPED_NOT_IN_TOPOLOGY
  SKIPPED_STALE_IDENTITY
  SEND_FAILED
}
```

| Outcome | Post-bind ledger |
|---------|------------------|
| `EMITTED` | `markSatisfied` |
| `ALREADY_SATISFIED` | no-op |
| `SEND_FAILED` | **not** marked satisfied (R2-3) |
| `SKIPPED_*` | no-op |

Transport ledger: **unchanged** on post-bind path (already published at pre-bind).

---

## 6. Unit test matrix (R2-1 … R2-7)

| Id | Property | Required outcome |
|----|----------|------------------|
| **R2-1** | Pre-bind EMITTED → transport published → peer post-bind eligible | Same cached bytes emitted again; post-bind ledger satisfied |
| **R2-2** | Post-bind callback repeated | At most one successful post-bind publication (`ALREADY_SATISFIED` on duplicate) |
| **R2-3** | Post-bind send failure | Post-bind ledger **not** satisfied; later eligible event may retry same cached bytes |
| **R2-4** | Source generation (or identity tuple) changes | Old post-bind state does not satisfy new source; stale identity skipped |
| **R2-5** | Already post-bind satisfied | No duplicate replay |
| **R2-6** | Pre-bind SOURCE | Peer ingress path remains `PRE_BIND_NOT_RETAINED` — no retention added |
| **R2-7** | B1-6 preserved | All republication (pre-bind fanout + post-bind) remains byte-identical to cached artifact |

**Regression:** B1-1…B1-7, R1-1…R1-5 remain PASS. B0 fence unchanged.

**R2 unit PASS does not close PA-SR3.** Field re-run required after implementation + unit closure.

---

## 7. OBS (behavior-neutral — narrow fingerprint)

```text
PROFILE01_SOURCE_POST_BIND_REPUBLISH
  session=…
  conferenceId=…
  sourceModuleId=…
  target=…
  sourceGeneration=…
  factDigest=…
  membershipVersion=…
  mediaKeyEpoch=…
  reason=admission_ready_after_answer_settled
  outcome=ATTEMPTED|EMITTED|FAILED|ALREADY_SATISFIED
```

Mapping:

| Bridge outcome | OBS `outcome` |
|----------------|---------------|
| Enter hook with gates passing, before send | `ATTEMPTED` (optional — may coalesce with EMITTED) |
| `EMITTED` | `EMITTED` |
| `SEND_FAILED` | `FAILED` |
| `ALREADY_SATISFIED` | `ALREADY_SATISFIED` |

Existing `PROFILE01_ORIGIN_EMIT … outcome=EMITTED` (transport) **unchanged**.  
Field adjudication may use post-bind OBS as corroboration; frozen PA-SR3 chain still keys off
`PROFILE01_ORIGIN_EMIT factType=9` EMITTED + peer `WIRE_INGRESS APPLIED` (same bytes → same digest).

No `sourceInstanceId` / SSRC secret material in logs beyond existing B1 OBS policy.

---

## 8. Field gate (unchanged PA-SR3 chain + expected new evidence)

After B1-R2 unit closure, re-run `phase-a-sr3-field-first-run.ps1`.

**Target observable sequence:**

```text
pre-bind SOURCE EMITTED (transport)
  → peer PRE_BIND_NOT_RETAINED factType=9
  → PROFILE01_SOURCE_POST_BIND_REPUBLISH outcome=EMITTED target=M02
  → (optional) second PROFILE01_ORIGIN_EMIT factType=9 EMITTED same factDigest
  → peer WIRE_INGRESS factType=9 outcome=APPLIED
  → SHADOW MEMBER_MEDIA_READY module=M01 outcome=APPLIED
```

Frozen adjudication chain (`meeting-product-integration-phase-a-sr3-adjudication-criteria.md`):

```text
M01 SOURCE self-origin → BUILT
  → EMITTED target=M02
  → M02 WIRE_INGRESS factType=9 APPLIED
  → registry.member proxy → MEMBER_MEDIA_READY APPLIED
```

Class 2 (pre-bind republication): satisfied when post-bind auto EMITTED shares
`sourceGeneration + factDigest` with SR3-A — B1-R2 is the intended product mechanism.

Verdict model unchanged: `PASS` · `INVALID_*` · `FAIL_PA_SR3`.

---

## 9. Implementation scope

### IN

```text
- SourcePostBindPublicationIdentity (new)
- SourcePostBindPublicationLedger (new — not reuse SourcePublicationLedger)
- MeetingProfile01SourceOriginBridge.onPeerSourceConsumptionEligible
- TalkbackCoordinator.maybeRepublishSourceDeclarationAfterPeerBind
  → onConferenceAcceptMediaSettled (admission_ready_after_answer_settled)
- PROFILE01_SOURCE_POST_BIND_REPUBLISH OBS
- Unit tests R2-1 … R2-7
```

### OUT

```text
- Changes to SourcePublicationLedger transport semantics
- Changes to onEligiblePeers pre-bind fanout behavior
- B1-R1 eligibility obligation store / continuation triggers
- Peer pre-bind SOURCE retention (B1-5 / wire ingress)
- Ingress validate/apply for factType=9
- registry.publishMember semantic changes
- Timer, polling, generic retry worker
- ICE CONNECTED as republication trigger
- PA-SR4 … PA-SR6 fixes
- J0 / P1-C / P1-E changes
- ADR-0056 / Meeting media cutover
- Manual replay / harness-only closure of PA-SR3
```

### Implementation map (reference — not started)

| Component | Change |
|-----------|--------|
| `SourcePostBindPublicationLedger.kt` | New post-bind consumption delivery ledger |
| `SourcePostBindPublicationIdentity.kt` | Frozen identity tuple |
| `MeetingProfile01SourceOriginBridge.kt` | `onPeerSourceConsumptionEligible` + OBS |
| `TalkbackCoordinator.kt` | Parallel hook beside `maybeRepublishMediaKeyPackageAfterPeerBind` |
| `MeetingProfile01SourcePostBindRepublicationTest.kt` | R2-1 … R2-7 |

---

## 10. State transitions

### On B1-R2 design freeze (this document)

```text
B1-R2           DESIGN FROZEN
PA-SR3          VALID FAIL / RCA CLOSED
```

### On B1-R2 implementation authorization (signed 2026-09-09)

```text
B1-R2           IMPLEMENTATION AUTHORIZED
PA-SR3          VALID FAIL / FIELD RE-RUN BLOCKED
```

See: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r2-implementation-authorization.md`

### On R2 unit closure (target)

```text
B1-R2           CLOSED / UNIT-PROVEN
PA-SR3          AUTHORIZED FOR FIELD RE-RUN
```

### On PA-SR3 field PASS (target)

```text
PR-PA-SR-B1     CLOSED / UNIT+FIELD-PROVEN
PA-SR3          CLOSED / FIELD-PROVEN
PA-SR4…SR6      NEXT / still not closed
```

---

## 11. References

- RCA: `meeting-product-integration-phase-a-sr3-rca-source-chain-20260909-152252.md`
- B1-R1 design: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r1-origin-eligibility-continuation-design.md`
- B1 authorization / unit: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-implementation-authorization.md` · `…-unit-closure.md`
- PACKAGE analog: `meeting-profile01-p1-c-r1-post-bind-republish-obligation.md`
- PA-SR3 adjudication: `meeting-product-integration-phase-a-sr3-adjudication-criteria.md`
- PA-SR3 run card: `meeting-product-integration-phase-a-sr3-field-run-card.md`
- Field log: `talkback/logs/phase-a-sr3-field-20260909-152252/`
