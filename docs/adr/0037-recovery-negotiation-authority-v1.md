# ADR-0037 (ADR-RNA-001): Recovery Negotiation Authority v1

## Status

**Accepted - v1 FINAL** (2026-08-05). Design frozen; open questions resolved. Phase 3.1 Observability **VERIFIED**. Phase 3.2 behavior **NOT authorized** (design review open).

Scope: ownership model, identity layering, glare FSM, deferred intent lifecycle, completion evidence, and phased implementation plan. No code change, no APK rebuild, no field re-collection required.

**Parent evidence:** Recovery Negotiation Audit (W2 run `logs/wifi-recovery-m03-w2-p24d-20260805-151602`).

**Complements:** [ADR-0036](./0036-recovery-completion-authority-v1.md), [ADR-0022](./0022-recovery-completion-ownership.md), [ADR-0021](./0021-conference-edge-recovery-lifecycle.md), [ADR-0019](./0019-conference-signaling-media-separation.md), [ADR-0011](./0011-conference-mesh-reconcile-vs-invite.md)

**Orthogonal authority boundary (frozen):**

```text
ADR-0036: who owns membership truth?
ADR-0037: who owns negotiation progress?
```

These domains MUST NOT be conflated.

## Summary

ADR-0036 closed the **membership** authority gap. W2 proves the same failure shape one layer down: recovery negotiation has **no single transaction owner**, so two actors concurrently believe they may drive the ICE restart. The resulting glare is not resolved - it is silently dropped - and the deferred intent waits on a predicate that can never become true.

This ADR freezes the **Recovery Negotiation Authority** model: who may open a recovery negotiation transaction, how concurrent offers converge deterministically, and how a deferred intent always reaches a terminal state.

## Context

### Evidence chain (W2, session `c03bcde9`, edge M03<->M01, attempt 7)

```text
Recovery transport   OK
Membership           OK (ADR-0036: already aligned -> skip repair)
Control reconcile    OK
Negotiation recovery BROKEN
```

Two concurrent producers:

| Actor | Self-assigned role | Log evidence |
|-------|--------------------|--------------|
| M03 | `MediaActionOwner=HOST_RESTART`, `mediaActionOwnerModuleId=M03` | `RECOVERY_MEDIA_ACTION_ASSIGNMENT ... owner=HOST_RESTART trigger=SUPERSEDE:ROUTE_CONVERGED` |
| M01 | `localRole=OFFERER`, repeated `createOffer(iceRestart=true)` | `ICE_RESTART_REQUESTED ... localRole=OFFERER signalingState=HAVE_LOCAL_OFFER` (>=10 dispatches) |

Glare is real, and observed as such:

```text
OFFER_DELIVERY ... signalingState=HAVE_LOCAL_OFFER localDesc=OFFER remoteDesc=OFFER
                   decision=DROP_DUPLICATE_ICE_CONNECTED joinIntent=NORMAL_JOIN
```

Resulting deadlock:

```text
M03 HAVE_LOCAL_OFFER (awaiting ANSWER)
  + M01 remote OFFER dropped as "duplicate"
  -> no createAnswer, no ANSWER emitted
  -> gate probe never STABLE -> NEGOTIATION_CAN_EXECUTE never rises
  -> DEFERRED_INTENT R2 never EXECUTED, never terminal
  -> RECOVERY_ATTEMPT_TIMEOUT controlPlaneStarted=false
```

### Failure classification

This is **not** an ICE connectivity failure. A media failure would show `ANSWER_RECEIVED` then `ICE_FAILED`. Observed instead: offer ownership never resolves. Fault domain = **negotiation transaction ownership**.

### Structural gaps found in code

| # | Gap | Location |
|---|-----|----------|
| G1 | No negotiation-owner concept; ownership inferred from three unrelated roles | `TalkbackCoordinator`, `ConferenceEdgeRecoveryController` |
| G2 | Remote recovery offer dropped before any glare logic | `acceptGroupJoin` - `meshCompleted && ICE connected` -> `DROP_DUPLICATE_ICE_CONNECTED` -> `return` |
| G3 | Engine has polite-glare path unreachable from duplicate branch | `RealWebRtcAudioEngine.applyRemoteOffer` |
| G4 | Gate treats only `STABLE` as executable; no glare disposition | `computeIceRestartGateProbe` |
| G5 | Deferred intent has no glare terminal; timeout does not close intent | `issueBoundedIceRestart`, `enterFailedMediaResidency` |
| G6 | Recovery offer carries no transaction identity | GROUP_JOIN payload |

