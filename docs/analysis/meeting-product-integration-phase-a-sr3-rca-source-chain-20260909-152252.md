# RCA — PA-SR3 SOURCE Chain (`RCA_PA_SR3_SOURCE_CHAIN`)

**Field run:** `talkback/logs/phase-a-sr3-field-20260909-152252`  
**Verdict:** `FAIL_PA_SR3` (valid product fail — preconditions PASS)  
**Date:** 2026-09-09  
**Scope:** SOURCE post-bind publication lifecycle only. **No code change in this document.**

---

## Failure locus (frozen)

```text
host SOURCE
  → BUILT
  → EMITTED (pre-bind, conference_invite_sent)
  → peer PRE_BIND_NOT_RETAINED factType=9
  → no automatic post-bind republication
  → no factType=9 WIRE_INGRESS APPLIED on peer
  → no registry.member
  → MEMBER_MEDIA_READY only DEFERRED_NO_BINDING
```

**Not** B1-R1 eligibility continuation (host build succeeded; peer-side R1-B is orthogonal to host→peer delivery).

**Not** harness / runtime readiness (PRE-G/I/J PASS; service zombie cleared on this run).

---

## Correlated identity

| Key | Value |
|-----|-------|
| sessionId | `f90f68fd-3951-4870-b16c-a2df6dbba1d3` |
| conferenceId | `5a78378b802eabbd0cebb0ef852068dc` |
| senderModuleId | `M01` |
| sourceGeneration | `1` |
| factDigest | `a334be640540d3aca075f134afa16b0630f6999891270893716eb2819125f21c` |
| republicationRequired | `true` |
| republicationObserved | `false` |

---

## Timeline (authoritative log order)

| Time (local) | Device | Event |
|--------------|--------|-------|
| 15:26:29.632 | M01 | `PROFILE01_ORIGIN_EMIT factType=9 outcome=BUILT` (`r1a_host_creation_projected`) |
| 15:26:29.899 | M01 | `PROFILE01_ORIGIN_EMIT factType=9 target=M02 outcome=EMITTED` (`conference_invite_sent`) |
| 15:26:30.957 | M02 | `PRE_BIND_NOT_RETAINED factType=9 reason=UNAUTHORIZED_FACT_TYPE` |
| 15:26:41.235 | M01 | `PROFILE01_MEDIA_KEY_PACKAGE_POST_BIND_REPUBLISH … target=M02 outcome=EMITTED` |
| 15:26:41.235 | M01 | `PROFILE01_PACKAGE_POST_BIND_REPUBLISH reason=admission_ready_after_answer_settled` |
| 15:26:41.544 | M01 | `MEMBER_MEDIA_READY module=M02 outcome=DEFERRED_NO_BINDING` |
| — | M02 | **No** `WIRE_INGRESS factType=9 outcome=APPLIED` for M01 SOURCE |
| — | M01 | **No** second `PROFILE01_ORIGIN_EMIT factType=9 … EMITTED` after peer bind |

M01 log contains **exactly one** factType=9 EMITTED line (pre-bind). MEDIA_KEY_PACKAGE republished at peer bind; SOURCE did not.

---

## RCA questions (four)

### Q1 — After host `ORIGIN_EMITTED`, does B1 per-peer ledger mark M02 “published” so later eligible-peer scans skip resend?

**Yes — confirmed.**

`MeetingProfile01SourceOriginBridge.onEligiblePeers` marks `SourcePublicationLedger` on successful `publishToPeer`:

```431:443:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/MeetingProfile01SourceOriginBridge.kt
                if (publicationLedger.isPublished(sessionId, identity)) {
                    return@forEach
                }
                // ...
                if (publishToPeer(peerModuleId, cachedBytes)) {
                    publicationLedger.markPublished(sessionId, identity)
                    state.publishedToPeers.add(peerModuleId)
```

Field: pre-bind `conference_invite_sent` produced `outcome=EMITTED target=M02`. Ledger identity is keyed by `(conferenceId, sourceGeneration, factDigest, peerModuleId)` — identical bytes on replay (B1-6). Any subsequent `onEligiblePeers` call for M02 returns early at `isPublished` → **no resend**.

