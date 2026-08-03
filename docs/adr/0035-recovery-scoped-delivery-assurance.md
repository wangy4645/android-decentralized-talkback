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
| INV-DELIVERY-006 | `DELIVERY_CONFIRMED` requires a matching `RECOVERY_REATTACH_ACK` generated from the **current** recovery identity on the receiver. ACK = peer receipt + handler processing — not recovery completion. Stale identity → rejection observation only; no `DELIVERY_CONFIRMED`. Not SEND accept, `LINK_READY`, heartbeat, or raw `ICE_CONNECTED`. |
| INV-DELIVERY-007 | `DELIVERY_EXHAUSTED` closes delivery budget only; does not close recovery, obligation, or completion |
| INV-DELIVERY-008 | `DELIVERY_CONFIRMED` requires ACK with `handlerOutcome ∈ {ACCEPTED, ALREADY_SATISFIED}` and **strict** identity match on all four correlation fields; stale ACK → `RECOVERY_ACK_IGNORED`, not CONFIRMED |
| INV-DELIVERY-009 | `DELIVERY_CONFIRMED` MUST trigger `RECOVERY_REEVALUATE` on the sender Episode; MUST NOT directly `RECOVERED`, `closeObligation`, or bypass Completion policy; `handlerOutcome` is reevaluate **input**, not completion signal |
| INV-DELIVERY-010 | `DELIVERY_CONFIRMED`, `handlerOutcome`, and `RECOVERY_REATTACH_ACK` MUST NOT directly update UVCP, `sessionEdgeRecovering`, or media presence; projection clears only via Recovery/Completion owner after full invariant evaluation — no reverse shortcut from handler fact to UI |

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
| `D1_EXHAUSTED` | SEND; retries exhausted; no ACK (may be ingress OR handler-handled gap — see D1-E) |
| `D1-E` | Ingress OK; `RECOVERY_HANDLER_REJECTED_AFTER_INGRESS`; no ACK |
| `D2_HANDLER_REJECT` | ACK received; downstream handler/negotiation failed (legacy label) |
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

---

## Appendix B — PR4 Handler Handled Facts (Q1 CLOSED 2026-07-30)

PR2 (bounded retransmit) and PR3 (admission freshness) remain **CLOSED**. PR4 addresses a gap exposed after PR3-1 soak: **ingress OK + handler terminal reject + no ACK**.

### B.1 Problem (post-PR3-1)

```text
M02: SEND RECOVERY_REATTACH (DELIVERY_PENDING)
M03: UDP receive → decode → RECOVERY_HANDLER_ENTER
M03: DROP_DUPLICATE_ICE_CONNECTED (or lineage skip)
M03: no RECOVERY_REATTACH_ACK
M02: DELIVERY_EXHAUSTED
```

Delivery state **misreads** handler-handled rejection as **network non-delivery**.

Classification: **`RECOVERY_HANDLER_REJECTED_AFTER_INGRESS` (D1-E)** — not D1 ingress failure.

### B.2 Three-layer boundary (frozen)

```text
Transport:  send / receive datagram facts
Delivery:   peer received + handled facts (ACK-correlated)
Recovery:   policy (accept / drop / supersede / complete)
```

Core contract (frozen):

```text
ACK = peer received + handler processed
  ≠ recovered
  ≠ completion
  ≠ obligation closed

Only current-identity-scope handler decisions produce RECOVERY_REATTACH_ACK.
```

`RECOVERY_REATTACH_ACK` 是 **handled fact**。`RECOVERED` 是 **Episode policy result**。二者不可合并。

### B.3 Observation vs state (frozen ADD)

**Delivery states** (unchanged):

```text
DELIVERY_PENDING | DELIVERY_CONFIRMED | DELIVERY_EXHAUSTED
```

**Handler observation facts** (NOT delivery states):

```text
RECOVERY_HANDLER_ACCEPTED
RECOVERY_HANDLER_REJECTED(reason)
```

MUST NOT name `DELIVERY_REJECTED`.

**Rejection reason taxonomy (initial):**

