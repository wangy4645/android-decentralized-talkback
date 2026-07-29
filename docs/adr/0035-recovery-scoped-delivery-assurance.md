# ADR-0035: Recovery-Scoped Delivery Assurance

## Status

**Accepted** (2026-07-29). Grill Q1–Q5 closed in [investigation](../investigations/ice-restart-offer-delivery-investigation.md).

Depends on:

- [ADR-0022](./0022-recovery-completion-ownership.md) — Recovery / Completion ownership
- [ADR-0034](./0034-user-visible-connectivity-projection.md) — UVCP (CLOSED; do not reopen)

Scope: **`RECOVERY_REATTACH` delivery only**.

Not included: HELLO, HEARTBEAT, GROUP_MESH, media transport, generic reliable UDP framework.

## Summary

Recovery control messages that advance a Recovery Episode MUST NOT rely on best-effort UDP send success as proof of peer ingress. This ADR introduces **lineage-scoped delivery assurance** so Recovery can distinguish:

```text
offer never reached peer          (delivery failure)
        vs
offer reached peer but negotiation incomplete
```

```text
Delivery        -> Negotiation -> Completion
(confirmed)       (answer)        (RECOVERED)
```

SYNCING while obligation OPEN is an honest projection (ADR-0034); fixing delivery is the correct fix, not UVCP timeout.

## 1. Problem

### Current implicit contract (broken)

```text
LOCAL_ACCEPT / SEND_SUCCESS
        |
        v
HAVE_LOCAL_OFFER
        |
        v
wait for answer
```

UDP `socket.send()` success only proves kernel accepted bytes — not peer receive, not NIC egress, not current route validity.

### Observed failure (D1)

```text
M02: SEND_REQUEST + LOCAL_ACCEPT (L1)
M03: ENETUNREACH / no RECOVERY_REATTACH ingress
M03: HELLO/HEARTBEAT resume later (GROUP_MESH path OK)
```

`D1_NO_REMOTE_RECEIVE` was misread as negotiation failure. Root cause is **Recovery Signaling Path**, not Drain / Completion / UVCP.

### Missing question

```text
Did the peer receive this recovery offer?
```

## 2. Decision

### 2.1 Message classification

```text
BEST_EFFORT:      HELLO, HEARTBEAT, presence hints
DELIVERY_ASSURED: RECOVERY_REATTACH (+ future transaction-like control only)
```

NOT: upgrade all `SIGNAL_DATAGRAM` to a reliable transport layer.

### 2.2 Delivery lifecycle

```text
CREATED
   |
SEND_REQUEST
   |
DELIVERY_PENDING
   |
   +------------------+
   v                  v
DELIVERY_CONFIRMED   DELIVERY_EXHAUSTED
```

Semantic separation (MUST hold):

```text
DELIVERY_CONFIRMED  !=  ANSWER_RECEIVED
ANSWER_RECEIVED     !=  RECOVERED
```

After `DELIVERY_CONFIRMED`, negotiation may enter `WAITING_REMOTE_ANSWER` (existing Negotiation owner).

### 2.3 Identity — `RecoveryDeliveryIdentity`

```kotlin
RecoveryDeliveryIdentity(
    offerLineageId,
    recoveryAttemptId,
    obligationGeneration,
    deliveryAttemptId
)
```

| Field | On retransmit |
|-------|----------------|
| `offerLineageId` | MUST NOT change |
| `recoveryAttemptId` | MUST NOT change |
| `obligationGeneration` / generation | MUST NOT change |
| `deliveryAttemptId` | MUST increment |

```text
L1 deliveryAttempt=1  ->  retry  ->  L1 deliveryAttempt=2
```

FORBIDDEN: `L1 failed -> create L2` as a delivery retry (pollutes completion lineage, analyzer, obligation ownership).

### 2.4 ACK contract — `RECOVERY_REATTACH_ACK`

Independent delivery confirmation. NOT answer. NOT `NEGOTIATION_CAN_EXECUTE`. NOT `RECOVERED`.

Minimum payload:

```text
offerLineageId
recoveryAttemptId
obligationGeneration / generation
deliveryAttemptId
sender
receiver
```

Chain:

```text
M02  RECOVERY_REATTACH(L1, deliveryAttempt=n)
        |
        v
M03  REMOTE_RECEIVE + validate lineage
        |
        v
M03  RECOVERY_REATTACH_ACK(L1, deliveryAttempt=n)
        |
        v
M02  DELIVERY_CONFIRMED
```