`onEligiblePeers` does **not** log `SKIPPED_NO_PENDING_PUBLICATION`; silence after first EMITTED is expected when ledger blocks.

---

### Q2 — After `PRE_BIND_NOT_RETAINED factType=9`, which product event should trigger SOURCE post-bind republication; why did it not fire or why was it eaten?

**Expected product event (by parity with MEDIA_KEY_PACKAGE):** peer admission / media settle on host after remote answer — `onConferenceAcceptMediaSettled` → post-bind republication hook.

**MEDIA_KEY_PACKAGE (works):**

```11527:11532:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        conferenceAdmissionTracker.markReadyIfAbsent(conferenceAdmissionKey(session.id, moduleId))
        maybeRepublishMediaKeyPackageAfterPeerBind(
            session = session,
            remoteModuleId = moduleId,
            reason = "admission_ready_after_answer_settled",
        )
```

Field at `15:26:41.235`: `PROFILE01_PACKAGE_POST_BIND_REPUBLISH reason=admission_ready_after_answer_settled target=M02 outcome=EMITTED`.

**SOURCE (missing):** No `maybeRepublishSourceAfterPeerBind` (or equivalent). `publishSourceDeclarationToEligiblePeers` is **not** called from `onConferenceAcceptMediaSettled`.

SOURCE publication triggers today:

| Trigger | Calls `publishSourceDeclarationToEligiblePeers` |
|---------|---------------------------------------------------|
| `conference_invite_sent` | Yes (via `replayProfile01CreationToEligiblePeers`) |
| `membership_invite_no_media` | Yes |
| `media_supplement_ready` | Yes |
| `r1a_host_creation_projected` | Yes |
| `r1b_peer_creation_applied` | Yes (peer-local build path; not host→peer post-bind) |
| `admission_ready_after_answer_settled` | **No** |

**Not eaten by gate** — never scheduled. Even if a second `onEligiblePeers` ran post-bind, ledger would suppress it (Q1).

B1-R1 continuation re-evaluates **build eligibility** only (`maybeCommitAndPublishLocalConferenceSource`); host SOURCE was already `BUILT` → `ALREADY_BUILT`. R1-B does not reopen **transport publication** after pre-bind drop.

---

### Q3 — Does SOURCE only model “first build + replay to newly eligible peers” without pre-bind vs post-bind consumption distinction?

**Yes — confirmed lifecycle gap vs P1-C-R1 MEDIA_KEY_PACKAGE.**

MEDIA_KEY_PACKAGE uses **two ledgers**:

- `MediaKeyPackagePublicationLedger` — transport send accepted
- `MediaKeyPackagePostBindPublicationLedger` — post-bind consumption delivery complete

`MediaKeyPackagePostBindPublicationLedger` docstring:

> Separate from publication ledger: initial transport send accepted does not imply the recipient could consume the package.

`onPeerPackageConsumptionEligible` requires pre-bind publication **and** post-bind not yet delivered before republishing.

SOURCE uses **one ledger** (`SourcePublicationLedger`) with no post-bind consumption phase. `onEligiblePeers` treats first successful send as terminal for that `(generation, digest, peer)` tuple.

Unit test `b1_6_postBindRepublication_byteIdenticalSignedBytes` proves byte-identical multi-peer fanout in a **single** `onEligiblePeers` invocation when peers are already eligible — **not** pre-bind drop + post-bind recovery.

B1-R1 design explicitly left publication path unchanged:

> Publication / republication path unchanged: `onEligiblePeers` + `SourcePublicationLedger`.

Field adjudication criteria Class 2 already require post-bind auto EMITTED when `PRE_BIND_NOT_RETAINED factType=9` — product does not satisfy that contract.

---

### Q4 — After peer bind / CREATION APPLIED, does host observe a `SOURCE publication eligibility` event?

**No — lifecycle observability gap on host.**