| Reason | ACK? | Notes |
|--------|------|-------|
| `STALE_OBLIGATION_GENERATION` | **No** | observation only; split-brain |
| `DUPLICATE_ICE_CONNECTED` | **Yes** (`handlerOutcome=ALREADY_SATISFIED`) | handler rejects action; intent may already be satisfied |
| `INVALID_SESSION` | observation (Q2) | |
| `INVALID_GENERATION` | observation (Q2) | |

### B.4 PR4-Q1 — CLOSED (2026-07-30)

**Decision: A (split)** — ACK only within **current recovery identity**; stale identity → observation, not fake CONFIRMED.

| Option | Status |
|--------|--------|
| **A (split)** | **FROZEN** |
| B | rejected |
| C | rejected (subset of A) |
| D | rejected |

### B.5 PR4-Q1a — STALE lineage / split-brain — CLOSED

**Decision: no ACK, no fake `DELIVERY_CONFIRMED`.**

```text
incoming RECOVERY_REATTACH
        |
        v
lineage / recoveryAttempt identity check
        |
        +-- current identity → handler decision
        |
        +-- stale identity
                |
                v
          RECOVERY_HANDLER_REJECTED(STALE_OBLIGATION_GENERATION)
                |
                X
          RECOVERY_REATTACH_ACK
```

If sender `attempt=2` while receiver current `attempt=3`, `ACK(attempt=2)` would falsely imply `DELIVERY_CONFIRMED(L2)` while Episode no longer admits L2 — violates **ACK identity must belong to active recovery identity**.

Silent drop is forbidden — analyzer must distinguish **peer received + identity stale** from **network non-delivery**.

### B.6 PR4-Q1b — `DROP_DUPLICATE_ICE_CONNECTED` — CLOSED

**Decision: ACK with `handlerOutcome=ALREADY_SATISFIED`.**

```text
RECOVERY_REATTACH received
        → identity valid
        → ICE CONNECTED + mesh completed + intent already satisfied
        → RECOVERY_REATTACH_ACK(handlerOutcome=ALREADY_SATISFIED)
        → DELIVERY_CONFIRMED
        → X RECOVERED
```

Delivery: *"你的请求我收到了，并处理了。"* Recovery: *"整个 episode 是否完成。"* — separate questions.

### B.7 PR4-Q1c — post-`DELIVERY_CONFIRMED` Episode — CLOSED

```text
DELIVERY_CONFIRMED
        |
        v
Episode reevaluate
        |
        +-- completion satisfied → RECOVERED (Completion owner)
        |
        +-- obligation remains → continue recovery
```

FORBIDDEN: `ACK → RECOVERED` or `DELIVERY_CONFIRMED → RECOVERED` (auto).

### B.8 FORBIDDEN (PR4)

```text
DROP_DUPLICATE_ICE_CONNECTED → RECOVERED
ACK → closeObligation
DELIVERY_CONFIRMED → RECOVERED
stale identity → RECOVERY_REATTACH_ACK
handler reject → silent exhaustion without observation or handled fact
```

### B.9 PR4-Q2 — `RecoveryReattachAck` contract — CLOSED (2026-07-30)

**Layering (frozen):**

```text
ACK = Handler outcome fact
  ≠ Delivery policy
  ≠ Recovery state transition
  ≠ Completion decision
```

Sender answers: *offer 到达 peer，peer 对这个 recovery intent 做了什么处理？* — not *recovery 是否完成？*

#### B.9.1 Q2-1 — `handlerOutcome` in payload — CLOSED: **A**

Add `handlerOutcome` to `RECOVERY_REATTACH_ACK`:

```text
RECOVERY_REATTACH_ACK(
    offerLineageId,
    recoveryAttemptId,
    obligationGeneration,
    deliveryAttemptId,
    handlerOutcome
)
```

| `handlerOutcome` | Meaning | Does NOT mean |
|------------------|---------|---------------|
| `ACCEPTED` | Receiver got intent; identity match; handler **accepts** recovery action | ICE completed, media restored, episode recovered |
| `ALREADY_SATISFIED` | Identity match; handler terminal decision: current media/ICE already satisfies recovery intent (e.g. `DROP_DUPLICATE_ICE_CONNECTED`) | auto-`RECOVERED` |

Rejected: B (sender infers from later state), C (full handler/negotiation state in ACK), D.

#### B.9.2 Q2-2 — Sender `DELIVERY_CONFIRMED` match — CLOSED: strict identity

