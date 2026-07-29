# Recovery Signaling Delivery Assurance

**Workstream:** Recovery Signaling Delivery Assurance  
**Status:** ADR-0035 **Accepted** — PR1 **CLOSED** — PR2 Grill **CLOSED** — **Implementation NEXT**  
**ADR:** [0035-recovery-scoped-delivery-assurance.md](../adr/0035-recovery-scoped-delivery-assurance.md) (Appendix A = PR2 contract)

## ADR Q1–Q5 — CLOSED

| Q | Decision |
|---|----------|
| Q1 | YES — Recovery-Scoped Lineage Reliable Signaling |
| Q2 | A — `RECOVERY_REATTACH_ACK(L*)` sole delivery confirmation |
| Q3 | YES — `DELIVERY_PENDING` delivery phase |
| Q4 | YES — Recovery-owner bounded retransmit |
| Q5 | A — `DELIVERY_EXHAUSTED` fact → Episode policy |

## PR1 Observability — PASS / CLOSED (2026-07-29)

Established fact chain:

```text
REQUESTED → LOCAL_ACCEPT → DELIVERY_PENDING → (REMOTE_RECEIVED + ACK) → DELIVERY_CONFIRMED
```

PR1 soak (`logs/signal-path-20260729-200203/`): all recovery edges `classification: DELIVERY_PENDING`; zero `RECOVERY_REATTACH_ACK`; `REMOTE_RECEIVED: FAIL` on M01→M03 and M02→M03 L1.

**Excluded:** ACK-loss hypothesis; lineage-mismatch-as-root-cause; negotiation answer path as D1 explanation.

Report: `RECOVERY_DELIVERY_REPORT.txt` per session (analyzer: `scripts/analyze-recovery-delivery.ps1`).

## PR2 Bounded Retransmission — Grill CLOSED (2026-07-29)

Full contract: [ADR-0035 Appendix A](../adr/0035-recovery-scoped-delivery-assurance.md#appendix-a--pr2-bounded-retransmission-grill-closed-2026-07-29).

| PR2-Q | Decision |
|-------|----------|
| Q1 | A — Episode Owner policy + scheduling; Coordinator dispatch; Transport facts only |
| Q2 | C — Timer backstop + reachability hint early re-evaluate; neither implies delivery success |
| Q3 | A — Independent clocks; count budget; fixed interval; no watchdog/obligation coupling |
| Q4 | A — `DELIVERY_EXHAUSTED` → `WAITING`; no auto-supersede / new lineage / recovery terminal |
| Q5 | A — `maxDeliveryAttempts=3`; `deliveryRetryIntervalMs=3000`; in-memory only |

Invariants added: INV-DELIVERY-004 .. INV-DELIVERY-007.

### PR2 implementation checklist

1. **State** — per-lineage delivery state (`PENDING` / `RETRY_PENDING` / `CONFIRMED` / `EXHAUSTED`) on Episode; same `offerLineageId` on retry.
2. **Episode** — `deliveryRetryTimer`; hint → decision point; exhaustion → `WAITING(DELIVERY_EXHAUSTED)`; no watchdog refresh on retry.
3. **Coordinator** — `dispatchRecoveryOffer(identity)` with `deliveryAttempt++`; defer when `!canDispatchRecoverySignal()`; late ACK discard.
4. **Logs** — `RECOVERY_DELIVERY_RETRY_PENDING`, `RETRY_DEFERRED`, `DELIVERY_EXHAUSTED`, etc.
5. **Analyzer** — `D1_RETRY_CONFIRMED`, `D1_EXHAUSTED`, per-attempt timeline.
6. **UT** — Cases A (hint retry + ACK), B (single ACK, no retry), C (exhaust + late ACK discard).

### PR2 review risks (must not ship if violated)

- retry in Transport
- delivery timer touches `RECOVERY_WATCHDOG` or `obligationDeadlineAt`
- ACK / `DELIVERY_CONFIRMED` → `RECOVERED`
- delivery retry allocates new `offerLineageId`

## NEXT

**PR2 implementation** → **D1 soak after PR2** (classify retry vs network window).

Frozen (do not reopen): UVCP (ADR-0034), Drain (ADR-0022 Appendix D), Completion, SYNCING timeout, peer readiness/qualification/PRR.