## Decision

### RNA-1 - Three distinct roles; negotiation authority modeled separately

Session truth, media action intent, and negotiation initiative are **three different authorities**. This ADR only creates the third one.

| Role | Owns | MUST NOT imply |
|------|------|----------------|
| Membership / session authority (ADR-0036, ADR-0023) | roster epoch, member hash, session truth | negotiation initiative |
| `MediaActionOwner` (ADR-0022 stack) | which local media action a recovery attempt intends | the right to emit an offer on the wire |
| **`RecoveryNegotiationAuthority` (new)** | who may open/drive a recovery negotiation transaction on an edge | session truth |

Forbidden identities:

```text
ConferenceHost            == NegotiationOwner    FORBIDDEN
MediaActionOwner          == NegotiationOwner    FORBIDDEN
Lexicographic polite role == RecoveryOwner       FORBIDDEN
```

**Interface:**

```text
RecoveryNegotiationAuthority.resolveOwner(
    sessionId,
    recoveryEpisodeId,
    edge,
    capability
) -> negotiationOwnerModuleId
```

**INV-RNA-001:** For a given `(sessionId, recoveryEpisodeId, edge)`, at most one module is `negotiationOwnerModuleId` at any time; both peers MUST resolve the same value.

**INV-RNA-002:** Lexicographic polite role is valid only as SDP tiebreaker inside `GlareResolver` (`isPoliteNegotiator != RecoveryNegotiationAuthority`).

### RNA-2 - Deterministic owner election

```text
1. Existing transaction owner (unclosed intent for episodeId + edge)
2. Recovery coordinator owner (attempt lineage owner, ADR-0021/0022)
3. Stable tie-breaker (deterministic moduleId function)
```

**MUST NOT:** `whoever detects the ICE issue first wins`

**INV-RNA-003:** Pure function of `(episodeId, edge, attempt lineage, moduleId)` - no wall-clock or arrival order.

**INV-RNA-004:** Losing election is not failure; non-owner MUST still converge as answerer.

### RNA-014 - Episode identity and epoch layering (OQ-1 resolved)

Reuse attempt lineage; no fourth generation counter.

```text
RecoveryEpisode
        |
        +-- NegotiationTransaction (per edge)
                  |
                  +-- negotiationEpoch
```

| Identifier | Scope | Source |
|------------|-------|--------|
| `recoveryEpisodeId` | recovery fault lifecycle on edge | attempt lineage (not `obligationGeneration` alone) |
| `negotiationEpoch` | offer/answer ownership round | owner bumps only |

```text
RecoveryEpisodeId = RecoveryAttemptLineageId
```

**INV-RNA-014:** `RecoveryEpisodeId` derives from attempt lineage. `NegotiationEpoch` scoped under episode and edge.

### RNA-3 - Ownership token (OQ-2 resolved)

```text
RecoveryNegotiationKey: (sessionId, edgeId, recoveryEpisodeId)

RecoveryNegotiationIntent {
    sessionId
    recoveryEpisodeId
    negotiationEpoch     // edge-scoped
    ownerModuleId
    intentId
    reason
}
```

`negotiationEpoch` is **edge-scoped** (M03-M01 epoch=5, M03-M02 epoch=2 may coexist).

**INV-RNA-005:** Recovery offers MUST carry envelope; absent envelope = normal join.

**INV-RNA-006:** Only resolved owner may bump `negotiationEpoch`.

### RNA-4 - GlareResolver front-loaded (OQ-3 resolved)

Required path:

```text
REMOTE_OFFER_RECEIVED -> GlareResolver (before duplicate/throttle)
  KEEP_LOCAL | ACCEPT_REMOTE | REJECT_STALE
```

```text
ICE CONNECTED != negotiation transaction resolved
```

No new signal type. Reuse envelope:

```text
GLARE_RESOLUTION_DECISION { decision, recoveryEpisodeId, negotiationEpoch, ownerModuleId, intentId }
```

or `NEGOTIATION_REJECT_REASON=GLARE_LOST` on existing reject path.

**INV-RNA-007:** GlareResolver before mesh-duplicate/throttle for recovery envelopes.

**INV-RNA-008:** No silent return; every branch observable and answerable.

**INV-RNA-009:** Complementary outcomes (KEEP_LOCAL <-> ACCEPT_REMOTE).

### RNA-4b - Non-owner escalation (OQ-4 resolved)

