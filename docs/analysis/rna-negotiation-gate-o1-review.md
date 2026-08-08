# RNA Negotiation Gate — O1 Desk Review

**Status:** **CLOSED** · **observation complete** · **see** [rna-intent-observation-close.md](./rna-intent-observation-close.md)  
**Date:** 2026-08-08  
**Episode:** `logs/adr0043-appendix-b-20260808-185802/` · M02→M03 · `intentId=R1`  
**Parents:** [rna-intent-lifecycle-contract-review.md](./rna-intent-lifecycle-contract-review.md) · [rna-intent-lifecycle-hypothesis.md](./rna-intent-lifecycle-hypothesis.md)

---

## Purpose

Answer O1 from contract review:

> Why did `OFFER_AWAITING_ANSWER` persist through L2 recovery, and why did R1 expire without `EXECUTED`?

**Not asking:** how to fix recovery · whether to change completion predicate · field run authorization.

---

## Status board

```text
ADR-0043       CLOSED ✅
ADR-0042       REVIEWED ✅
RNA intent       HYPOTHESIS OPEN
  └─ Contract Review   COMPLETE ✅
  └─ O1 Gate Review    CLOSED (observation) ✅
  └─ O2 Projection     OBSERVATION COMPLETE ✅
RNA observation      CLOSED (desk) ✅
RNA run          NOT AUTHORIZED
Field            NOT AUTHORIZED
```

---

## Contract对照表

| Question | Evidence | Verdict |
| -------- | -------- | ------- |
| OFFER created where? | M02 `trySendSingleConferenceInvite` rejoin path → `engine.createOffer(iceRestart=true)` | `TalkbackCoordinator` + `WebRtcAudioEngine` SLD |
| OFFER owner? | M02 OFFERER; `WEBRTC_NEGOTIATION op=SLD type=OFFER` @ 19:00:56.775–782 | M02 local PC |
| ANSWER producer? | Expected: M03 answerer (`applyRemoteOffer` → `GROUP_ACCEPT`) | **Never produced** |
| ANSWER consumer? | M02 `applyRemoteAnswer` → `recomputeNegotiationCapability` | **Never invoked** for this offer |
| Transition out of `OFFER_AWAITING_ANSWER`? | `signalingState=STABLE` → `NEGOTIATION_CAN_EXECUTE` rising edge → drain | **Never fired** for R1 window |
| R1 expiry expected? | `NEGOTIATION_INTENT_BUDGET_ARMED budgetMs=10000` → `NEGOTIATION_BUDGET_EXHAUSTED` | **Yes** — consistent with RNA-5 budget when prerequisite unmet |

---

## O1-A — Did an answer ever materialize?

### Three-case classification

| Case | Pattern | This episode |
| ---- | ------- | ------------ |
| **Case 1** | Answer received but not committed/applied | ❌ No `SRD type=ANSWER` on M02 after 19:00:56 |
| **Case 2** | Offer sent, no answer (peer negotiation gap) | ✅ **Primary** — peer sent `CALL_REJECT` instead of answer |
| **Case 3** | Answer ignored (epoch/generation mismatch) | ❌ No answer artifact to fence |

### Observed negotiation chain (M02, M03 edge)

```text
19:00:56.775  ICE_RESTART_REQUESTED (iceRestart=true, signalingState=STABLE)
19:00:56.778  SIGNALING_STATE → HAVE_LOCAL_OFFER
19:00:56.789  GROUP_INVITE SENT (signalType=GROUP_INVITE, 2367 bytes, SDP embedded)
19:00:56.797  ICE_RESTART_GATE_BLOCKED intentId=R1 reason=OFFER_AWAITING_ANSWER
              wakeupBinding=NEGOTIATION_CAN_EXECUTE

[10s gap — no NEGOTIATION_CAN_EXECUTE for M03]

19:01:06.800  NEGOTIATION_BUDGET_EXHAUSTED intentId=R1
19:01:06.804  NEGOTIATION_RECOVERY_FACT terminalState=EXPIRED
```

### Answer path never started

On M02 after R1 creation:

```text
❌ WEBRTC_NEGOTIATION op=SRD type=ANSWER
❌ NEGOTIATION_CAPABILITY_RISING remote=M03 (post-R1)
❌ NEGOTIATION_CAN_EXECUTE remote=M03 intentId=R1
❌ GROUP_ACCEPT from M03
```

Last `NEGOTIATION_CAN_EXECUTE` for M03 was **18:58:39** (initial join), before recovery episode.

### Peer response: CALL_REJECT, not ANSWER

Earlier in the same recovery window (first rejoin invite):

```text
M03 19:00:54.583  GROUP_INVITE received from M02
M03 19:00:54.595  CALL_REJECT sent (BUSY)
M02 19:00:56.878  CALL_REJECT received
M02 19:00:56.884  "Ignoring BUSY from connected peer M03 (duplicate invite)"
```

Second invite (same nonce pattern, new SDP):

```text
M02 19:00:56.789  GROUP_INVITE SENT
M03                 no GROUP_INVITE receive log at 19:00:56
M02 19:00:56.878  CALL_REJECT received (likely delayed from 19:00:54.595)
```

**O1-A verdict:** No remote SDP answer was produced or applied. Stall is **peer negotiation-layer rejection** (`CALL_REJECT` / BUSY), not transport loss of an answer artifact.

---

## O1-B — Who owns clearing `OFFER_AWAITING_ANSWER`?

### Gate probe (code contract)

`TalkbackCoordinator.computeIceRestartGateProbe`:

```text
signalingState == STABLE        → executable=true
signalingState == HAVE_LOCAL_OFFER → block=OFFER_AWAITING_ANSWER
else                            → block=SIGNALING_NOT_STABLE
```

### Lifecycle ownership map

| Phase | Owner | Mechanism |
| ----- | ----- | --------- |
| **Enter** `OFFER_AWAITING_ANSWER` | `WebRtcAudioEngine` via `createOffer` | Local SLD → `HAVE_LOCAL_OFFER` |
| **Observe** gate blocked | `ConferenceEdgeRecoveryController` | `probeIceRestartGate` → defer R1, arm `NEGOTIATION_CAN_EXECUTE` wakeup |
| **Exit** (intended) | `TalkbackCoordinator` | Remote answer applied → `STABLE` → `recomputeNegotiationCapability` rising edge → `drainPendingIceRestart` |
| **Exit** (answerer path) | `TalkbackCoordinator` | `commitAnswererTransactionAndDrain` after `GROUP_ACCEPT` handoff |
| **CALL_REJECT received** | `TalkbackCoordinator.handleCallReject` | `evictMeshInvitee` returns false for connected peer BUSY → **ignored**; **no** `recomputeNegotiationCapability` |

### Named owners exist; missing bridge on BUSY

```text
OFFER created (M02)
      |
      v
HAVE_LOCAL_OFFER ─────────────────────────────┐
      |                                        |
      v                                        | no bridge
R1 deferred (wakeup=NEGOTIATION_CAN_EXECUTE)   |
      |                                        |
      v                                        v
await remote ANSWER              M03 CALL_REJECT (BUSY)
      |                                        |
      |                                        v
      |                              M02 ignores BUSY
      |                                        |
      v                                        v
still HAVE_LOCAL_OFFER  ──────────>  budget expires → EXPIRED
```

**O1-B verdict:** Transition authority is **named and implemented** for the happy path (`STABLE` after remote answer). There is **no owner** that maps invite-layer `CALL_REJECT`/BUSY to gate clearance or negotiation rollback on the offerer. This is an **observed seam**, not a missing component list.

---

## O1-C — Why L2 recovered but intent stayed uncovered

### L2 facts at R1 creation (M02 view)

```text
iceConnected=true
mediaReady=true
controlReady=true
l2Satisfied=true
edgePhase=REATTACH_ACCEPTED
```

### Intent prerequisite (separate layer)

```text
gateBlock=OFFER_AWAITING_ANSWER
baselineCapability=false
wakeup=NEGOTIATION_CAN_EXECUTE
```

### Design principle (confirmed, not assumed)

```text
L2 recovery facts (ICE/media/control)
        ≠
negotiation artifact (remote SDP answer committed → STABLE)
        ≠
restart intent EXECUTED
```

R1 was created precisely because ICE restart dispatch was **blocked** while local offer awaited answer. L2 convergence does not automatically satisfy that prerequisite.

**O1-C verdict:** L2 recovery without intent cover is **architecturally consistent** with frozen semantics (`media recovered ≠ restart intent executed`). The gap is **missing negotiation artifact**, not missing L2 evidence.

---

## Why R1 → EXPIRED (not a surprise)

```text
DEFERRED_INTENT_CREATED baselineCapability=false
NEGOTIATION_INTENT_BUDGET_ARMED budgetMs=10000
NEGOTIATION_CAPABILITY_REEVAL executable=false rising=false (observationSeq=3)
```

Prerequisite `NEGOTIATION_CAN_EXECUTE` never rose false→true while R1 was pending.

Per RNA-5 budget semantics: **EXPIRED after budget exhaustion without EXECUTED is expected** when the negotiation gate never cleared.

---

## Root question closure

> R1 为什么从 CREATED 走向 EXPIRED，而没有获得 EXECUTED 所需的 negotiation artifact？

```text
M02 created ICE-restart offer and entered HAVE_LOCAL_OFFER
        ↓
M02 sent GROUP_INVITE (rejoin) with embedded SDP
        ↓
M03 rejected with CALL_REJECT (BUSY — duplicate invite / same sessionId)
        ↓
M02 ignored BUSY; local PC remained HAVE_LOCAL_OFFER
        ↓
NEGOTIATION_CAN_EXECUTE never fired for R1
        ↓
10s budget exhausted → EXPIRED (discard, not cover)
```

---

## Boundary (reconfirmed)

```text
Not ADR-0043:  membership authorization operated correctly (orthogonal)
Not ADR-0042:  transport SENT truth not primary stall signal here
RNA O1:        invite-layer BUSY vs WebRTC-layer OFFER_AWAITING_ANSWER seam
```

**Do not** route to: completion predicate change · handler patch · field run · UI investigation (O2 deferred).

---

## Frozen finding (O1 close)

```text
Classification:
  Cross-layer lifecycle seam observed

Finding:
  Invite-layer rejection (CALL_REJECT/BUSY) does not currently terminate
  or translate WebRTC OFFER_AWAITING_ANSWER transaction.

Not:
  CALL_REJECT bug
  need rollback
  ADR authorization
```

**Continues in:** [rna-obligation-projection-o2-review.md](./rna-obligation-projection-o2-review.md)

---

## One-line statement

> R1 expired because the offerer entered `OFFER_AWAITING_ANSWER` via a rejoin `GROUP_INVITE`, the answerer responded with invite-layer `CALL_REJECT` (BUSY) instead of SDP answer, and the offerer gate waits only for WebRTC `STABLE` — not for BUSY — so `NEGOTIATION_CAN_EXECUTE` never fired before budget exhaustion.