```text
DELIVERY_CONFIRMED requires:

  ACK.identity == pendingDeliveryTransaction.identity   (all four fields)
  AND
  handlerOutcome ∈ terminalHandledOutcomes
```

**Correlation fields (all MUST match):**

```text
offerLineageId
recoveryAttemptId
obligationGeneration
deliveryAttemptId
```

**`terminalHandledOutcomes` (frozen):**

```text
ACCEPTED
ALREADY_SATISFIED
```

FORBIDDEN loose match:

```text
same peer + same episode + same lineage  => accept ACK
```

**Stale ACK example:**

```text
sender: deliveryAttempt=3, recoveryAttemptId=5
receiver ACK: recoveryAttemptId=4, outcome=ALREADY_SATISFIED
→ RECOVERY_ACK_IGNORED reason=STALE_OBLIGATION_GENERATION
→ NOT DELIVERY_CONFIRMED
```

Late ACK after exhaustion: same — `ACK_IGNORED`, not CONFIRMED (PR2 Case C preserved).

#### B.9.3 Q2-3 — ACK carries recovery phase? — CLOSED: **A**

ACK carries **handler outcome only**. FORBIDDEN in ACK payload:

```text
recovered=true
episodePhase
negotiationState (NEGOTIATING / CONNECTED / FAILED)
obligationClosed
```

Reason: remote receiver must not terminalize sender Episode — **Completion = local policy owner**.

#### B.9.4 Q2-4 — Receiver outcome matrix — CLOSED

| Condition | Action |
|-----------|--------|
| identity match + handler will process recovery | `ACK(handlerOutcome=ACCEPTED)` |
| identity match + intent already satisfied | `ACK(handlerOutcome=ALREADY_SATISFIED)` |
| identity stale | **No ACK**; `RECOVERY_HANDLER_REJECTED(STALE_OBLIGATION_GENERATION)` |
| malformed payload | **No ACK**; observation |
| unknown lineage | **No ACK**; observation |

```text
Reject ≠ Delivery failure
```

Reject is a **handler observation fact** — distinguishes peer-received+stale from network non-delivery.

#### B.9.5 Q2-5 — UT / analyzer minimum — CLOSED

| Case | Chain |
|------|-------|
| **A** | `OFFER_SENT` → received → `HANDLER_ACCEPTED` → `ACK ACCEPTED` → `DELIVERY_CONFIRMED` |
| **B** | `OFFER_SENT` → received → `DROP_DUPLICATE_ICE_CONNECTED` → `ACK ALREADY_SATISFIED` → `DELIVERY_CONFIRMED` (soak L2) |
| **C** | `OFFER_SENT(L1)` → receiver current L2 → `STALE_OBLIGATION_GENERATION` → **no ACK** → sender retry/exhaustion unchanged |
| **D** | `deliveryAttempt=1` expired → late `ACK(ALREADY_SATISFIED)` → `ACK_IGNORED` → **not** CONFIRMED |

### B.10 PR4-Q3 — `DELIVERY_CONFIRMED` → Episode reevaluate — CLOSED (2026-07-30)

**Boundary (frozen):**

```text
PR4-Q2:  ACK = handler handled fact
PR4-Q3:  CONFIRMED 后 Episode 如何重新决策

ACK ≠ Recovery completion
ALREADY_SATISFIED ≠ RECOVERED
```

**Core sentences (frozen):**

```text
DELIVERY_CONFIRMED closes delivery uncertainty, not recovery uncertainty.
RECOVERY_REEVALUATE resolves recovery uncertainty, not ACK handler.
```

#### B.10.1 Q3-1 — CONFIRMED triggers reevaluate? — CLOSED: **A**

`DELIVERY_CONFIRMED` **MUST** trigger `RECOVERY_REEVALUATE(required)` on the sender Episode.

Rejected: B (CONFIRMED observation-only), C (`ALREADY_SATISFIED` only), D (CONFIRMED → RECOVERED).

```text
RECOVERY_REATTACH_ACK
        |
        v
DELIVERY_CONFIRMED
        |
        v
RECOVERY_REEVALUATE (required)
        |
        +--> RECOVERED
        +--> WAITING
        +--> CONTINUE_RECOVERY
```