```text
Non-owner -> NEGOTIATION_RECOVERY_REQUEST -> Owner decides (ignore | continue | new epoch)
```

**INV-RNA-015:** Only elected owner may advance `negotiationEpoch`.

### RNA-4c - Polite role (OQ-5 resolved)

`isPoliteNegotiator` MAY be used only inside `GlareResolver` tiebreaker, NOT owner election.

### RNA-5 - Deferred intent lifecycle

```text
DEFERRED_INTENT_CREATED -> EXECUTABLE -> EXECUTED
                         -> BLOCKED -> BLOCKED_BY_GLARE -> FAILED_NEGOTIATION
```

`NEGOTIATION_BLOCKED_BY_GLARE` parallels `MEMBERSHIP_CONVERGENCE_PENDING`.

**INV-RNA-010:** Every intent has reachable terminal state.

**INV-RNA-011:** Wait on CAN_EXECUTE only while a producer exists.

### RNA-6 - Completion consumes negotiation fact

```text
NEGOTIATION_RECOVERY_FACT {
    recoveryEpisodeId, negotiationEpoch,
    ownerResolved, transactionClosed, mediaReady,
    blockedReason  // NONE | GLARE | STALE | OWNER_UNRESOLVED
}
```

```text
RecoveryComplete := TransportReady AND MediaReady AND MembershipConverged
                    AND ControlReconciled AND NegotiationTransactionClosed
```

**INV-RNA-012:** Negotiation block MUST NOT project as media failure.

**INV-RNA-013:** Fact-only input to existing verdict writer (ADR-0036 RCA-7).

### RNA-016 - Single writer rule

```text
Negotiation transaction state has exactly one writer: RecoveryNegotiationAuthority
```

| Role | Permission |
|------|------------|
| Owner | create/advance transaction; bump epoch |
| Non-owner | observe; `NEGOTIATION_RECOVERY_REQUEST` |
| GlareResolver | SDP branch only |
| MediaActionOwner | capability report only |
| Completion authority | consume fact only |

**INV-RNA-016:** Only elected owner mutates transaction state.

## Resolved open questions (v1 freeze)

| OQ | Decision |
|----|----------|
| OQ-1 | `recoveryEpisodeId` from attempt lineage; `negotiationEpoch` under episode (RNA-014) |
| OQ-2 | Edge-scoped epoch via `RecoveryNegotiationKey` |
| OQ-3 | Envelope/reason reply; no new signal type |
| OQ-4 | `NEGOTIATION_RECOVERY_REQUEST`; non-owner cannot bump epoch (INV-RNA-015) |
| OQ-5 | Polite only in GlareResolver tiebreaker |

## Implementation phases (NOT authorized)

### Phase 3.1 - Observability only (shadow)

Logs/facts only: owner resolve, intent token, glare decision, deferred terminal states. **No behavior change.**

### Phase 3.2 - Behavior switch

Owner gate, GlareResolver front-loaded, duplicate drop after glare, deferred authority terminals. **Requires Phase 3.1 baseline.**

## Explicit non-goals

No ICE timeout change, no budget increase, no forced deferred execute, no auto-answer without ownership, no completion bypass, no membership weaken, no new glare signal type.

## Acceptance conditions (v1 final)

RNA-1 through RNA-6, RNA-014, RNA-016, all INV-RNA, OQs resolved, phased plan defined.

## Status board

```text
ADR-0036 Membership              VERIFIED
Recovery Completion              OPEN
Negotiation Audit                COMPLETE
ADR-0037 RecoveryNegotiationAuthority  v1 FINAL (accepted)
  Phase 3.1 observability        VERIFIED (field W2 p31-20260805-162525)
  Phase 3.2 behavior             NOT AUTHORIZED
  Phase 3.2 design review        OPEN — docs/analysis/phase32-recovery-negotiation-behavior-design-review.md
```

## References

- [ADR-0036](./0036-recovery-completion-authority-v1.md)
- [ADR-0022](./0022-recovery-completion-ownership.md)
- [ADR-0021](./0021-conference-edge-recovery-lifecycle.md)
- [ADR-0019](./0019-conference-signaling-media-separation.md)
- [ADR-0011](./0011-conference-mesh-reconcile-vs-invite.md)
- W2: `logs/wifi-recovery-m03-w2-p24d-20260805-151602`
- [RNA-5 v2 Intent Terminal Contract Amendment](./0037-rna-5-intent-terminal-contract-amendment.md) **FROZEN** (2026-08-05)
