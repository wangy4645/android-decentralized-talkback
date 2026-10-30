# PR-PA-SR-B1-R2 — Unit Closure Record

**Authorization:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r2-implementation-authorization.md`  
**Design:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r2-source-post-bind-publication-design.md`  
**RCA:** `meeting-product-integration-phase-a-sr3-rca-source-chain-20260909-152252.md`  
**Signed:** 2026-09-09  
**Status:** **CLOSED / UNIT+FIELD-PROVEN**

```text
PR-PA-SR-B1     CLOSED / UNIT+FIELD-PROVEN
B1-R1           CLOSED / UNIT-PROVEN
B1-R2           CLOSED / UNIT+FIELD-PROVEN
PA-SR3          CLOSED / FIELD-PROVEN

B0 / A1 / J0    UNCHANGED_CLOSED
P1-C / P1-E     UNCHANGED_CLOSED
PA-SR4…SR6      HOLD
ADR-0056        KEEP · cutover NOT AUTHORIZED
```

**R2 unit PASS does not close PA-SR3.** PA-SR3 remains **VALID FAIL** until frozen field chain PASS.

---

## Unit test matrix

| Id | Test | Result |
|----|------|--------|
| R2-1 | `MeetingProfile01SourcePostBindRepublicationTest.r2_1_preBindEmitted_peerPostBindEligible_replaysCachedBytes` | PASS |
| R2-2 | `…r2_2_postBindCallbackRepeated_atMostOneSuccessfulPublication` | PASS |
| R2-3 | `…r2_3_sendFailure_obligationRemainsOpen_retriesOnNextProductEvent` | PASS |
| R2-4 | `…r2_4_sourceGenerationChange_oldPostBindDoesNotSatisfyNewSource` · `…r2_4_supersededSourceWithoutTransportPublish_skipsBeforeSend` | PASS |
| R2-5 | `…r2_5_alreadyPostBindSatisfied_noDuplicateReplay` | PASS |
| R2-6 | `…r2_6_preBindSourceOnPeer_remainsNotRetained` | PASS |
| R2-7 | `…r2_7_allRepublication_byteIdenticalToCachedArtifact` | PASS |
| — | `…r2_transportPublished_doesNotBlockFirstPostBindSend` (R2-HC2 guard) | PASS |
| — | `…postBindRepublish_logsStructuredObs` | PASS |

**Regression:** B1-1 … B1-7 (`MeetingProfile01SourceOriginBridgeTest`) — PASS  
**Regression:** R1-1 … R1-5 (`MeetingProfile01SourceOriginEligibilityContinuationTest`) — PASS  
**Regression:** B0 fence (`Profile01SourceSignerSenderFenceTest`) — PASS  
**Regression:** B1-5 (`MeetingProfile01FactWireIngressTest.b1_5_preBindSource_notRetained`) — PASS

---

## Implementation landed

```text
SourcePostBindPublicationIdentity
SourcePostBindPublicationLedger
SourcePostBindRepublishOutcome
MeetingProfile01SourceOriginBridge.onPeerSourceConsumptionEligible
  → cached signedFactBytes replay (B1-6 / R2-HC1)
  → transport vs post-bind ledger independence (R2-HC2)
  → identity verification before send (R2-HC6)
  → success-only markSatisfied (R2-HC7)
TalkbackCoordinator.maybeRepublishSourceDeclarationAfterPeerBind
onConferenceAcceptMediaSettled wiring (parallel to P1-C-R1)
PROFILE01_SOURCE_POST_BIND_REPUBLISH OBS
SourcePublicationLedger.publishedIdentities (test/obs support)
```

---

## Hard constraints verified (unit)

| Contract | Evidence |
|----------|----------|
| R2-HC1 byte-identical replay | R2-1, R2-7 — post-bind bytes == `sourceStateBySession.signedFactBytes` |
| R2-HC2 distinct ledger semantics | `r2_transportPublished_doesNotBlockFirstPostBindSend` — transport published ≠ post-bind satisfied |
| R2-HC3 product bind trigger only | Coordinator wires `admission_ready_after_answer_settled` only; no timer/retry in bridge |
| R2-HC4 no pre-bind retention | R2-6 — `PRE_BIND_NOT_RETAINED` unchanged |
| R2-HC5 no build reopen | Post-bind path never calls `onLocalConferenceSourceCommitted` |
| R2-HC6 identity gate before send | R2-4b — superseded source without transport publish → no send |
| R2-HC7 failed keeps obligation open | R2-3 — `SEND_FAILED` then retry on next callback |
| R2-HC8 success consumes post-bind only | R2-2 / R2-5 — transport ledger untouched; duplicate post-bind blocked |

---

## Field closure (20260909-154735)

**LogDir:** `talkback/logs/phase-a-sr3-field-20260909-154735/`  
**Session:** `35e78840-9ebd-489e-976a-bfaa32f9e4a1`  
**Build:** `cac628d`  
**Verdict:** `PASS` / `PA-SR3=CLOSED / FIELD-PROVEN`

Observed chain (M01→M02):

```text
15:55:43  PROFILE01_ORIGIN_EMIT factType=9 outcome=EMITTED target=M02 (pre-bind)
15:55:44  M02 PRE_BIND_NOT_RETAINED factType=9
15:55:55  PROFILE01_SOURCE_POST_BIND_REPUBLISH outcome=EMITTED target=M02
15:55:56  M02 WIRE_INGRESS factType=9 outcome=APPLIED
15:55:56  M02 SHADOW MEMBER_MEDIA_READY module=M01 outcome=APPLIED
```

**Note:** Initial script exit was `FAIL_PA_SR3` / `SR3-R_pre_bind_without_auto_republication` because
`phase-a-sr3-field-adjudicate.ps1` Class 2 still keyed on second `PROFILE01_ORIGIN_EMIT`. Product chain
was complete; adjudicator updated to accept `PROFILE01_SOURCE_POST_BIND_REPUBLISH outcome=EMITTED` (B1-R2).
Re-adjudication on same log dir → `PASS`.

**PA-SR4…SR6:** observe only — not closed on this run.