`RECOVERY_REEVALUATE ≠ RECOVERED`. Without mandatory reevaluate, `DELIVERY_CONFIRMED` + obligation OPEN creates a **decision vacuum** (soak: offer arrived, handler processed, Episode had no new decision point).

#### B.10.2 Q3-2 — `ALREADY_SATISFIED` higher completion hint? — CLOSED: **A**

`ACCEPTED` and `ALREADY_SATISFIED` **equally** trigger reevaluate. Both are handler facts; neither bypasses completion checks.

`ALREADY_SATISFIED` means: receiver handler judges recovery **intent** already satisfied (e.g. ICE CONNECTED). Episode may still have membership / control-plane / drain obligations pending.

FORBIDDEN:

```text
ALREADY_SATISFIED → closeObligation
ALREADY_SATISFIED → bypass completion check
```

#### B.10.3 Q3-3 — reevaluate carries `handlerOutcome`? — CLOSED: **A**

Reevaluate receives policy input:

```text
deliveryConfirmedOutcome = ACCEPTED | ALREADY_SATISFIED
```

Policy may branch on peer signal (accepted action vs already-satisfied), but **Completion owner** decides terminal outcome locally.

FORBIDDEN:

```text
handlerOutcome = ALREADY_SATISFIED → completion=true
handlerOutcome → completion signal (option C)
```

Rejected: B (reevaluate blind to outcome).

#### B.10.4 Q3-4 — decision owner — CLOSED

```text
DELIVERY_CONFIRMED
        |
        v
Episode owner (sender)
        |
        v
RecoveryDecision
```

Example outcomes (local policy only):

| Case | Inputs | Decision |
|------|--------|----------|
| A | media + control + membership satisfied | `RECOVERED` |
| B | media true, control false | `CONTINUE_RECOVERY` |
| C | obligation deadline pressure | `WAITING` / terminal policy |

**ACK receiver path** (ingress handler on peer) MUST NOT own:

```text
closeObligation()
markRecovered()
completeEpisode()
```

#### B.10.5 Q3-5 — UT / analyzer — CLOSED

| Case | Chain | Proves |
|------|-------|--------|
| **A** | `ACK ACCEPTED` → `DELIVERY_CONFIRMED` → `RECOVERY_REEVALUATE` → `RECOVERED` | happy path |
| **B** | `ACK ALREADY_SATISFIED` → `CONFIRMED` → `REEVALUATE` → `WAITING(CONTROL_PLANE_PENDING)` | soak shape; `ALREADY_SATISFIED ≠ RECOVERED` |
| **C** | `ACK ALREADY_SATISFIED` + stale obligation → `REEVALUATE` → `IGNORE` / `WAIT` | no shortcut on stale episode state |
| **D** | `DELIVERY_CONFIRMED` → `REEVALUATE` → no auto completion shortcut | CONFIRMED does not close recovery |

### B.11 PR4-Q4 — Projection consistency (UVCP / presence) — CLOSED (2026-07-30)

**Scope:** Projection **consistency guard** only. NOT UVCP redesign, media state machine refactor, or Completion policy rewrite. ADR-0034 UVCP semantics remain CLOSED.

**Problem:** `ALREADY_SATISFIED` as delivery handler fact must not be misread as recovery complete in UI / presence projection.

**Frozen layering:**

```text
Handler outcome informs Recovery policy.
Recovery policy informs Completion projection.
Completion projection informs UVCP.

No reverse shortcut.
```

#### B.11.1 Q4-1 — `ALREADY_SATISFIED` affects media presence? — CLOSED: **A**

`ALREADY_SATISFIED` enters **Recovery policy input only**. UVCP continues to consume existing media / control / completion projection inputs.

Rejected: B (clear `sessionEdgeRecovering` from ACK), C (media recovered hint / presence boost), D.

FORBIDDEN chain:

```text
ACK(ALREADY_SATISFIED) → media=CONNECTED hint → UVCP CONNECTED
while membership/control/obligation still pending
```

#### B.11.2 Q4-2 — UVCP waits for reevaluate? — CLOSED: **A**

UVCP does **not** consume `DELIVERY_CONFIRMED`. Delivery domain ≠ user-visible connectivity domain.