REJECTED: reuse GROUP_JOIN response; heartbeat as delivery proof.

## 3. Ownership

```text
Transport
    | facts (send, receive ACK datagram)
    v
Recovery Delivery Assurance
    | DELIVERY_PENDING / DELIVERY_CONFIRMED / DELIVERY_EXHAUSTED
    v
Recovery Episode Owner
    | policy (retry / abandon / wait / supersede)
    v
Negotiation / Completion  (unchanged)
```

### Transport MUST NOT

- `retry()` with recovery policy
- `failRecovery()`
- `closeObligation()`

### Delivery Assurance MUST NOT

- `markRecovered()`
- close obligation
- auto-supersede lineage
- drive `RECOVERY_FAILED` as authority

### Recovery Episode Owner

- bounded retransmission decision (same lineage, `deliveryAttempt++`)
- max delivery attempts
- response to `DELIVERY_EXHAUSTED` fact

## 4. Retransmission (INV-DELIVERY-002)

Only **Recovery-owner-driven bounded retransmission**:

```text
deliveryAttempt=1 -> no ACK -> deliveryAttempt=2 -> ACK -> DELIVERY_CONFIRMED
```

FORBIDDEN:

- timer in transport layer
- blind UDP retry
- new recovery attempt / new obligation on delivery loss

## 5. Delivery exhaustion (INV-DELIVERY-003)

When delivery attempts exhausted:

```text
DELIVERY_EXHAUSTED(lineage=L1, generation=G, attempts=N)
```

Recovery Episode applies existing policy (abandon / wait / supersede). Delivery does NOT fail recovery directly.

## 6. Invariants

| ID | Statement |
|----|-----------|
| INV-DELIVERY-001 | `DELIVERY_PENDING` / `DELIVERY_CONFIRMED` / `WAITING_REMOTE_ANSWER` are distinct phases; delivery produces facts only |
| INV-DELIVERY-002 | Retransmission preserves lineage + generation; increments `deliveryAttemptId`; transport MUST NOT schedule retry |
| INV-DELIVERY-003 | Exhaustion emits `DELIVERY_EXHAUSTED` fact; Episode owns policy; no auto-supersede |
| INV-DELIVERY-004 | Retransmission is an Episode policy action, not a Delivery state transition |
| INV-DELIVERY-005 | Retry MUST preserve `offerLineageId`, `recoveryAttemptId`, `obligationGeneration`; only `deliveryAttemptId` increments |
| INV-DELIVERY-006 | `DELIVERY_CONFIRMED` requires `RECOVERY_REATTACH_ACK` matching current identity; not SEND accept, `LINK_READY`, heartbeat, or `ICE_CONNECTED` |
| INV-DELIVERY-007 | `DELIVERY_EXHAUSTED` closes delivery budget only; does not close recovery, obligation, or completion |

### FORBIDDEN shortcuts

```text
DELIVERY_CONFIRMED -> RECOVERED
DELIVERY_PENDING timeout -> closeObligation
ACK -> obligation closed
```

## 7. Analyzer extension

Extend D-classification so delivery vs negotiation are not conflated:

| Class | Meaning |
|-------|---------|
| `D1_PENDING` | SEND; no ACK yet |
| `D1_RETRY_CONFIRMED` | retry (`deliveryAttempt>1`); ACK; `DELIVERY_CONFIRMED` |
| `D1_EXHAUSTED` | SEND; retries exhausted; no ACK |
| `D2_HANDLER_REJECT` | ACK; handler failed |
| `D3_NO_ANSWER` | ACK; answer missing |
| `D4_NO_APPLY` | answer; apply missing |
| `D5_FULL_SUCCESS` | full chain |

`SignalPathKey` MUST include `signalDomain` (RECOVERY_REATTACH vs GROUP_MESH), lineage, attempt, generation — never score GROUP_MESH receive as recovery L* success.

## 8. Implementation boundary (planned PRs)

### ADD

- `RecoveryDeliveryTracker` (facts)
- `RECOVERY_REATTACH_ACK` message type
- `deliveryAttemptId` on `RECOVERY_REATTACH`
- delivery-phase observation logs
- unit tests

### CHANGE

