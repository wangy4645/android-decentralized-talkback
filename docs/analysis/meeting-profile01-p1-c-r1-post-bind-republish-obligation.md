# Meeting Profile01 — P1-C-R1 Post-Bind MEDIA_KEY_PACKAGE Republish Obligation

**Parent:** `meeting-profile01-media-key-package-origin-path1-design-slice.md`  
**Triggered by:** P1-C first-run INVALID — product delivery obligation gap (RCA valid)  
**Prerequisite:**  
- P1-C unit (origin/publication) — **CLOSED / UNIT PASS**  
- P1-C first-run — **ATTEMPTED / INVALID** (evidence valid)  
**Status:** **CLOSED / FIELD-PROVEN** (2026-09-09) — **UNIT PASS (R1–R7)** · **field PASS** `20260909-101752` · `product_auto_POST_BIND_EMITTED`

```text
P1-C package implementation     UNIT-PROVEN
P1-C field                      CLOSED / FIELD-PROVEN
  log: talkback/logs/p1c-package-field-20260909-101752/
  closure: meeting-profile01-p1-c-field-closure.md

P1-C-R1                         CLOSED / FIELD-PROVEN
R1 product hook                 FIELD-PROVEN (product auto POST_BIND_EMITTED)
Field harness B (ledger clear)  NOT NORMAL PATH
Meeting J0                      AUTHORIZABLE / NEXT
```

**Authorizes:** narrow product fix — after host observes authoritative peer package-consumption
eligibility, republish **fresh** `MEDIA_KEY_PACKAGE` when pre-bind transport publish succeeded
but post-bind delivery is incomplete.

**Does NOT authorize:** factType=11 pre-bind retention · P1-E changes · generic retry policy ·
Meeting J0 · replay of prior ciphertext bytes · modification of initial `PUBLISHED` ledger semantics.

---

## 1. Problem (field-proven)

```text
Host emits factType=11 (invite-time)
→ peer not yet bound
→ PRE_BIND_NOT_RETAINED (frozen contract — correct)
→ host publicationLedger marks PUBLISHED (transport send accepted)
→ peer permanently loses package for this epoch
```

This is a **product delivery obligation gap**, not a test harness defect.

---

## 2. Frozen semantics

```text
initial package publication  !=  delivery completion

recipient becomes package-consumption eligible (authoritative admission fact)
  → if current package identity pre-bind-published
    AND NOT post-bind-delivered for this recipient
  → build fresh ciphertext/package
  → send
  → only transport success marks post-bind delivery complete
```

### 2.1 Ledger split (frozen)

| Ledger | Meaning |
|--------|---------|
| `MediaKeyPackagePublicationLedger` (existing) | Pre-bind transport send accepted |
| `MediaKeyPackagePostBindPublicationLedger` (new) | Post-bind delivery obligation complete |

Do **not** reinterpret existing `PUBLISHED` as consumption-ready.

### 2.2 Identity key (frozen)

```text
conferenceId
mediaKeyEpoch
recipientModuleId
recipientEstablishmentKeyVersion
membershipFactDigest
```

Retry/republish always builds **fresh** ciphertext — no byte replay.

---

## 3. Peer eligibility trigger (frozen)

Post-bind republish opens only from **existing product authoritative facts** on the host:

```text
ConferenceAdmissionPhase.READY
  after answer committed / media edge settled

retry path:
  peer ICE connected → markMeetingProfile01MediaConnected
```

**Not** from raw signaling packet visibility alone.

Coordinator hook: `maybeRepublishMediaKeyPackageAfterPeerBind(session, remoteModuleId, reason)`
→ `MeetingProfile01MediaKeyPackageOriginBridge.onPeerPackageConsumptionEligible(...)`.

Field debug broadcast `P1C_REPUBLISH_AFTER_BIND` delegates to the same product hook (no ledger clear).

---

## 4. Exit criteria (R1–R7)

| ID | Criterion | Unit test |
|----|-----------|-----------|
| R1 | Pre-bind send success + peer not bound → post-bind **not** complete | `r1_preBindPublished_peerNotBound_doesNotMarkPostBindDelivered` |
| R2 | Peer eligible → exactly one post-bind republish obligation | `r2_peerEligible_exactlyOnePostBindRepublish` |
| R3 | Republish uses current epoch/digest/key version + fresh ciphertext | `r3_republishUsesFreshCiphertext` |
| R4 | Send failure → obligation open; same hook can retry | `r4_sendFailure_obligationRemainsOpen` |
| R5 | Duplicate eligible event → at most one successful post-bind publication | `r5_duplicateEligibleEvent_atMostOneSuccessfulPostBindPublication` |
| R6 | Session/epoch identity changed → stale obligation discarded | `r6_staleIdentityAfterCreationChange_skipsRepublish` |
| R7 | No factType=11 pre-bind retention added | contract + `r7_noPreBindPublication_skipsPostBindRepublish` |

---

## 5. OBS (behavior-neutral)

```text
PROFILE01_MEDIA_KEY_PACKAGE_POST_BIND_REPUBLISH
  conferenceId mediaKeyEpoch recipientModuleId
  recipientEstablishmentKeyVersion membershipFactDigest
  publicationOutcome=EMITTED|SEND_FAILED|BUILD_REJECTED

PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN
  publicationOutcome=POST_BIND_EMITTED|POST_BIND_SEND_FAILED
```

No key material in logs.

---

## 6. Implementation map

| Component | Change |
|-----------|--------|
| `MediaKeyPackagePostBindPublicationLedger.kt` | New post-bind delivery ledger |
| `MeetingProfile01MediaKeyPackageOriginBridge.kt` | `onPeerPackageConsumptionEligible` + OBS |
| `TalkbackCoordinator.kt` | Admission / ICE hooks + debug harness delegates to product path |
| `MeetingProfile01MediaKeyPackagePostBindRepublishTest.kt` | R1–R7 unit tests |

---

## 7. Next authorized step

```text
P1-C field re-run (same C-F1…C-F7 adjudicator)
  → product post-bind republish should close C-F3 without harness ledger clear

Meeting J0                  AUTHORIZABLE / NEXT (separate run card when executing)
```