```text
DELIVERY_CONFIRMED  -X->  UVCP_CONNECTED

DELIVERY_CONFIRMED
        → RECOVERY_REEVALUATE
        → Completion / Presence projection
        → UVCP
```

Rejected: B (CONFIRMED as UVCP clearing), C (`ALREADY_SATISFIED` only clearing).

#### B.11.3 Q4-3 — who clears `sessionEdgeRecovering`? — CLOSED: **A**

Only **Recovery/Completion projection owner** clears `sessionEdgeRecovering` when **full invariants** satisfied.

```text
sessionEdgeRecovering owner: Recovery/Completion projection

inputs:
    media
    control
    membership
    obligation
    recovery phase

NOT: ACK event
```

FORBIDDEN:

```text
onDeliveryConfirmed { sessionEdgeRecovering = false }
Coordinator clears on ACK
Media state machine clears on ACK
UI ViewModel infers from ACK
```

Rejected: B, C, D.

#### B.11.4 Q4-4 — observation ADD — CLOSED

Add reevaluate / projection observations. MUST NOT add `UVCP_OVERRIDE_FROM_ACK` (that event should never exist).

**Recommended logs:**

```text
RECOVERY_REEVALUATE_STARTED
    deliveryConfirmedOutcome=ACCEPTED | ALREADY_SATISFIED

RECOVERY_PROJECTION_RESULT
    media=…
    controlPlane=…
    membership=…
    final=RECOVERED | WAITING | …
```

Proves: ACK arrived → no UVCP shortcut.

Optional alias acceptable: `RECOVERY_DELIVERY_CONFIRMED_PROJECTION_ONLY` — observation only, not a UVCP override.

#### B.11.5 Q4-5 — UT / replay — CLOSED

| Case | Chain | Proves |
|------|-------|--------|
| **A** | `ACK(ACCEPTED)` → CONFIRMED → REEVALUATE → all invariants → UVCP CONNECTED | happy path |
| **B** | `ACK(ALREADY_SATISFIED)` → CONFIRMED → REEVALUATE → media CONNECTED, control PENDING → UVCP SYNCING/RECONNECTING | soak; `ALREADY_SATISFIED ≠ CONNECTED` |
| **C** | ACK → CONFIRMED → REEVALUATE → membership=false → WAITING → UVCP unchanged | no projection jump |
| **D** | `ACK(ALREADY_SATISFIED)` + local obligation already terminal → ignore / observation only | stale terminal guard |

### B.12 PR4 Grill — CLOSED (2026-07-30)

| Phase | Status |
|-------|--------|
| Q1 Handler ACK contract (A split) | CLOSED |
| Q2 `RecoveryReattachAck` payload | CLOSED |
| Q3 Episode reevaluate | CLOSED (A/A/A/A) |
| Q4 Projection consistency | CLOSED (A/A/A) |

**Implementation authorization:** PR4 minimal implementation PR may proceed per B.13 — subject to explicit user authorization per rollout gate.

### B.13 PR4 implementation scope (authorized contract — not yet coded)

**IN:**

- `handlerOutcome` on `RECOVERY_REATTACH_ACK` wire + strict sender match + `terminalHandledOutcomes`
- Receiver: `ACK(ACCEPTED)` / `ACK(ALREADY_SATISFIED)` per outcome matrix; stale → observation only
- Sender: `DELIVERY_CONFIRMED` → mandatory `RECOVERY_REEVALUATE(deliveryConfirmedOutcome)`
- Handler observation: `RECOVERY_HANDLER_ACCEPTED` / `RECOVERY_HANDLER_REJECTED(reason)`
- Projection guard: no ACK/CONFIRMED → UVCP or `sessionEdgeRecovering` shortcut
- Observations: `RECOVERY_REEVALUATE_STARTED`, `RECOVERY_PROJECTION_RESULT`
- Analyzer: D1-E vs stale-vs-exhausted vs `ALREADY_SATISFIED` path
- UT: Q2 Cases A–D + Q3 Cases A–D + Q4 Cases A–D

**OUT:**

- PR2 retry mechanism / parameters change
- ADR-0034 UVCP rule changes
- Completion policy rewrite
- Ingress repair
- `DELIVERY_REJECTED` delivery state
- `UVCP_OVERRIDE_FROM_ACK` or equivalent