- `RECOVERY_REATTACH` send path (enqueue semantics; rename misleading `LOCAL_ACCEPT`/`SENT` in observations)
- analyzer (`analyze-ice-restart-offer-delivery.ps1`)

### KEEP (no change)

- ICE restart gate
- NegotiationCapability / Drain ownership
- Completion authority
- UVCP projection

## 9. Rollout order

1. **This ADR** (frozen) — **DONE**
2. **PR1 Observability** — **PASS** (2026-07-29). Evidence: `logs/signal-path-20260729-200203/RECOVERY_DELIVERY_REPORT.txt`; analyzer: `scripts/analyze-recovery-delivery.ps1`. Proved `LOCAL_ACCEPT ≠ DELIVERY_CONFIRMED`; excluded ACK-loss / lineage-mismatch / negotiation-answer hypotheses for D1 soak.
3. **PR2 Bounded Retransmission** — **Grill CLOSED** (2026-07-29; see Appendix A). **Implementation NEXT**. Same `offerLineageId` / `recoveryAttemptId` / `obligationGeneration`; `deliveryAttempt++` only.
4. **D1 soak after PR2** — classify delivery retry vs network window (not before PR2 facts exist)

Do NOT fix retry count/interval before delivery facts exist. **PR1 scope CLOSED** — no further PR1 changes.

## 10. Consequences

### Positive

- D1 attributed to delivery assurance, not negotiation
- `HAVE_LOCAL_OFFER` pollution removed from delivery diagnosis
- Analyzer stops conflating GROUP_MESH with RECOVERY_REATTACH

### Negative / risks

- New control message type and correlation fields
- Temporary dual logging during migration (`LOCAL_ACCEPT` vs `DELIVERY_PENDING`)

### Neutral

- UVCP may show long SYNCING until delivery + completion — correct per ADR-0034

## 11. References

- Investigation: `docs/investigations/ice-restart-offer-delivery-investigation.md`
- Soak evidence: `logs/signal-path-20260729-185201/`, `logs/signal-path-20260729-191529/`, `logs/signal-path-20260729-200203/` (PR1 PASS)
- ADR-0022 Appendix D (Drain — CLOSED)
- ADR-0034 UVCP (CLOSED)

---

## Appendix A — PR2 Bounded Retransmission (Grill CLOSED 2026-07-29)

PR2 grill Q1–Q5 frozen. PR1 scope remains **CLOSED**. Do not reopen UVCP, Appendix D, or Completion.

### A.1 Ownership (PR2-Q1)

```text
ConferenceEdgeRecoveryController
        | policy: retry? deliveryAttempt++ exhausted?
        v
TalkbackCoordinator
        | dispatchRecoveryOffer(identity) — same lineage
        v
UdpSignalingChannel
        | send packet; report transport facts only
```

- **Episode Owner** owns retry policy and scheduling authority.
- **Coordinator** owns dispatch capability only — MUST NOT own retry policy.
- **Transport** owns I/O facts only — MUST NOT `retry()`, `scheduleRetry()`, `awaitAck()`, or `markDeliveryConfirmed()`.

**INV-DELIVERY-004:** Retransmission is an Episode policy action, not a Delivery state transition.

```text
DELIVERY_PENDING (fact)
        |
        v
Episode decision
        |
        v
dispatch same lineage (deliveryAttempt++)
        |
        v
new deliveryAttempt fact
```

FORBIDDEN: Delivery module calling `retry()` and re-entering `DELIVERY_PENDING` as policy owner.

### A.2 Retry triggers (PR2-Q2)

```text
Timer  = delivery assurance backstop
Hint   = delivery opportunity signal
Both   → converge to Episode decision point

Neither timer nor hint owns delivery success.
```

Eligible hints (observation only; MUST NOT imply `DELIVERY_CONFIRMED`):

- `LINK_READY`
- `PEER_REACHABILITY_RESTORED`

FORBIDDEN:

```text
LINK_READY / PEER_REACHABILITY_RESTORED
        → DELIVERY_CONFIRMED | ANSWERED | RECOVERED
```

Hint may only make Episode **eligible for another dispatch decision** (subject to `canDispatchRecoverySignal()` and `deliveryRetryMinGapMs`).

### A.3 Timing contract (PR2-Q3)

Three **independent** clocks:

| Clock | Owner | Scope |
|-------|-------|-------|
| `deliveryRetryTimer` | Episode | `deliveryAttempt` lifecycle |
| `RECOVERY_WATCHDOG` | Episode | `recoveryAttempt` progress |
| `obligationDeadline` | Episode | episode terminal policy |