| Fact | MEDIA_KEY_PACKAGE | SOURCE |
|------|-------------------|--------|
| Post-bind republish OBS | `PROFILE01_MEDIA_KEY_PACKAGE_POST_BIND_REPUBLISH` | **Absent** |
| Coordinator hook at `admission_ready_after_answer_settled` | `maybeRepublishMediaKeyPackageAfterPeerBind` | **None** |
| Second EMITTED after peer bind | Yes (field) | **No** (field) |

Peer bind also runs `drainRetainedAfterSessionBind` for wire ingress — irrelevant for factType=9 because pre-bind bytes were **not** retained (`UNAUTHORIZED_FACT_TYPE`).

Peer-side `PROFILE01_SOURCE_ELIGIBILITY_CONTINUATION action=RETAINED trigger=r1b_peer_creation_applied` on M02 is **peer self-origin** (`NOT_IN_ROSTER`), not host→peer republication — separate track, not SR3-B failure.

---

## Confirmed hypothesis

```text
send(pre-bind) success
  → SourcePublicationLedger.markPublished(M02)
  → peer PRE_BIND_NOT_RETAINED (no retention)
  → peer bind / admission_ready_after_answer_settled
  → MEDIA_KEY_PACKAGE: onPeerPackageConsumptionEligible → POST_BIND_EMITTED
  → SOURCE: no post-bind hook; onEligiblePeers not re-invoked with ledger bypass
  → ledger.isPublished(M02) == true
  → no resend
  → FAIL_PA_SR3
```

**Root cause class:** SOURCE publication lifecycle treats **transport send success** as **consumption-eligible delivery complete**, without a post-bind republication fence analogous to P1-C-R1.

---

## Distinction from prior field fail (121252)

| Run | Failure layer |
|-----|----------------|
| `20260909-121252` | Build eligibility — `NO_BUILT_SOURCE` / `MEMBERSHIP_NOT_CONVERGED` / peer `NOT_IN_ROSTER` before CREATION |
| `20260909-152252` | Publication lifecycle — host **BUILT+EMITTED**, peer pre-bind drop, **no post-bind republication** |

B1-R1 addressed the first; this RCA is a **different fault layer**.

---

## Recommended fix direction (design only — B1-R2)

Narrow **SOURCE post-bind republication fence** — not B1-R1 rollback:

```text
pre-bind transport publication != post-bind consumable publication
```

Properties (aligned with user freeze):

- Distinguish transport-published vs post-bind consumption-eligible for each peer identity
- On peer consumption eligibility (same trigger as MEDIA_KEY_PACKAGE: `admission_ready_after_answer_settled`), guarantee ≥1 post-bind publication if pre-bind was sent but peer never APPLIED
- Replay **same cached** `signedFactBytes` (B1-6 / byte-identical)
- No pre-bind retention, timer, ICE truth, generic retry, or ingress semantic change
- Minimal OBS: `PROFILE01_SOURCE_POST_BIND_REPUBLISH` (mirror package naming)

**Out of scope for B1-R2:** membership gate bypass, registry.publishMember changes, PA-SR4…SR6, ADR-0056 cutover.

---

## Status board (frozen)

```text
B1-R1           CLOSED / UNIT-PROVEN
PA-SR3          VALID FAIL / RCA OPEN → RCA CLOSED (this doc)
Failure locus   SOURCE post-bind publication lifecycle
Harness         CLEARED
PA-SR4…SR6      HOLD
ADR-0056        KEEP
Next            B1-R2 design + implementation authorization (not started)
```

---

## Evidence pointers

- Adjudication: `talkback/logs/phase-a-sr3-field-20260909-152252/ADJUDICATION.txt`
- M01 SOURCE EMITTED + PACKAGE post-bind: `M01-logcat.txt` ~3029, ~3690
- M02 pre-bind drop: `M02-logcat.txt` ~2575
- Criteria Class 2: `meeting-product-integration-phase-a-sr3-adjudication-criteria.md`
- B1-R1 publication path unchanged: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r1-origin-eligibility-continuation-design.md` §3.3