MUST NOT:

- couple delivery retry to `RECOVERY_WATCHDOG` or `obligationDeadlineAt`
- reset `RECOVERY_WATCHDOG` on delivery retry (only first dispatch per `recoveryAttemptId` starts watchdog)
- let delivery retry emit `ATTEMPT_TIMEOUT`, `FAILED_MEDIA_RECOVERY`, `RECOVERED`, or extend obligation

Retry strategy for PR2: **bounded count** + **fixed interval** (no exponential backoff).

### A.4 Exhaustion policy (PR2-Q4)

When `deliveryAttempt > maxDeliveryAttempts`:

```text
DELIVERY_EXHAUSTED(L1, gen=G, recoveryAttempt=N)
        |
        v
Episode: WAITING(reason=DELIVERY_EXHAUSTED)
```

- `offerLineageId` frozen (delivery-terminal); late ACK discarded — no false `DELIVERY_CONFIRMED`.
- `recoveryAttemptId` unchanged; obligation **OPEN**.
- **No auto-supersede**, no new lineage, no `FAILED_MEDIA_RECOVERY`, no `closeObligation` on exhaustion alone.

Subsequent recovery progression uses **existing** channels only:

- `RECOVERY_WATCHDOG` → `ATTEMPT_TIMEOUT` (if control plane never started)
- `RECOVERY_REEVALUATE` + capability → explicit `SUPERSEDE` | `DISPATCH` | `WAITING` (not exhaustion-automatic)
- `obligationDeadline` → `OBLIGATION_DEADLINE` (orthogonal)

### A.5 Parameters and scope (PR2-Q5)

| Parameter | Default | Frozen in ADR? |
|-----------|---------|----------------|
| `maxDeliveryAttempts` | 3 | mechanism yes; value tunable via `TalkbackConfig` |
| `deliveryRetryIntervalMs` | 3000 | mechanism yes; soak-tunable |
| `deliveryRetryMinGapMs` | 500 | mechanism yes; soak-tunable |

- Inject via `TalkbackConfig`; **not** remote-config or runtime policy.
- Process restart drops in-memory delivery pending state — **acceptable** for PR2 (owner is Recovery Episode, not Durable Delivery Queue).

### A.6 PR2 delivery state machine

```text
REQUESTED
    |
    v
DELIVERY_PENDING
    |
    +------------------+
    v                  v
DELIVERY_CONFIRMED   DELIVERY_RETRY_PENDING
                         |
                         v
                  bounded retransmit
                         |
                         v
                  DELIVERY_EXHAUSTED
                         |
                         v
              Episode WAITING(DELIVERY_EXHAUSTED)
```

### A.7 Observation events (PR2 ADD)

```text
RECOVERY_DELIVERY_REQUESTED
RECOVERY_DELIVERY_PENDING
RECOVERY_DELIVERY_RETRY_PENDING
RECOVERY_DELIVERY_RETRY_DEFERRED
RECOVERY_DELIVERY_EXHAUSTED
RECOVERY_REATTACH_ACK_RECEIVED
RECOVERY_DELIVERY_CONFIRMED
RECOVERY_DECISION decision=WAITING reason=DELIVERY_EXHAUSTED
```

### A.8 PR2 IN / OUT

**IN:** delivery retry state machine; delivery facts; ACK handling; bounded retransmission; analyzer `D1_RETRY_CONFIRMED` / `D1_EXHAUSTED`; UT Cases A/B/C.

**OUT:** persistence; remote tuning; adaptive retry; UI; UVCP; Completion; ICE qualification; `DELIVERY_FAILED` / `RECOVERY_FAILED` phases.

### A.9 UT acceptance cases

| Case | Chain |
|------|-------|
| **A** | send → pending → network hint → retry same lineage → ACK → CONFIRMED |
| **B** | send → ACK → timer cancelled → CONFIRMED (no retry) |
| **C** | send L1 → exhaust → late ACK L1 → discard |

### A.10 Implementation review risks

Before merge, verify code does **not**:

1. run retry in Transport
2. reset or couple to `RECOVERY_WATCHDOG`
3. map ACK or `DELIVERY_CONFIRMED` to `RECOVERED`
4. allocate new `offerLineageId` on delivery retry